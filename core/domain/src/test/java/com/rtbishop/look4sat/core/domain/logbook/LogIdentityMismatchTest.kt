package com.rtbishop.look4sat.core.domain.logbook

import org.junit.Assert.*
import org.junit.Test

class LogIdentityMismatchTest {
    @Test fun normalizedMatchingIdentityDoesNotWarn() {
        assertEquals(LogIdentityMismatch(false,false),logIdentityMismatch(" bg5jsu ","ol87ab","BG5JSU","OL87AB"))
    }
    @Test fun callsignAndGridDifferencesAreIndependent() {
        assertEquals(LogIdentityMismatch(true,false),logIdentityMismatch("BG5JSU/P","OL87AB","BG5JSU","OL87"))
        assertEquals(LogIdentityMismatch(false,true),logIdentityMismatch("BG5JSU","OL88","BG5JSU","OL87"))
        assertEquals(LogIdentityMismatch(true,true),logIdentityMismatch("JA1ABC","PM95","BG5JSU","OL87"))
    }
    @Test fun boundaryAndMixedPrecisionGridsAreCoveredButDifferentSubgridsAreNot() {
        assertFalse(logIdentityMismatch("","OL87AB","","OL86,OL87").grid)
        assertFalse(logIdentityMismatch("","OL87","","OL87AB").grid)
        assertTrue(logIdentityMismatch("","OL87AB","","OL87CD").grid)
        assertTrue(logIdentityMismatch("","OL87,OL88","","OL87").grid)
    }
    @Test fun unconfiguredOrUnknownValuesDoNotInventAMismatch() {
        assertEquals(LogIdentityMismatch(false,false),logIdentityMismatch("","","BG5JSU","OL87"))
        assertEquals(LogIdentityMismatch(false,false),logIdentityMismatch("BG5JSU","OL87","",""))
    }
}
