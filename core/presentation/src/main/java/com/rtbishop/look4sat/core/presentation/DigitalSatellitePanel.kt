// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos

@Composable
fun DigitalRadarSizeButton(large: Boolean, onChange: (Boolean)->Unit) {
    val description=stringResource(if(large) R.string.radar_use_small else R.string.radar_use_large)
    TextButton(onClick={onChange(!large)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics { contentDescription=description }) {
        Text(stringResource(if(large) R.string.radar_size_small_short else R.string.radar_size_large_short))
    }
}

@Composable
fun DigitalSatellitePanel(
    name: String, subtitle: String, position: OrbitalPos?, track: List<OrbitalPos>,
    large: Boolean, orientation: Pair<Float,Float> = 0f to 0f,
    sweep: Boolean = false, compass: Boolean = false,
    information: @Composable ColumnScope.()->Unit = {}
) {
    @Composable fun Information() {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(name,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            information()
        }
    }
    @Composable fun Radar(modifier: Modifier) {
        Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
            if(position!=null) {
                Text(stringResource(R.string.radar_az_text)+" "+stringResource(R.string.radar_az_value,Math.toDegrees(position.azimuth)),style=MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.radar_el_text)+" "+stringResource(R.string.radar_el_value,Math.toDegrees(position.elevation)),style=MaterialTheme.typography.labelSmall)
                RadarViewCompose(item=position,items=track,azimElev=orientation,shouldShowSweep=sweep,shouldUseCompass=compass,
                    modifier=Modifier.fillMaxWidth().aspectRatio(1f))
                if(large) Text(stringResource(R.string.radar_alt_value,position.altitude)+" · "+
                    stringResource(R.string.radar_dist_value,position.distance),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(large) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Information();Radar(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1.25f)) { Information() };Radar(Modifier.weight(1f))
    }
}
