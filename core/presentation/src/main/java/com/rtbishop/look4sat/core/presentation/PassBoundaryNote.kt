package com.rtbishop.look4sat.core.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass

@Composable
fun PassBoundaryNote(pass: OrbitalPass) {
    if (pass.startClipped || pass.endClipped) {
        val text = when {
            pass.continuousVisibility -> stringResource(R.string.astro_continuous)
            pass.startClipped -> stringResource(R.string.astro_start_clipped)
            else -> stringResource(R.string.astro_end_clipped)
        }
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
