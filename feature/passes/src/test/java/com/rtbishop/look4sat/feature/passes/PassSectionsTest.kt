package com.rtbishop.look4sat.feature.passes

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PassSectionsTest {
    @Test fun deepSpaceIsPinnedWithoutChangingTheTrueWindows() {
        val low = pass(15.0, 1000)
        val deep = pass(2.0, 2000)
        val laterDeep = deep.copy(aosTime = 4000, losTime = 5000)
        val sections = groupPassSections(listOf(low, deep, laterDeep)) { "date" }
        assertEquals(listOf(DEEP_SPACE_SECTION, "date"), sections.keys.toList())
        assertEquals(listOf(deep, laterDeep), sections[DEEP_SPACE_SECTION])
        assertEquals(listOf(low), sections["date"])
        assertFalse(sections[DEEP_SPACE_SECTION]!!.first().continuousVisibility)
    }

    @Test fun datesRetainTheirOrderAndEmptyDeepSpaceSectionIsOmitted() {
        val first = pass(15.0, 1000)
        val second = pass(15.0, 2000)
        val sections = groupPassSections(listOf(first, second)) { it.aosTime.toString() }
        assertEquals(listOf("1000", "2000"), sections.keys.toList())
        assertFalse(sections.containsKey(DEEP_SPACE_SECTION))
        assertEquals(emptyMap<String, List<OrbitalPass>>(), groupPassSections(emptyList()) { "date" })
    }

    private fun pass(meanMotion: Double, start: Long) = OrbitalPass(
        aosTime = start, losTime = start + 1000,
        orbitalObject = OrbitalData("TEST", 26001.0, meanMotion, .001, 30.0,
            0.0, 0.0, 0.0, 1, 0.0).getObject()
    )
}
