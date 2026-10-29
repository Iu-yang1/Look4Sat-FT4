package com.rtbishop.look4sat.feature.logbook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWPhase
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.R as CoreR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private data class PendingBackgroundSync(
    val provider: LogbookSyncProvider,
    val mode: String,
    val callsign: String = "",
    val password: String = ""
)

@Composable
internal fun LoTWUploadDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingBackgroundSync by remember { mutableStateOf<PendingBackgroundSync?>(null) }

    fun startBackgroundSync(request: PendingBackgroundSync) {
        try {
            LogbookBackgroundSyncController.start(
                context = context,
                provider = request.provider,
                mode = request.mode,
                callsign = request.callsign,
                password = request.password
            )
            Toast.makeText(context, R.string.logbook_sync_started, Toast.LENGTH_LONG).show()
            onAction(LogbookAction.DismissLoTWAfterBackgroundStart)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.logbook_sync_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingBackgroundSync
        pendingBackgroundSync = null
        if (request == null) return@rememberLauncherForActivityResult
        if (granted) {
            startBackgroundSync(request)
        } else {
            Toast.makeText(
                context,
                R.string.logbook_sync_notification_permission_required,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestBackgroundSync(
        provider: LogbookSyncProvider,
        mode: String,
        callsign: String = "",
        password: String = ""
    ) {
        val request = PendingBackgroundSync(provider, mode, callsign, password)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundSync = request
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startBackgroundSync(request)
        }
    }

    var certificateBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var accountCallsign by rememberSaveable { mutableStateOf(state.lotwSettings.callsign) }
    var accountPassword by rememberSaveable { mutableStateOf(state.lotwSettings.password) }
    var wavelogUrl by rememberSaveable { mutableStateOf(state.wavelogSettings.url) }
    var wavelogApiKey by rememberSaveable { mutableStateOf(state.wavelogSettings.apiKey) }
    var showWavelogClearConfirm by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(state.lotwDefaultGrid) }
    var cq by remember { mutableStateOf("") }
    var itu by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var county by remember { mutableStateOf("") }
    var iota by remember { mutableStateOf("") }
    var resubmit by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var removeConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(state.lotwStation) {
        state.lotwStation?.let { saved ->
            grid = saved.grid; cq = saved.cqZone; itu = saved.ituZone
            region = saved.region; county = saved.county; iota = saved.iota
        }
    }
    LaunchedEffect(state.lotwSettings) {
        if (!state.lotwSyncing) {
            accountCallsign = state.lotwSettings.callsign
            accountPassword = state.lotwSettings.password
        }
    }
    LaunchedEffect(state.wavelogSettings.url, state.wavelogSettings.apiKey) {
        if (!state.wavelogSyncing && !state.wavelogProbeBusy) {
            wavelogUrl = state.wavelogSettings.url
            wavelogApiKey = state.wavelogSettings.apiKey
        }
    }
    DisposableEffect(Unit) { onDispose { certificateBytes?.fill(0) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            fileError = false
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 1024 * 1024)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
                if (bytes == null) {
                    fileError = true
                } else {
                    certificateBytes?.fill(0)
                    certificateBytes = bytes
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fileError = true }
            finally { reading = false }
        }
    }
    val busy = state.lotwSyncing || state.lotwAuditing || state.wavelogSyncing || state.wavelogProbeBusy || reading
    val certificate = state.lotwCertificate
    val profileReady = certificate?.passwordSaved == true && state.lotwStation != null
    val editedStation = LoTWStation(grid, cq, itu, region, county, iota)
    val profileDirty = certificateBytes != null || password.isNotEmpty() || state.lotwStation != editedStation
    val uploadReady = profileReady && !profileDirty
    val audit = state.lotwAudit
    val pendingCount = audit?.pending ?: 0
    val unavailable = stringResource(R.string.logbook_value_unavailable)
    SharedDialog(
        title = stringResource(R.string.logbook_sync),
        // The foreground service owns sync work, so the UI can be dismissed at any time.
        onDismissRequest = { onAction(LogbookAction.DismissLoTW) }
    ) { padding ->
            val scheme = MaterialTheme.colorScheme
            val lock = state.logbookSourceLock
            val lotwBlocked = lock != null && lock != LogbookSyncProvider.LOTW
            val wavelogBlocked = lock != null && lock != LogbookSyncProvider.WAVELOG
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = padding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.syncProvider == LogbookSyncProvider.LOTW,
                    onClick = { onAction(LogbookAction.SelectSyncProvider(LogbookSyncProvider.LOTW)) },
                    enabled = !busy,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = if (lotwBlocked) scheme.surfaceVariant else scheme.surface,
                        labelColor = if (lotwBlocked) scheme.onSurfaceVariant else scheme.onSurface,
                        selectedContainerColor = if (lotwBlocked) scheme.surfaceVariant else scheme.secondaryContainer,
                        selectedLabelColor = if (lotwBlocked) scheme.onSurfaceVariant else scheme.onSecondaryContainer
                    ),
                    label = { Text("LoTW") }
                )
                FilterChip(
                    selected = state.syncProvider == LogbookSyncProvider.WAVELOG,
                    onClick = { onAction(LogbookAction.SelectSyncProvider(LogbookSyncProvider.WAVELOG)) },
                    enabled = !busy,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = if (wavelogBlocked) scheme.surfaceVariant else scheme.surface,
                        labelColor = if (wavelogBlocked) scheme.onSurfaceVariant else scheme.onSurface,
                        selectedContainerColor = if (wavelogBlocked) scheme.surfaceVariant else scheme.secondaryContainer,
                        selectedLabelColor = if (wavelogBlocked) scheme.onSurfaceVariant else scheme.onSecondaryContainer
                    ),
                    label = { Text("Wavelog") }
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = padding, vertical = 8.dp)
            ) {
                if (state.syncProvider == LogbookSyncProvider.LOTW) {
                LoTWDownloadCard(
                    state = state,
                    callsign = accountCallsign,
                    password = accountPassword,
                    onCallsignChange = { accountCallsign = it.uppercase() },
                    onPasswordChange = { accountPassword = it },
                    busy = busy,
                    onSync = { mode ->
                        requestBackgroundSync(
                            provider = LogbookSyncProvider.LOTW,
                            mode = mode.name,
                            callsign = accountCallsign,
                            password = accountPassword
                        )
                    }
                )
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lotw_certificate_section), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.lotw_certificate_hint), style = MaterialTheme.typography.bodySmall)
                        certificate?.let { cert ->
                            Text(stringResource(
                                R.string.lotw_certificate_info,
                                cert.callsign,
                                cert.dxcc,
                                cert.expires,
                                cert.firstQsoDate,
                                cert.lastQsoDate.ifBlank { unavailable }
                            ))
                        }
                        TextButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Text(stringResource(if (certificateBytes == null) R.string.lotw_choose_certificate else R.string.lotw_certificate_selected))
                        }
                        OutlinedTextField(
                            password,
                            { password = it },
                            label = {
                                Text(stringResource(
                                    if (certificate?.passwordSaved == true && certificateBytes == null) {
                                        R.string.lotw_certificate_password_saved
                                    } else {
                                        R.string.lotw_certificate_password
                                    }
                                ))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.lotw_station_hint), style = MaterialTheme.typography.bodySmall)
                        LoTWField(grid, { grid = it }, R.string.lotw_station_grid, !busy)
                        LoTWField(cq, { cq = it }, R.string.lotw_cq_zone, !busy)
                        LoTWField(itu, { itu = it }, R.string.lotw_itu_zone, !busy)
                        LoTWField(region, { region = it }, R.string.lotw_region, !busy)
                        LoTWField(county, { county = it }, R.string.lotw_county, !busy)
                        LoTWField(iota, { iota = it }, R.string.lotw_iota, !busy)
                        Button(
                            onClick = {
                                val selected = certificateBytes
                                val passwordChars = when {
                                    selected != null -> password.toCharArray()
                                    certificate?.passwordSaved != true || password.isNotEmpty() -> password.toCharArray()
                                    else -> null
                                }
                                onAction(LogbookAction.SaveLoTWProfile(
                                    selected,
                                    passwordChars,
                                    editedStation
                                ))
                                certificateBytes = null
                                password = ""
                            },
                            enabled = !busy && grid.isNotBlank() && (certificateBytes != null || certificate != null),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.lotw_save_certificate_profile)) }
                        if (state.lotwProfileSaved) {
                            Text(
                                stringResource(R.string.lotw_certificate_profile_saved),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (certificate != null) {
                            TextButton(onClick = { removeConfirmation = true }, enabled = !busy) {
                                Text(stringResource(R.string.lotw_remove_certificate))
                            }
                        }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lotw_log_upload_section), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.lotw_audit_hint), style = MaterialTheme.typography.bodySmall)
                        if (!uploadReady) {
                            Text(
                                stringResource(if (profileReady) R.string.lotw_unsaved_profile else R.string.lotw_setup_required),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            if (state.lotwAuditing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            audit?.let { result ->
                                Text(
                                    stringResource(R.string.lotw_pending_count, result.pending),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(stringResource(
                                    R.string.lotw_audit_summary,
                                    result.total,
                                    result.uploaded,
                                    result.unknown,
                                    result.unavailable
                                ), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                stringResource(R.string.lotw_upload_scope, state.filteredRecords.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { onAction(LogbookAction.RefreshLoTWUpload) },
                                enabled = !busy && !state.lotwAuditing
                            ) { Text(stringResource(R.string.lotw_recheck)) }
                            LoTWCheck(resubmit, { resubmit = it }, R.string.lotw_retry_unknown, !busy)
                            Button(
                                onClick = { onAction(LogbookAction.PrepareLoTWUpload(resubmit)) },
                                enabled = !busy && !state.lotwAuditing && (pendingCount > 0 || resubmit),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(
                                    if (resubmit) R.string.lotw_retry_upload_button else R.string.lotw_upload_button,
                                    if (resubmit) state.filteredRecords.size else pendingCount
                                ))
                            }
                        }
                    }
                }
                if (busy && state.lotwSyncMode == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (fileError) Text(stringResource(R.string.lotw_certificate_file_error), color = MaterialTheme.colorScheme.error)
                state.lotwProblem?.let { error ->
                    Text(stringResource(problemText(error.reason)), color = MaterialTheme.colorScheme.error)
                    if (error.detail.isNotBlank()) Text(error.detail.take(80), style = MaterialTheme.typography.bodySmall)
                }
                if (!busy) state.lotwUploadResult?.let { result ->
                    Text(when (result) {
                        is LoTWUploadResult.Accepted -> stringResource(R.string.lotw_upload_accepted, result.count)
                        is LoTWUploadResult.Rejected -> stringResource(R.string.lotw_upload_rejected) +
                            result.message.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                        is LoTWUploadResult.Unknown -> stringResource(CoreR.string.lotw_upload_result_unknown_guidance) +
                            result.reason.takeIf {it.isNotBlank()}?.let {"\n$it"}.orEmpty()
                        is LoTWUploadResult.NotSent -> stringResource(CoreR.string.lotw_upload_result_not_sent_guidance) +
                            result.reason.takeIf {it.isNotBlank()}?.let {"\n$it"}.orEmpty()
                        LoTWUploadResult.ExpiredPreview -> stringResource(R.string.lotw_preview_expired)
                    })
                }
                } else {
                    WavelogSyncSettingsContent(
                        state = state,
                        url = wavelogUrl,
                        apiKey = wavelogApiKey,
                        onUrlChange = { wavelogUrl = it },
                        onApiKeyChange = { wavelogApiKey = it },
                        onFetchStations = { onAction(LogbookAction.FetchWavelogStations(wavelogUrl, wavelogApiKey)) },
                        onSelectStation = { onAction(LogbookAction.SelectWavelogStation(it)) },
                        onFullSync = {
                            onAction(LogbookAction.SaveWavelogSettings(state.wavelogSettings.copy(url = wavelogUrl.trim(), apiKey = wavelogApiKey.trim())))
                            requestBackgroundSync(LogbookSyncProvider.WAVELOG, WavelogSyncMode.Full.name)
                        },
                        onIncrementalSync = {
                            onAction(LogbookAction.SaveWavelogSettings(state.wavelogSettings.copy(url = wavelogUrl.trim(), apiKey = wavelogApiKey.trim())))
                            requestBackgroundSync(LogbookSyncProvider.WAVELOG, WavelogSyncMode.Incremental.name)
                        },
                        onClear = { showWavelogClearConfirm = true },
                        busy = busy
                    )
                }
            }
    }
    state.providerSwitchConflict?.let { requestedProvider ->
        AlertDialog(
            onDismissRequest = { onAction(LogbookAction.DismissProviderSwitchConflict) },
            title = { Text(stringResource(R.string.logbook_sync_source_conflict_title)) },
            text = {
                Text(stringResource(
                    if (requestedProvider == LogbookSyncProvider.WAVELOG) {
                        R.string.logbook_sync_source_conflict_wavelog
                    } else {
                        R.string.logbook_sync_source_conflict_lotw
                    }
                ))
            },
            confirmButton = {
                TextButton(onClick = { onAction(LogbookAction.DismissProviderSwitchConflict) }) {
                    Text(stringResource(CoreR.string.btn_accept))
                }
            }
        )
    }
    if (showWavelogClearConfirm) AlertDialog(
        onDismissRequest = { showWavelogClearConfirm = false },
        title = { Text(stringResource(CoreR.string.prefs_wavelog_clear_title)) },
        text = { Text(stringResource(CoreR.string.prefs_wavelog_clear_message)) },
        confirmButton = {
            TextButton(onClick = {
                showWavelogClearConfirm = false
                onAction(LogbookAction.ClearWavelogConfig)
                wavelogUrl = ""
                wavelogApiKey = ""
            }) { Text(stringResource(CoreR.string.btn_accept)) }
        },
        dismissButton = { TextButton(onClick = { showWavelogClearConfirm = false }) { Text(stringResource(R.string.logbook_cancel)) } }
    )
    if (state.syncProvider == LogbookSyncProvider.LOTW) state.lotwPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { onAction(LogbookAction.DismissLoTWPreview) },
            title = { Text(stringResource(R.string.lotw_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.lotw_preview_summary, preview.callsign, preview.dxcc, preview.grid, preview.count, preview.skipped))
                    if (preview.unknownSkipped > 0) Text(stringResource(R.string.lotw_unknown_skipped, preview.unknownSkipped))
                    if (preview.count == 0) Text(stringResource(R.string.lotw_nothing_to_upload)) else {
                        Text(stringResource(R.string.lotw_preview_utc, preview.firstUtc, preview.lastUtc))
                        Text(stringResource(R.string.lotw_preview_warning), style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.heightIn(max = 200.dp)) { items(preview.contacts) { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(LogbookAction.ConfirmLoTWUpload) }, enabled = preview.count > 0 && !busy) {
                    Text(stringResource(R.string.lotw_submit, preview.count))
                }
            },
            dismissButton = { TextButton(onClick = { onAction(LogbookAction.DismissLoTWPreview) }) { Text(stringResource(R.string.logbook_cancel)) } }
        )
    }
    if (removeConfirmation) AlertDialog(
        onDismissRequest = { removeConfirmation = false },
        title = { Text(stringResource(R.string.lotw_remove_certificate)) },
        text = { Text(stringResource(R.string.lotw_remove_certificate_hint)) },
        confirmButton = { TextButton(onClick = { onAction(LogbookAction.RemoveLoTWCertificate); removeConfirmation = false }) { Text(stringResource(R.string.logbook_delete)) } },
        dismissButton = { TextButton(onClick = { removeConfirmation = false }) { Text(stringResource(R.string.logbook_cancel)) } }
    )
}

