package com.rtbishop.look4sat.core.domain.rotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatorPointingPolicyTest {

    @Test
    fun disabledAndUnconfiguredSettingsHoldWithoutMoving() {
        assertHold(
            RotatorHoldReason.DISABLED,
            RotatorPointingPolicy.decide(RotatorSettings(), input(look = look(100.0, 30.0)))
        )
        assertHold(
            RotatorHoldReason.NOT_CONFIGURED,
            RotatorPointingPolicy.decide(
                activeSettings(host = ""),
                input(look = look(100.0, 30.0))
            )
        )
    }

    @Test
    fun visibleSatelliteUsesLeadLookOffsetsAndMagneticCorrection() {
        val settings = activeSettings(
            trackingLeadSeconds = 2,
            azimuthOffsetDegrees = 10.0,
            elevationOffsetDegrees = 5.0,
            magneticCorrection = true
        )
        val decision = RotatorPointingPolicy.decide(
            settings,
            input(
                look = look(100.0, 30.0),
                lead = look(104.0, 32.0, time = NOW + 2_000L),
                magneticDeclination = 7.0
            )
        ).requirePoint()

        assertEquals(RotatorTrackingPhase.TRACKING, decision.phase)
        assertPosition(114.0, 37.0, decision.truePosition)
        assertPosition(107.0, 37.0, decision.wirePosition)
    }

    @Test
    fun prepositionsInsideLeadWindowAndParksOutsideIt() {
        val settings = activeSettings(prepositionLeadSeconds = 120, parkOnLos = true)
        val upcoming = RotatorPassContext(
            aosTimeMillis = NOW + 60_000L,
            losTimeMillis = NOW + 600_000L,
            aosAzimuthDegrees = 350.0
        )
        val preposition = RotatorPointingPolicy.decide(
            settings,
            input(look = look(120.0, -5.0), pass = upcoming)
        ).requirePoint()
        assertEquals(RotatorTrackingPhase.PREPOSITIONING, preposition.phase)
        assertPosition(350.0, 0.0, preposition.truePosition)

        val park = RotatorPointingPolicy.decide(
            settings.copy(parkAzimuthDegrees = 180.0, parkElevationDegrees = 10.0),
            input(look = look(120.0, -5.0), pass = upcoming.copy(aosTimeMillis = NOW + 180_000L))
        ).requirePoint()
        assertEquals(RotatorTrackingPhase.PARKING, park.phase)
        assertPosition(180.0, 10.0, park.truePosition)
    }

    @Test
    fun ignoresLeadSampleWhenMechanicalLeadIsDisabledAndClampsNormalElevation() {
        val decision = RotatorPointingPolicy.decide(
            activeSettings(trackingLeadSeconds = 0),
            input(
                look = look(100.0, 95.0),
                lead = look(140.0, 40.0, time = NOW + 2_000L)
            )
        ).requirePoint()

        assertPosition(100.0, 90.0, decision.truePosition)
    }

    @Test
    fun belowMinimumElevationCanHoldInsteadOfParking() {
        val decision = RotatorPointingPolicy.decide(
            activeSettings(minimumElevationDegrees = 10.0, parkOnLos = false),
            input(look = look(100.0, 5.0))
        )

        assertHold(RotatorHoldReason.BELOW_MINIMUM_ELEVATION, decision)
    }

    @Test
    fun staleAndInvalidSamplesNeverProduceMovement() {
        assertHold(
            RotatorHoldReason.STALE_POSITION,
            RotatorPointingPolicy.decide(
                activeSettings(sampleTimeoutMillis = 1_000L),
                input(look = look(100.0, 30.0, time = NOW - 1_001L))
            )
        )
        assertHold(
            RotatorHoldReason.INVALID_POSITION,
            RotatorPointingPolicy.decide(
                activeSettings(),
                input(look = look(Double.NaN, 30.0))
            )
        )
    }

    @Test
    fun overheadFlipChangesBothAxesAndAllowsElevationAboveNinety() {
        val pass = RotatorPassContext(
            aosTimeMillis = NOW - 10_000L,
            losTimeMillis = NOW + 100_000L,
            aosAzimuthDegrees = 10.0,
            requiresFlip = true
        )
        val decision = RotatorPointingPolicy.decide(
            activeSettings(flipOverheadPasses = true),
            input(look = look(100.0, 80.0), pass = pass)
        ).requirePoint()

        assertPosition(280.0, 100.0, decision.truePosition)
    }

    @Test
    fun supportsMinus180And450DegreeAxisConventions() {
        val signed = RotatorPointingPolicy.decide(
            activeSettings(azimuthRange = RotatorAzimuthRange.MINUS_180_TO_180),
            input(look = look(270.0, 20.0))
        ).requirePoint()
        assertEquals(-90.0, signed.truePosition.azimuthDegrees, 0.0)

        val overlap = RotatorPointingPolicy.decide(
            activeSettings(
                azimuthRange = RotatorAzimuthRange.ZERO_TO_450,
                azimuthLookAheadSeconds = 3
            ),
            input(
                look = look(5.0, 20.0),
                azimuthAhead = look(350.0, 20.0, time = NOW + 3_000L)
            )
        ).requirePoint()
        assertEquals(365.0, overlap.truePosition.azimuthDegrees, 0.0)
        assertTrue(overlap.nextAzimuth450Precommitted)
    }

    @Test
    fun deadbandUsesShortestDistanceAndPstKeepalive() {
        val settings = activeSettings(deadbandDegrees = 3.0)
        assertFalse(
            RotatorPointingPolicy.shouldSend(
                previousPosition = RotatorPosition(359.0, 20.0),
                targetPosition = RotatorPosition(1.0, 21.0),
                settings = settings,
                nowMillis = NOW,
                lastSendAtMillis = NOW - 1_000L
            )
        )
        assertTrue(
            RotatorPointingPolicy.shouldSend(
                previousPosition = RotatorPosition(359.0, 20.0),
                targetPosition = RotatorPosition(5.0, 20.0),
                settings = settings,
                nowMillis = NOW,
                lastSendAtMillis = NOW - 1_000L
            )
        )

        val pst = settings.copy(protocol = RotatorProtocol.PST_ROTATOR, transport = RotatorTransport.UDP)
        assertFalse(
            RotatorPointingPolicy.shouldSend(
                previousPosition = RotatorPosition(10.0, 10.0),
                targetPosition = RotatorPosition(10.0, 10.0),
                settings = pst,
                nowMillis = NOW,
                lastSendAtMillis = NOW - 1_999L
            )
        )
        assertTrue(
            RotatorPointingPolicy.shouldSend(
                previousPosition = RotatorPosition(10.0, 10.0),
                targetPosition = RotatorPosition(10.0, 10.0),
                settings = pst,
                nowMillis = NOW,
                lastSendAtMillis = NOW - 2_000L
            )
        )
    }

    @Test
    fun firstValidTargetAlwaysSendsAndNonFiniteTargetNeverSends() {
        assertTrue(
            RotatorPointingPolicy.shouldSend(
                previousPosition = null,
                targetPosition = RotatorPosition(10.0, 20.0),
                settings = activeSettings(),
                nowMillis = NOW,
                lastSendAtMillis = null
            )
        )
        assertFalse(
            RotatorPointingPolicy.shouldSend(
                previousPosition = null,
                targetPosition = RotatorPosition(Double.NaN, 20.0),
                settings = activeSettings(),
                nowMillis = NOW,
                lastSendAtMillis = null
            )
        )
    }

    private fun activeSettings(
        host: String = "127.0.0.1",
        prepositionLeadSeconds: Int = 120,
        trackingLeadSeconds: Int = 0,
        azimuthLookAheadSeconds: Int = 3,
        azimuthRange: RotatorAzimuthRange = RotatorAzimuthRange.ZERO_TO_360,
        azimuthOffsetDegrees: Double = 0.0,
        elevationOffsetDegrees: Double = 0.0,
        deadbandDegrees: Double = 3.0,
        magneticCorrection: Boolean = false,
        parkOnLos: Boolean = true,
        flipOverheadPasses: Boolean = false,
        minimumElevationDegrees: Double = 0.0,
        sampleTimeoutMillis: Long = 3_000L
    ) = RotatorSettings(
        enabled = true,
        host = host,
        prepositionLeadSeconds = prepositionLeadSeconds,
        trackingLeadSeconds = trackingLeadSeconds,
        azimuthLookAheadSeconds = azimuthLookAheadSeconds,
        azimuthRange = azimuthRange,
        azimuthOffsetDegrees = azimuthOffsetDegrees,
        elevationOffsetDegrees = elevationOffsetDegrees,
        deadbandDegrees = deadbandDegrees,
        magneticCorrection = magneticCorrection,
        parkOnLos = parkOnLos,
        flipOverheadPasses = flipOverheadPasses,
        minimumElevationDegrees = minimumElevationDegrees,
        sampleTimeoutMillis = sampleTimeoutMillis
    )

    private fun input(
        look: RotatorLook?,
        lead: RotatorLook? = null,
        azimuthAhead: RotatorLook? = null,
        pass: RotatorPassContext? = null,
        magneticDeclination: Double = 0.0
    ) = RotatorPointingInput(
        nowMillis = NOW,
        currentLook = look,
        trackingLeadLook = lead,
        azimuthLookAhead = azimuthAhead,
        nextPass = pass,
        magneticDeclinationDegrees = magneticDeclination
    )

    private fun look(azimuth: Double, elevation: Double, time: Long = NOW) =
        RotatorLook(azimuth, elevation, time)

    private fun RotatorPointingDecision.requirePoint(): RotatorPointingDecision.Point {
        assertTrue(this is RotatorPointingDecision.Point)
        return this as RotatorPointingDecision.Point
    }

    private fun assertHold(expected: RotatorHoldReason, decision: RotatorPointingDecision) {
        assertTrue(decision is RotatorPointingDecision.Hold)
        assertEquals(expected, (decision as RotatorPointingDecision.Hold).reason)
    }

    private fun assertPosition(expectedAzimuth: Double, expectedElevation: Double, actual: RotatorPosition) {
        assertEquals(expectedAzimuth, actual.azimuthDegrees, 0.0001)
        assertEquals(expectedElevation, actual.elevationDegrees, 0.0001)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
