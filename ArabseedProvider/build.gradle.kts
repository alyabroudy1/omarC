version = 30 // Fix: Reduce sniff timeout to 30s + HTML dump on timeout for debugging (v30)

// Include shared source directory
android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Anime", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=arabseed.show&sz=%size%"
}

