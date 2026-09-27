package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.rotator.RotatorConnectionState
import com.rtbishop.look4sat.core.domain.rotator.RotatorLook
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingPhase
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RotatorTrackingServiceTest {

    @Test
    fun connectsAndTracksTheSelectedPass() = runTest {
        var now = 50_000L
        val transport = FakeRotatorTransport()
        val service = service(
            now = { now },
            orbit = FakeRotatorOrbitSource(lookProvider = { time -> RotatorLook(100.0, 30.0, time) }),
            transports = ArrayDeque(listOf(transport))
        )

        service.connect()
        assertEquals(RotatorConnectionState.CONNECTED, service.state.value.connectionState)
        service.startTracking(pass(aos = 0L, los = 100_000L))
        runCurrent()

        assertTrue(transport.writes.any { it.decodeToString() == "P 100.0 30.0\n" })
        assertEquals(RotatorTrackingPhase.TRACKING, service.state.value.trackingPhase)
        assertEquals(100.0, service.state.value.commandedPosition?.azimuthDegrees ?: Double.NaN, 0.0)
        service.disconnect()
    }

    @Test
    fun prepositionsBeforeAosThenParksAfterLos() = runTest {
        var now = 0L
        val transport = FakeRotatorTransport()
        val settings = activeSettings().copy(
            prepositionLeadSeconds = 120,
            parkAzimuthDegrees = 180.0,
            parkElevationDegrees = 10.0,
            updateIntervalMillis = 1_000L
        )
        val service = service(
            now = { now },
            settings = { settings },
            orbit = FakeRotatorOrbitSource(lookProvider = { time -> RotatorLook(20.0, -5.0, time) }),
            transports = ArrayDeque(listOf(transport))
        )
        val pass = pass(aos = 60_000L, los = 120_000L, aosAzimuth = 350.0)

        service.connect()
        service.startTracking(pass)
        runCurrent()
        assertTrue(transport.writes.any { it.decodeToString() == "P 350.0 0.0\n" })
        assertEquals(RotatorTrackingPhase.PREPOSITIONING, service.state.value.trackingPhase)

        now = 121_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("P 180.0 10.0\n", transport.writes.last().decodeToString())
        assertEquals(RotatorTrackingPhase.PARKED, service.state.value.trackingPhase)
        service.disconnect()
    }

    @Test
    fun reconnectsAfterAWriteFailureAndTrackingContinues() = runTest {
        var now = 50_000L
        val failed = FakeRotatorTransport(writeResults = ArrayDeque(listOf(false)))
        val recovered = FakeRotatorTransport()
        val transports = ArrayDeque(listOf<ControlTransport>(failed, recovered))
        val service = service(
            now = { now },
            orbit = FakeRotatorOrbitSource(lookProvider = { time -> RotatorLook(100.0, 30.0, time) }),
            transports = transports,
            reconnectDelayMillis = 10L
        )

        service.connect()
        service.startTracking(pass(aos = 0L, los = 100_000L))
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(1, failed.disconnectCalls)
        assertEquals(1, recovered.connectCalls)
        assertEquals(RotatorConnectionState.CONNECTED, service.state.value.connectionState)

        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(recovered.writes.any { it.decodeToString() == "P 100.0 30.0\n" })
        service.disconnect()
    }

    @Test
    fun emergencyStopInvalidatesMovementAndSendsProtocolStop() = runTest {
        val transport = FakeRotatorTransport()
        val settings = activeSettings().copy(protocol = RotatorProtocol.GS232)
        val service = service(
            now = { 50_000L },
            settings = { settings },
            orbit = FakeRotatorOrbitSource(lookProvider = { time -> RotatorLook(100.0, 30.0, time) }),
            transports = ArrayDeque(listOf(transport))
        )

        service.connect()
        service.startTracking(pass(aos = 0L, los = 100_000L))
        runCurrent()
        service.emergencyStop()
        val writeCountAfterStop = transport.writes.size
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("S\r", transport.writes.last().decodeToString())
        assertEquals(writeCountAfterStop, transport.writes.size)
        assertFalse(service.state.value.isTrackingRequested)
        assertEquals(RotatorTrackingPhase.HOLDING, service.state.value.trackingPhase)
        service.disconnect()
    }

    @Test
    fun queriesAndPublishesReportedPosition() = runTest {
        var responseTime = 0L
        val transport = FakeRotatorTransport(
            reads = ArrayDeque(
                listOf(
                    "123.4\n45.6\n".encodeToByteArray(),
                    ByteArray(0),
                    ByteArray(0),
                    ByteArray(0)
                )
            )
        )
        val accumulator = ControlResponseAccumulator(
            nowMillis = { responseTime },
            waitMillis = { responseTime += it }
        )
        val service = service(
            now = { 50_000L },
            orbit = FakeRotatorOrbitSource(lookProvider = { time -> RotatorLook(100.0, 30.0, time) }),
            transports = ArrayDeque(listOf(transport)),
            responseAccumulator = accumulator,
            positionQueryIntervalMillis = 1L
        )

        service.connect()
        service.startTracking(pass(aos = 0L, los = 100_000L))
        runCurrent()

        assertTrue(transport.writes.any { it.decodeToString() == "p\n" })
        assertEquals(123.4, service.state.value.reportedPosition?.azimuthDegrees ?: Double.NaN, 0.0)
        assertEquals(45.6, service.state.value.reportedPosition?.elevationDegrees ?: Double.NaN, 0.0)
        service.disconnect()
    }

    private fun TestScope.service(
        now: () -> Long,
        settings: () -> RotatorSettings = { activeSettings() },
        orbit: RotatorOrbitSource,
        transports: ArrayDeque<ControlTransport>,
        reconnectDelayMillis: Long = 2_000L,
        responseAccumulator: ControlResponseAccumulator = ControlResponseAccumulator(),
        positionQueryIntervalMillis: Long = 0L
    ) = RotatorTrackingService(
        appScope = backgroundScope,
        orbitSource = orbit,
        nowMillis = now,
        settingsProvider = settings,
        transportFactory = { transports.removeFirst() },
        stationPosition = { GeoPos(0.0, 0.0) },
        responseAccumulator = responseAccumulator,
        reconnectDelayMillis = reconnectDelayMillis,
        positionQueryIntervalMillis = positionQueryIntervalMillis
    )

    private fun activeSettings() = RotatorSettings(
        enabled = true,
        protocol = RotatorProtocol.ROTCTLD,
        transport = RotatorTransport.TCP,
        host = "127.0.0.1",
        port = 4533
    )

    private fun pass(aos: Long, los: Long, aosAzimuth: Double = 90.0): OrbitalPass = OrbitalPass(
        aosTime = aos,
        aosAzimuth = aosAzimuth,
        losTime = los,
        orbitalObject = OrbitalData(
            name = "TEST-SAT",
            epoch = 24_001.0,
            meanmo = 15.0,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = 99_001,
            bstar = 0.0
        ).getObject()
    )
}

