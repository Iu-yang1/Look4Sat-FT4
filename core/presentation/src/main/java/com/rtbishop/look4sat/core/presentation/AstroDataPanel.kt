package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.astro.AstroDataPhase
import com.rtbishop.look4sat.core.domain.astro.AstroDataState
import com.rtbishop.look4sat.core.domain.astro.DEFAULT_ASTRO_DATA_URL
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository

@Composable
fun astroStatus(state: AstroDataState): String = stringResource(when (state.phase) {
    AstroDataPhase.LOADING -> R.string.astro_loading
    AstroDataPhase.MISSING -> R.string.astro_missing
    AstroDataPhase.DOWNLOADING -> R.string.astro_downloading
    AstroDataPhase.VERIFYING -> R.string.astro_verifying
    AstroDataPhase.READY -> R.string.astro_ready
    AstroDataPhase.FAILED -> if (state.ready) R.string.astro_update_failed else R.string.astro_failed
})

@Composable
fun AstroDataPanel(repository: IAstroDataRepository, onDismiss: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(state.downloadUrl) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    SharedDialog(
        title = stringResource(R.string.astro_title),
        onDismissRequest = onDismiss,
        onCancel = if (editing) ({ editing = false; invalid = false }) else onDismiss,
        onAccept = if (editing) ({
            try {
                repository.setDownloadUrl(url)
                invalid = false
                editing = false
            } catch (_: IllegalArgumentException) { invalid = true }
        }) else null
    ) { padding ->
        Column(Modifier.padding(horizontal = padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; invalid = false },
                    label = { Text(stringResource(R.string.astro_url)) },
                    isError = invalid, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (invalid) ({ Text(stringResource(R.string.astro_url_invalid)) }) else null
                )
                TextButton(onClick = { url = DEFAULT_ASTRO_DATA_URL }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.astro_reset))
                }
            } else {
                Text(astroStatus(state), style = MaterialTheme.typography.bodyLarge)
                if (state.phase == AstroDataPhase.DOWNLOADING || state.phase == AstroDataPhase.VERIFYING) {
                    val total = state.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.version?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val start = state.coverageStartMillis
                val end = state.coverageEndMillis
                if (start != null && end != null) {
                    val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    Text(stringResource(R.string.astro_coverage, format.format(java.util.Date(start)),
                        format.format(java.util.Date(end - 1))), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        if (state.busy) repository.cancel() else repository.download()
                    },
                    enabled = state.phase != AstroDataPhase.LOADING,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                ) {
                    Text(stringResource(if (state.busy) R.string.btn_cancel else R.string.astro_download))
                }
                TextButton(onClick = { url = state.downloadUrl; editing = true },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.astro_edit_url))
                }
            }
        }
    }
}

/** Do not construct orbital page ViewModels until a complete data generation is available. */
@Composable
fun AstroDataGate(
    repository: IAstroDataRepository,
    failures: kotlinx.coroutines.flow.StateFlow<Map<Int, String>>? = null,
    content: @Composable () -> Unit
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var showPanel by rememberSaveable { mutableStateOf(false) }
    if (state.ready) {
        val issues = failures?.collectAsStateWithLifecycle()?.value.orEmpty()
        Column(Modifier.fillMaxSize()) {
            if (issues.isNotEmpty()) Text(stringResource(R.string.astro_invalid_orbits),
                modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.astro_title), style = MaterialTheme.typography.titleLarge)
            Text(astroStatus(state))
            Button(onClick = { showPanel = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text(stringResource(R.string.astro_download))
            }
        }
    }
    if (showPanel) AstroDataPanel(repository, onDismiss = { showPanel = false })
}
