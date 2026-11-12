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
package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

class UpdateRepository(
    private val remoteSource: IRemoteSource,
    context: Context
) : IUpdateRepository {
    private val context = context.applicationContext

    override suspend fun getLatestRelease(): LatestRelease? =
        getLatestRelease(UpdateSource.AUTO) {}

    override suspend fun getLatestRelease(
        source: UpdateSource,
        onSourceChanged: (UpdateSource) -> Unit
    ): LatestRelease? = withContext(Dispatchers.IO) {
        var metadata: LatestRelease? = null
        for (candidate in orderedSources(source)) {
            onSourceChanged(candidate)
            val prefix = sourcePrefix(candidate)
            coroutineContext.ensureActive()
            val manifest = readPage(prefix + LATEST_RELEASE_MANIFEST_URL)
                ?.let { parseReleaseManifest(it, prefix, Build.SUPPORTED_ABIS.toList()) }
            if (manifest != null) {
                metadata = metadata ?: manifest
                if (manifest.apkUrl != null) return@withContext manifest
            }
            // Download proxies support release files, not GitHub HTML or API pages.
            if (candidate != UpdateSource.GITHUB) continue
            val url = prefix + LATEST_RELEASE_URL
            val html = readPage(url) ?: continue
            val release = parseReleasePage(html, prefix, Build.SUPPORTED_ABIS.toList()) ?: continue
            metadata = metadata ?: release
            if (release.apkUrl != null) return@withContext release
            // GitHub lazy-loads the actual asset list in an include-fragment.
            val assets = readPage("$prefix$RELEASE_REPOSITORY/releases/expanded_assets/${release.versionTag}") ?: continue
            val withAssets = parseReleasePage(html + "\n" + assets, prefix, Build.SUPPORTED_ABIS.toList())
            if (withAssets?.apkUrl != null) return@withContext withAssets
        }
        metadata
    }

    private suspend fun readPage(url: String): String? = try {
        val network = remoteSource.getNetworkStream(url, CHECK_TIMEOUT_MS)
        if (network.code !in 200..299) {
            network.stream?.close()
            null
        } else network.stream?.bufferedReader()?.use { reader ->
            val text = StringBuilder()
            val buffer = CharArray(8192)
            var read = reader.read(buffer)
            while (read >= 0) {
                coroutineContext.ensureActive()
                text.append(buffer, 0, read)
                if (text.length > 4_000_000) return@use null
                read = reader.read(buffer)
            }
            text.toString()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun downloadApk(url: String, dest: File): Boolean =
        downloadCandidates(url, dest, listOf(url) + listOfNotNull(canonicalDownloadUrl(url)) +
            UPDATE_MIRRORS.mapNotNull { prefix -> canonicalDownloadUrl(url)?.let { prefix + it } }) { true }

    override suspend fun downloadApk(
        release: LatestRelease,
        dest: File,
        source: UpdateSource,
        preferred: UpdateSource?,
        onSourceChanged: (UpdateSource) -> Unit,
        validate: (File) -> Boolean
    ): Boolean {
        val canonical = release.apkUrl?.let(::canonicalDownloadUrl) ?: return false
        val path = URI(canonical).path
        if (path.substringBeforeLast('/').substringAfterLast('/') != release.versionTag) return false
        for (candidate in orderedSources(source, preferred)) {
            onSourceChanged(candidate)
            if (downloadCandidates(canonical, dest, listOf(sourcePrefix(candidate) + canonical), validate)) return true
        }
        return false
    }

    private fun orderedSources(source: UpdateSource, preferred: UpdateSource? = null): List<UpdateSource> =
        if (source != UpdateSource.AUTO) listOf(source)
        else (listOfNotNull(preferred?.takeUnless { it == UpdateSource.AUTO }) +
            listOf(UpdateSource.GITHUB, UpdateSource.GHFAST, UpdateSource.LLKK, UpdateSource.MOEYY)).distinct()

    private fun sourcePrefix(source: UpdateSource): String = when (source) {
        UpdateSource.AUTO, UpdateSource.GITHUB -> ""
        UpdateSource.GHFAST -> UPDATE_MIRRORS[0]
        UpdateSource.LLKK -> UPDATE_MIRRORS[1]
        UpdateSource.MOEYY -> UPDATE_MIRRORS[2]
    }

    private suspend fun downloadCandidates(
        url: String,
        dest: File,
        candidates: List<String>,
        validate: (File) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isTrustedDownloadUrl(url)) return@withContext false
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.nameWithoutExtension}.download.apk")
        try {
            for (candidate in candidates.distinct()) {
                coroutineContext.ensureActive()
                val network = remoteSource.getNetworkStream(candidate, DOWNLOAD_TIMEOUT_MS)
                val stream = network.stream ?: continue
                if (network.code !in 200..299) {
                    stream.close()
                    continue
                }
                val downloaded = try {
                    stream.use { input ->
                        partial.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var count = input.read(buffer)
                            while (count >= 0) {
                                coroutineContext.ensureActive()
                                total += count
                                if (total > MAX_APK_BYTES) throw java.io.IOException("APK exceeds size limit")
                                out.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                        }
                    }
                    validateApk(partial) && validate(partial)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!downloaded) continue
                if (dest.exists() && !dest.delete()) return@withContext false
                if (partial.renameTo(dest)) return@withContext true
                partial.copyTo(dest, overwrite = false)
                partial.delete()
                return@withContext true
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            println("UpdateRepository download failed: $e")
            false
        } finally {
            partial.delete()
        }
    }

    private fun validateApk(apk: File): Boolean {
        if (!apk.isFile || apk.length() !in MIN_APK_BYTES..MAX_APK_BYTES) return false
        val archive = packageInfo(apk.absolutePath) ?: return false
        val installed = packageInfo(context.packageName, archive = false) ?: return false
        if (archive.packageName != context.packageName) return false
        if (versionCode(archive) <= versionCode(installed)) return false
        if (!signaturesMatch(installed, archive)) return false
        return containsCompatibleNativeLibraries(apk)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(value: String, archive: Boolean = true): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (archive) {
            context.packageManager.getPackageArchiveInfo(value, flags)
        } else {
            context.packageManager.getPackageInfo(value, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo, includeHistory: Boolean): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (includeHistory && signingInfo.hasPastSigningCertificates()) {
                signingInfo.signingCertificateHistory
            } else {
                signingInfo.apkContentsSigners
            }
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }

    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        val installedCurrent = certificateDigests(installed, includeHistory = false)
        val archiveCurrent = certificateDigests(archive, includeHistory = false)
        if (installedCurrent.isEmpty() || archiveCurrent.isEmpty()) return false
        if (archiveCurrent == installedCurrent) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || archive.signingInfo?.hasMultipleSigners() == true) {
            return false
        }
        val archiveHistory = certificateDigests(archive, includeHistory = true)
        return archive.signingInfo?.hasPastSigningCertificates() == true &&
            installedCurrent.all(archiveHistory::contains)
    }

    private fun containsCompatibleNativeLibraries(apk: File): Boolean = runCatching {
        ZipFile(apk).use { zip ->
            val packagedAbis = zip.entries().asSequence()
                .mapNotNull { entry ->
                    entry.name.takeIf {
                        it.startsWith("lib/") && it.endsWith("/liblook4sat_ft4.so")
                    }
                        ?.substringAfter("lib/")
                        ?.substringBefore('/')
                }
                .toSet()
            packagedAbis.isNotEmpty() && Build.SUPPORTED_ABIS.any(packagedAbis::contains)
        }
    }.getOrDefault(false)

    private companion object {
        const val MIN_APK_BYTES = 100_000L
        const val MAX_APK_BYTES = 250_000_000L
        const val CHECK_TIMEOUT_MS = 9_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}

internal data class ReleaseAsset(
    val name: String,
    val url: String,
    val contentType: String,
    val state: String,
    val size: Long
)

internal fun selectReleaseAsset(
    assets: List<ReleaseAsset>,
    tag: String,
    supportedAbis: List<String>
): ReleaseAsset? = assets.mapNotNull { asset ->
    val score = releaseAssetScore(
        name = asset.name,
        contentType = asset.contentType,
        state = asset.state,
        size = asset.size,
        tag = tag,
        supportedAbis = supportedAbis
    ) ?: return@mapNotNull null
    if (!isTrustedDownloadUrl(asset.url)) return@mapNotNull null
    asset to score
}.maxByOrNull { it.second }?.first

private fun releaseAssetScore(
    name: String,
    contentType: String,
    state: String,
    size: Long,
    tag: String,
    supportedAbis: List<String>
): Int? {
    val normalized = name.lowercase(Locale.US)
    if (!normalized.endsWith(".apk") || "look4sat" !in normalized || "ft4" !in normalized) return null
    if (listOf("debug", "unsigned", "unaligned").any(normalized::contains)) return null
    if (state.isNotBlank() && state != "uploaded") return null
    if (contentType.isNotBlank() && contentType !in APK_CONTENT_TYPES) return null
    // Release HTML has no reliable byte size; the downloaded APK is checked before publication.
    if (size != -1L && size !in 100_000L..250_000_000L) return null

    val namedAbis = KNOWN_ABIS.filter { abi ->
        Regex("(?<![a-z0-9])${Regex.escape(abi)}(?![a-z0-9_])").containsMatchIn(normalized)
    }
    if (namedAbis.isNotEmpty() && namedAbis.none(supportedAbis::contains)) return null
    var score = 100
    if ("universal" in normalized) score += 20
    supportedAbis.forEachIndexed { index, abi ->
        if (abi in namedAbis) score += 50 - index
    }
    if (tag.lowercase(Locale.US).removePrefix("v") in normalized) score += 10
    if ("release" in normalized) score += 5
    return score
}

internal fun isTrustedDownloadUrl(url: String): Boolean = canonicalDownloadUrl(url) != null

internal fun canonicalDownloadUrl(url: String): String? = runCatching {
    val prefix = UPDATE_MIRRORS.firstOrNull { url.startsWith(it) }.orEmpty()
    val canonical = url.removePrefix(prefix)
    val uri = URI(canonical)
    val pathPrefix = "${URI(RELEASE_REPOSITORY).path}/releases/download/"
    val segments = uri.path.orEmpty().takeIf { it.startsWith(pathPrefix, true) }?.substring(pathPrefix.length)?.split('/')
    canonical.takeIf {
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) && uri.query == null && uri.fragment == null &&
            uri.normalize().path == uri.path &&
            segments?.size == 2 && segments.none { it.isBlank() || it == "." || it == ".." } &&
            segments.last().endsWith(".apk", true)
    }
}.getOrNull()

private val APK_CONTENT_TYPES = setOf("application/vnd.android.package-archive", "application/octet-stream")
private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
