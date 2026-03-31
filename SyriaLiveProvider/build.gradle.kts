version = 6

// Include shared source directory
android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=syrlive.com&sz=%size%"
}