@Composable
private fun WavelogSyncSettingsContent(
    state: LogbookState,
    url: String,
    apiKey: String,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onFetchStations: () -> Unit,
    onSelectStation: (WavelogStationInfo) -> Unit,
    onFullSync: () -> Unit,
    onIncrementalSync: () -> Unit,
    onClear: () -> Unit,
    busy: Boolean
) {
    val settings = state.wavelogSettings
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(CoreR.string.prefs_wavelog_url)) },
        placeholder = { Text("http://100.x.x.x/wavelog") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(CoreR.string.prefs_wavelog_upload_apikey)) },
        placeholder = { Text("wl…") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = onFetchStations, enabled = !busy && url.isNotBlank() && apiKey.isNotBlank()) {
        Text(if (state.wavelogProbeBusy) stringResource(CoreR.string.prefs_wavelog_upload_probing) else stringResource(CoreR.string.prefs_wavelog_upload_fetch))
    }
    if (state.wavelogRights.isNotBlank()) {
        Text(
            text = when (state.wavelogRights) {
                "rw" -> stringResource(CoreR.string.prefs_wavelog_upload_rights_rw)
                else -> stringResource(CoreR.string.prefs_wavelog_upload_rights_r)
            },
            color = if (state.wavelogRights == "rw") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.wavelogStations.isEmpty() && settings.stationId.isBlank()) {
        Text(stringResource(CoreR.string.prefs_wavelog_upload_station_none), style = MaterialTheme.typography.bodySmall)
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
        ) {
            state.wavelogStations.forEach { station ->
                val selected = station.id == settings.stationId
                Text(
                    text = (if (selected) "● " else "○ ") + listOf(station.name, station.callsign, station.grid)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelectStation(station) }.padding(vertical = 4.dp)
                )
            }
        }
    }
    if (settings.stationId.isNotBlank()) {
        Text(
            stringResource(
                CoreR.string.prefs_wavelog_upload_configured,
                listOf(settings.stationName, settings.stationCallsign, settings.stationGrid).filter { it.isNotBlank() }.joinToString(" · ")
            ),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.wavelogLastSyncEpochMs != 0L) {
        val timePattern = stringResource(CoreR.string.prefs_updated_time)
        val lastSyncText = remember(state.wavelogLastSyncEpochMs, timePattern) {
            java.text.SimpleDateFormat(timePattern, java.util.Locale.getDefault()).format(java.util.Date(state.wavelogLastSyncEpochMs))
        }
        Text(stringResource(CoreR.string.prefs_updated_title, lastSyncText), style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onFullSync, enabled = !busy && settings.isConfigured && state.wavelogStations.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Text(stringResource(CoreR.string.prefs_wavelog_sync_full))
        }
        Button(onClick = onIncrementalSync, enabled = !busy && settings.isConfigured && state.wavelogStations.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Text(stringResource(CoreR.string.prefs_wavelog_sync_incremental))
        }
    }
    if (state.wavelogSyncing) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(CoreR.string.prefs_wavelog_syncing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.wavelogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(stringResource(CoreR.string.prefs_wavelog_upload_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onClear, enabled = !busy) {
        Text(stringResource(CoreR.string.prefs_wavelog_clear), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LoTWDownloadCard(
    state: LogbookState,
    callsign: String,
    password: String,
    onCallsignChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    busy: Boolean,
    onSync: (LoTWSyncMode) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lotw_download_section), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.lotw_download_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = callsign,
                onValueChange = onCallsignChange,
                label = { Text(stringResource(CoreR.string.prefs_lotw_callsign)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(CoreR.string.prefs_lotw_password)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false
                ),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(CoreR.string.prefs_lotw_hint, state.workedGridsCount),
                style = MaterialTheme.typography.bodySmall
            )
            if (state.lotwSyncMode != null) {
                val progress = state.lotwProgress
                val progressText = when {
                    progress == null || progress.phase == LoTWPhase.Connecting ->
                        stringResource(CoreR.string.lotw_sync_progress_connecting)
                    progress.fraction >= 1f ->
                        stringResource(CoreR.string.lotw_sync_progress_saving)
                    progress.qsoCount > 0 && progress.remainingSeconds > 0 ->
                        stringResource(
                            CoreR.string.lotw_sync_progress_qso,
                            progress.qsoCount,
                            progress.remainingSeconds
                        )
                    progress.qsoCount > 0 ->
                        stringResource(CoreR.string.lotw_sync_progress_count, progress.qsoCount)
                    else -> stringResource(CoreR.string.lotw_sync_progress_downloading)
                }
                Text(progressText, style = MaterialTheme.typography.bodySmall)
                if (progress != null && progress.expectedBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.lotwDownloadError?.let { error ->
                val message = when (error) {
                    LoTWDownloadError.NotConfigured -> stringResource(CoreR.string.lotw_sync_error_not_configured)
                    LoTWDownloadError.BadCredentials -> stringResource(CoreR.string.lotw_sync_error_credentials)
                    LoTWDownloadError.RateLimited -> stringResource(CoreR.string.lotw_sync_error_rate_limited)
                    LoTWDownloadError.Timeout -> stringResource(CoreR.string.lotw_sync_error_timeout)
                    LoTWDownloadError.Storage -> stringResource(R.string.lotw_storage_error)
                    is LoTWDownloadError.Network ->
                        stringResource(CoreR.string.lotw_sync_error_network, error.detail)
                }
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.lotwDownloadSummary?.let { result ->
                Text(
                    stringResource(
                        R.string.lotw_download_result,
                        result.downloaded,
                        result.imported,
                        result.updated,
                        result.skipped,
                        result.confirmedGrids
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSync(LoTWSyncMode.Full) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(CoreR.string.lotw_sync_full)) }
                Button(
                    onClick = { onSync(LoTWSyncMode.Incremental) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(CoreR.string.lotw_sync_incremental)) }
            }
        }
    }
}

@Composable
private fun LoTWField(value: String, update: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value, update, label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LoTWCheck(checked: Boolean, onChange: (Boolean) -> Unit, label: Int, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
    }
}

private fun problemText(problem: LoTWProblem): Int = when (problem) {
    LoTWProblem.CERTIFICATE_PASSWORD -> R.string.lotw_certificate_password_error
    LoTWProblem.CERTIFICATE_EXPIRED -> R.string.lotw_certificate_expired
    LoTWProblem.CERTIFICATE_INVALID, LoTWProblem.CERTIFICATE_MISSING -> R.string.lotw_certificate_invalid
    LoTWProblem.CERTIFICATE_FORMAT -> R.string.lotw_certificate_format
    LoTWProblem.STORAGE -> R.string.lotw_storage_error
    LoTWProblem.EMPTY_SELECTION -> R.string.lotw_nothing_to_upload
    LoTWProblem.CALLSIGN_MISMATCH -> R.string.lotw_callsign_mismatch
    LoTWProblem.QSO_DATE -> R.string.lotw_qso_date_error
    LoTWProblem.QSO_FUTURE -> R.string.lotw_qso_date_error
    LoTWProblem.STATION_GRID -> R.string.lotw_grid_error
    LoTWProblem.STATION_GRID_COUNT -> R.string.lotw_grid_count_error
    LoTWProblem.STATION_GRID_NOT_ADJACENT -> R.string.lotw_grid_adjacent_error
    LoTWProblem.STATION_REGION -> R.string.lotw_region_error
    LoTWProblem.STATION_ZONE -> R.string.lotw_zone_error
    LoTWProblem.STATION_IOTA -> R.string.lotw_iota_error
    LoTWProblem.MODE -> R.string.lotw_mode_error
    LoTWProblem.BAND -> R.string.lotw_band_error
    LoTWProblem.SATELLITE -> R.string.lotw_satellite_error
    LoTWProblem.INVALID_CONTACT -> R.string.lotw_contact_error
    LoTWProblem.LOCATION_MISMATCH -> R.string.lotw_location_mismatch
    LoTWProblem.TOO_MANY_CONTACTS -> R.string.lotw_too_many
}
