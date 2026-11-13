/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.predict.CelestialState
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.PassRadarPreview
import com.rtbishop.look4sat.core.presentation.PassDateDivider
import com.rtbishop.look4sat.core.presentation.elevationColor
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val PASS_SCHEDULE_PREFS = "pass_schedule_preferences"
private const val PASS_SCHEDULE_CHANNEL = "satellite_pass_schedule"
private const val PASS_SCHEDULE_WINDOW_HOURS = 72
private const val PASS_SCHEDULE_CACHE_TTL_MS = 5 * 60_000L
private val PASS_REMINDER_PRESETS = listOf(5, 10, 15, 30, 60)
private val PASS_REMINDER_OPTIONS = listOf(0) + PASS_REMINDER_PRESETS

private data class PassScheduleConfig(
    val satelliteIds: Set<Int> = emptySet(),
    val minimumElevation: Float = 10f,
    /** 0 means at AOS; positive values are minutes before AOS. */
    val reminderMinutes: Set<Int> = emptySet()
)

/** Cache pass predictions across navigation to the radar detail screen. */
private data class PassScheduleCalculationKey(
    val selectedSatelliteIds: Set<Int>,
    val trackedSatelliteIds: Set<Int>,
    val minimumElevation: Float,
    val satellitesFingerprint: Int,
    val stationPosition: GeoPos
)

private object PassSchedulePassCache {
    private var key: PassScheduleCalculationKey? = null
    private var passes: List<OrbitalPass> = emptyList()
    private var calculatedAtMillis: Long = 0L

    fun get(key: PassScheduleCalculationKey): List<OrbitalPass>? {
        val ageMillis = System.currentTimeMillis() - calculatedAtMillis
        return if (this.key == key && ageMillis in 0L..PASS_SCHEDULE_CACHE_TTL_MS) passes else null
    }

    fun put(key: PassScheduleCalculationKey, passes: List<OrbitalPass>) {
        this.key = key
        this.passes = passes
        calculatedAtMillis = System.currentTimeMillis()
    }
}

private data class ScheduledPass(
    val catNum: Int,
    val name: String,
    val aosTime: Long,
    val losTime: Long,
    val maxElevation: Double
)

private object PassScheduleStore {
    private fun prefs(context: Context) = context.getSharedPreferences(PASS_SCHEDULE_PREFS, Context.MODE_PRIVATE)

    fun readConfig(context: Context): PassScheduleConfig {
        val prefs = prefs(context)
        val legacyReminder = prefs.getInt("reminder_minutes", 10)
            .takeIf { it in PASS_REMINDER_PRESETS } ?: 10
        val reminderMinutes = prefs.getStringSet("reminder_minutes_set", null)
            ?.mapNotNull(String::toIntOrNull)
            ?.filter { it in PASS_REMINDER_OPTIONS }
            ?.toSet()
            ?: buildSet {
                if (prefs.getBoolean("notify_at_start", false)) add(0)
                if (prefs.getBoolean("notify_before", false)) add(legacyReminder)
            }
        val legacyElevation = runCatching { prefs.getInt("minimum_elevation", 10).toFloat() }.getOrDefault(10f)
        val storedElevation = runCatching {
            prefs.getFloat("minimum_elevation_value", legacyElevation)
        }.getOrDefault(legacyElevation)
        return PassScheduleConfig(
            satelliteIds = prefs.getStringSet("satellite_ids", emptySet()).orEmpty()
                .mapNotNull(String::toIntOrNull).toSet(),
            minimumElevation = storedElevation.coerceIn(0f, 90f),
            reminderMinutes = reminderMinutes
        )
    }

    fun saveConfig(context: Context, config: PassScheduleConfig) {
        val reminderMinutes = config.reminderMinutes.intersect(PASS_REMINDER_OPTIONS.toSet())
        prefs(context).edit()
            .putStringSet("satellite_ids", config.satelliteIds.map { it.toString() }.toSet())
            .putInt("minimum_elevation", config.minimumElevation.coerceIn(0f, 90f).toInt())
            .putFloat("minimum_elevation_value", config.minimumElevation.coerceIn(0f, 90f))
            .putStringSet("reminder_minutes_set", reminderMinutes.map { it.toString() }.toSet())
            // Keep the legacy keys in sync for older builds that may still be installed.
            .putBoolean("notify_at_start", 0 in reminderMinutes)
            .putBoolean("notify_before", reminderMinutes.any { it > 0 })
            .putInt("reminder_minutes", reminderMinutes.firstOrNull { it > 0 } ?: 10)
            .apply()
    }

