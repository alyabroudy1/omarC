version = 9

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=cimanow.cc&sz=%size%"
}

// NOTE — do not add `implementation("androidx.webkit:webkit:…")` here. It was tried on 2026-08-05 to
// let `NavigationEngine.hideXRequestedWithHeader`'s Approach 0 run, and it cannot work: this module is
// packaged by the CloudStream gradle plugin, whose `compileDex` dexes **project classes only**.
// Declared dependencies are compile-time stubs resolved against what the *host app* already ships —
// which is what the root build file means by "these dependencies can include any of those which are
// added by the app". A fresh `.cs3` built with the dependency present contains zero `androidx/webkit`
// strings (rhino and appcompat, declared the same way, are likewise absent), and the device log still
// read `androidx.webkit not on the classpath`. Nothing declared in gradle reaches the device unless
// Cloudstream itself bundles it, and Cloudstream does not bundle androidx.webkit.

android {
    namespace = "com.cimanow"

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}
