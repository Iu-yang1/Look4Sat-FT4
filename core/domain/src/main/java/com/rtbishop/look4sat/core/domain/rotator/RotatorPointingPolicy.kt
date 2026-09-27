/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * Portions of the pointing policy are adapted from OrbitDeckiOS.
 * Copyright (c) 2025 Paul Stoetzer, N8HM. Licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.rotator

import kotlin.math.abs
import kotlin.math.min

data class RotatorLook(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val sampleTimeMillis: Long
)

data class RotatorPassContext(
    val aosTimeMillis: Long,
    val losTimeMillis: Long,
    val aosAzimuthDegrees: Double,
    val requiresFlip: Boolean = false
)

data class RotatorPointingInput(
    val nowMillis: Long,
    val currentLook: RotatorLook?,
    val trackingLeadLook: RotatorLook? = null,
    val azimuthLookAhead: RotatorLook? = null,
    val nextPass: RotatorPassContext? = null,
    val azimuth450Precommitted: Boolean = false,
    val magneticDeclinationDegrees: Double = 0.0
)

enum class RotatorHoldReason {
    DISABLED,
    NOT_CONFIGURED,
    NO_POSITION,
    STALE_POSITION,
    INVALID_POSITION,
    BELOW_MINIMUM_ELEVATION
}

sealed interface RotatorPointingDecision {
    val nextAzimuth450Precommitted: Boolean

    data class Point(
        val phase: RotatorTrackingPhase,
        val truePosition: RotatorPosition,
        val wirePosition: RotatorPosition,
        override val nextAzimuth450Precommitted: Boolean
    ) : RotatorPointingDecision

    data class Hold(
        val reason: RotatorHoldReason,
        override val nextAzimuth450Precommitted: Boolean = false
    ) : RotatorPointingDecision
}

object RotatorPointingPolicy {

    fun decide(settings: RotatorSettings, input: RotatorPointingInput): RotatorPointingDecision {
        if (!settings.enabled) return RotatorPointingDecision.Hold(RotatorHoldReason.DISABLED)
        if (!settings.isConfigured) return RotatorPointingDecision.Hold(RotatorHoldReason.NOT_CONFIGURED)

        val normalized = settings.normalized()
        val current = input.currentLook
        if (current != null && !current.hasFiniteCoordinates()) {
            return RotatorPointingDecision.Hold(RotatorHoldReason.INVALID_POSITION)
        }
        if (current != null && input.nowMillis - current.sampleTimeMillis > normalized.sampleTimeoutMillis) {
            return RotatorPointingDecision.Hold(RotatorHoldReason.STALE_POSITION)
        }

        val visible = current != null &&
            current.elevationDegrees >= 0.0 &&
            current.elevationDegrees >= normalized.minimumElevationDegrees
        if (visible) {
            val aim = input.trackingLeadLook
                ?.takeIf { normalized.trackingLeadSeconds > 0 && it.hasFiniteCoordinates() }
                ?: current
            val precommitted = nextPrecommitState(normalized, input, aim)
            return targetDecision(
                phase = RotatorTrackingPhase.TRACKING,
                sourceAzimuth = aim.azimuthDegrees,
                sourceElevation = aim.elevationDegrees,
                flip = normalized.flipOverheadPasses && input.nextPass?.requiresFlip == true,
                precommitted = precommitted,
                settings = normalized,
                magneticDeclinationDegrees = input.magneticDeclinationDegrees
            )
        }

        val pass = input.nextPass
        val millisToAos = pass?.aosTimeMillis?.minus(input.nowMillis)
        val shouldPreposition = pass != null &&
            millisToAos != null &&
            millisToAos > 0L &&
            millisToAos <= normalized.prepositionLeadSeconds * MILLIS_PER_SECOND
        if (shouldPreposition) {
            return targetDecision(
                phase = RotatorTrackingPhase.PREPOSITIONING,
                sourceAzimuth = pass.aosAzimuthDegrees,
                sourceElevation = 0.0,
                flip = normalized.flipOverheadPasses && pass.requiresFlip,
                precommitted = false,
                settings = normalized,
                magneticDeclinationDegrees = input.magneticDeclinationDegrees
            )
        }

        if (normalized.parkOnLos) {
            return targetDecision(
                phase = RotatorTrackingPhase.PARKING,
                sourceAzimuth = normalized.parkAzimuthDegrees,
                sourceElevation = normalized.parkElevationDegrees,
                flip = false,
                precommitted = false,
                settings = normalized.copy(azimuthOffsetDegrees = 0.0, elevationOffsetDegrees = 0.0),
                magneticDeclinationDegrees = input.magneticDeclinationDegrees
            )
        }

        val reason = if (current == null) {
            RotatorHoldReason.NO_POSITION
        } else {
            RotatorHoldReason.BELOW_MINIMUM_ELEVATION
        }
        return RotatorPointingDecision.Hold(reason)
    }

