package com.cloudstream.shared.util

/**
 * Release-risk diagnostics, off by default. [DUMPS] gates every write of page HTML or JS to
 * `cacheDir`/`externalCacheDir`; [WEBVIEW_REMOTE_DEBUGGING] gates DevTools-over-adb. Flip locally only.
 */
object DebugFlags {
    /**
     * Gates every write of HTML/JS to `cacheDir`/`externalCacheDir`.
     * Flip locally only — never commit `true`.
     */
    const val DUMPS = false

    /**
     * Gates `WebView.setWebContentsDebuggingEnabled`, which makes this app's WebViews and their
     * cookies inspectable by any process on the device. Flip locally only — never commit `true`.
     */
    const val WEBVIEW_REMOTE_DEBUGGING = false
}
