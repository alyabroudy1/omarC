package com.cloudstream.shared.core

import com.cloudstream.shared.strategy.VideoSource
import org.jsoup.nodes.Document

/**
 * The HTTP surface a provider (or a shared extractor) is allowed to see.
 *
 * Seven members, deliberately: every one of them replaces a counted set of call sites on the
 * 992-line HTTP god object this replaced (see docs/wave-4b-design.md section 1). Adding an
 * eighth needs the same justification.
 */
interface ProviderRuntime {
    /** Current provider host, no scheme. Replaces currentDomain and mainUrl. */
    val domain: String

    /** Queued, CF-solving GET parsed as HTML. Rewrites the host to the session domain unless
     *  `rewrite = false` — pass false for absolute foreign URLs (embed hosts) and for URLs whose
     *  host must be honoured verbatim. solveCf=false throws CloudflareBlockedSearchException
     *  instead of solving. Replaces getDocument and getDocumentNoFallback. */
    suspend fun document(
        pathOrUrl: String,
        headers: Map<String, String> = emptyMap(),
        solveCf: Boolean = true,
        adoptRedirect: Boolean = false,
        allowCached: Boolean = false,
        rewrite: Boolean = true
    ): Document?

    /** Unqueued GET body as text, no domain rewriting, Tier-3 Chrome-TLS retry kept. Replaces getText. */
    suspend fun text(pathOrUrl: String, headers: Map<String, String> = emptyMap()): String?

    /** Form POST body as text. Replaces postText, post and postDebug. */
    suspend fun post(
        pathOrUrl: String,
        form: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        rewrite: Boolean = false
    ): String?

    /** Raw okhttp3.Response with session identity, no CF solve. Replaces getRaw verbatim. */
    suspend fun raw(url: String, headers: Map<String, String> = emptyMap(), useSession: Boolean = true): okhttp3.Response

    /** Headless-then-fullscreen CF WebView load, regex-scraped video sources. Replaces sniffVideos. */
    suspend fun sniff(url: String): List<VideoSource>

    /** One image header shape from Fingerprint.imageHeaders. Replaces getImageHeaders and getImageHeadersFull. */
    fun imageHeaders(targetDomain: String? = null): Map<String, String>
}
