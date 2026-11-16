package com.rtbishop.look4sat.feature.backup

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupPanel(container: IMainContainer, onDismiss: () -> Unit) {
    val model: BackupViewModel = viewModel(factory = BackupViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    val ui by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val close: () -> Unit = {
        model.onAction(BackupAction.ClearPassword)
        if (!state.busy) container.appScope.launch { container.backupRepository.cancel() }
        onDismiss()
    }
    DisposableEffect(model) {
        onDispose { model.onAction(BackupAction.ClearPassword) }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let { model.onAction(BackupAction.Create(it.toString())) }
    }
    val retryExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let { model.onAction(BackupAction.RetryExport(it.toString())) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { model.onAction(BackupAction.Inspect(it.toString())) }
    }
    LaunchedEffect(state.preview?.createdAtMillis) {
        state.preview?.let { model.initializePreview(it.counts.keys) }
    }
    val back: () -> Unit = {
        if (ui.page != "home" && !state.busy && !state.needsRestart) {
            container.appScope.launch { container.backupRepository.cancel() }
            model.onAction(BackupAction.Page("home"))
        } else if (state.phase != BackupPhase.RESTORING && !state.needsRestart) close()
    }
    BackHandler(onBack = back)
    SharedDialog(
        title = stringResource(R.string.backup_title),
        onDismissRequest = { if (state.phase != BackupPhase.RESTORING && !state.needsRestart) close() },
        onCancel = back,
        cancelText = stringResource(if (ui.page == "home") R.string.maintenance_close else R.string.maintenance_back),
        dismissOnClickOutside = state.phase != BackupPhase.RESTORING && !state.needsRestart,
        dismissOnBackPress = state.phase != BackupPhase.RESTORING && !state.needsRestart,
        dismissOnSwipe = state.phase != BackupPhase.RESTORING && !state.needsRestart
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f)
                .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = padding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.needsRestart) {
                Text(stringResource(if (state.phase == BackupPhase.FAILED) R.string.backup_failed else R.string.backup_restored),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (state.phase == BackupPhase.FAILED) R.string.backup_recovery_notice else R.string.backup_restart_notice))
                if (state.needsRebinding) Text(stringResource(R.string.backup_rebind))
                Button(onClick = {
                    (context as? Activity)?.finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_close_app))
                }
            } else if (state.busy) {
                Text(stringResource(phaseLabel(state.phase)))
                if (state.totalBytes > 0) LinearProgressIndicator(
                    progress = { (state.completedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.phase == BackupPhase.RESTORING) Text(stringResource(R.string.backup_commit_notice))
                else OutlinedButton(onClick = { container.appScope.launch { container.backupRepository.cancel() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.btn_cancel))
                }
            } else if (state.phase == BackupPhase.PREVIEW && state.preview != null) {
                val preview = checkNotNull(state.preview)
                Text(stringResource(R.string.backup_preview), style = MaterialTheme.typography.titleMedium)
                Text(DateFormat.getDateTimeInstance().format(Date(preview.createdAtMillis)))
                Text(stringResource(R.string.backup_source_version, preview.appVersion))
                Text(android.text.format.Formatter.formatFileSize(context, preview.bytes))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RestoreMode.entries.forEach { mode ->
                        FilterChip(selected = ui.mode == mode, onClick = { model.onAction(BackupAction.Mode(mode)) },
                            label = { Text(stringResource(if (mode == RestoreMode.MERGE) R.string.backup_merge else R.string.backup_replace)) })
                    }
                }
                Text(stringResource(if (ui.mode == RestoreMode.MERGE) R.string.backup_merge_hint else R.string.backup_replace_hint),
                    style = MaterialTheme.typography.bodySmall)
                preview.counts.forEach { (category, count) ->
                    CategoryRow(category, category in ui.restoreCategories, { model.onAction(BackupAction.RestoreToggle(category)) }) {
                        Text(stringResource(R.string.backup_count, count), style = MaterialTheme.typography.bodySmall)
                        if (category in ui.restoreCategories) Text(stringResource(
                            R.string.backup_changes, preview.added[category] ?: 0, preview.changed[category] ?: 0,
                            preview.removed[category] ?: 0), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.backup_restore_notice), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.onAction(BackupAction.Restore) }, enabled = ui.restoreCategories.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_restore_selected))
                }
            } else if (ui.page == "create") {
                Text(stringResource(R.string.backup_create), style = MaterialTheme.typography.titleMedium)
                BackupCategory.entries.forEach { category ->
                    CategoryRow(category, category in ui.categories, { model.onAction(BackupAction.Toggle(category)) }) {
                        state.availableCounts[category]?.let { Text(stringResource(R.string.backup_count, it),
                            style = MaterialTheme.typography.bodySmall) }
                    }
                }
                state.estimatedBytes?.let { Text(stringResource(R.string.backup_estimated_size,
                    android.text.format.Formatter.formatFileSize(context, it))) }
                PasswordFields(ui, model::onAction, confirm = true)
                if (BackupCategory.CREDENTIALS in ui.categories) Text(stringResource(R.string.backup_password_required))
                Button(onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                    create.launch("Look4Sat-BA7OPF-Orekit-backup-$stamp.l4sbackup")
                }, enabled = ui.categories.isNotEmpty() && ui.password == ui.confirmation &&
                    (BackupCategory.CREDENTIALS !in ui.categories || ui.password.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_choose_destination))
                }
            } else if (ui.page == "restore") {
                Text(stringResource(R.string.backup_restore), style = MaterialTheme.typography.titleMedium)
                PasswordFields(ui, model::onAction, confirm = false)
                Button(onClick = { open.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_choose_file))
                }
            } else {
                Text(stringResource(R.string.backup_last), style = MaterialTheme.typography.titleSmall)
                Text(if (state.lastBackupMillis == 0L) stringResource(R.string.backup_never)
                    else DateFormat.getDateTimeInstance().format(Date(state.lastBackupMillis)))
                if (state.lastBackupName.isNotBlank()) Text(state.lastBackupName, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.onAction(BackupAction.Page("create")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_create)) }
                OutlinedButton(onClick = { model.onAction(BackupAction.Page("restore")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_restore)) }
            }
            if (state.phase == BackupPhase.COMPLETE && !state.needsRestart) Text(stringResource(R.string.backup_saved))
            state.error?.let {
                Text(stringResource(R.string.backup_failed), color = MaterialTheme.colorScheme.error)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (state.retryExportAvailable) Button(onClick = {
                retryExport.launch("Look4Sat-BA7OPF-Orekit-backup-${System.currentTimeMillis()}.l4sbackup")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_retry_export)) }
        }
    }
}

