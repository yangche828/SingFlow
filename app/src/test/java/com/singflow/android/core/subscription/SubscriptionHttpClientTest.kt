package com.singflow.android.core.subscription

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 订阅 HTTP 下载器集成测试（MockWebServer）。
 */
class SubscriptionHttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: SubscriptionHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = SubscriptionHttpClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloads content userinfo and strips bom`() = runTest {
        val body = "\uFEFFdmxlc3M6Ly9leGFtcGxl"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .addHeader(
                    "Subscription-Userinfo",
                    "upload=1024; download=2048; total=1048576; expire=1893456000"
                )
        )

        val result = httpClient.download(server.url("/sub").toString())

        assertEquals(body.removePrefix("\uFEFF"), result.content)
        assertNotNull(result.userInfo)
        assertEquals(1024L, result.userInfo?.upload)
        assertEquals(2048L, result.userInfo?.download)
        assertEquals(1_048_576L, result.userInfo?.total)
        assertEquals(1_893_456_000L, result.userInfo?.expireAt)
    }

    @Test
    fun `missing userinfo header returns null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("plain"))
        val result = httpClient.download(server.url("/sub").toString())
        assertEquals("plain", result.content)
        assertNull(result.userInfo)
    }

    @Test(expected = IllegalStateException::class)
    fun `non successful response throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        httpClient.download(server.url("/missing").toString())
    }
}
