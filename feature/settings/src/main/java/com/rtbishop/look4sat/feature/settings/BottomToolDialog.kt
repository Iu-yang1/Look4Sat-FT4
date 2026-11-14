/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.BottomBarTool
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog

/** Bottom navigation shortcut picker styled consistently with the other settings dialogs. */
@Composable
fun BottomToolDialog(
    currentTool: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selectedTool by rememberSaveable(currentTool) {
        mutableStateOf(BottomBarTool.normalize(currentTool))
    }
    val options = listOf(
        BottomBarTool.FT4 to R.string.nav_ft4,
        BottomBarTool.JTTY to R.string.nav_jtty,
        BottomBarTool.SUNLIGHT to R.string.sunlight_title,
        BottomBarTool.MAP to R.string.nav_map,
        BottomBarTool.GRID_FINDER to R.string.gridfinder_title,
        BottomBarTool.MUTUAL to R.string.nav_mutual,
        BottomBarTool.AMSAT to R.string.nav_amsat,
        BottomBarTool.PASS_SCHEDULE to R.string.pass_schedule_title
    )

    SharedDialog(
        title = stringResource(R.string.prefs_bottom_tool_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = {
            onSave(selectedTool)
            onDismiss()
        },
        acceptEnabled = selectedTool != BottomBarTool.normalize(currentTool)
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padding)
        ) {
            Text(
                text = stringResource(R.string.prefs_bottom_tool_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            options.forEach { (tool, labelRes) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedTool = tool }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                ) {
                    RadioButton(
                        selected = selectedTool == tool,
                        onClick = { selectedTool = tool }
                    )
                    Text(
                        text = stringResource(labelRes),
                        modifier = Modifier.padding(start = 6.dp),
                        color = if (selectedTool == tool) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
