package com.cloudstream.shared.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * T1: what the interceptor actually puts on the wire. `headersFor` is pure and tested elsewhere;
 * this pins the `intercept()` half — tag lookup, `Referer` parsing, the caller-UA short-circuit,
 * and the fact that we leave `Accept-Encoding` to OkHttp.
 */
class FingerprintInterceptorWireTest {

    private val fp = Fingerprint.fromUserAgent(
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/150.0.7100.5 Mobile Safari/537.36",
        Locale.US, "16", "Pixel 9"
    )

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        FingerprintInterceptor.fingerprintProvider = { fp }
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().addInterceptor(FingerprintInterceptor).build()
    }

    @After
    fun tearDown() {
        FingerprintInterceptor.fingerprintProvider = { Fingerprint.current() }
        server.shutdown()
    }

    private fun send(build: Request.Builder.() -> Unit = {}): RecordedRequest {
        server.enqueue(MockResponse())
        val request = Request.Builder().url(server.url("/page")).apply(build).build()
        client.newCall(request).execute().use { it.body.string() }
        return server.takeRequest()
    }

    @Test
    fun documentTagGetsTheFullIdentity() {
        val recorded = send { tag(RequestKind::class.java, RequestKind.Document) }
        assertEquals(fp.userAgent, recorded.getHeader("user-agent"))
        assertEquals(fp.secChUa, recorded.getHeader("sec-ch-ua"))
        assertEquals("?1", recorded.getHeader("sec-ch-ua-mobile"))
        assertEquals("\"Android\"", recorded.getHeader("sec-ch-ua-platform"))
        assertEquals(fp.acceptLanguage, recorded.getHeader("accept-language"))
        assertEquals("document", recorded.getHeader("sec-fetch-dest"))
        assertEquals("navigate", recorded.getHeader("sec-fetch-mode"))
        assertEquals("1", recorded.getHeader("upgrade-insecure-requests"))
    }

    @Test
    fun untaggedIsASubresource() {
        val recorded = send()
        assertEquals("empty", recorded.getHeader("sec-fetch-dest"))
        assertEquals("cors", recorded.getHeader("sec-fetch-mode"))
        assertEquals("*/*", recorded.getHeader("accept"))
        assertNull(recorded.getHeader("upgrade-insecure-requests"))
    }

    @Test
    fun foreignCallerUaSuppressesTheHints() {
        val recorded = send {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
        }
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0",
            recorded.getHeader("user-agent")
        )
        assertNull(recorded.getHeader("sec-ch-ua"))
        assertNull(recorded.getHeader("accept-language"))
    }

    @Test
    fun callerUaEqualToFingerprintKeepsTheHints() {
        val recorded = send { header("User-Agent", fp.userAgent) }
        assertEquals(fp.userAgent, recorded.getHeader("user-agent"))
        assertEquals(fp.secChUa, recorded.getHeader("sec-ch-ua"))
    }

    /** We never set `Accept-Encoding`; OkHttp's BridgeInterceptor adds `gzip` and gunzips for us. */
    @Test
    fun acceptEncodingIsOkHttpsGzip() {
        assertEquals("gzip", send().getHeader("accept-encoding"))
    }

    @Test
    fun secFetchSiteFollowsTheReferer() {
        val same = send { header("Referer", server.url("/other").toString()) }
        assertEquals("same-origin", same.getHeader("sec-fetch-site"))

        val cross = send { header("Referer", "https://cdn.other.com/x") }
        assertEquals("cross-site", cross.getHeader("sec-fetch-site"))

        assertEquals("none", send().getHeader("sec-fetch-site"))
    }
}
