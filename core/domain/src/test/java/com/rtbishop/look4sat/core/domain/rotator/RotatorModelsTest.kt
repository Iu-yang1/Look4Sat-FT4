package com.rtbishop.look4sat.core.domain.rotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatorModelsTest {

    @Test
    fun protocolTransportCompatibilityIsExplicit() {
        assertTrue(RotatorProtocol.GS232.supportsTransport(RotatorTransport.BLUETOOTH_SPP))
        assertTrue(RotatorProtocol.GS232.supportsTransport(RotatorTransport.USB_SERIAL))
        assertTrue(RotatorProtocol.GS232.supportsTransport(RotatorTransport.TCP))
        assertFalse(RotatorProtocol.GS232.supportsTransport(RotatorTransport.UDP))

        assertTrue(RotatorProtocol.ROTCTLD.supportsTransport(RotatorTransport.TCP))
        assertFalse(RotatorProtocol.ROTCTLD.supportsTransport(RotatorTransport.UDP))
        assertTrue(RotatorProtocol.PST_ROTATOR.supportsTransport(RotatorTransport.UDP))
        assertFalse(RotatorProtocol.PST_ROTATOR.supportsTransport(RotatorTransport.TCP))
        assertTrue(RotatorProtocol.CUSTOM_TEMPLATE.supportsTransport(RotatorTransport.USB_SERIAL))
        assertTrue(RotatorProtocol.CUSTOM_TEMPLATE.supportsTransport(RotatorTransport.UDP))
    }

    @Test
    fun settingsRequireTheEndpointExpectedByTheTransport() {
        assertFalse(RotatorSettings(enabled = true).copy(host = "").isConfigured)
        assertTrue(RotatorSettings(enabled = true).isConfigured)
        assertTrue(
            RotatorSettings(
                enabled = true,
                protocol = RotatorProtocol.GS232,
                transport = RotatorTransport.BLUETOOTH_SPP,
                deviceAddress = "00:11:22:33:44:55"
            ).isConfigured
        )
        assertFalse(
            RotatorSettings(
                enabled = true,
                protocol = RotatorProtocol.GS232,
                transport = RotatorTransport.BLUETOOTH_SPP
            ).isConfigured
        )
        assertFalse(
            RotatorSettings(
                enabled = true,
                protocol = RotatorProtocol.PST_ROTATOR,
                transport = RotatorTransport.TCP,
                host = "rotator.local",
                port = 12000
            ).isConfigured
        )
    }

    @Test
    fun normalizationClampsUnsafeValuesAndReplacesNonFiniteNumbers() {
        val normalized = RotatorSettings(
            port = 70_000,
            baudRate = 1,
            prepositionLeadSeconds = 1_000,
            trackingLeadSeconds = -1,
            azimuthOffsetDegrees = Double.NaN,
            elevationOffsetDegrees = Double.POSITIVE_INFINITY,
            deadbandDegrees = -5.0,
            parkAzimuthDegrees = 999.0,
            parkElevationDegrees = -10.0,
            minimumElevationDegrees = 100.0,
            updateIntervalMillis = 1L,
            sampleTimeoutMillis = Long.MAX_VALUE
        ).normalized()

        assertEquals(4533, normalized.port)
        assertEquals(300, normalized.baudRate)
        assertEquals(600, normalized.prepositionLeadSeconds)
        assertEquals(0, normalized.trackingLeadSeconds)
        assertEquals(0.0, normalized.azimuthOffsetDegrees, 0.0)
        assertEquals(0.0, normalized.elevationOffsetDegrees, 0.0)
        assertEquals(0.0, normalized.deadbandDegrees, 0.0)
        assertEquals(450.0, normalized.parkAzimuthDegrees, 0.0)
        assertEquals(0.0, normalized.parkElevationDegrees, 0.0)
        assertEquals(90.0, normalized.minimumElevationDegrees, 0.0)
        assertEquals(200L, normalized.updateIntervalMillis)
        assertEquals(30_000L, normalized.sampleTimeoutMillis)
    }

    @Test
    fun networkProtocolsExposeDocumentedDefaultPortsAndCapabilities() {
        assertEquals(4533, RotatorProtocol.ROTCTLD.defaultPort)
        assertEquals(12000, RotatorProtocol.PST_ROTATOR.defaultPort)
        assertEquals(1111, RotatorProtocol.OZ9AAR_URC.defaultPort)
        assertTrue(RotatorProtocol.ROTCTLD.supportsPositionQuery)
        assertTrue(RotatorProtocol.PST_ROTATOR.supportsStop)
        assertFalse(RotatorProtocol.SAEBRTRACK.supportsStop)
        assertFalse(RotatorProtocol.OZ9AAR_URC.supportsStop)
    }
}
