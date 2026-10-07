/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain.utility

/**
 * Compares two version strings of the form "<major>.<minor>.<patch>[-<build>]" used by the
 * BA7OPF fork releases (e.g. "4.4.6-ba7opf.6" or "v4.4.6-ba7opf.9.1").
 *
 * The comparison is done on the numeric version segments first; when the base versions are
 * equal, the trailing build numbers of the suffix decide. All numeric segments of the suffix
 * are compared as a sequence, so multi-level suffixes work correctly:
 * "4.4.6-ba7opf.9.1" > "4.4.6-ba7opf.9" > "4.4.6-ba7opf.8".
 */
object VersionComparator {

    /** True when [candidate] is a newer version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val cand = parse(candidate)
        val curr = parse(current)
        val maxLen = maxOf(cand.first.size, curr.first.size)
        for (i in 0 until maxLen) {
            val c = cand.first.getOrElse(i) { 0 }
            val k = curr.first.getOrElse(i) { 0 }
            if (c != k) return c > k
        }
        val maxBuild = maxOf(cand.second.size, curr.second.size)
        for (i in 0 until maxBuild) {
            val c = cand.second.getOrElse(i) { 0 }
            val k = curr.second.getOrElse(i) { 0 }
            if (c != k) return c > k
        }
        return false
    }

    /**
     * Parses a version string into (numeric base segments, numeric suffix segments).
     * "v4.4.6-ba7opf.9.1" -> ([4,4,6], [9,1]); "4.4.6-ba7opf.8" -> ([4,4,6], [8]);
     * "4.4.6" -> ([4,4,6], []); "4.4.6-ba7opf" -> ([4,4,6], []).
     */
    fun parse(version: String): Pair<List<Int>, List<Int>> {
        val cleaned = version.trim().removePrefix("v")
        val dashIndex = cleaned.indexOf('-')
        val basePart = if (dashIndex >= 0) cleaned.substring(0, dashIndex) else cleaned
        val suffixPart = if (dashIndex >= 0) cleaned.substring(dashIndex + 1) else ""
        val base = basePart.split('.').mapNotNull { it.toIntOrNull() }
        // All numeric segments of the suffix, in order (e.g. "ba7opf.9.1" -> [9, 1]).
        val build = suffixPart.split('.').mapNotNull { it.toIntOrNull() }
        return base to build
    }
}
