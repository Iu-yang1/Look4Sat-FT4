package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateSourceMergeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tag = "4.4.8-ba7opf.13.20"
    private val asset = "$RELEASE_REPOSITORY/releases/download/$tag/Look4Sat-FT4-$tag-release.apk"

    @Test fun automaticCheckFallsBackWithoutChangingRepositoryOrGuessingAssetNames() = runBlocking {
        val remote = Remote { url ->
            if (url.startsWith(UPDATE_MIRRORS[0])) NetworkResult(200, page().byteInputStream())
            else NetworkResult(503, null)
        }
        val seen = mutableListOf<UpdateSource>()
        val release = UpdateRepository(remote, context).getLatestRelease(UpdateSource.AUTO) { seen += it }
        assertEquals(tag, release?.versionTag)
        assertEquals(UPDATE_MIRRORS[0] + asset, release?.apkUrl)
        assertEquals(listOf(UpdateSource.GITHUB, UpdateSource.GHFAST), seen)
        assertTrue(remote.requests.all { RELEASE_REPOSITORY in it.first && it.second == 9_000L })
    }

    @Test fun manualSourceNeverFallsBack() = runBlocking {
        val remote = Remote { NetworkResult(503, null) }
        val release = UpdateRepository(remote, context).getLatestRelease(UpdateSource.LLKK) {}
        assertNull(release)
        assertEquals(listOf(UPDATE_MIRRORS[1] + "$RELEASE_REPOSITORY/releases/latest"),
            remote.requests.map { it.first })
    }

    @Test fun mismatchedTagIsRejectedBeforeDownloading() = runBlocking {
        val remote = Remote { error("Unexpected network request") }
        val dest = File(context.cacheDir, "mismatched-update.apk")
        val release = LatestRelease("4.4.8-ba7opf.13.19", "", "", asset)
        assertFalse(UpdateRepository(remote, context).downloadApk(
            release, dest, UpdateSource.AUTO, null, {}, { true }))
        assertTrue(remote.requests.isEmpty())
    }

    private fun page() = """
        <meta property="og:url" content="$RELEASE_REPOSITORY/releases/tag/$tag">
        <title>Release $tag</title>
        <a href="$asset">APK</a>
    """.trimIndent()

    private class Remote(private val respond: (String) -> NetworkResult) : IRemoteSource {
        val requests = mutableListOf<Pair<String, Long>>()
        override suspend fun getNetworkStream(url: String) = getNetworkStream(url, 0L)
        override suspend fun getNetworkStream(url: String, timeoutMs: Long): NetworkResult {
            requests += url to timeoutMs
            return respond(url)
        }
        override suspend fun getFileStream(uri: String): InputStream? = null
        override suspend fun getAmSatCatalog(): String? = null
        override suspend fun getAmSatReports(hours: Int, limit: Int): String? = null
        override suspend fun submitAmSatReport(payloadJson: String): Pair<Int, String>? = null
    }
}
