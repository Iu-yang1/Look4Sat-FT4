/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The fork's Project Wiki: a short overview of what Look4Sat-BA7OPF adds over upstream
 * and how to use those features. Shown in the What's-New card (first launch after an
 * update) and behind the Settings → Project Wiki entry. Text lives in string resources,
 * so it always renders even when the GitHub page is unreachable.
 */
@Composable
fun ProjectWikiBody(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.project_wiki_intro),
            fontSize = 13.sp
        )
        WikiSection(stringResource(R.string.project_wiki_section_logbook))
        WikiEntry(stringResource(R.string.project_wiki_logbook_1))
        WikiEntry(stringResource(R.string.project_wiki_logbook_2))
        WikiEntry(stringResource(R.string.project_wiki_logbook_3))
        WikiHow(stringResource(R.string.project_wiki_logbook_how))
        WikiSection(stringResource(R.string.project_wiki_section_mutual))
        WikiEntry(stringResource(R.string.project_wiki_mutual_1))
        WikiHow(stringResource(R.string.project_wiki_mutual_how))
        WikiSection(stringResource(R.string.project_wiki_section_grids))
        WikiEntry(stringResource(R.string.project_wiki_grids_1))
        WikiHow(stringResource(R.string.project_wiki_grids_how))
    }
}

@Composable
private fun WikiSection(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    )
}

@Composable
private fun WikiEntry(text: String) {
    Text(text = text, fontSize = 12.sp)
}

@Composable
private fun WikiHow(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp)
    )
}
