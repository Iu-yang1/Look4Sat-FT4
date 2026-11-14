package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog

@Composable
internal fun TrajectoryPrecisionDialog(
    current: TrajectoryPrecision,
    onDismiss: () -> Unit,
    onSave: (TrajectoryPrecision) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current.name) }
    SharedDialog(
        title = stringResource(R.string.prefs_trajectory_precision),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = {
            onSave(TrajectoryPrecision.fromStored(selected))
            onDismiss()
        },
        acceptEnabled = selected != current.name
    ) { padding ->
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = padding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.prefs_trajectory_precision_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TrajectoryPrecision.entries.forEach { precision ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { selected = precision.name },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected == precision.name, onClick = { selected = precision.name })
                    Text(precision.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
