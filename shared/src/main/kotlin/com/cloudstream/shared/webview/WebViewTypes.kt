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

    /** Exit when a CSS selector matches at least [minCount] elements */
    data class ElementsFound(val selector: String, val minCount: Int = 1) : ExitCondition()

    /** Exit when current URL matches [urlPattern] regex */
    data class UrlMatches(val urlPattern: String) : ExitCondition()

    /** Exit after a fixed delay from page load */
    data class AfterDelay(val delayMs: Long) : ExitCondition()
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

    /** User explicitly cancelled the operation (e.g., pressed back on CF dialog). */
    data class Cancelled(val reason: String) : WebViewResult()
}

data class CapturedLinkData(
    val url: String,
    val qualityLabel: String,
    val headers: Map<String, String>
)

/**
 * A single step in a multi-step WebView navigation flow.
 */
sealed class NavigationStep {
    data class LoadUrl(
        val url: String,
        val referer: String? = null,
        val extraHeaders: Map<String, String> = emptyMap()
    ) : NavigationStep()

    data class ClickElement(
        val selector: String,
        val timeoutMs: Long = 10_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class ClickCoordinates(
        val x: Float,
        val y: Float
    ) : NavigationStep()

    data class ExecuteJs(
        val javascript: String,
        /** If non-blank, the JS return value is stored in NavigationResult.extractedHtml[key] */
        val key: String = ""
    ) : NavigationStep()

    data class WaitForSelector(
        val selector: String,
        val timeoutMs: Long = 10_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class WaitForUrl(
        val urlPattern: String,
        val timeoutMs: Long = 15_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class WaitForDelay(
        val delayMs: Long
    ) : NavigationStep()

    data class WaitForDomCondition(
        val jsCondition: String,
        val timeoutMs: Long = 15_000L,
        val pollIntervalMs: Long = 500L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class ExtractHtml(
        val selector: String? = null,
        val key: String = ""
    ) : NavigationStep()
}

/**
 * Result of a multi-step navigation flow.
 */
data class NavigationResult(
    val success: Boolean,
    val finalUrl: String,
    val cookies: Map<String, String>,
    val extractedHtml: Map<String, String>,
    val completedSteps: Int,
    val failedAtStep: Int? = null,
    val error: String? = null
)