    fun readEvents(context: Context): List<ScheduledPass> = runCatching {
        val array = JSONArray(prefs(context).getString("events", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val catNum = item.optInt("catNum", 0)
            val aos = item.optLong("aosTime", 0L)
            if (catNum <= 0 || aos <= 0L) return@mapNotNull null
            ScheduledPass(
                catNum = catNum,
                name = item.optString("name", "Satellite $catNum"),
                aosTime = aos,
                losTime = item.optLong("losTime", 0L),
                maxElevation = item.optDouble("maxElevation", 0.0)
            )
        }
    }.getOrDefault(emptyList())

    fun saveEvents(context: Context, events: List<ScheduledPass>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().apply {
                put("catNum", event.catNum)
                put("name", event.name)
                put("aosTime", event.aosTime)
                put("losTime", event.losTime)
                put("maxElevation", event.maxElevation)
            })
        }
        prefs(context).edit().putString("events", array.toString()).apply()
    }
}

private object PassScheduleNotificationScheduler {
    const val ACTION_ALERT = "com.rtbishop.look4sat.PASS_SCHEDULE_ALERT"
    const val ACTION_REFRESH = "com.rtbishop.look4sat.PASS_SCHEDULE_REFRESH"
    const val EXTRA_CAT_NUM = "cat_num"
    const val EXTRA_SATELLITE_NAME = "satellite_name"
    const val EXTRA_AOS_TIME = "aos_time"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MAX_ELEVATION = "max_elevation"
    const val EXTRA_REMINDER_MINUTES = "reminder_minutes"
    const val KIND_START = "start"
    const val KIND_BEFORE = "before"

    fun replacePasses(context: Context, config: PassScheduleConfig, passes: List<OrbitalPass>) {
        replaceEvents(context, config, passes.map { pass ->
            ScheduledPass(pass.catNum, pass.name, pass.aosTime, pass.losTime, pass.maxElevation)
        })
    }

