package com.cloudstream.shared.network

import com.cloudstream.shared.core.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** T7: the ExoPlayer-shaped validation headers, pure. */
class MediaUrlValidatorHeadersTest {

    private val fp = Fingerprint.fromUserAgent(
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/150.0.7100.5 Mobile Safari/537.36",
        Locale.US, "16", "Pixel 9"
    )

    private val validator = MediaUrlValidator()

    @Test
    fun keepsIdentityEncoding() {
        val headers = validator.buildExoPlayerHeaders(
            sourceHeaders = mapOf("Referer" to "https://example.com/"),
            fingerprint = fp,
            sessionCookies = emptyMap()
        )
        assertEquals("identity", headers["Accept-Encoding"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("https://example.com/", headers["Referer"])
    }

    @Test
    fun usesFingerprintUaWhenNoneSupplied() {
        val headers = validator.buildExoPlayerHeaders(emptyMap(), fp, emptyMap())
        assertEquals(fp.userAgent, headers["User-Agent"])
    }

    @Test
    fun sourceUaWins() {
        val headers = validator.buildExoPlayerHeaders(
            mapOf("User-Agent" to "custom/1.0"), fp, emptyMap()
        )
        assertEquals("custom/1.0", headers["User-Agent"])
    }

    @Test
    fun sessionCookiesOnlyWhenSourceHasNone() {
        assertEquals(
            "a=1; b=2",
            validator.buildExoPlayerHeaders(emptyMap(), fp, mapOf("a" to "1", "b" to "2"))["Cookie"]
        )
        assertEquals(
            "src=1",
            validator.buildExoPlayerHeaders(
                mapOf("Cookie" to "src=1"), fp, mapOf("a" to "1")
            )["Cookie"]
        )
    }
}