@Composable
private fun CategoryRow(category: BackupCategory, selected: Boolean, onToggle: () -> Unit,
    details: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(selected, onCheckedChange = null)
        Column(Modifier.weight(1f)) { Text(stringResource(categoryLabel(category))); details() }
    }
}

@Composable
private fun PasswordFields(ui: BackupUiState, onAction: (BackupAction) -> Unit, confirm: Boolean) {
    OutlinedTextField(ui.password, { onAction(BackupAction.Password(it)) }, label = {
        Text(stringResource(if (confirm) R.string.backup_password else R.string.backup_password_optional)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    if (confirm) OutlinedTextField(ui.confirmation, { onAction(BackupAction.Confirmation(it)) },
        label = { Text(stringResource(R.string.backup_password_confirm)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = ui.confirmation.isNotEmpty() && ui.password != ui.confirmation)
}

internal fun categoryLabel(category: BackupCategory) = when (category) {
    BackupCategory.SETTINGS -> R.string.backup_settings
    BackupCategory.SATELLITES -> R.string.backup_satellites
    BackupCategory.LOGBOOK -> R.string.backup_logbook
    BackupCategory.RECORDINGS -> R.string.backup_recordings
    BackupCategory.ASTRONOMY -> R.string.backup_astronomy
    BackupCategory.CREDENTIALS -> R.string.backup_credentials
}

private fun phaseLabel(phase: BackupPhase) = when (phase) {
    BackupPhase.PREPARING -> R.string.backup_preparing
    BackupPhase.EXPORTING -> R.string.backup_exporting
    BackupPhase.READING -> R.string.backup_reading
    else -> R.string.backup_restoring
}
