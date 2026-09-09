package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Read-only LoTW confirmed satellite report. Credentials are never stored or logged. */
class LoTWRepository(client: OkHttpClient = OkHttpClient()) : ILoTWRepository {
    private val client = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun fetchConfirmedQsos(callsign: String, password: String): LoTWResult {
        val call = callsign.trim().uppercase(Locale.US)
        if (call.isBlank() || password.isBlank()) return LoTWResult.BadCredentials
        val url = "https://lotw.arrl.org/lotwuser/lotwreport.adi".toHttpUrl().newBuilder()
            .addQueryParameter("login", call)
            .addQueryParameter("password", password)
            .addQueryParameter("qso_query", "1")
            .addQueryParameter("qso_qsl", "yes")
            .addQueryParameter("qso_qsldetail", "yes")
            .addQueryParameter("qso_mydetail", "yes")
            .addQueryParameter("qso_qslsince", "2000-01-01")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val request = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { request.cancel() }
            request.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        if (e is InterruptedIOException) LoTWResult.Timeout else LoTWResult.NetworkError
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        when {
                            it.code == 401 || it.code == 403 || it.code in 300..399 -> LoTWResult.BadCredentials
                            it.code == 429 || it.code == 503 -> LoTWResult.RateLimited
                            !it.isSuccessful -> LoTWResult.NetworkError
                            else -> try {
                                val reader = it.body.charStream()
                                val body = StringBuilder()
                                val buffer = CharArray(8192)
                                var count = reader.read(buffer)
                                while (count >= 0) {
                                    body.append(buffer, 0, count)
                                    if (body.length > MAX_REPORT_CHARS) return@use LoTWResult.InvalidReport
                                    count = reader.read(buffer)
                                }
                                parseLoTWReport(body.toString(), callsign)
                            } catch (_: InterruptedIOException) {
                                LoTWResult.Timeout
                            } catch (_: IOException) {
                                LoTWResult.NetworkError
                            } catch (_: RuntimeException) {
                                LoTWResult.InvalidReport
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private companion object { const val MAX_REPORT_CHARS = 32 * 1024 * 1024 }
}

internal fun parseLoTWReport(body: String, stationCallsign: String): LoTWResult {
    if (!body.contains("<eoh>", ignoreCase = true)) {
        return when {
            body.contains("password", true) || body.contains("login", true) -> LoTWResult.BadCredentials
            body.contains("limit", true) || body.contains("try again", true) -> LoTWResult.RateLimited
            else -> LoTWResult.InvalidReport
        }
    }
    val decoded = AdifCodec.decode(body)
    if (decoded.isEmpty() && body.contains("<eor>", true)) return LoTWResult.InvalidReport
    // Field-length ADIF parsing accepts any field order and multiple fields per line.
    val records = decoded.filter { it.propagationMode.equals("SAT", true) }.map {
        it.copy(
            myCallsign = it.myCallsign.ifBlank { stationCallsign.trim().uppercase(Locale.US) },
            lotwConfirmed = true,
            status = QsoStatus.COMPLETE
        )
    }
    return LoTWResult.Success(records)
}