    fun replaceEvents(context: Context, config: PassScheduleConfig, events: List<ScheduledPass>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val oldEvents = PassScheduleStore.readEvents(context)
        oldEvents.forEach { event ->
            cancelEventAlarm(context, alarmManager, event, KIND_START)
            // Cancel the previous single-reminder alarm and every multi-select alarm.
            cancelEventAlarm(context, alarmManager, event, KIND_BEFORE)
            PASS_REMINDER_PRESETS.forEach { minutes ->
                cancelEventAlarm(context, alarmManager, event, KIND_BEFORE, minutes)
            }
        }
        cancelRefresh(context, alarmManager)
        val futureEvents = events.filter { it.losTime > System.currentTimeMillis() }
            .distinctBy { it.catNum to it.aosTime }
            .sortedBy { it.aosTime }
        PassScheduleStore.saveEvents(context, futureEvents)
        val now = System.currentTimeMillis()
        futureEvents.forEach { event ->
            config.reminderMinutes.forEach { minutes ->
                if (minutes == 0) {
                    if (event.aosTime > now) {
                        scheduleEvent(context, alarmManager, event, KIND_START, event.aosTime)
                    }
                } else {
                    val beforeTime = event.aosTime - minutes * 60_000L
                    if (beforeTime > now) {
                        scheduleEvent(context, alarmManager, event, KIND_BEFORE, beforeTime, minutes)
                    }
                }
            }
        }
        if (config.satelliteIds.isNotEmpty()) {
            val refreshIntent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
                action = ACTION_REFRESH
                data = Uri.parse("look4sat://pass-schedule/refresh")
            }
            setAlarm(context, alarmManager, System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
                PendingIntent.getBroadcast(context, 0, refreshIntent, pendingFlags()))
        }
    }

    fun restoreAfterReboot(context: Context) {
        replaceEvents(context, PassScheduleStore.readConfig(context), PassScheduleStore.readEvents(context))
    }

    fun showAlert(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureNotificationChannel(context)
        val name = intent.getStringExtra(EXTRA_SATELLITE_NAME).orEmpty().ifBlank { "Satellite" }
        val aosTime = intent.getLongExtra(EXTRA_AOS_TIME, 0L)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(aosTime))
        val title = if (kind == KIND_BEFORE) {
            context.getString(R.string.pass_schedule_notification_before_title)
        } else {
            context.getString(R.string.pass_schedule_notification_start_title)
        }
        val content = if (kind == KIND_BEFORE) {
            context.getString(R.string.pass_schedule_notification_before_text, name, time)
        } else {
            context.getString(R.string.pass_schedule_notification_start_text, name, time)
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )
        val notification = NotificationCompat.Builder(context, PASS_SCHEDULE_CHANNEL)
            .setSmallIcon(R.drawable.ic_satellite_alt)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val reminderMinutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0)
        val id = ((intent.getIntExtra(EXTRA_CAT_NUM, 0) * 31 + aosTime.hashCode()) * 31 + kind.hashCode()) * 31 + reminderMinutes
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun scheduleRefreshRetry(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_REFRESH
            data = Uri.parse("look4sat://pass-schedule/refresh")
        }
        setAlarm(context, manager, System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
            PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
    }

    private fun scheduleEvent(
        context: Context,
        manager: AlarmManager,
        event: ScheduledPass,
        kind: String,
        triggerAt: Long,
        reminderMinutes: Int? = null
    ) {
        val alarmKey = if (kind == KIND_BEFORE && reminderMinutes != null) "$kind/$reminderMinutes" else kind
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_ALERT
            data = Uri.parse("look4sat://pass-schedule/${event.catNum}/${event.aosTime}/$alarmKey")
            putExtra(EXTRA_CAT_NUM, event.catNum)
            putExtra(EXTRA_SATELLITE_NAME, event.name)
            putExtra(EXTRA_AOS_TIME, event.aosTime)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_MAX_ELEVATION, event.maxElevation)
            putExtra(EXTRA_REMINDER_MINUTES, reminderMinutes ?: 0)
        }
        setAlarm(context, manager, triggerAt,
            PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
    }

    private fun cancelEventAlarm(
        context: Context,
        manager: AlarmManager,
        event: ScheduledPass,
        kind: String,
        reminderMinutes: Int? = null
    ) {
        val alarmKey = if (kind == KIND_BEFORE && reminderMinutes != null) "$kind/$reminderMinutes" else kind
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_ALERT
            data = Uri.parse("look4sat://pass-schedule/${event.catNum}/${event.aosTime}/$alarmKey")
        }
        PendingIntent.getBroadcast(context, 0, intent, pendingFlags())?.let(manager::cancel)
    }

    private fun cancelRefresh(context: Context, manager: AlarmManager) {
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_REFRESH
            data = Uri.parse("look4sat://pass-schedule/refresh")
        }
        PendingIntent.getBroadcast(context, 0, intent, pendingFlags())?.let(manager::cancel)
    }

    private fun setAlarm(context: Context, manager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            val channel = android.app.NotificationChannel(
                PASS_SCHEDULE_CHANNEL,
                context.getString(R.string.pass_schedule_notification_channel),
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.pass_schedule_notification_channel_description) }
            manager.createNotificationChannel(channel)
        }
    }
}

class PassScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PassScheduleNotificationScheduler.ACTION_ALERT ->
                PassScheduleNotificationScheduler.showAlert(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> PassScheduleNotificationScheduler.restoreAfterReboot(context)
            PassScheduleNotificationScheduler.ACTION_REFRESH -> refreshSchedule(context)
        }
    }

    private fun refreshSchedule(context: Context) {
        val pending: PendingResult = goAsync()
        val provider = context.applicationContext as? IContainerProvider
        val container = provider?.getMainContainer()
        if (container == null) {
            pending.finish()
            PassScheduleNotificationScheduler.scheduleRefreshRetry(context)
            return
        }
        container.appScope.launch {
            try {
                val config = PassScheduleStore.readConfig(context)
                if (config.satelliteIds.isEmpty()) {
                    PassScheduleNotificationScheduler.replaceEvents(context, config, emptyList())
                    return@launch
                }
                withTimeoutOrNull(20_000L) {
                    container.satelliteRepo.satellites.first { it.isNotEmpty() }
                }
                val passes = container.satelliteRepo.getSchedulePasses(
                    catNums = config.satelliteIds,
                    startTime = System.currentTimeMillis(),
                    hoursAhead = PASS_SCHEDULE_WINDOW_HOURS,
                    minElevation = config.minimumElevation.toDouble()
                )
                PassScheduleNotificationScheduler.replacePasses(context, config, passes)
            } catch (_: Exception) {
                PassScheduleNotificationScheduler.scheduleRefreshRetry(context)
            } finally {
                pending.finish()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PassScheduleDestination(
    navigateUp: () -> Unit,
    navigateToRadar: (Int, Long) -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val satellites by container.satelliteRepo.satellites.collectAsStateWithLifecycle()
    val currentlyTrackedIds by container.settingsRepo.selectedIds.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    val stationPosition by container.settingsRepo.stationPosition.collectAsStateWithLifecycle()
    val isUtc = otherSettings.stateOfUtc
    var config by remember(context) { mutableStateOf(PassScheduleStore.readConfig(context)) }
    val passCalculationKey = remember(
        satellites,
        currentlyTrackedIds,
        config.satelliteIds,
        config.minimumElevation,
        stationPosition.latitude,
        stationPosition.longitude,
        stationPosition.altitude
    ) {
        PassScheduleCalculationKey(
            selectedSatelliteIds = config.satelliteIds,
            trackedSatelliteIds = currentlyTrackedIds.toSet(),
            minimumElevation = config.minimumElevation,
            satellitesFingerprint = satellites.hashCode(),
            stationPosition = GeoPos(stationPosition.latitude, stationPosition.longitude, stationPosition.altitude)
        )
    }
    // Restore previously calculated rows immediately when returning from pass details.
    var passes by remember(passCalculationKey) {
        mutableStateOf(PassSchedulePassCache.get(passCalculationKey) ?: emptyList())
    }
    var isCalculating by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var minimumElevationSlider by remember(config.minimumElevation) { mutableStateOf(config.minimumElevation) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val showTrackingSettings = remember { mutableStateOf(false) }
    // Completed passes drop off the list the moment LOS passes. The schedule is only
    // recalculated on user changes, so filter against the ticking clock instead of
    // recomputing predictions every second.
    val visiblePasses by remember(passCalculationKey) {
        derivedStateOf { passes.filter { it.losTime > currentTime } }
    }
    var scheduleDays by remember { mutableStateOf<List<PassScheduleDay>>(emptyList()) }
    LaunchedEffect(visiblePasses, isUtc, stationPosition) {
        scheduleDays = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            groupPassScheduleByDate(visiblePasses, isUtc, stationPosition, container.satelliteRepo.celestial)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun updateConfig(next: PassScheduleConfig, requestNotificationPermissionIfNeeded: Boolean = false) {
        config = next
        PassScheduleStore.saveConfig(context, next)
        if (requestNotificationPermissionIfNeeded) requestNotificationPermission()
    }

    BackHandler(enabled = showTrackingSettings.value) { showTrackingSettings.value = false }

    LaunchedEffect(passCalculationKey, config.reminderMinutes) {
        // The repository is populated asynchronously after app startup. Wait for it
        // when the tracking selection is non-empty, rather than clearing a saved schedule.
        if (satellites.isEmpty() && currentlyTrackedIds.isNotEmpty()) return@LaunchedEffect

        // The schedule can only contain satellites that are currently selected for tracking.
        val availableIds = currentlyTrackedIds.toSet()
        val validSelection = config.satelliteIds.intersect(availableIds)
        if (validSelection != config.satelliteIds) {
            val cleanedConfig = config.copy(satelliteIds = validSelection)
            config = cleanedConfig
            PassScheduleStore.saveConfig(context, cleanedConfig)
            return@LaunchedEffect
        }

        val cachedPasses = PassSchedulePassCache.get(passCalculationKey)
        val calculatedPasses = if (cachedPasses != null) {
            // Navigation back to this destination can reuse the recent prediction.
            cachedPasses
        } else {
            isCalculating = true
            try {
                val calculated = if (config.satelliteIds.isEmpty()) {
                    emptyList()
                } else {
                    container.satelliteRepo.getSchedulePasses(
                        catNums = config.satelliteIds,
                        startTime = System.currentTimeMillis(),
                        hoursAhead = PASS_SCHEDULE_WINDOW_HOURS,
                        minElevation = config.minimumElevation.toDouble()
                    )
                }
                PassSchedulePassCache.put(passCalculationKey, calculated)
                calculated
            } finally {
                isCalculating = false
            }
        }
        passes = calculatedPasses
        PassScheduleStore.saveConfig(context, config)
        // Refresh alarms when reminders change, reusing cached predictions when possible.
        PassScheduleNotificationScheduler.replacePasses(context, config, calculatedPasses)
    }

    Scaffold(
        // NavigationSuiteScaffold owns the app's bottom navigation area. Do not
        // apply the system navigation-bar inset a second time inside this screen.
        // Keeping top and horizontal safe insets still protects the content on
        // edge-to-edge devices in both gesture and three-button navigation modes.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (showTrackingSettings.value) R.string.pass_schedule_tracking_settings
                            else R.string.pass_schedule_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showTrackingSettings.value) showTrackingSettings.value = false else navigateUp()
                    }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.radar_back))
                    }
                },
                actions = {
                    if (!showTrackingSettings.value) {
                        TextButton(onClick = { showTrackingSettings.value = true }) {
                            Text(stringResource(R.string.pass_schedule_tracking_settings))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (showTrackingSettings.value) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.pass_schedule_tracking_hint), style = MaterialTheme.typography.bodyMedium)
                if (satellites.isEmpty()) {
                    Text(stringResource(R.string.pass_schedule_no_tracked_satellites), color = MaterialTheme.colorScheme.error)
                } else {
                    satellites.forEach { satellite ->
                        val catNum = satellite.data.catnum
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val selected = config.satelliteIds.toMutableSet()
                                if (!selected.add(catNum)) selected.remove(catNum)
                                updateConfig(config.copy(satelliteIds = selected))
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = catNum in config.satelliteIds,
                                onCheckedChange = { checked ->
                                    val selected = config.satelliteIds.toMutableSet()
                                    if (checked) selected.add(catNum) else selected.remove(catNum)
                                    updateConfig(config.copy(satelliteIds = selected))
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(satellite.data.name, fontWeight = FontWeight.Medium)
                                Text("NORAD $catNum", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.pass_schedule_minimum_elevation, minimumElevationSlider),
                    style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = minimumElevationSlider,
                    onValueChange = { minimumElevationSlider = it.coerceIn(0f, 90f) },
                    onValueChangeFinished = {
                        updateConfig(config.copy(minimumElevation = minimumElevationSlider))
                    },
                    valueRange = 0f..90f,
                    steps = 0
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0°", style = MaterialTheme.typography.labelSmall)
                    Text("90°", style = MaterialTheme.typography.labelSmall)
                }

                HorizontalDivider()
                Text(stringResource(R.string.pass_schedule_notifications), style = MaterialTheme.typography.titleMedium)
                val reminderRows = listOf(PASS_REMINDER_OPTIONS.take(3), PASS_REMINDER_OPTIONS.drop(3))
                reminderRows.forEach { rowMinutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowMinutes.forEach { minutes ->
                            val selected = minutes in config.reminderMinutes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val reminders = config.reminderMinutes.toMutableSet()
                                    if (selected) reminders.remove(minutes) else reminders.add(minutes)
                                    updateConfig(
                                        config.copy(reminderMinutes = reminders),
                                        requestNotificationPermissionIfNeeded = !selected
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        if (minutes == 0) stringResource(R.string.pass_schedule_notify_at_start)
                                        else stringResource(R.string.pass_schedule_minutes_short, minutes),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(AlarmManager::class.java)
                    if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                        TextButton(onClick = {
                            val request = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(request) }
                        }) {
                            Text(stringResource(R.string.pass_schedule_enable_exact_alarm))
                        }
                    }
                }
                Button(onClick = { showTrackingSettings.value = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pass_schedule_done))
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * otherSettings.passListFontScale
                )
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(stringResource(R.string.pass_schedule_range), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.pass_schedule_summary, config.satelliteIds.size, visiblePasses.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            if (isCalculating) Text(stringResource(R.string.pass_schedule_calculating),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (config.satelliteIds.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.pass_schedule_empty_selection), fontWeight = FontWeight.Medium)
                                    Text(stringResource(R.string.pass_schedule_empty_selection_hint), style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = { showTrackingSettings.value = true }) {
                                        Text(stringResource(R.string.pass_schedule_tracking_settings))
                                    }
                                }
                            }
                        }
                    } else if (visiblePasses.isEmpty() && !isCalculating) {
                        item {
                            Text(stringResource(R.string.pass_schedule_no_passes),
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    scheduleDays.forEach { day ->
                        stickyHeader(key = "schedule_date_${day.label}") {
                            PassDateDivider(
                                label = day.label,
                                sunriseTime = day.sunriseTime,
                                sunsetTime = day.sunsetTime
                            )
                        }
                        items(day.passes, key = { "${it.catNum}-${it.aosTime}" }) { pass ->
                            PassScheduleItem(
                                pass = pass,
                                navigateToRadar = navigateToRadar,
                                now = currentTime,
                                isUtc = isUtc,
                                satelliteRepo = container.satelliteRepo,
                                stationPosition = stationPosition
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    }
            }
        }
    }
}
private data class PassScheduleDay(
    val label: String,
    val sunriseTime: String,
    val sunsetTime: String,
    val passes: List<OrbitalPass>
)

private suspend fun groupPassScheduleByDate(
    passes: List<OrbitalPass>,
    isUtc: Boolean,
    stationPosition: GeoPos,
    celestial: com.rtbishop.look4sat.core.domain.astro.ICelestialService
): List<PassScheduleDay> {
    if (passes.isEmpty()) return emptyList()
    val displayTimeZone = if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    val datePattern = if (Locale.getDefault().language == "zh") {
        "yyyy'年'M'月'd'日' EEEE"
    } else {
        "EEE, dd MMM yyyy"
    }
    val dateFormatter = SimpleDateFormat(datePattern, Locale.getDefault()).also {
        it.timeZone = displayTimeZone
    }
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault()).also {
        it.timeZone = displayTimeZone
    }
    val grouped = LinkedHashMap<String, List<OrbitalPass>>()
    passes.groupByTo(LinkedHashMap()) { dateFormatter.format(Date(it.aosTime)) }
        .forEach { (label, dayPasses) -> grouped[label] = dayPasses }

    return grouped.map { (label, dayPasses) ->
        // Use a calendar day in the same time zone as the date grouping.
        val dateAnchor = dayPasses.first().aosTime
        val localMidnight = startOfDayMillis(dateAnchor, displayTimeZone)
        val riseSet = findSunRiseSetForDisplayDay(stationPosition, localMidnight, celestial,
            displayTimeZone, dayPasses.first().orbitalObject.snapshot)
        PassScheduleDay(
            label = label,
            sunriseTime = if (riseSet.riseTimeMillis > 0L) timeFormatter.format(Date(riseSet.riseTimeMillis)) else "--:--",
            sunsetTime = if (riseSet.setTimeMillis > 0L) timeFormatter.format(Date(riseSet.setTimeMillis)) else "--:--",
            passes = dayPasses
        )
    }
}

