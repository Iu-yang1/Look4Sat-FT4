/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * The service structure and pointing lifecycle are informed by OrbitDeckiOS.
 * Copyright (c) 2025 Paul Stoetzer, N8HM. Licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IRotatorTrackingService
import com.rtbishop.look4sat.core.domain.rotator.RotatorConnectionState
import com.rtbishop.look4sat.core.domain.rotator.RotatorLook
import com.rtbishop.look4sat.core.domain.rotator.RotatorPassContext
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingDecision
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingInput
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingPolicy
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingPhase
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RotatorTrackingService(
    private val appScope: CoroutineScope,
    private val orbitSource: RotatorOrbitSource,
    private val nowMillis: () -> Long,
    private val settingsProvider: () -> RotatorSettings,
    private val transportFactory: (RotatorSettings) -> ControlTransport,
    private val stationPosition: () -> GeoPos,
    private val magneticDeclinationDegrees: (GeoPos, Long) -> Double = { _, _ -> 0.0 },
    private val responseAccumulator: ControlResponseAccumulator = ControlResponseAccumulator(),
    private val reconnectDelayMillis: Long = DEFAULT_RECONNECT_DELAY_MILLIS,
    private val maxReconnectAttempts: Int = DEFAULT_RECONNECT_ATTEMPTS,
    private val positionQueryIntervalMillis: Long = DEFAULT_POSITION_QUERY_INTERVAL_MILLIS
) : IRotatorTrackingService {
    private val _state = MutableStateFlow(RotatorTrackingState())
    override val state: StateFlow<RotatorTrackingState> = _state

    private val connectionMutex = Mutex()
    private val sequence = AtomicLong(0L)
    private val commandActor = RotatorCommandActor(appScope, ::handleCommandOutcome)

    @Volatile
    private var transport: ControlTransport? = null

    @Volatile
    private var activePass: OrbitalPass? = null

    @Volatile
    private var lastWirePosition: RotatorPosition? = null

    @Volatile
    private var lastSendAtMillis: Long? = null

    private var trackingJob: Job? = null
    private var reconnectJob: Job? = null
    private var wantConnection = false
    private var lastQueryAtMillis: Long? = null
    private var azimuth450Precommitted = false

    init {
        require(reconnectDelayMillis >= 0L)
        require(maxReconnectAttempts >= 0)
        require(positionQueryIntervalMillis >= 0L)
    }

    override suspend fun connect() {
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        connectionMutex.withLock {
            wantConnection = true
            commandActor.cancelPendingTargets()
            connectLocked(reconnecting = false)
        }
    }

    override suspend fun disconnect(park: Boolean) {
        wantConnection = false
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        trackingJob?.cancelAndJoin()
        trackingJob = null
        activePass = null
        commandActor.cancelPendingTargets()
        val settings = settingsProvider().normalized()
        if ((park || settings.parkOnDisconnect) && transport?.isConnected == true) {
            runCatching { parkInternal(settings) }
        }
        connectionMutex.withLock {
            transport?.disconnect()
            transport = null
        }
        resetCommandHistory()
        _state.value = RotatorTrackingState(
            connectionState = RotatorConnectionState.DISCONNECTED,
            protocol = settings.protocol,
            transport = settings.transport
        )
    }

    override fun startTracking(pass: OrbitalPass) {
        activePass = pass
        val settings = settingsProvider().normalized()
        _state.update {
            it.copy(
                isTrackingRequested = true,
                trackingPhase = RotatorTrackingPhase.HOLDING,
                satelliteCatalogNumber = pass.catNum,
                satelliteName = pass.name,
                protocol = settings.protocol,
                transport = settings.transport,
                errorMessage = null
            )
        }
        trackingJob?.cancel()
        trackingJob = appScope.launch { runTrackingLoop(pass) }
    }

    override fun stopTracking(park: Boolean) {
        activePass = null
        trackingJob?.cancel()
        trackingJob = null
        commandActor.cancelPendingTargets()
        azimuth450Precommitted = false
        _state.update {
            it.copy(
                isTrackingRequested = false,
                trackingPhase = RotatorTrackingPhase.IDLE,
                satelliteCatalogNumber = null,
                satelliteName = ""
            )
        }
        if (park) appScope.launch { park() }
    }

    override suspend fun point(position: RotatorPosition) {
        val settings = settingsProvider().normalized()
        val decision = RotatorPointingDecision.Point(
            phase = RotatorTrackingPhase.HOLDING,
            truePosition = position,
            wirePosition = position,
            nextAzimuth450Precommitted = false
        )
        val commandSequence = sequence.incrementAndGet()
        commandActor.executeImmediate(commandSequence, invalidateTargets = true) {
            sendPosition(settings, decision, commandSequence)
        }
    }

    override suspend fun park() {
        parkInternal(settingsProvider().normalized())
    }

    override suspend fun emergencyStop() {
        activePass = null
        trackingJob?.cancelAndJoin()
        trackingJob = null
        azimuth450Precommitted = false
        val settings = settingsProvider().normalized()
        val frame = com.rtbishop.look4sat.core.domain.rotator.RotatorCodec.stop(
            settings.protocol,
            settings.customStopTemplate
        )
        val commandSequence = sequence.incrementAndGet()
        val sent = commandActor.executeStop(commandSequence) {
            frame?.let { transport?.write(it) == true } ?: true
        }
        _state.update {
            it.copy(
                trackingPhase = RotatorTrackingPhase.HOLDING,
                isTrackingRequested = false,
                satelliteCatalogNumber = null,
                satelliteName = "",
                lastCommandSequence = commandSequence,
                lastCommandAtMillis = nowMillis(),
                errorMessage = when {
                    !sent -> "Rotator stop command failed"
                    frame == null -> "${settings.protocol} does not provide a stop command"
                    else -> null
                }
            )
        }
    }

    private suspend fun connectLocked(reconnecting: Boolean): Boolean {
        val settings = settingsProvider().normalized()
        if (!settings.isConfigured) {
            transport = null
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    protocol = settings.protocol,
                    transport = settings.transport,
                    errorMessage = "Rotator settings are incomplete"
                )
            }
            return false
        }
        _state.update {
            it.copy(
                connectionState = if (reconnecting) {
                    RotatorConnectionState.RECONNECTING
                } else {
                    RotatorConnectionState.CONNECTING
                },
                protocol = settings.protocol,
                transport = settings.transport,
                errorMessage = null
            )
        }
        transport?.disconnect()
        val candidate = runCatching { transportFactory(settings) }.getOrElse { error ->
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = error.message ?: "Could not create rotator transport"
                )
            }
            return false
        }
        transport = candidate
        val connected = runCatching { candidate.connect() }.getOrDefault(false)
        if (connected) {
            resetCommandHistory()
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.CONNECTED,
                    trackingPhase = if (activePass == null) {
                        RotatorTrackingPhase.IDLE
                    } else {
                        RotatorTrackingPhase.HOLDING
                    },
                    errorMessage = null
                )
            }
        } else {
            candidate.disconnect()
            if (transport === candidate) transport = null
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = "Could not connect to rotator"
                )
            }
        }
        return connected
    }

    private suspend fun runTrackingLoop(pass: OrbitalPass) {
        val initialSettings = settingsProvider().normalized()
        val requiresFlip = if (initialSettings.flipOverheadPasses) {
            runCatching {
                orbitSource.passRequiresFlip(pass, initialSettings.azimuthOffsetDegrees)
            }.getOrDefault(false)
        } else {
            false
        }
        while (currentCoroutineContext().isActive && activePass === pass) {
            val settings = settingsProvider().normalized()
            if (_state.value.connectionState == RotatorConnectionState.CONNECTED) {
                try {
                    runTrackingCycle(pass, settings, requiresFlip)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _state.update {
                        it.copy(
                            trackingPhase = RotatorTrackingPhase.ERROR,
                            errorMessage = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
            } else {
                _state.update { it.copy(trackingPhase = RotatorTrackingPhase.HOLDING) }
            }
            delay(settings.updateIntervalMillis)
        }
    }

    private suspend fun runTrackingCycle(
        pass: OrbitalPass,
        settings: RotatorSettings,
        requiresFlip: Boolean
    ) {
        val now = nowMillis()
        maybeQueryPosition(settings, now)
        val current = orbitSource.look(pass, now)
        val lead = settings.trackingLeadSeconds.takeIf { it > 0 }?.let {
            orbitSource.look(pass, now + it * MILLIS_PER_SECOND)
        }
        val azimuthAhead = if (
            settings.azimuthLookAheadSeconds > 0 &&
            settings.azimuthRange == com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange.ZERO_TO_450
        ) {
            orbitSource.look(pass, now + settings.azimuthLookAheadSeconds * MILLIS_PER_SECOND)
        } else {
            null
        }
        val decision = RotatorPointingPolicy.decide(
            settings,
            RotatorPointingInput(
                nowMillis = now,
                currentLook = current,
                trackingLeadLook = lead,
                azimuthLookAhead = azimuthAhead,
                nextPass = RotatorPassContext(
                    aosTimeMillis = pass.aosTime,
                    losTimeMillis = pass.losTime,
                    aosAzimuthDegrees = pass.aosAzimuth,
                    requiresFlip = requiresFlip
                ),
                azimuth450Precommitted = azimuth450Precommitted,
                magneticDeclinationDegrees = magneticDeclinationDegrees(stationPosition(), now)
            )
        )
        azimuth450Precommitted = decision.nextAzimuth450Precommitted
        when (decision) {
            is RotatorPointingDecision.Hold -> _state.update {
                it.copy(trackingPhase = RotatorTrackingPhase.HOLDING)
            }
            is RotatorPointingDecision.Point -> {
                _state.update { it.copy(trackingPhase = decision.phase, errorMessage = null) }
                if (
                    RotatorPointingPolicy.shouldSend(
                        previousPosition = lastWirePosition,
                        targetPosition = decision.wirePosition,
                        settings = settings,
                        nowMillis = now,
                        lastSendAtMillis = lastSendAtMillis
                    )
                ) {
                    val commandSequence = sequence.incrementAndGet()
                    commandActor.submitTarget(commandSequence) {
                        sendPosition(settings, decision, commandSequence)
                    }
                } else if (decision.phase == RotatorTrackingPhase.PARKING) {
                    _state.update { it.copy(trackingPhase = RotatorTrackingPhase.PARKED) }
                }
            }
        }
    }

    private suspend fun sendPosition(
        settings: RotatorSettings,
        decision: RotatorPointingDecision.Point,
        commandSequence: Long
    ): Boolean {
        val currentTransport = transport ?: return false
        val frame = com.rtbishop.look4sat.core.domain.rotator.RotatorCodec.point(
            settings.protocol,
            decision.wirePosition,
            settings.customPointTemplate
        )
        val success = currentTransport.write(frame)
        if (success) {
            val sentAt = nowMillis()
            lastWirePosition = decision.wirePosition
            lastSendAtMillis = sentAt
            _state.update {
                it.copy(
                    trackingPhase = if (decision.phase == RotatorTrackingPhase.PARKING) {
                        RotatorTrackingPhase.PARKED
                    } else {
                        decision.phase
                    },
                    commandedPosition = decision.truePosition,
                    lastCommandSequence = commandSequence,
                    lastCommandAtMillis = sentAt,
                    errorMessage = null
                )
            }
        }
        return success
    }

    private suspend fun parkInternal(settings: RotatorSettings) {
        val decision = RotatorPointingPolicy.decide(
            settings.copy(enabled = true, parkOnLos = true),
            RotatorPointingInput(
                nowMillis = nowMillis(),
                currentLook = null,
                magneticDeclinationDegrees = magneticDeclinationDegrees(stationPosition(), nowMillis())
            )
        ) as? RotatorPointingDecision.Point ?: return
        _state.update { it.copy(trackingPhase = RotatorTrackingPhase.PARKING) }
        val commandSequence = sequence.incrementAndGet()
        commandActor.executeImmediate(commandSequence, invalidateTargets = true) {
            sendPosition(settings, decision, commandSequence)
        }
        azimuth450Precommitted = false
    }

    private suspend fun maybeQueryPosition(settings: RotatorSettings, now: Long) {
        if (positionQueryIntervalMillis == 0L) return
        if (!settings.protocol.supportsPositionQuery && settings.protocol != RotatorProtocol.PST_ROTATOR) return
        val previousQuery = lastQueryAtMillis
        if (previousQuery != null && now - previousQuery < positionQueryIntervalMillis) return
        lastQueryAtMillis = now
        val commandSequence = sequence.incrementAndGet()
        runCatching {
            commandActor.executeQuery(commandSequence) {
                val currentTransport = transport ?: return@executeQuery false
                val query = com.rtbishop.look4sat.core.domain.rotator.RotatorCodec.positionQuery(
                    settings.protocol,
                    settings.customQueryTemplate
                )
                if (query != null && !currentTransport.write(query)) return@executeQuery false
                val response = responseAccumulator.readUntilQuiet(
                    transport = currentTransport,
                    maxBytes = MAX_RESPONSE_BYTES,
                    timeoutMillis = RESPONSE_TIMEOUT_MILLIS
                )
                val position = com.rtbishop.look4sat.core.domain.rotator.RotatorCodec.parsePosition(
                    settings.protocol,
                    response
                )
                if (position != null) _state.update { it.copy(reportedPosition = position) }
                true
            }
        }
    }

    private fun handleCommandOutcome(outcome: RotatorCommandOutcome) {
        if (outcome.superseded) return
        _state.update {
            it.copy(
                lastCommandSequence = maxOf(it.lastCommandSequence, outcome.sequence),
                errorMessage = if (outcome.success) it.errorMessage else outcome.errorMessage
            )
        }
        if (!outcome.success && wantConnection) scheduleReconnect(outcome.errorMessage)
    }

    private fun scheduleReconnect(reason: String?) {
        if (!wantConnection || reconnectJob?.isActive == true || maxReconnectAttempts == 0) return
        reconnectJob = appScope.launch {
            commandActor.cancelPendingTargets()
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.RECONNECTING,
                    trackingPhase = RotatorTrackingPhase.HOLDING,
                    errorMessage = reason
                )
            }
            connectionMutex.withLock {
                transport?.disconnect()
                transport = null
            }
            repeat(maxReconnectAttempts) {
                delay(reconnectDelayMillis)
                if (!wantConnection) return@launch
                val connected = connectionMutex.withLock { connectLocked(reconnecting = true) }
                if (connected) return@launch
            }
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = "Rotator reconnect attempts exhausted"
                )
            }
        }
    }

    private fun resetCommandHistory() {
        lastWirePosition = null
        lastSendAtMillis = null
        lastQueryAtMillis = null
        azimuth450Precommitted = false
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val RESPONSE_TIMEOUT_MILLIS = 500L
        const val MAX_RESPONSE_BYTES = 4_096
        const val DEFAULT_RECONNECT_DELAY_MILLIS = 2_000L
        const val DEFAULT_RECONNECT_ATTEMPTS = 3
        const val DEFAULT_POSITION_QUERY_INTERVAL_MILLIS = 2_000L
    }
}
