package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord

interface ILoTWUploadRepository {
    suspend fun certificate(): LoTWCertificate?
    suspend fun station(): LoTWStation?
    suspend fun importCertificate(data: ByteArray, password: CharArray): LoTWCertificate
    suspend fun saveCertificatePassword(password: CharArray): LoTWCertificate
    suspend fun saveStation(station: LoTWStation): LoTWStation
    suspend fun removeCertificate()
    suspend fun audit(records: List<QsoRecord>): LoTWUploadAudit
    suspend fun prepare(records: List<QsoRecord>, resubmit: Boolean): LoTWUploadPreview
    suspend fun upload(previewId: String): LoTWUploadResult
    fun discardPreview()
}

data class LoTWCertificate(
    val callsign: String,
    val dxcc: Int,
    val serial: String,
    val expires: String,
    val firstQsoDate: String,
    val lastQsoDate: String,
    val passwordSaved: Boolean = false
)

/** The location is explicitly confirmed for the selected contacts, not inferred from today's GPS. */
data class LoTWStation(
    val grid: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val region: String = "",
    val county: String = "",
    val iota: String = ""
)

data class LoTWUploadPreview(
    val id: String,
    val callsign: String,
    val dxcc: Int,
    val grid: String,
    val count: Int,
    val skipped: Int,
    val firstUtc: String,
    val lastUtc: String,
    val contacts: List<String>,
    val unknownSkipped: Int = 0
)

/** Local comparison against downloaded LoTW receipt flags and this app's durable upload receipts. */
data class LoTWUploadAudit(
    val total: Int,
    val pending: Int,
    val uploaded: Int,
    val unknown: Int,
    val unavailable: Int
)

sealed interface LoTWUploadResult {
    data class Accepted(val count: Int) : LoTWUploadResult
    data class Rejected(val message: String = "") : LoTWUploadResult
    /** May have reached LoTW. Do not retry automatically. */
    data object Unknown : LoTWUploadResult
    data object ExpiredPreview : LoTWUploadResult
}

class LoTWOperationException(val reason: LoTWProblem, val detail: String = "") : Exception(reason.name)

enum class LoTWProblem {
    CERTIFICATE_PASSWORD, CERTIFICATE_INVALID, CERTIFICATE_EXPIRED, CERTIFICATE_MISSING, CERTIFICATE_FORMAT,
    STORAGE, EMPTY_SELECTION, CALLSIGN_MISMATCH, QSO_DATE, STATION_GRID, STATION_REGION,
    STATION_ZONE, STATION_IOTA, MODE, BAND, SATELLITE, INVALID_CONTACT, LOCATION_MISMATCH, TOO_MANY_CONTACTS
}
