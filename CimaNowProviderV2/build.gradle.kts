version = 9

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=cimanow.cc&sz=%size%"
}

dependencies {
    // The one thing standing between this provider and the flow that worked on 2026-07-30.
    //
    // `NavigationEngine.hideXRequestedWithHeader` already implements the right fix — Approach 0,
    // `WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())` — and it has
    // never once run, because it is reached by `Class.forName("androidx.webkit.WebViewFeature")` and
    // androidx.webkit is on nobody's classpath. Both runs in log3.txt say so outright:
    //
    //     [hideXRequestedWithHeader] androidx.webkit not on the classpath — falling through to reflection
    //     [hideXRequestedWithHeader] All reflection approaches failed — X-Requested-With may leak
    //
    // The four reflection fallbacks target WebView internals that moved years ago and cannot work on
    // WebView 150. So every request Chromium issues *without* passing through the interceptor goes out
    // wearing `X-Requested-With: com.lagradost.cloudstream3` — and per the on-device curl matrix
    // recorded at NavigationEngine.kt:2503, that header is exactly what turns freex2line's 35-byte
    // answer into a 403 block page. `get-link.php` is a POST, and a POST is the one thing the
    // interceptor must decline (no body on `WebResourceRequest`), so it is the one request that
    // leaks. Hence a button that never receives a URL.
    //
    // Scoped to this module rather than the root build file on purpose: no other provider depends on
    // a request Chromium issues on its own, and this bundles a library into every plugin it touches.
    implementation("androidx.webkit:webkit:1.12.1")
}

android {
    namespace = "com.cimanow"

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}
