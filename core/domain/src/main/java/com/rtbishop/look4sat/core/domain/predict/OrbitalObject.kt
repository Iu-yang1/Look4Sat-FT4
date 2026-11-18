package com.rtbishop.look4sat.core.domain.predict

import com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot
import com.rtbishop.look4sat.core.domain.astro.OrbitEphemerisSnapshot

/** Immutable orbital elements and optional pinned astronomical-data generation. */
data class OrbitalObject(
    val data: OrbitalData,
    val snapshot: AstroDataSnapshot? = null,
    val ephemeris: OrbitEphemerisSnapshot? = null
)