    fun shouldSend(
        previousPosition: RotatorPosition?,
        targetPosition: RotatorPosition,
        settings: RotatorSettings,
        nowMillis: Long,
        lastSendAtMillis: Long?
    ): Boolean {
        if (!targetPosition.hasFiniteCoordinates()) return false
        if (previousPosition == null || !previousPosition.hasFiniteCoordinates()) return true
        val normalized = settings.normalized()
        val rawAzimuthDelta = abs(targetPosition.azimuthDegrees - previousPosition.azimuthDegrees)
        val azimuthDelta = if (normalized.azimuthRange == RotatorAzimuthRange.ZERO_TO_360) {
            min(rawAzimuthDelta, abs(360.0 - rawAzimuthDelta))
        } else {
            rawAzimuthDelta
        }
        val elevationDelta = abs(targetPosition.elevationDegrees - previousPosition.elevationDegrees)
        if (azimuthDelta >= normalized.deadbandDegrees || elevationDelta >= normalized.deadbandDegrees) {
            return true
        }
        return normalized.protocol == RotatorProtocol.PST_ROTATOR &&
            (lastSendAtMillis == null || nowMillis - lastSendAtMillis >= PST_KEEPALIVE_MILLIS)
    }

    private fun targetDecision(
        phase: RotatorTrackingPhase,
        sourceAzimuth: Double,
        sourceElevation: Double,
        flip: Boolean,
        precommitted: Boolean,
        settings: RotatorSettings,
        magneticDeclinationDegrees: Double
    ): RotatorPointingDecision {
        if (!sourceAzimuth.isFinite() || !sourceElevation.isFinite()) {
            return RotatorPointingDecision.Hold(RotatorHoldReason.INVALID_POSITION)
        }
        var azimuth = sourceAzimuth + settings.azimuthOffsetDegrees
        var elevation = sourceElevation + settings.elevationOffsetDegrees
        if (flip) {
            azimuth += 180.0
            elevation = 180.0 - elevation
        }
        val truePosition = RotatorPosition(
            azimuthDegrees = normalizeAzimuth(azimuth, settings.azimuthRange, precommitted),
            elevationDegrees = elevation.coerceIn(0.0, if (flip) 180.0 else 90.0)
        )
        val declination = magneticDeclinationDegrees.takeIf(Double::isFinite) ?: 0.0
        val wirePosition = if (settings.magneticCorrection) {
            truePosition.copy(
                azimuthDegrees = normalizeAzimuth(
                    truePosition.azimuthDegrees - declination,
                    settings.azimuthRange,
                    precommitted
                )
            )
        } else {
            truePosition
        }
        return RotatorPointingDecision.Point(
            phase = phase,
            truePosition = truePosition,
            wirePosition = wirePosition,
            nextAzimuth450Precommitted = precommitted
        )
    }

    private fun nextPrecommitState(
        settings: RotatorSettings,
        input: RotatorPointingInput,
        aim: RotatorLook
    ): Boolean {
        if (settings.azimuthRange != RotatorAzimuthRange.ZERO_TO_450) return false
        if (input.azimuth450Precommitted) return true
        if (settings.azimuthLookAheadSeconds <= 0) return false
        val ahead = input.azimuthLookAhead?.takeIf { it.hasFiniteCoordinates() } ?: return false
        val currentAzimuth = wrap360(aim.azimuthDegrees + settings.azimuthOffsetDegrees)
        val aheadAzimuth = wrap360(ahead.azimuthDegrees + settings.azimuthOffsetDegrees)
        return currentAzimuth <= 90.0 && aheadAzimuth > 270.0
    }

    private fun normalizeAzimuth(
        azimuth: Double,
        range: RotatorAzimuthRange,
        precommitted: Boolean
    ): Double {
        val wrapped = wrap360(azimuth)
        return when (range) {
            RotatorAzimuthRange.ZERO_TO_360 -> wrapped
            RotatorAzimuthRange.MINUS_180_TO_180 -> if (wrapped > 180.0) wrapped - 360.0 else wrapped
            RotatorAzimuthRange.ZERO_TO_450 -> if (precommitted && wrapped <= 90.0) wrapped + 360.0 else wrapped
        }
    }

    private fun wrap360(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private fun RotatorLook.hasFiniteCoordinates(): Boolean =
        azimuthDegrees.isFinite() && elevationDegrees.isFinite()

    private fun RotatorPosition.hasFiniteCoordinates(): Boolean =
        azimuthDegrees.isFinite() && elevationDegrees.isFinite()

    private const val MILLIS_PER_SECOND = 1_000L
    private const val PST_KEEPALIVE_MILLIS = 2_000L
}
