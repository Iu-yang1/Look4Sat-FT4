/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.presentation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 7+ resolves Chinese by script, so an unqualified values-zh is treated as
 * zh-Hans and a Traditional device (zh-TW / zh-HK / zh-MO, i.e. zh-Hant) never matches
 * it — it used to fall back to the English default. values-b+zh+Hant carries the
 * Simplified text for those devices, and the locale filter in the convention plugin
 * keeps that config in the APK.
 *
 * Both halves fail silently at runtime (English strings, or a Hant config dropped from
 * the resource table), so they are pinned here: keep the Hant copy verbatim and keep
 * the Traditional locales in the locale filter.
 */
class TraditionalChineseFallbackTest {

    private val simplified = stringEntries("src/main/res/values-zh/strings.xml")
    private val traditional = stringEntries("src/main/res/values-b+zh+Hant/strings.xml")
    private val conventionPlugin = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "build-logic/convention/src/main/java/com/rtbishop/look4sat/convention/PluginSetupUtils.kt") }
        .first { it.isFile }

    @Test
    fun `hant fallback is a verbatim copy of the simplified strings`() {
        assertTrue("values-zh/strings.xml looks empty or unparsable", simplified.size > 400)
        val missing = (simplified.keys - traditional.keys).sorted()
        val extra = (traditional.keys - simplified.keys).sorted()
        val changed = simplified.keys.intersect(traditional.keys)
            .filter { simplified[it] != traditional[it] }
            .sorted()
        assertEquals("keys missing from values-b+zh+Hant", emptyList<String>(), missing)
        assertEquals("keys only present in values-b+zh+Hant", emptyList<String>(), extra)
        assertEquals("values-b+zh+Hant diverged from values-zh (copy the change over)", emptyList<String>(), changed)
    }

    @Test
    fun `traditional locales survive the locale filter`() {
        assertTrue("convention plugin not found: ${conventionPlugin.absolutePath}", conventionPlugin.isFile)
        // Comments may mention either spelling, so only the code lines are inspected.
        val filters = conventionPlugin.readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val traditionalLocales = listOf("zh-rTW", "zh-rHK", "zh-rMO")
        val dropped = traditionalLocales.filterNot { filters.contains("\"$it\"") }
        assertEquals(
            "localeFilters must list the Traditional locales, otherwise aapt2 strips" +
                " values-b+zh+Hant from the APK and Traditional devices get English",
            emptyList<String>(),
            dropped
        )
        // aapt2 rejects the script spelling, so it must not creep back into the filter.
        assertTrue("localeFilters must use the aapt2 'zh-rTW' spelling, not 'zh-Hant'", !filters.contains("\"zh-Hant\""))
    }

    private fun stringEntries(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("resource file not found: ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val element = nodes.item(it) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }
}
