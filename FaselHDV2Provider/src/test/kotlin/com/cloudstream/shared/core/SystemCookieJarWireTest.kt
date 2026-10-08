package com.cloudstream.shared.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wave 2 acceptance 3, on the wire: a `Set-Cookie` from one response comes back as a `Cookie`
 * header on the next request to the same host, with no cookie plumbing in provider code.
 */
class SystemCookieJarWireTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: FakeCookieStorage
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = FakeCookieStorage()
        client = OkHttpClient.Builder().cookieJar(SystemCookieJar(storage)).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun secondRequestCarriesTheCookieTheFirstResponseSet() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "sid=1; Path=/").setBody("one"))
        server.enqueue(MockResponse().setBody("two"))

        val url = server.url("/")
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

        val first = server.takeRequest()
        assertEquals(null, first.headers["Cookie"])

        val second = server.takeRequest()
        assertEquals("sid=1", second.headers["Cookie"])

        // The store got the Set-Cookie verbatim, attributes and all, against the response URL.
        assertEquals(1, storage.writes.size)
        assertEquals(url.toString(), storage.writes[0].first)
        val written = storage.writes[0].second
        assertTrue(written, written.startsWith("sid=1"))
        assertTrue(written, written.contains("path=/"))
    }
}
