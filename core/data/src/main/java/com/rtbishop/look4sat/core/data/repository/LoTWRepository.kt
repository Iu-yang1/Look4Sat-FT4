package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWDownloadRequest
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Credentials are sent only to the fixed HTTPS origin, never persisted or logged. */
class LoTWRepository(client: OkHttpClient = OkHttpClient()) : ILoTWRepository {
    private val client = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun fetchConfirmedQsos(callsign: String, password: String): LoTWResult = download(
        LoTWDownloadRequest(callsign, password, confirmedOnly = true, satellitesOnly = true)
    )

    override suspend fun download(request: LoTWDownloadRequest): LoTWResult {
        if (request.username.isBlank() || request.password.isBlank()) return LoTWResult.BadCredentials
        if (!validLoTWDate(request.since)) return LoTWResult.InvalidDate
        val url = "https://lotw.arrl.org/lotwuser/lotwreport.adi".toHttpUrl().newBuilder()
            .addQueryParameter("login", request.username.trim())
            .addQueryParameter("password", request.password)
            .addQueryParameter("qso_query", "1")
            .addQueryParameter("qso_qsl", if (request.confirmedOnly) "yes" else "no")
            .addQueryParameter("qso_qsldetail", "yes")
            .addQueryParameter("qso_mydetail", "yes")
            .addQueryParameter("qso_withown", "yes")
            .addQueryParameter(if (request.confirmedOnly) "qso_qslsince" else "qso_qsorxsince", request.since)
            .apply {
                if (request.stationCallsign.isNotBlank()) addQueryParameter("qso_owncall", request.stationCallsign.trim())
            }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        if (e is InterruptedIOException) LoTWResult.Timeout else LoTWResult.NetworkError
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            when {
                                it.code == 401 || it.code == 403 -> LoTWResult.BadCredentials
                                it.code == 429 || it.code == 503 -> LoTWResult.RateLimited
                                !it.isSuccessful -> LoTWResult.ServerError(it.code)
                                else -> parseLoTWReport(readBoundedBody(it, MAX_REPORT_CHARS), request)
                            }
                        }
                    } catch (_: InterruptedIOException) {
                        LoTWResult.Timeout
                    } catch (_: IOException) {
                        LoTWResult.NetworkError
                    } catch (_: RuntimeException) {
                        LoTWResult.InvalidReport
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private companion object { const val MAX_REPORT_CHARS = 32 * 1024 * 1024 }
}

internal fun readBoundedBody(response: Response, limit: Int): String = response.body.charStream().use { reader ->
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(result.length <= limit - count) { "Response exceeds size limit" }
        result.append(buffer, 0, count)
    }
    result.toString()
}

internal fun validLoTWDate(value: String): Boolean = value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value) != null }.getOrDefault(false)