private class FakeRotatorOrbitSource(
    private val lookProvider: suspend (Long) -> RotatorLook,
    private val flip: Boolean = false
) : RotatorOrbitSource {
    override suspend fun look(pass: OrbitalPass, timeMillis: Long): RotatorLook = lookProvider(timeMillis)

    override suspend fun passRequiresFlip(pass: OrbitalPass, azimuthOffsetDegrees: Double): Boolean = flip
}

private class FakeRotatorTransport(
    private val connectResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
    private val writeResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val reads: ArrayDeque<ByteArray> = ArrayDeque()
) : ControlTransport {
    override var isConnected: Boolean = false
        private set
    val writes = mutableListOf<ByteArray>()
    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set

    override suspend fun connect(): Boolean {
        connectCalls++
        isConnected = connectResults.removeFirstOrNull() ?: true
        return isConnected
    }

    override suspend fun disconnect() {
        disconnectCalls++
        isConnected = false
    }

    override suspend fun write(bytes: ByteArray): Boolean {
        writes += bytes.copyOf()
        val success = writeResults.removeFirstOrNull() ?: true
        if (!success) isConnected = false
        return success
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray {
        val value = reads.removeFirstOrNull() ?: return ByteArray(0)
        return value.copyOf(minOf(value.size, maxBytes))
    }
}
