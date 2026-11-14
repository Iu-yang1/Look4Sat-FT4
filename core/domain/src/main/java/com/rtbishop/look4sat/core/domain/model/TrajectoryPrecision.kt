package com.rtbishop.look4sat.core.domain.model

/** Search sampling only; the propagation model and event root solver stay unchanged. */
enum class TrajectoryPrecision(
    val label: String,
    val samplingScale: Double,
    val maximumSearchStepMillis: Long
) {
    LIGHT("Light", 8.0, 60_000),
    MIDDLE("Middle", 6.0, 50_000),
    HIGH("High", 4.0, 45_000),
    XHIGH("Xhigh", 3.0, 40_000),
    MAX("Max", 2.0, 35_000),
    ULTRA("Ultra", 1.0, 30_000);

    companion object {
        fun fromStored(value: String?): TrajectoryPrecision =
            entries.firstOrNull { it.name == value } ?: ULTRA
    }
}
