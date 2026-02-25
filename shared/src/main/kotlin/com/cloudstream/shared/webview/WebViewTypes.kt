package com.cloudstream.shared.webview

/**
 * Shared types for WebView engines.
 *
 * Contains the common sealed classes and data types used by both
 * [CfBypassEngine] and [VideoSnifferEngine].
 */

/**
 * Operating mode for WebView engines.
 */
enum class Mode {
    HEADLESS,    // No UI, runs in background
    FULLSCREEN   // User-visible dialog for CAPTCHA or video sniffing
}

/**
 * Exit conditions for WebView sessions.
 */
sealed class ExitCondition {
    /** Exit when page loads without CF challenge */
    object PageLoaded : ExitCondition()

    /** Exit when specific cookies are present */
    data class CookiesPresent(val keys: List<String>) : ExitCondition()

    /** Exit when video URLs are found */
    data class VideoFound(val minCount: Int = 1) : ExitCondition()
}

/**
 * Result of a WebView session.
 */
sealed class WebViewResult {
    data class Success(
        val cookies: Map<String, String>,
        val html: String,
        val finalUrl: String,
        val foundLinks: List<CapturedLinkData> = emptyList()
    ) : WebViewResult()

    /** Video is playing in the WebView itself (DRM or unsniffable). Dialog stays open as player. */
    data class PlayingInWebView(
        val dialog: android.app.Dialog
    ) : WebViewResult()

    data class Timeout(
        val lastUrl: String,
        val partialHtml: String?
    ) : WebViewResult()

    data class Error(val reason: String) : WebViewResult()
}

data class CapturedLinkData(
    val url: String,
    val qualityLabel: String,
    val headers: Map<String, String>
)
