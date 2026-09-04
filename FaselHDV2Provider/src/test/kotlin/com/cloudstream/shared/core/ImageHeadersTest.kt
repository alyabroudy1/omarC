package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** T5: the one image-header shape, pure. */
class ImageHeadersTest {

    private val fp = Fingerprint.fromUserAgent(
        "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP1A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/150.0.7100.5 Mobile Safari/537.36",
        Locale.US, "16", "Pixel 9"
    )

    @Test
    fun exactShape() {
        assertEquals(
            mapOf(
                "User-Agent" to fp.userAgent,
                "Referer" to "https://example.com/",
                "Accept" to "image/avif,image/webp,*/*",
                "Cookie" to "a=1; b=2"
            ),
            fp.imageHeaders("https://example.com/", "a=1; b=2")
        )
    }

    @Test
    fun cookieOmittedWhenBlank() {
        for (blank in listOf(null, "", "   ")) {
            val headers = fp.imageHeaders("https://example.com/", blank)
            assertEquals(
                mapOf(
                    "User-Agent" to fp.userAgent,
                    "Referer" to "https://example.com/",
                    "Accept" to "image/avif,image/webp,*/*"
                ),
                headers
            )
        }
    }

    @Test
    fun refererOmittedWhenNull() {
        assertEquals(
            mapOf(
                "User-Agent" to fp.userAgent,
                "Accept" to "image/avif,image/webp,*/*"
            ),
            fp.imageHeaders(null, null)
        )
    }
}
