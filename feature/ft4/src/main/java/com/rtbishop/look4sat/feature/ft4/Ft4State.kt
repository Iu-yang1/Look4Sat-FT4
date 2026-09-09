/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomationSnapshot
import com.rtbishop.look4sat.core.domain.ft4.Ft4Capability
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.domain.model.Ft4Settings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot

data class Ft4State(
    val settings: Ft4Settings,
    val capability: Ft4Capability,
    val engineState: Ft4EngineState,
    val decodeResults: List<Ft4DecodeResult>,
    val clock: ClockSnapshot,
    val radio: RadioTrackingState,
    val audioHub: AudioHubState,
    val transmitState: Ft4TransmitState,
    val automation: Ft4AutomationSnapshot = Ft4AutomationSnapshot(),
    val radioTransport: String = RadioControlSettings.TRANSPORT_BLUETOOTH,
    val stationGrid: String = "",
    val targetCall: String = "",
    val selectedAudioFrequencyHz: Float = 1_500f,
    val txSlotParity: Int = 0,
    val hasMicrophonePermission: Boolean = false,
    val error: String = "",
    val manualTimeWarning: Boolean = false,
    val selectedPass: OrbitalPass? = null,
    val orbitalPosition: OrbitalPos? = null,
    val satelliteTrack: List<OrbitalPos> = emptyList(),
    val orientationValues: Pair<Float, Float> = 0f to 0f,
    val shouldUseCompass: Boolean = false,
    val shouldShowSweep: Boolean = false
) {
    val grid4: String
        get() = stationGrid.take(4).uppercase()

    val isReceiving: Boolean
        get() = engineState is Ft4EngineState.Receiving

    val trackingPass: OrbitalPass?
        get() = radio.currentPass ?: selectedPass
}

data class Ft4TimingState(
    val nowUtcMillis: Long,
    val slotProgress: Float,
    val clock: ClockSnapshot
)

sealed interface Ft4Action {
    data class MicrophonePermissionChanged(val granted: Boolean) : Ft4Action
    data object ToggleReceiving : Ft4Action
    data object ClearDecodes : Ft4Action
    data class SelectAudioFrequency(val frequencyHz: Float) : Ft4Action
    data class SetTargetCall(val callsign: String) : Ft4Action
    data class SelectDecode(val result: Ft4DecodeResult) : Ft4Action
    data class SetTxSlotParity(val parity: Int) : Ft4Action
    data object ManualTransmit : Ft4Action
    data object StopTransmit : Ft4Action
    data object ArmAutomation : Ft4Action
    data object StopAutomation : Ft4Action
    data object ConnectRadios : Ft4Action
    data object DisconnectRadios : Ft4Action
    data object ToggleTracking : Ft4Action
    data object EmergencyStop : Ft4Action
    data object ClearError : Ft4Action
    data object Leave : Ft4Action
}
