/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * Portions of the rotator-control model are adapted from OrbitDeckiOS.
 * Copyright (c) 2025 Paul Stoetzer, N8HM. Licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.rotator

enum class RotatorTransport {
    BLUETOOTH_SPP,
    USB_SERIAL,
    TCP,
    UDP
}

enum class RotatorProtocol(
    val defaultPort: Int?,
    val supportsPositionQuery: Boolean,
    val supportsStop: Boolean
) {
    GS232(defaultPort = null, supportsPositionQuery = true, supportsStop = true),
    EASYCOMM_I(defaultPort = null, supportsPositionQuery = true, supportsStop = true),
    EASYCOMM_II(defaultPort = null, supportsPositionQuery = true, supportsStop = true),
    EASYCOMM_III(defaultPort = null, supportsPositionQuery = true, supportsStop = true),
    SPID_ROT2PROG(defaultPort = null, supportsPositionQuery = true, supportsStop = true),
    SAEBRTRACK(defaultPort = null, supportsPositionQuery = false, supportsStop = false),
    ROTCTLD(defaultPort = 4533, supportsPositionQuery = true, supportsStop = true),
    PST_ROTATOR(defaultPort = 12000, supportsPositionQuery = false, supportsStop = true),
    OZ9AAR_URC(defaultPort = 1111, supportsPositionQuery = true, supportsStop = false),
    CUSTOM_TEMPLATE(defaultPort = null, supportsPositionQuery = false, supportsStop = false);

    fun supportsTransport(transport: RotatorTransport): Boolean = when (this) {
        GS232,
        EASYCOMM_I,
        EASYCOMM_II,
        EASYCOMM_III,
        SPID_ROT2PROG,
        SAEBRTRACK -> transport == RotatorTransport.BLUETOOTH_SPP ||
            transport == RotatorTransport.USB_SERIAL ||
            transport == RotatorTransport.TCP
        ROTCTLD, OZ9AAR_URC -> transport == RotatorTransport.TCP
        PST_ROTATOR -> transport == RotatorTransport.UDP
        CUSTOM_TEMPLATE -> true
    }
}

enum class RotatorAzimuthRange {
    ZERO_TO_360,
    MINUS_180_TO_180,
    ZERO_TO_450
}

data class RotatorSettings(
    val enabled: Boolean = false,
    val protocol: RotatorProtocol = RotatorProtocol.ROTCTLD,
    val transport: RotatorTransport = RotatorTransport.TCP,
    val deviceAddress: String = "",
    val host: String = "127.0.0.1",
    val port: Int = RotatorProtocol.ROTCTLD.defaultPort ?: 4533,
    val baudRate: Int = 9600,
    val customPointTemplate: String = "P \$AZ \$EL\n",
    val customStopTemplate: String = "",
    val customQueryTemplate: String = "",
    val prepositionLeadSeconds: Int = 120,
    val trackingLeadSeconds: Int = 0,
    val azimuthLookAheadSeconds: Int = 3,
    val azimuthRange: RotatorAzimuthRange = RotatorAzimuthRange.ZERO_TO_360,
    val azimuthOffsetDegrees: Double = 0.0,
    val elevationOffsetDegrees: Double = 0.0,
    val deadbandDegrees: Double = 3.0,
    val magneticCorrection: Boolean = false,
    val parkAzimuthDegrees: Double = 0.0,
    val parkElevationDegrees: Double = 0.0,
    val parkOnLos: Boolean = true,
    val parkOnDisconnect: Boolean = false,
    val flipOverheadPasses: Boolean = false,
    val minimumElevationDegrees: Double = 0.0,
    val updateIntervalMillis: Long = 1_000L,
    val sampleTimeoutMillis: Long = 3_000L
) {
    val isConfigured: Boolean
        get() = enabled && protocol.supportsTransport(transport) && when (transport) {
            RotatorTransport.BLUETOOTH_SPP, RotatorTransport.USB_SERIAL -> deviceAddress.isNotBlank()
            RotatorTransport.TCP, RotatorTransport.UDP -> host.isNotBlank() && port in 1..65_535
        }

    fun normalized(): RotatorSettings = copy(
        deviceAddress = deviceAddress.trim(),
        host = host.trim(),
        port = port.takeIf { it in 1..65_535 } ?: protocol.defaultPort ?: 0,
        baudRate = baudRate.coerceIn(300, 921_600),
        prepositionLeadSeconds = prepositionLeadSeconds.coerceIn(0, 600),
        trackingLeadSeconds = trackingLeadSeconds.coerceIn(0, 30),
        azimuthLookAheadSeconds = azimuthLookAheadSeconds.coerceIn(0, 30),
        azimuthOffsetDegrees = azimuthOffsetDegrees.finiteOrZero().coerceIn(-360.0, 360.0),
        elevationOffsetDegrees = elevationOffsetDegrees.finiteOrZero().coerceIn(-180.0, 180.0),
        deadbandDegrees = deadbandDegrees.finiteOrZero().coerceIn(0.0, 90.0),
        parkAzimuthDegrees = parkAzimuthDegrees.finiteOrZero().coerceIn(-180.0, 450.0),
        parkElevationDegrees = parkElevationDegrees.finiteOrZero().coerceIn(0.0, 180.0),
        minimumElevationDegrees = minimumElevationDegrees.finiteOrZero().coerceIn(0.0, 90.0),
        updateIntervalMillis = updateIntervalMillis.coerceIn(200L, 5_000L),
        sampleTimeoutMillis = sampleTimeoutMillis.coerceIn(500L, 30_000L)
    )
}

data class RotatorPosition(
    val azimuthDegrees: Double,
    val elevationDegrees: Double
)

enum class RotatorConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class RotatorTrackingPhase {
    IDLE,
    HOLDING,
    PREPOSITIONING,
    TRACKING,
    PARKING,
    PARKED,
    ERROR
}

data class RotatorTrackingState(
    val connectionState: RotatorConnectionState = RotatorConnectionState.DISCONNECTED,
    val trackingPhase: RotatorTrackingPhase = RotatorTrackingPhase.IDLE,
    val isTrackingRequested: Boolean = false,
    val protocol: RotatorProtocol? = null,
    val transport: RotatorTransport? = null,
    val satelliteCatalogNumber: Int? = null,
    val satelliteName: String = "",
    val commandedPosition: RotatorPosition? = null,
    val reportedPosition: RotatorPosition? = null,
    val lastCommandSequence: Long = 0L,
    val lastCommandAtMillis: Long? = null,
    val errorMessage: String? = null
)

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
