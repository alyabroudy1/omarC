package com.cloudstream.shared.extractors

/**
 * Headers captured from a WebView are evidence, not a request template.
 *
 * The WebView sends a media request with `Range` and its own `Accept-Encoding`; copying those into a
 * later manifest request makes the server answer with a partial body, which is not a parseable
 * playlist. Transport headers are likewise owned by the HTTP client that actually performs the
 * playback request. Everything else — Origin, Referer, cookies, the client hints that the page used
 * to mint the token — is preserved.
 */
object SnifferRequestHeaders {

    private val DROPPED_HEADERS = setOf(
        "host",
        "connection",
        "content-length",
        "content-type",
        "upgrade",
        "transfer-encoding",
        "accept-encoding",
        "range",
        "if-range"
    )

    fun sanitize(captured: Map<String, String>): Map<String, String> = buildMap {
        for ((rawName, value) in captured) {
            val name = rawName.trim()
            if (name.isBlank() || value.isBlank()) continue
            if (name.lowercase() in DROPPED_HEADERS) continue
            put(name, value)
        }
    }
}
