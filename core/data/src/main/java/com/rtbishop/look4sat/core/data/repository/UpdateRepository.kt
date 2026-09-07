package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

class UpdateRepository(
    private val remoteSource: IRemoteSource,
    context: Context
) : IUpdateRepository {
    private val context = context.applicationContext

    override suspend fun getLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        val result = remoteSource.getNetworkStream(LATEST_RELEASE_URL)
        val stream = result.stream ?: return@withContext null
        try {
            val json = stream.bufferedReader().use { it.readText() }
            parseRelease(json, Build.SUPPORTED_ABIS.toList())
        } catch (e: Exception) {
            println("UpdateRepository parse failure: $e")
            null
        }
    }

    override suspend fun downloadApk(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (!isTrustedDownloadUrl(url)) return@withContext false
        val result = remoteSource.getNetworkStream(url)
        val stream = result.stream ?: return@withContext false
        val partial = File(dest.parentFile, "${dest.name}.part")
        try {
            partial.delete()
            partial.outputStream().use { out -> stream.use { it.copyTo(out) } }
            if (!validateApk(partial)) return@withContext false
            if (dest.exists() && !dest.delete()) return@withContext false
            partial.renameTo(dest) || runCatching {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }.isSuccess
        } catch (e: Exception) {
            println("UpdateRepository download failure: $e")
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
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Iu-yang1/Look4Sat-FT4/releases/latest"
        const val MIN_APK_BYTES = 100_000L
        const val MAX_APK_BYTES = 250_000_000L
    }
}

internal fun parseRelease(json: String, supportedAbis: List<String>): LatestRelease? {
    val obj = JSONObject(json)
    val tag = obj.optString("tag_name")
    if (tag.isBlank()) return null
    val assets = buildList {
        val values = obj.optJSONArray("assets") ?: return@buildList
        for (index in 0 until values.length()) {
            val asset = values.optJSONObject(index) ?: continue
            add(
                ReleaseAsset(
                    name = asset.optString("name", ""),
                    url = asset.optString("browser_download_url", ""),
                    contentType = asset.optString("content_type", ""),
                    state = asset.optString("state", ""),
                    size = asset.optLong("size", 0L)
                )
            )
        }
    }
    return LatestRelease(
        versionTag = tag,
        title = obj.optString("name", ""),
        body = obj.optString("body", ""),
        apkUrl = selectReleaseAsset(assets, tag, supportedAbis)?.url
    )
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
    if (size !in 100_000L..250_000_000L) return null

    val namedAbis = KNOWN_ABIS.filter(normalized::contains)
    if (namedAbis.isNotEmpty() && namedAbis.none(supportedAbis::contains)) return null
    var score = 100
    if ("universal" in normalized) score += 20
    supportedAbis.forEachIndexed { index, abi ->
        if (abi.lowercase(Locale.US) in normalized) score += 50 - index
    }
    if (tag.lowercase(Locale.US).removePrefix("v") in normalized) score += 10
    if ("release" in normalized) score += 5
    return score
}

private fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.startsWith("/Iu-yang1/Look4Sat-FT4/releases/download/", ignoreCase = true)
}.getOrDefault(false)

private val APK_CONTENT_TYPES = setOf("application/vnd.android.package-archive", "application/octet-stream")
private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
