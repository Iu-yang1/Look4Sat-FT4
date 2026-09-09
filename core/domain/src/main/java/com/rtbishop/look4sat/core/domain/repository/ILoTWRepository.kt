package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord

interface ILoTWRepository {
    suspend fun fetchConfirmedQsos(callsign: String, password: String): LoTWResult
}

sealed interface LoTWResult {
    data class Success(val records: List<QsoRecord>) : LoTWResult
    data object BadCredentials : LoTWResult
    data object RateLimited : LoTWResult
    data object Timeout : LoTWResult
    data object NetworkError : LoTWResult
    data object InvalidReport : LoTWResult
}
