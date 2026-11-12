package com.rtbishop.look4sat.core.domain.predict

import com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot

/** Immutable orbital elements and optional pinned astronomical-data generation. */
data class OrbitalObject(
    val data: OrbitalData,
    val snapshot: AstroDataSnapshot? = null
)
