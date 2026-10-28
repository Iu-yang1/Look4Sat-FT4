// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.core.domain.jtty

import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import kotlinx.coroutines.flow.StateFlow

data class JttyMessage(
    val id: String, val utcMillis: Long, val frequencyHz: Float, val snrDb: Float,
    val text: String, val complete: Boolean, val ended: Boolean, val calls: List<String> = emptyList()
)
data class JttyCarrier(val id: Long, val frequencyHz: Float, val strengthDb: Float, val lastSeenMillis: Long, val confirmed: Boolean = false)
data class JttyReceiveState(
    val receiving: Boolean = false, val messages: List<JttyMessage> = emptyList(),
    val carriers: List<JttyCarrier> = emptyList(), val spectrum: Ft4SpectrumFrame? = null,
    val selectedRxHz: Float = 1500f, val discontinuities: Long = 0, val error: String? = null
)
data class JttyEncoding(val normalized: String, val frames: Int) {
    val durationMillis: Long get() = frames * 1_888L
}
data class JttyTransmitRequest(
    val message: String, val frequencyHz: Float, val satelliteCatalogNumber: Int,
    val transponderUuid: String, val sessionGeneration: Long, val isCurrent: () -> Boolean,
    val volume: Float = 0.8f
)
interface IJttyService {
    val state: StateFlow<JttyReceiveState>
    suspend fun startReceiving()
    suspend fun stopReceiving()
    fun clear()
    fun selectReceiveFrequency(hz: Float)
    suspend fun encode(message: String): JttyEncoding
    suspend fun generate(message: String, frequencyHz: Float, rate: Int): FloatArray
}
interface IJttyTransmitter {
    val busy: StateFlow<Boolean>
    suspend fun transmit(request: JttyTransmitRequest)
    suspend fun stop()
}

object JttyText {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ +-./?!\"#\$%,&*()_'=[]{}<>|:;"
    fun normalize(text: String): String {
        require(text.length<=80 && text.all { it in ALPHABET || it in 'a'..'z' }) { "Unsupported JTTY character or text longer than 80 characters" }
        val result=text.trim().uppercase(java.util.Locale.US).replace(Regex(" +")," ")
        require(result.isNotEmpty() && result.length <= 80) { "JTTY text must contain 1–80 characters" }
        require(result.all { it in ALPHABET }) { "Unsupported JTTY character" }
        return result
    }
    fun expand(template: String, fields: Map<String,String>): String {
        val variables=Regex("\\{([^{}]*)}")
        val expanded=variables.replace(template) { match ->
            val key=match.groupValues[1]
            require(key in setOf("CALL","MYCALL","RST","GRID")) { "Unknown variable: $key" }
            fields[key]?.takeIf(String::isNotBlank) ?: error("Missing field: $key")
        }
        require('{' !in expanded && '}' !in expanded) { "Invalid template variable" }
        return normalize(expanded)
    }
}
