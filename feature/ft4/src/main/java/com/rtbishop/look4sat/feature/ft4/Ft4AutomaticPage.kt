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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomationPhase
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.R

@Composable
internal fun Ft4AutomaticPage(
    state: Ft4State,
    onAction: (Ft4Action) -> Unit,
    modifier: Modifier = Modifier
) {
    Ft4AutomationSection(state, onAction, modifier)
}

@Composable
internal fun Ft4AutomationSection(
    state: Ft4State,
    onAction: (Ft4Action) -> Unit,
    modifier: Modifier = Modifier
) {
    val automation = state.automation
    val blockReason = automaticGateReason(state)
    val gateAllowed = blockReason == null
    val notSet = stringResource(R.string.ft4_not_set)
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.ft4_page_automatic),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AutomationMetric(
                    stringResource(R.string.ft4_automation_state_label),
                    phaseLabel(automation.phase),
                    Modifier.weight(1f)
                )
                AutomationMetric(
                    stringResource(R.string.ft4_automation_target_label),
                    automation.targetCall.ifBlank { state.targetCall.ifBlank { notSet } },
                    Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AutomationMetric(
                    stringResource(R.string.ft4_automation_slots_label),
                    stringResource(
                        R.string.ft4_automation_slots_compact,
                        compactSlotLabel(automation.rxSlotParity),
                        compactSlotLabel(automation.txSlotParity)
                    ),
                    Modifier.weight(1f)
                )
                AutomationMetric(
                    stringResource(R.string.ft4_automation_cq_label),
                    automation.consecutiveCqCount.toString(),
                    Modifier.weight(1f)
                )
            }
            AutomationMessage(
                stringResource(R.string.ft4_automation_current_label),
                automation.currentMessage.ifBlank { notSet }
            )
            AutomationMessage(
                stringResource(R.string.ft4_automation_next_label),
                automation.nextMessage.ifBlank { notSet }
            )
            Text(
                if (gateAllowed) {
                    stringResource(R.string.ft4_automation_time_ok)
                } else {
                    stringResource(R.string.ft4_automation_time_blocked, requireNotNull(blockReason))
                },
                color = if (gateAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
            if (automation.abortReason.isNotBlank()) {
                Text(
                    automation.abortReason,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = { onAction(Ft4Action.ArmAutomation) },
                    text = stringResource(R.string.ft4_arm),
                    enabled = gateAllowed && automation.phase !in RUNNING_PHASES,
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(Ft4Action.StopAutomation) },
                    text = stringResource(R.string.ft4_stop_automation),
                    enabled = automation.phase in RUNNING_PHASES,
                    modifier = Modifier.weight(1f)
                )
            }
            CardButton(
                onClick = { onAction(Ft4Action.EmergencyStop) },
                text = stringResource(R.string.ft4_emergency_stop),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AutomationMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AutomationMessage(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(
            value,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
private fun compactSlotLabel(parity: Int?): String = when (parity) {
    0 -> stringResource(R.string.ft4_slot_even)
    1 -> stringResource(R.string.ft4_slot_odd)
    else -> stringResource(R.string.ft4_slot_unset_compact)
}

@Composable
private fun automaticGateReason(state: Ft4State): String? {
    val resource = when {
        !state.settings.decodeEnabled -> R.string.ft4_gate_disabled
        !state.capability.officialCoreAvailable -> R.string.ft4_gate_stub
        !state.capability.receiveAvailable -> R.string.ft4_gate_rx_unavailable
        !state.capability.transmitAvailable -> R.string.ft4_gate_tx_unavailable
        !state.hasMicrophonePermission -> R.string.ft4_gate_microphone
        state.clock.source == ClockSource.SYSTEM -> R.string.ft4_gate_system
        state.clock.sampleAgeMillis > MAX_TIME_SAMPLE_AGE_MILLIS -> R.string.ft4_gate_expired
        state.clock.uncertaintyMillis > 250.0 -> R.string.ft4_gate_uncertain
        !state.clock.healthy -> R.string.ft4_gate_unhealthy
        !state.radio.isActive -> R.string.ft4_gate_tracking
        !state.radio.txConnected -> R.string.ft4_gate_tx_radio
        state.trackingPass == null -> R.string.ft4_gate_pass
        state.radio.selectedTransponder == null -> R.string.ft4_gate_transponder
        state.settings.operatorCallsign.isBlank() -> R.string.ft4_gate_callsign
        else -> return null
    }
    return stringResource(resource)
}

private val RUNNING_PHASES = setOf(
    Ft4AutomationPhase.ARMED,
    Ft4AutomationPhase.CALLING,
    Ft4AutomationPhase.REPLYING,
    Ft4AutomationPhase.REPORT,
    Ft4AutomationPhase.ROGER,
    Ft4AutomationPhase.SIGNOFF
)
private const val MAX_TIME_SAMPLE_AGE_MILLIS = 30 * 60 * 1_000L