private fun startOfDayMillis(timeMillis: Long, timeZone: TimeZone): Long =
    Calendar.getInstance(timeZone, Locale.getDefault()).apply {
        this.timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private suspend fun findSunRiseSetForDisplayDay(
    observer: GeoPos,
    localMidnightMillis: Long,
    celestial: com.rtbishop.look4sat.core.domain.astro.ICelestialService,
    timezone: TimeZone,
    snapshot: com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot?
): CelestialState.RiseSetTimes {
    val end = Calendar.getInstance(timezone).apply {
        timeInMillis = localMidnightMillis
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
    val context = kotlin.coroutines.coroutineContext
    return celestial.riseSet(observer, localMidnightMillis, end, snapshot = snapshot,
        checkCancelled = { context.ensureActive() })
}

@Composable
private fun PassScheduleItem(
    pass: OrbitalPass,
    navigateToRadar: (Int, Long) -> Unit,
    now: Long,
    isUtc: Boolean,
    satelliteRepo: com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo,
    stationPosition: GeoPos
) {
    val timeZone = remember(isUtc) {
        if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    val timeFormat = remember(isUtc) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).also { it.timeZone = timeZone }
    }
    val aosTimeStr = remember(pass.aosTime, isUtc) { timeFormat.format(Date(pass.aosTime)) }
    val losTimeStr = remember(pass.losTime, isUtc) { timeFormat.format(Date(pass.losTime)) }
    val durationStr = remember(pass.aosTime, pass.losTime) {
        val seconds = (pass.losTime - pass.aosTime) / 1000L
        "${seconds / 60}m ${seconds % 60}s"
    }
    val isInProgress = pass.aosTime <= now && pass.losTime > now
    // The schedule's calculated pass list is refreshed on user changes, not every
    // second. Derive progress from the ticking wall clock so the active pass bar
    // advances continuously from AOS to LOS instead of remaining at its stale value.
    val passProgress = when {
        pass.continuousVisibility -> 1f
        pass.losTime <= pass.aosTime -> 0f
        else -> ((now - pass.aosTime).toFloat() / (pass.losTime - pass.aosTime).toFloat())
            .coerceIn(0f, 1f)
    }
    val countdownTarget = if (isInProgress) pass.losTime else pass.aosTime
    val remainingSeconds = ((countdownTarget - now) / 1000L).coerceAtLeast(0L)
    val countdown = String.format(
        androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
        "%02d:%02d:%02d",
        remainingSeconds / 3600L,
        (remainingSeconds % 3600L) / 60L,
        remainingSeconds % 60L
    )
    val countdownLabel = if (isInProgress) {
        stringResource(R.string.pass_schedule_los_countdown, countdown)
    } else {
        stringResource(R.string.pass_schedule_countdown, countdown)
    }

    Column(modifier = Modifier.fillMaxWidth().clickable { navigateToRadar(pass.catNum, pass.aosTime) }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pass.name,
                            modifier = Modifier.weight(1f).padding(end = 6.dp).infiniteMarquee(),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 21.sp,
                            lineHeight = 22.sp
                        )
                        Text(
                            text = countdownLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 18.sp,
                            lineHeight = 19.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (pass.isDeepSpace) stringResource(R.string.pass_deep_space) else durationStr,
                            modifier = Modifier.weight(1f),
                            fontSize = 18.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pass.altitude} km",
                            modifier = Modifier.weight(1f),
                            fontSize = 18.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        val elevColor = elevationColor(pass.maxElevation)
                        Text(
                            text = stringResource(R.string.radar_el_value, pass.maxElevation),
                            modifier = Modifier.weight(0.7f),
                            fontSize = 18.sp,
                            lineHeight = 19.sp,
                            color = elevColor,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.pass_aosLos, pass.aosAzimuth.toInt(), pass.losAzimuth.toInt()),
                            modifier = Modifier.weight(1.2f),
                            fontSize = 18.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val defaultTime = "--:--:--"
                        Text(
                            text = if (pass.isDeepSpace || pass.startClipped) defaultTime else aosTimeStr,
                            fontSize = 16.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        LinearProgressIndicator(
                            progress = { if (pass.isDeepSpace) 1f else passProgress },
                            drawStopIndicator = {},
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        Text(
                            text = if (pass.isDeepSpace || pass.endClipped) defaultTime else losTimeStr,
                            fontSize = 16.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                PassRadarPreview(
                    pass = pass,
                    satelliteRepo = satelliteRepo,
                    stationPosition = stationPosition,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        HorizontalDivider(
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}
