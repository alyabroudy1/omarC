package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** JVM-pure: only [Fingerprint.fromUserAgent] is exercised, never `current()`. */
class FingerprintTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP1A.250505.005; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/150.0.7100.5 " +
            "Mobile Safari/537.36"

    private fun of(ua: String, locale: Locale = Locale.US) =
        Fingerprint.fromUserAgent(ua, locale, "16", "Pixel 9")

    @Test
    fun stripsWebViewMarker() {
        val fp = of(webViewUa)
        assertFalse(fp.userAgent.contains("; wv)"))
        assertTrue(fp.userAgent.contains("Build/BP1A.250505.005)"))
    }

    @Test
    fun forcesMobileToken() {
        val desktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/150.0.7100.5 Safari/537.36"
        assertTrue(of(desktop).userAgent.contains("Mobile Safari"))
    }

    @Test
    fun parsesFullChromeVersion() {
        val fp = of(webViewUa)
        assertEquals("150.0.7100.5", fp.chromeFullVersion)
        assertEquals("150", fp.chromeMajor)
    }

    @Test
    fun brandListHasOneSpelling() {
        assertEquals(
            "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"",
            of(webViewUa).secChUa
        )
    }

    @Test
    fun acceptLanguageFromLocale() {
        assertEquals("ar-EG,ar;q=0.9", of(webViewUa, Locale("ar", "EG")).acceptLanguage)
        assertEquals("en-US,en;q=0.9", of(webViewUa, Locale.US).acceptLanguage)
        assertEquals("ar", of(webViewUa, Locale("ar")).acceptLanguage)
    }

    @Test
    fun stripsVersionToken() {
        val fp = of(webViewUa)
        assertFalse(fp.userAgent.contains("Version/"))
        assertTrue(fp.userAgent.contains("(KHTML, like Gecko) Chrome/150.0.7100.5"))
    }

    @Test
    fun buildThrowsWhenNoWebViewUa() {
        for (bad in listOf(null, "", "   ")) {
            try {
                Fingerprint.build(bad, Locale.US, "16", "Pixel 9")
                throw AssertionError("build($bad) should have thrown IllegalStateException")
            } catch (_: IllegalStateException) {
                // expected: there is no fallback User-Agent
            }
        }
    }

    @Test
    fun fromUserAgentIsDeterministic() {
        assertEquals(of(webViewUa), of(webViewUa))
    }

    @Test
    fun brandListMatchesSecChUa() {
        assertEquals(Fingerprint.brandListFor("150"), of(webViewUa).secChUa)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingChromeVersionThrows() {
        of("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36")
    }
}
