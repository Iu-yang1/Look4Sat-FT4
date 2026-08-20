/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.time

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssClock
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.GnssTimeConverter
import java.util.concurrent.Executor
import kotlin.math.max

/** 只提交具有近期非 mock GPS fix、FullBias 与闰秒信息的 GNSS 时间。 */
class AndroidGnssTimeDiscipline(
    context: Context,
    private val submitSample: (ClockSample) -> Boolean
) {
    private val applicationContext = context.applicationContext
    private val locationManager =
        applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val directExecutor = Executor { command -> command.run() }
    private var started = false
    private var lastTrustedFixElapsedNanos = Long.MIN_VALUE
    private var lastDiscontinuityCount: Int? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            @Suppress("DEPRECATION")
            if (!location.isFromMockProvider) {
                lastTrustedFixElapsedNanos = location.elapsedRealtimeNanos
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            acceptGnssClock(eventArgs.clock)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): StartResult {
        if (started) return StartResult.ALREADY_STARTED
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return StartResult.PERMISSION_REQUIRED
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return StartResult.PROVIDER_DISABLED
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            locationListener
        )
        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssMeasurementsCallback(directExecutor, measurementsCallback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssMeasurementsCallback(measurementsCallback)
        }
        if (!registered) {
            locationManager.removeUpdates(locationListener)
            return StartResult.REGISTRATION_FAILED
        }
        started = true
        return StartResult.STARTED
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssMeasurementsCallback(measurementsCallback)
        started = false
        lastTrustedFixElapsedNanos = Long.MIN_VALUE
        lastDiscontinuityCount = null
    }

    private fun acceptGnssClock(clock: GnssClock) {
        if (!clock.hasFullBiasNanos() || !clock.hasLeapSecond()) return
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        if (lastTrustedFixElapsedNanos == Long.MIN_VALUE ||
            nowElapsed - lastTrustedFixElapsedNanos > MAX_FIX_AGE_NANOS
        ) return

        val discontinuity = clock.hardwareClockDiscontinuityCount
        val previousDiscontinuity = lastDiscontinuityCount
        lastDiscontinuityCount = discontinuity
        if (previousDiscontinuity != null && previousDiscontinuity != discontinuity) return

        val sampleElapsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            clock.hasElapsedRealtimeNanos()
        ) clock.elapsedRealtimeNanos else nowElapsed
        val biasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val utcMillis = GnssTimeConverter.toUtcMillis(
            timeNanos = clock.timeNanos,
            fullBiasNanos = clock.fullBiasNanos,
            biasNanos = biasNanos,
            leapSeconds = clock.leapSecond
        )
        var uncertaintyMillis = if (clock.hasTimeUncertaintyNanos()) {
            max(1.0, clock.timeUncertaintyNanos / 1_000_000.0)
        } else {
            20.0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            clock.hasElapsedRealtimeUncertaintyNanos()
        ) {
            uncertaintyMillis += clock.elapsedRealtimeUncertaintyNanos / 1_000_000.0
        }
        submitSample(
            ClockSample(
                utcMillis = utcMillis,
                monotonicNanos = sampleElapsed,
                uncertaintyMillis = uncertaintyMillis,
                source = ClockSource.GNSS,
                detail = "GNSS time fix"
            )
        )
    }

    enum class StartResult {
        STARTED,
        ALREADY_STARTED,
        PERMISSION_REQUIRED,
        PROVIDER_DISABLED,
        REGISTRATION_FAILED
    }

    companion object {
        private const val MAX_FIX_AGE_NANOS = 120_000_000_000L
    }
}
