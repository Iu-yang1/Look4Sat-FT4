package com.rtbishop.look4sat.core.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RemoteSourceAmSatTest {
    @Test fun catalogReportsAndSubmissionUseTheNewApiWithoutContactingTheServer()=runBlocking {
        val requests=mutableListOf<Pair<String,String>>()
        var submitted=""
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("https",request.url.scheme)
            assertEquals("status.amsat.org",request.url.host)
            assertEquals("Look4Sat",request.header("User-Agent"))
            requests+=request.method to request.url.encodedPath
            if(request.method=="POST") {
                submitted=Buffer().also {request.body!!.writeTo(it)}.readUtf8()
                assertEquals("application/json; charset=utf-8",request.body!!.contentType().toString())
            }
            if(request.method=="GET" && request.url.encodedPath.endsWith("reports.php")) {
                assertEquals("48",request.url.queryParameter("hours"))
                assertEquals("25",request.url.queryParameter("limit"))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"ok\":true}".toResponseBody()).build()
        }.build()
        val source=RemoteSource(Dispatchers.Unconfined,RuntimeEnvironment.getApplication().contentResolver,client)
        assertNotNull(source.getAmSatCatalog())
        assertNotNull(source.getAmSatReports(48,25))
        assertEquals(200,source.submitAmSatReport("{\"test\":true}")?.first)
        assertEquals("{\"test\":true}",submitted)
        assertEquals(listOf("GET" to "/api/v1/catalog.php","GET" to "/api/v1/reports.php","POST" to "/api/v1/reports.php"),requests)
    }
}
