package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale

data class LogIdentityMismatch(val callsign: Boolean, val grid: Boolean)
private val identityGrid=Regex("[A-R]{2}[0-9]{2}(?:[A-X]{2}(?:[0-9]{2})?)?")

fun logIdentityMismatch(ownCallsign: String, ownGrid: String, uploadCallsign: String, uploadGrid: String): LogIdentityMismatch {
    fun String.normalized() = trim().uppercase(Locale.ROOT)
    val call = ownCallsign.normalized()
    val stationCall = uploadCallsign.normalized()
    val grids = uploadGrid.split(',', ';', ' ').map {it.normalized()}.filter {identityGrid.matches(it)}
    val loggedGrids = ownGrid.split(',', ';', ' ').map {it.normalized()}.filter {identityGrid.matches(it)}
    return LogIdentityMismatch(
        call.isNotBlank() && stationCall.isNotBlank() && call != stationCall,
        grids.isNotEmpty() && loggedGrids.any { own -> grids.none {it.startsWith(own) || own.startsWith(it)} }
    )
}
