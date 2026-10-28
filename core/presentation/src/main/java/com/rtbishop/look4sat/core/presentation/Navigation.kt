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

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

fun <T> MutableList<T>.popCurrentEntry(expectedEntry: T?): Boolean {
    // Outgoing screens can still receive clicks while their exit animation runs.
    if (size <= 1 || expectedEntry == null || last() != expectedEntry) return false
    removeAt(lastIndex)
    return true
}

@Serializable
sealed class Screen(val iconResId: Int, val titleResId: Int) : NavKey {

    @Serializable
    data object Satellites : Screen(R.drawable.ic_satellites, R.string.nav_sat)

    @Serializable
    data object Passes : Screen(R.drawable.ic_passes, R.string.nav_pass)

    @Serializable
    data object Map : Screen(R.drawable.ic_map, R.string.nav_map)

    @Serializable
    data object GridFinder : Screen(R.drawable.ic_grid_finder, R.string.gridfinder_title)

    @Serializable
    data object Mutual : Screen(R.drawable.ic_match, R.string.nav_mutual)

    @Serializable
    data object AMSAT : Screen(R.drawable.ic_satellite_alt, R.string.nav_amsat)

    @Serializable
    data object PassSchedule : Screen(R.drawable.ic_calendar, R.string.pass_schedule_title)

    @Serializable
    data object Ft4 : Screen(R.drawable.ic_radio_tower, R.string.nav_ft4)

    @Serializable
    data object Jtty : Screen(R.drawable.ic_radio_tower, R.string.nav_jtty)

    @Serializable
    data object Logbook : Screen(R.drawable.ic_logbook, R.string.nav_logbook)

    @Serializable
    data object Settings : Screen(R.drawable.ic_settings, R.string.nav_prefs)

    @Serializable
    data object More : Screen(R.drawable.ic_more, R.string.nav_more)
}

@Serializable
data object RadarDestination : NavKey

@Serializable
data object LogbookDestination : NavKey
/** Radar page opened with a selected common pass and an opposite station. */
@Serializable
data class RadarMatchDestination(
    val stationBGrid: String,
    val catNum: Int,
    val aosTime: Long,
    /** Unique entry key so rematching the same grid/pass creates a fresh radar ViewModel. */
    val sessionId: Long
) : NavKey

/** Dedicated map with the AOS–LOS bounds of the pass opened from radar. */
@Serializable
data class RadarPassMapDestination(val catNum: Int, val aosTime: Long, val losTime: Long) : NavKey

/** Grid picker opened from the radar's Match dialog, returning to the same radar. */
@Serializable
data object RadarMatchGridPickerDestination : NavKey

/** Grid picker opened from either station's grid field on the Match page. */
@Serializable
data class MatchGridPickerDestination(val stationA: Boolean) : NavKey

/** Full-screen field tool: guides the operator onto a VUCC grid line or corner. */
@Serializable
data object GridFinderDestination : NavKey

/** LoTW upload certificate + station location page, reached from the Grid Finder. */
@Serializable
data object LoTWUploadDestination : NavKey

interface IDeeplinkMatcher {
    fun match(deeplink: String): NavKey?
}

object PassDetailsMatcher : IDeeplinkMatcher {
    val passDetailsRegex = """https://github.com/rt-bishop/Look4Sat/passes/(.*)""".toRegex()

    override fun match(deeplink: String): NavKey? {
        val passMatch = passDetailsRegex.find(deeplink)
        passMatch?.let { match ->
            val passId = match.groupValues[1]
            if (passId.isNotEmpty()) return RadarDestination
        }
        return null
    }
}

class DeeplinkResolver(private val fallbackDestination: NavKey = Screen.Passes) {

    private val matchers: List<IDeeplinkMatcher> = listOf(PassDetailsMatcher)

    fun resolve(deeplink: String): NavKey {
        matchers.forEach { it.match(deeplink)?.let { match -> return match } }
        return fallbackDestination
    }
}
