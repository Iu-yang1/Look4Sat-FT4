package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWRegionField
import com.rtbishop.look4sat.core.domain.repository.LoTWRegionOption
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWStationMeta
import com.rtbishop.look4sat.core.domain.repository.LoTWZonePair
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Bundled ARRL configuration 11.34 from https://lotw.arrl.org/lotw/config.tq6. */
internal class LoTWConfig(input: InputStream) {
    private val root = input.use { source -> GZIPInputStream(source).use { gzip ->
        val xml = gzip.bufferedReader(Charsets.UTF_8).readText()
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        // Android's DOM provider does not support Xerces-specific setFeature flags.
        DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are not permitted") }
        }.parse(InputSource(StringReader(xml))).documentElement
    } }
    private val config = root.elements("tqslconfig").single()
    val version = config.getAttribute("majorversion") + "." + config.getAttribute("minorversion")
    private val spec = config.elements("sigspec").single { it.getAttribute("version") == "2.0" }
    val stationOrder = spec.elements("tSTATION").single().childElements().map { it.tagName }
    val contactOrder = spec.elements("tCONTACT").single().childElements().map { it.tagName }
    private val primaryRegionFields = setOf("US_STATE", "CA_PROVINCE", "RU_OBLAST", "CN_PROVINCE", "AU_STATE", "JA_PREFECTURE", "FI_KUNTA")
    private val secondaryRegionFields = setOf("US_COUNTY", "JA_CITY_GUN_KU")
    private val bands = config.elements("bands").single().elements("band")
    private val satellites = config.elements("satellite").associateBy { it.getAttribute("name").uppercase(Locale.US) }
    private val modes = config.elements("modes").single().elements("mode").map { it.textContent }.toSet()

    fun mode(record: QsoRecord): String {
        val display = record.displayMode
        if (display in modes) return display
        val mapped = config.elements("adifmode").firstOrNull { it.textContent.equals(display, true) }?.getAttribute("mode")
        return mapped ?: fail(LoTWProblem.MODE, display)
    }

    fun band(explicit: String, frequency: Long?, required: Boolean): String {
        if (frequency != null && frequency <= 0) fail(LoTWProblem.BAND)
        val frequencyBand = frequency?.let { hz -> bands.firstOrNull { band ->
            val unit = if (band.getAttribute("spectrum") == "HF") 1_000.0 else 1_000_000.0
            val mhz = hz / unit
            mhz >= band.getAttribute("low").toDouble() && mhz <= band.getAttribute("high").toDouble()
        }?.textContent }
        val value = explicit.trim().uppercase(Locale.US).ifBlank { frequencyBand.orEmpty() }
        if (value.isBlank() && !required && frequency == null) return ""
        if (bands.none { it.textContent == value } || (frequency != null && frequencyBand != value)) fail(LoTWProblem.BAND, value)
        return value
    }

    fun satellite(name: String, date: String): String {
        val normalized = name.trim().uppercase(Locale.US)
        // Look4Sat satellite names come from SatNOGS (e.g. "SO-50 (SaudiOSCAR 50)");
        // the bundled config only knows the official LoTW name ("SO-50"). Exact match
        // first, then accept any satellite whose official name is a substring of the
        // Look4Sat name (covers SO-50, AO-91, IO-86, CAS-7B …). Failing that the
        // contact is unavailable for upload.
        val sat = satellites[normalized]
            ?: satellites.entries.firstOrNull { (official, _) ->
                official.length > 1 && normalized.contains(official)
            }?.value
            ?: fail(LoTWProblem.SATELLITE, normalized)
        val first = sat.getAttribute("startDate")
        val last = sat.getAttribute("endDate")
        if ((first.isNotBlank() && date < first) || (last.isNotBlank() && date > last)) fail(LoTWProblem.SATELLITE, normalized)
        return sat.getAttribute("name").uppercase(Locale.US)
    }

    fun propagation(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        if (normalized.isNotBlank() && config.elements("propmode").none { it.getAttribute("name") == normalized || it.textContent == normalized }) {
            fail(LoTWProblem.INVALID_CONTACT, normalized)
        }
        return normalized
    }

    /** Region-field metadata and the national zonemap for one DXCC entity. */
    fun stationMeta(dxcc: Int): LoTWStationMeta {
        val pageFields = config.elements("page").filter { it.getAttribute("dependency") == dxcc.toString() }
            .flatMap { it.elements("pageField") }.map { it.textContent }
        val primary = pageFields.firstOrNull { it in primaryRegionFields }
        val regionField = primary?.let { id ->
            val field = config.elements("field").single { it.getAttribute("Id") == id }
            val dependency = if (field.getAttribute("dependsOn") == "DXCC") dxcc.toString() else null
            val options = field.elements("enums").filter {
                it.getAttribute("dependency").isBlank() || it.getAttribute("dependency") == dependency
            }.flatMap { it.elements("enum") }.map { enum ->
                LoTWRegionOption(
                    code = enum.getAttribute("value").uppercase(Locale.US),
                    name = enum.textContent.trim().ifBlank { enum.getAttribute("value") },
                    zones = parseZonemap(enum.getAttribute("zonemap"))
                )
            }
            LoTWRegionField(id = id, label = field.getAttribute("label"), options = options)
        }
        val entity = config.elements("dxcc").flatMap { it.elements("entity") }
            .firstOrNull { it.getAttribute("arrlId") == dxcc.toString() }
        val countryZones = entity?.getAttribute("zonemap")?.let(::parseZonemap).orEmpty()
        return LoTWStationMeta(regionField, countryZones)
    }

    private fun parseZonemap(zonemap: String): List<LoTWZonePair> = zonemap.split(',').mapNotNull { pair ->
        val parts = pair.split(':')
        if (parts.size != 2) return@mapNotNull null
        val itu = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
        val cq = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
        LoTWZonePair(itu, cq)
    }

    fun stationFields(station: LoTWStation, dxcc: Int): Map<String, String> {
        fun normalized(value: String) = value.trim().uppercase(Locale.US)
        val grids = station.grid.split(',').map(::normalized).filter(String::isNotBlank).distinct()
        if (grids.size !in 1..4 || grids.any { !it.matches(Regex("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?")) }) {
            fail(LoTWProblem.STATION_GRID)
        }
        // The primary grid goes into GRIDSQUARE (the field the config's tSTATION
        // order signs); the full multi-grid set goes into MY_VUCC_GRIDS, an extra
        // station field that TQSL stores but does not sign (same precedent as IOTA).
        val fields = linkedMapOf("GRIDSQUARE" to grids.first())
        if (grids.size > 1) fields["MY_VUCC_GRIDS"] = grids.joinToString(",")
        fun zone(name: String, value: String, maximum: Int) {
            if (value.isBlank()) return
            val number = value.trim().toIntOrNull()?.takeIf { it in 1..maximum } ?: fail(LoTWProblem.STATION_ZONE)
            fields[name] = number.toString()
        }
        zone("CQZ", station.cqZone, 40)
        zone("ITUZ", station.ituZone, 90)
        if (station.iota.isNotBlank()) {
            val iota = normalized(station.iota)
            if (!iota.matches(Regex("(AF|AN|AS|EU|NA|OC|SA)-[0-9]{3}"))) fail(LoTWProblem.STATION_IOTA)
            fields["IOTA"] = iota
        }
        val pageFields = config.elements("page").filter { it.getAttribute("dependency") == dxcc.toString() }
            .flatMap { it.elements("pageField") }.map { it.textContent }
        val primary = pageFields.firstOrNull { it in primaryRegionFields }
        val secondary = pageFields.firstOrNull { it in secondaryRegionFields }
        fun region(name: String?, value: String) {
            if (value.isBlank()) return
            if (name == null) fail(LoTWProblem.STATION_REGION)
            val field = config.elements("field").single { it.getAttribute("Id") == name }
            val dependency = when (field.getAttribute("dependsOn")) {
                "DXCC" -> dxcc.toString()
                else -> fields[field.getAttribute("dependsOn")]
            }
            val options = field.elements("enums").filter {
                it.getAttribute("dependency").isBlank() || it.getAttribute("dependency") == dependency
            }.flatMap { it.elements("enum") }.map { it.getAttribute("value").uppercase(Locale.US) }
            val code = normalized(value)
            if (code !in options) fail(LoTWProblem.STATION_REGION, name)
            fields[name] = code
        }
        region(primary, station.region)
        region(secondary, station.county)
        return fields
    }
}

private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
    (0 until nodes.length).map { nodes.item(it) as Element }
}
private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
