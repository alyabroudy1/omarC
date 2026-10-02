package com.cloudstream.shared.core

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An in-memory [CookieStorage]: the seam that keeps `android.webkit.CookieManager` out of the JVM
 * test run. It records every write so the tests can assert on what was handed to the store.
 */
class FakeCookieStorage(
    private val stored: MutableMap<String, String> = mutableMapOf()
) : CookieStorage {

    /** Every `set(url, header)` call, in order. */
    val writes = mutableListOf<Pair<String, String>>()

    fun seed(url: String, header: String) {
        stored[url] = header
    }

    override fun get(url: String): String? = stored[url]

    override fun set(url: String, header: String) {
        writes.add(url to header)
        // Good enough for the wire test: the store keeps whatever name=value the header opens with.
        val pair = header.substringBefore(";").trim()
        val existing = stored[url]
        stored[url] = if (existing.isNullOrBlank()) pair else "$existing; $pair"
    }
}

/** Wave 2: the mapping between OkHttp and the one cookie store, with nothing Android in it. */
class SystemCookieJarTest {

    private val url = "https://x.example/page".toHttpUrl()

    @Test
    fun loadForRequestParsesHeaderString() {
        val storage = FakeCookieStorage()
        storage.seed(url.toString(), "a=1; b=2")

        val cookies = SystemCookieJar(storage).loadForRequest(url)

        assertEquals(2, cookies.size)
        assertEquals(listOf("a", "b"), cookies.map { it.name })
        assertEquals(listOf("1", "2"), cookies.map { it.value })
    }

    @Test
    fun loadForRequestKeepsEqualsInsideValues() {
        // A base64/JWT-ish value carries `=`; only the first one separates name from value.
        val storage = FakeCookieStorage()
        storage.seed(url.toString(), "tok=a=b=c; x=1")

        val cookies = SystemCookieJar(storage).loadForRequest(url)

        assertEquals(2, cookies.size)
        assertEquals(listOf("tok", "x"), cookies.map { it.name })
        assertEquals(listOf("a=b=c", "1"), cookies.map { it.value })
    }

    @Test
    fun loadForRequestOnEmptyReturnsEmptyList() {
        val jar = SystemCookieJar(FakeCookieStorage())
        // Nothing stored at all.
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))

        val blank = FakeCookieStorage()
        blank.seed(url.toString(), "   ")
        assertEquals(emptyList<Cookie>(), SystemCookieJar(blank).loadForRequest(url))
    }

    @Test
    fun saveFromResponseWritesEachSetCookieVerbatim() {
        val storage = FakeCookieStorage()
        val cf = Cookie.Builder()
            .name("cf_clearance").value("abc")
            .domain("x.example").path("/")
            .expiresAt(2_000_000_000_000L)
            .build()
        val sid = Cookie.Builder()
            .name("sid").value("42")
            .domain("x.example").path("/sub")
            .build()

        SystemCookieJar(storage).saveFromResponse(url, listOf(cf, sid))

        assertEquals(2, storage.writes.size)
        val first = storage.writes[0].second
        assertTrue(first, first.startsWith("cf_clearance=abc"))
        assertTrue(first, first.contains("domain=x.example"))
        assertTrue(first, first.contains("path=/"))
        assertTrue(first, first.contains("expires="))
        val second = storage.writes[1].second
        assertTrue(second, second.startsWith("sid=42"))
        assertTrue(second, second.contains("path=/sub"))
    }

    @Test
    fun noHostRewritingOnSave() {
        val storage = FakeCookieStorage()
        val cookie = Cookie.Builder().name("a").value("1").domain("x.example").build()

        SystemCookieJar(storage).saveFromResponse(url, listOf(cookie))

        assertEquals(listOf(url.toString()), storage.writes.map { it.first })
    }
}
