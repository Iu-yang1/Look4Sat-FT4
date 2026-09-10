package com.rtbishop.look4sat.feature.logbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun LoTWUploadDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var certificateBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
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
                    } ?: error("Cannot read certificate")
                }
                certificateBytes?.fill(0)
                certificateBytes = bytes
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fileError = true }
            finally { reading = false }
        }
    }
    val busy = state.lotwSyncing || reading
    AlertDialog(
        onDismissRequest = { if (!busy) onAction(LogbookAction.DismissLoTW) },
        title = { LoTWTabs(true, !busy, onAction) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lotw_certificate_hint), style = MaterialTheme.typography.bodySmall)
                state.lotwCertificate?.let { cert ->
                    Text(stringResource(R.string.lotw_certificate_info, cert.callsign, cert.dxcc, cert.expires, cert.firstQsoDate, cert.lastQsoDate.ifBlank { "—" }))
                }
                TextButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text(stringResource(if (certificateBytes == null) R.string.lotw_choose_certificate else R.string.lotw_certificate_selected))
                }
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.lotw_certificate_password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (certificateBytes != null) TextButton(onClick = {
                    certificateBytes?.let { onAction(LogbookAction.ImportLoTWCertificate(it, password.toCharArray())) }
                    certificateBytes = null; password = ""
                }, enabled = !busy) { Text(stringResource(R.string.lotw_import_certificate)) }
                if (state.lotwCertificate != null) {
                    TextButton(onClick = { removeConfirmation = true }, enabled = !busy) { Text(stringResource(R.string.lotw_remove_certificate)) }
                    Text(stringResource(R.string.lotw_station_hint), style = MaterialTheme.typography.bodySmall)
                    LoTWField(grid, { grid = it }, R.string.lotw_station_grid, !busy)
                    LoTWField(cq, { cq = it }, R.string.lotw_cq_zone, !busy)
                    LoTWField(itu, { itu = it }, R.string.lotw_itu_zone, !busy)
                    LoTWField(region, { region = it }, R.string.lotw_region, !busy)
                    LoTWField(county, { county = it }, R.string.lotw_county, !busy)
                    LoTWField(iota, { iota = it }, R.string.lotw_iota, !busy)
                    Text(stringResource(R.string.lotw_upload_scope, state.filteredRecords.size), style = MaterialTheme.typography.bodySmall)
                    LoTWCheck(resubmit, { resubmit = it }, R.string.lotw_retry_unknown, !busy)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                        LoTWUploadResult.Unknown -> stringResource(R.string.lotw_upload_unknown)
                        LoTWUploadResult.ExpiredPreview -> stringResource(R.string.lotw_preview_expired)
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAction(LogbookAction.PrepareLoTWUpload(LoTWStation(grid, cq, itu, region, county, iota), password.toCharArray(), resubmit))
                password = ""
            }, enabled = !busy && state.lotwCertificate != null && certificateBytes == null) {
                Text(stringResource(R.string.lotw_prepare))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (state.lotwSyncing) onAction(LogbookAction.CancelLoTW) else if (!reading) onAction(LogbookAction.DismissLoTW)
            }) { Text(stringResource(R.string.logbook_cancel)) }
        }
    )
    state.lotwPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { onAction(LogbookAction.DismissLoTWPreview) },
            title = { Text(stringResource(R.string.lotw_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.lotw_preview_summary, preview.callsign, preview.dxcc, preview.grid, preview.count, preview.skipped))
                    if (preview.unknownSkipped > 0) Text(stringResource(R.string.lotw_unknown_skipped, preview.unknownSkipped))
                    if (preview.count == 0) Text(stringResource(R.string.lotw_nothing_to_upload)) else {
                        Text("UTC: ${preview.firstUtc} — ${preview.lastUtc}")
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
private fun LoTWField(value: String, update: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value, update, label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

private fun problemText(problem: LoTWProblem): Int = when (problem) {
    LoTWProblem.CERTIFICATE_PASSWORD -> R.string.lotw_certificate_password_error
    LoTWProblem.CERTIFICATE_EXPIRED -> R.string.lotw_certificate_expired
    LoTWProblem.CERTIFICATE_INVALID, LoTWProblem.CERTIFICATE_MISSING -> R.string.lotw_certificate_invalid
    LoTWProblem.STORAGE -> R.string.lotw_storage_error
    LoTWProblem.EMPTY_SELECTION -> R.string.lotw_nothing_to_upload
    LoTWProblem.CALLSIGN_MISMATCH -> R.string.lotw_callsign_mismatch
    LoTWProblem.QSO_DATE -> R.string.lotw_qso_date_error
    LoTWProblem.STATION_GRID -> R.string.lotw_grid_error
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
