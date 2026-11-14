package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrajectoryPrecisionDialog(
    current: TrajectoryPrecision,
    onDismiss: () -> Unit,
    onSave: (TrajectoryPrecision) -> Unit
) {
    var selected by rememberSaveable(current) { mutableStateOf(current.name) }
    val levels = TrajectoryPrecision.entries
    val selectedLevel = TrajectoryPrecision.fromStored(selected)
    val title = stringResource(R.string.prefs_trajectory_precision)
    SharedDialog(
        title = title,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                selectedLevel.label,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = selectedLevel.ordinal.toFloat(),
                onValueChange = { selected = levels[it.roundToInt()].name },
                valueRange = 0f..levels.lastIndex.toFloat(),
                steps = levels.size - 2,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = title
                    stateDescription = selectedLevel.label
                },
                thumb = {
                    Box(
                        Modifier.size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(levels.first().label, style = MaterialTheme.typography.labelMedium)
                Text(levels.last().label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
