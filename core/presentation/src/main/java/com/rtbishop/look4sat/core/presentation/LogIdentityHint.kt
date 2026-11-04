package com.rtbishop.look4sat.core.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.logbook.logIdentityMismatch
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A save-time hint; upload validation remains the responsibility of the uploader. */
@Composable
fun LogIdentityHint(ownCallsign: String, ownGrid: String) {
    val container=(LocalContext.current.applicationContext as? IContainerProvider)?.getMainContainer() ?: return
    val wavelog by container.settingsRepo.wavelogUploadSettings.collectAsStateWithLifecycle()
    var refresh by remember {mutableIntStateOf(0)}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {refresh++}
    var lotw by remember {mutableStateOf("" to "")}
    LaunchedEffect(container,refresh) {
        lotw=withContext(Dispatchers.IO) {
            runCatching {container.lotwUploadRepository.certificate()?.callsign.orEmpty() to
                container.lotwUploadRepository.station()?.grid.orEmpty()}.getOrDefault("" to "")
        }
    }
    val stations=buildList {
        add(Triple("LoTW",lotw.first,lotw.second))
        if(wavelog.isReady) add(Triple("Wavelog",wavelog.stationCallsign,wavelog.stationGrid))
    }
    stations.forEach { (provider,call,grid) ->
        val mismatch=logIdentityMismatch(ownCallsign,ownGrid,call,grid)
        val differences=buildList {
            if(mismatch.callsign) add(stringResource(R.string.log_identity_callsign,ownCallsign.trim(),call))
            if(mismatch.grid) add(stringResource(R.string.log_identity_grid,ownGrid.trim(),grid))
        }
        if(differences.isNotEmpty()) Text(
            stringResource(R.string.log_identity_mismatch,provider,differences.joinToString(" · ")),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
