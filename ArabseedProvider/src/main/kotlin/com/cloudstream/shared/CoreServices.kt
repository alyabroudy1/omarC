package com.cloudstream.shared

import com.cloudstream.shared.http.CookieStore
import com.cloudstream.shared.http.InMemoryCookieStore
import com.cloudstream.shared.webview.WebViewEngine

/**
 * Singleton holder for shared services.
 * Ensures all providers (Arabseed, FaselHD, etc.) share the same state.
 */
object CoreServices {
    val cookieStore: CookieStore by lazy { InMemoryCookieStore() }
    
    // WebViewEngine needs an ActivityProvider, which varies by call context,
    // so it might need to be instantiated per-provider or use a dynamic provider.
    // For now, we'll keep WebViewEngine per-provider but sharing the cookieStore.
}
