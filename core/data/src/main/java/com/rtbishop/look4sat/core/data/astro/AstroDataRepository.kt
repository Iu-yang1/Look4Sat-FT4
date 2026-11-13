package com.rtbishop.look4sat.core.data.astro

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.data.repository.UPDATE_MIRRORS
import com.rtbishop.look4sat.core.domain.astro.AstroDataPhase
import com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot
import com.rtbishop.look4sat.core.domain.astro.AstroDataState
import com.rtbishop.look4sat.core.domain.astro.AstroDataUnavailableException
import com.rtbishop.look4sat.core.domain.astro.DEFAULT_ASTRO_DATA_URL
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.UUID

internal fun astroDownloadCandidates(url: String, source: UpdateSource): List<String> {
    val prefix = UPDATE_MIRRORS.firstOrNull { url.startsWith(it) }
    val canonical = if (prefix != null) url.removePrefix(prefix) else url
    if (canonical.toHttpUrl().host != "github.com") return listOf(url)
    val prefixes = when (source) {
        UpdateSource.AUTO -> listOf("") + UPDATE_MIRRORS
        UpdateSource.GITHUB -> listOf("")
        UpdateSource.GHFAST -> listOf(UPDATE_MIRRORS[0])
        UpdateSource.LLKK -> listOf(UPDATE_MIRRORS[1])
        UpdateSource.MOEYY -> listOf(UPDATE_MIRRORS[2])
    }
    return (listOfNotNull(prefix) + prefixes).distinct().map { it + canonical }
}

internal fun migrateAstroDownloadUrl(url: String): String {
    val oldDefault =
        "https://github.com/Iu-yang1/Look4Sat-FT4/releases/download/orekit-data/orekit-data.zip"
    val prefix = UPDATE_MIRRORS.firstOrNull { url.startsWith(it) }.orEmpty()
    return if (url.removePrefix(prefix) == oldDefault) prefix + DEFAULT_ASTRO_DATA_URL else url
}

