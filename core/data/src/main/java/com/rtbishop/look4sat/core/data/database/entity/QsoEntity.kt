/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "qso_records",
    indices = [Index(value = ["dedupeKey"])]
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startUtcMillis: Long,
    val endUtcMillis: Long?,
    val theirCallsign: String,
    val myCallsign: String,
    val theirGrid: String,
    val myGrid: String,
    val sentReport: String,
    val receivedReport: String,
    val txFrequencyHz: Long?,
    val rxFrequencyHz: Long?,
    val band: String,
    val rxBand: String,
    @ColumnInfo(defaultValue = "'MFSK'") val mode: String,
    @ColumnInfo(defaultValue = "'FT4'") val submode: String,
    val satelliteName: String,
    val transponderName: String,
    val satelliteMode: String,
    val passAosUtcMillis: Long?,
    val automatic: Boolean,
    val status: String,
    @ColumnInfo(defaultValue = "''") val dedupeKey: String,
    @ColumnInfo(defaultValue = "''") val propagationMode: String = "",
    @ColumnInfo(defaultValue = "0") val lotwConfirmed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lotwReceived: Boolean = false,
    @ColumnInfo(defaultValue = "''") val lotwQslDate: String = "",
    @ColumnInfo(defaultValue = "''") val vuccGrids: String = "",
    val dxcc: Int? = null,
    @ColumnInfo(defaultValue = "''") val country: String = "",
    val cqZone: Int? = null,
    @ColumnInfo(defaultValue = "''") val region: String = "",
    @ColumnInfo(defaultValue = "''") val comment: String = ""
)
