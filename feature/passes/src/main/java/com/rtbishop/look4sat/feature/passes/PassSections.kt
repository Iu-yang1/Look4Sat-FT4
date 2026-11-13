package com.rtbishop.look4sat.feature.passes

import com.rtbishop.look4sat.core.domain.predict.OrbitalPass

internal const val DEEP_SPACE_SECTION = "deep-space"

internal fun groupPassSections(
    passes: List<OrbitalPass>,
    dateLabel: (OrbitalPass) -> String
): Map<String, List<OrbitalPass>> {
    val sections = linkedMapOf<String, List<OrbitalPass>>()
    val (deepSpace, nearEarth) = passes.partition { it.isDeepSpace }
    if (deepSpace.isNotEmpty()) sections[DEEP_SPACE_SECTION] = deepSpace
    nearEarth.groupByTo(linkedMapOf(), dateLabel).forEach { (date, windows) ->
        sections[date] = windows
    }
    return sections
}
