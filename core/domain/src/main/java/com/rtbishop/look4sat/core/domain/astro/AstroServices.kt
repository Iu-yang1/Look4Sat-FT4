package com.rtbishop.look4sat.core.domain.astro

import com.rtbishop.look4sat.core.domain.predict.CelestialState
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.TemeState
import kotlinx.coroutines.flow.StateFlow

const val DEFAULT_ASTRO_DATA_URL =
    "https://github.com/Iu-yang1/Look4Sat-FT4/releases/download/orekit-data/orekit-data.zip"

enum class AstroDataPhase { LOADING, MISSING, DOWNLOADING, VERIFYING, READY, FAILED }

data class AstroDataState(
    val phase: AstroDataPhase = AstroDataPhase.LOADING,
    val downloadUrl: String = DEFAULT_ASTRO_DATA_URL,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val version: String? = null,
    val generation: String? = null,
    val coverageStartMillis: Long? = null,
    val coverageEndMillis: Long? = null,
    val eopEndMillis: Long? = null,
    val error: String? = null
) {
    val ready: Boolean get() = version != null
    val busy: Boolean get() = phase == AstroDataPhase.LOADING ||
        phase == AstroDataPhase.DOWNLOADING || phase == AstroDataPhase.VERIFYING
}

/** A pinned, read-only data generation; no Orekit types cross this boundary. */
interface AstroDataSnapshot {
    val version: String
    val coverageStartMillis: Long
    val coverageEndMillis: Long
    val eopEndMillis: Long
    val eopStartMillis: Long get() = Long.MIN_VALUE
}

class AstroDataUnavailableException : IllegalStateException("Orbital and astronomical data are not ready")
class AstroDateOutsideCoverageException : IllegalArgumentException("Date is outside astronomical data coverage")
class OrbitPredictionException(
    val validUntilMillis: Long,
    val validPasses: List<OrbitalPass>,
    cause: Exception
) : IllegalStateException("Orbit prediction ended at $validUntilMillis", cause)

interface IAstroDataRepository {
    val state: StateFlow<AstroDataState>
    fun snapshot(): AstroDataSnapshot
    fun setDownloadUrl(url: String)
    fun download()
    fun cancel()
}

interface IOrbitEngine {
    fun openSession(target: OrbitalObject): IOrbitSession
    fun bind(elements: OrbitalData): OrbitalObject
}

/** Owned by one calculation; propagation workspaces are not shared between consumers. */
interface IOrbitSession {
    val target: OrbitalObject
    fun position(observer: GeoPos, timeMillis: Long): OrbitalPos
    fun elevation(observer: GeoPos, timeMillis: Long): Double
    fun temeState(timeMillis: Long): TemeState
    fun coverage(timeMillis: Long): List<GeoPos>
    fun passes(
        observer: GeoPos, startMillis: Long, endMillis: Long,
        checkCancelled: () -> Unit = {}
    ): List<OrbitalPass>
}

interface ICelestialService {
    fun sun(observer: GeoPos, timeMillis: Long, snapshot: AstroDataSnapshot? = null): CelestialState.SunPosition
    fun moon(observer: GeoPos, timeMillis: Long, snapshot: AstroDataSnapshot? = null): CelestialState.MoonPosition
    fun riseSet(
        observer: GeoPos, startMillis: Long, endMillis: Long, moon: Boolean = false,
        snapshot: AstroDataSnapshot? = null, checkCancelled: () -> Unit = {}
    ): CelestialState.RiseSetTimes
}