class AstroDataRepository(
    private val root: File,
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val diagnostics: IControlDiagnostics,
    private val source: () -> UpdateSource,
    private val bundledArchive: (() -> InputStream)? = null
) : IAstroDataRepository {
    private val mutableState = MutableStateFlow(AstroDataState(
        downloadUrl = migrateAstroDownloadUrl(
            preferences.getString("downloadUrl", DEFAULT_ASTRO_DATA_URL) ?: DEFAULT_ASTRO_DATA_URL
        )
    ))
    override val state = mutableState.asStateFlow()
    @Volatile private var active: OrekitRuntime? = null
    @Volatile private var activeCall: Call? = null
    private var downloadJob: Job? = null
    private val generations = mutableListOf<java.lang.ref.WeakReference<OrekitRuntime>>()

    init {
        downloadJob = scope.launch(Dispatchers.IO) {
            val saved = preferences.getString("activeDirectory", null)
            if (saved != null) {
                try {
                    require(saved.matches(Regex("generation-[a-f0-9-]+")))
                    val directory = File(root, saved)
                    val job = currentCoroutineContext()
                    val metadata = AstroDataPackage.verify(directory) { job.ensureActive() }
                    activate(OrekitRuntime(directory, metadata))
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (bundledArchive == null) {
                        failure(error)
                        return@launch
                    }
                    diagnostics.record(ControlDiagnosticSource.RADIO, "astro/restore",
                        "Unable to restore downloaded data, preparing bundled data: $error",
                        ControlDiagnosticSeverity.WARNING)
                }
            }
            if (bundledArchive == null) {
                mutableState.update { it.copy(phase = AstroDataPhase.MISSING) }
            } else {
                try {
                    installBundled()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure(error)
                }
            }
        }
    }

    private suspend fun installBundled() {
        val open = requireNotNull(bundledArchive)
        val job = currentCoroutineContext()
        check(root.isDirectory || root.mkdirs())
        val generation = "generation-${UUID.randomUUID()}"
        val temporary = File(root, "$generation.zip.part")
        val directory = File(root, generation)
        try {
            open().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            val metadata = AstroDataPackage.extract(temporary, directory) { job.ensureActive() }
            require(metadata.builtIn) { "Bundled data package is not marked as bundled" }
            val runtime = OrekitRuntime(directory, metadata)
            job.ensureActive()
            check(preferences.edit().putString("activeDirectory", generation).commit())
            activate(runtime)
        } finally {
            temporary.delete()
            if (preferences.getString("activeDirectory", null) != generation) directory.deleteRecursively()
        }
    }

    override fun snapshot(): AstroDataSnapshot = active ?: throw AstroDataUnavailableException()

    override fun setDownloadUrl(url: String) {
        val value = migrateAstroDownloadUrl(url.trim())
        value.toHttpUrl()
        check(preferences.edit().putString("downloadUrl", value).commit())
        mutableState.update { it.copy(downloadUrl = value) }
    }

    @Synchronized
    override fun download() {
        if (state.value.busy || downloadJob?.isActive == true) return
        val url = state.value.downloadUrl
        mutableState.update { it.copy(
            phase = AstroDataPhase.DOWNLOADING, downloadedBytes = 0, totalBytes = null, error = null
        ) }
        downloadJob = scope.launch(Dispatchers.IO) {
            val generation = "generation-${UUID.randomUUID()}"
            val directory = File(root, generation)
            val temporary = File(root, "$generation.zip.part")
            val context = currentCoroutineContext()
            try {
                check(root.isDirectory || root.mkdirs())
                mutableState.update { it.copy(
                    phase = AstroDataPhase.DOWNLOADING, downloadedBytes = 0, totalBytes = null, error = null
                ) }
                var lastFailure: Exception? = null
                for (candidate in astroDownloadCandidates(url, source())) {
                    context.ensureActive()
                    try {
                        fetch(candidate, temporary) { context.ensureActive() }
                        lastFailure = null
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        context.ensureActive()
                        lastFailure = error
                    }
                }
                lastFailure?.let { throw it }
                context.ensureActive()
                mutableState.update { it.copy(phase = AstroDataPhase.VERIFYING) }
                val metadata = AstroDataPackage.extract(temporary, directory) { context.ensureActive() }
                val runtime = OrekitRuntime(directory, metadata)
                context.ensureActive()
                check(preferences.edit().putString("activeDirectory", generation).commit())
                activate(runtime)
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(
                    phase = if (it.ready) AstroDataPhase.READY else AstroDataPhase.MISSING, error = null
                ) }
                throw cancelled
            } catch (error: Exception) {
                failure(error)
            } finally {
                activeCall = null
                temporary.delete()
                // Only this request's generated directory, never a configured external path.
                if (preferences.getString("activeDirectory", null) != generation) directory.deleteRecursively()
            }
        }
        val request = downloadJob
        request?.invokeOnCompletion { error ->
            if (error is CancellationException && downloadJob === request) {
                mutableState.update { current ->
                    if (!current.busy) current else current.copy(
                        phase = if (current.ready) AstroDataPhase.READY else AstroDataPhase.MISSING,
                        error = null
                    )
                }
            }
        }
    }

    private fun fetch(url: String, output: File, checkCancelled: () -> Unit) {
        val call = client.newCall(Request.Builder().url(url).build())
        activeCall = call
        call.execute().use { response ->
            check(response.isSuccessful) { "Astronomy download HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            var bytes = 0L
            var reported = 0L
            body.byteStream().use { input ->
                output.outputStream().use { stream ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        stream.write(buffer, 0, count)
                        bytes += count
                        if (bytes - reported >= 256 * 1024) {
                            reported = bytes
                            mutableState.update { it.copy(downloadedBytes = bytes, totalBytes = total) }
                        }
                    }
                }
            }
            check(total == null || bytes == total) { "Incomplete astronomy download" }
            mutableState.update { it.copy(downloadedBytes = bytes, totalBytes = total) }
        }
    }

    override fun cancel() {
        if (state.value.phase == AstroDataPhase.LOADING) return
        downloadJob?.cancel()
        activeCall?.cancel()
    }

    private fun activate(runtime: OrekitRuntime) {
        active = runtime
        generations.add(java.lang.ref.WeakReference(runtime))
        mutableState.update { it.copy(
            phase = AstroDataPhase.READY, version = runtime.version, generation = runtime.directory.name,
            coverageStartMillis = runtime.coverageStartMillis, coverageEndMillis = runtime.coverageEndMillis,
            eopEndMillis = runtime.eopEndMillis, error = null, builtIn = runtime.builtIn
        ) }
        diagnostics.record(ControlDiagnosticSource.RADIO, "astro/data",
            "version=${runtime.version} start=${runtime.coverageStartMillis} end=${runtime.coverageEndMillis} " +
                "eopEnd=${runtime.eopEndMillis}")
        try {
            retireUnusedGenerations()
        } catch (error: Exception) {
            diagnostics.record(ControlDiagnosticSource.RADIO, "astro/cleanup",
                "Unable to clean retired data generations: $error",
                ControlDiagnosticSeverity.WARNING)
        }
    }

    private fun retireUnusedGenerations() {
        generations.removeAll { it.get() == null }
        val retained = generations.mapNotNull { it.get()?.directory?.name }.toSet()
        val base = root.canonicalFile
        root.listFiles()?.filter {
            it.isDirectory && it.name.matches(Regex("generation-[a-f0-9-]+")) && it.name !in retained
        }?.forEach { directory ->
            if (directory.canonicalFile.parentFile == base && !directory.deleteRecursively()) {
                diagnostics.record(ControlDiagnosticSource.RADIO, "astro/cleanup",
                    "Unable to remove retired data generation ${directory.name}",
                    ControlDiagnosticSeverity.WARNING)
            }
        }
    }

    private fun failure(error: Exception) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "astro/data", error.toString(),
            ControlDiagnosticSeverity.ERROR)
        mutableState.update { it.copy(phase = AstroDataPhase.FAILED, error = error.message) }
    }
}
