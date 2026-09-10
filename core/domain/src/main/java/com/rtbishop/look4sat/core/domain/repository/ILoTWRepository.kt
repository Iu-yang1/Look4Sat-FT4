package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord

interface ILoTWRepository {
    suspend fun fetchConfirmedQsos(callsign: String, password: String): LoTWResult
    suspend fun download(request: LoTWDownloadRequest): LoTWResult
}

data class LoTWDownloadRequest(
    val username: String,
    val password: String,
    val confirmedOnly: Boolean = false,
    val satellitesOnly: Boolean = false,
    val since: String = "1900-01-01",
    val stationCallsign: String = ""
)

sealed interface LoTWResult {
    data class Success(val records: List<QsoRecord>, val downloaded: Int = records.size) : LoTWResult
    data object BadCredentials : LoTWResult
    data object RateLimited : LoTWResult
    data object Timeout : LoTWResult
    data object NetworkError : LoTWResult
    data object InvalidReport : LoTWResult
    data object InvalidDate : LoTWResult
    data class ServerError(val status: Int) : LoTWResult
}
