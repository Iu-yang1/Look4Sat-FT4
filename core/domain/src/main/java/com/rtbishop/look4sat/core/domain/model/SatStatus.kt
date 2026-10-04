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
package com.rtbishop.look4sat.core.domain.model

/** One satellite status report (AMSAT site tooltip data) */
data class SatReport(
    val id: String,          // 报告 ID(a885153)
    val statusText: String,  // Heard / Telemetry Only / Not Heard ...
    val call: String,        // 呼号
    val grid: String,        // 网格坐标(可为空)
    val dateUtc: String,     // 2026-08-04
    val timeUtc: String      // 2:46-:59 UTC
)

/** State of one 2-hour slot */
data class SatSlot(
    val statusColor: Long,   // ARGB 状态色（灰=无报告；判定见 AmSatRepository.slotStatusOf）
    val count: Int,          // 官网页口径：多数方计数（冲突槽/异常槽=总条数；0 = 无）
    val reportIds: List<String> = emptyList(), // 该槽报告 ID 列表
    val isConflicted: Boolean = false // 官网页"Conflicting reports"：该槽无严格多数（弹窗据此标注）
)

/** One satellite day (12 two-hour slots) */
data class SatDay(
    val dateLabel: String,   // "Aug 4"
    val slots: List<SatSlot>, // 12 槽(00-02 ... 22-24)
    val streakCount: Int = 0 // 当天最近连续相同状态报告数(0 = 当天无报告)
)

/** One satellite, 3 days of state */
data class SatStatus(
    val name: String,        // "AO-123_[FM]"
    val days: List<SatDay>   // 3 days (newest first)
)

/** Overall page parse result */
data class SatStatusPage(
    val fetchedAtUtcMs: Long,
    val statuses: List<SatStatus>,
    val reports: Map<String, SatReport> // id → 报告
)
