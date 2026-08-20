/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomationPhase
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.presentation.R

@Composable
internal fun Ft4AutomaticPage(
    state: Ft4State,
    onAction: (Ft4Action) -> Unit,
    modifier: Modifier = Modifier
) {
    val automation = state.automation
    val gateAllowed = state.clock.source != ClockSource.SYSTEM &&
        state.clock.healthy &&
        state.clock.uncertaintyMillis <= 250.0 &&
        state.clock.sampleAgeMillis <= MAX_TIME_SAMPLE_AGE_MILLIS
    val notSet = stringResource(R.string.ft4_not_set)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    stringResource(R.string.ft4_automation_state, phaseLabel(automation.phase)),
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = state.targetCall,
                    onValueChange = { onAction(Ft4Action.SetTargetCall(it)) },
                    label = { Text(stringResource(R.string.ft4_target_call)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                StatusLine(R.string.ft4_automation_target, automation.targetCall.ifBlank { state.targetCall.ifBlank { notSet } })
                StatusLine(R.string.ft4_automation_current, automation.currentMessage.ifBlank { notSet })
                StatusLine(R.string.ft4_automation_next, automation.nextMessage.ifBlank { notSet })
                Text(
                    stringResource(
                        R.string.ft4_automation_slots,
                        slotLabel(automation.rxSlotParity),
                        slotLabel(automation.txSlotParity)
                    )
                )
                Text(stringResource(R.string.ft4_automation_cq, automation.consecutiveCqCount))
                Text(
                    if (gateAllowed) {
                        stringResource(R.string.ft4_automation_time_ok)
                    } else {
                        stringResource(R.string.ft4_automation_time_blocked, automaticGateReason(state))
                    },
                    color = if (gateAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
                if (automation.abortReason.isNotBlank()) {
                    Text(
                        automation.abortReason,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onAction(Ft4Action.ArmAutomation) },
                        enabled = gateAllowed && automation.phase !in RUNNING_PHASES,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.ft4_arm)) }
                    Button(
                        onClick = { onAction(Ft4Action.StopAutomation) },
                        enabled = automation.phase in RUNNING_PHASES,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.ft4_stop_automation)) }
                }
                Button(
                    onClick = { onAction(Ft4Action.EmergencyStop) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.ft4_emergency_stop)) }
            }
        }
    }
}

@Composable
private fun StatusLine(labelRes: Int, value: String) {
    Text(
        stringResource(labelRes, value),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun phaseLabel(phase: Ft4AutomationPhase): String = stringResource(
    when (phase) {
        Ft4AutomationPhase.IDLE -> R.string.ft4_phase_idle
        Ft4AutomationPhase.ARMED -> R.string.ft4_phase_armed
        Ft4AutomationPhase.CALLING -> R.string.ft4_phase_calling
        Ft4AutomationPhase.REPLYING -> R.string.ft4_phase_replying
        Ft4AutomationPhase.REPORT -> R.string.ft4_phase_report
        Ft4AutomationPhase.ROGER -> R.string.ft4_phase_roger
        Ft4AutomationPhase.SIGNOFF -> R.string.ft4_phase_signoff
        Ft4AutomationPhase.COMPLETE -> R.string.ft4_phase_complete
        Ft4AutomationPhase.ABORTED -> R.string.ft4_phase_aborted
    }
)

@Composable
private fun slotLabel(parity: Int?): String = when (parity) {
    0 -> stringResource(R.string.ft4_slot_even)
    1 -> stringResource(R.string.ft4_slot_odd)
    else -> stringResource(R.string.ft4_not_set)
}

@Composable
private fun automaticGateReason(state: Ft4State): String = stringResource(
    when {
        state.clock.source == ClockSource.SYSTEM -> R.string.ft4_gate_system
        state.clock.sampleAgeMillis > MAX_TIME_SAMPLE_AGE_MILLIS -> R.string.ft4_gate_expired
        state.clock.uncertaintyMillis > 250.0 -> R.string.ft4_gate_uncertain
        else -> R.string.ft4_gate_unhealthy
    }
)

private val RUNNING_PHASES = setOf(
    Ft4AutomationPhase.ARMED,
    Ft4AutomationPhase.CALLING,
    Ft4AutomationPhase.REPLYING,
    Ft4AutomationPhase.REPORT,
    Ft4AutomationPhase.ROGER,
    Ft4AutomationPhase.SIGNOFF
)
private const val MAX_TIME_SAMPLE_AGE_MILLIS = 30 * 60 * 1_000L
