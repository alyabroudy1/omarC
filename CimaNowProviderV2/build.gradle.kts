version = 7

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=cimanow.cc&sz=%size%"
}

android {
    namespace = "com.cimanow"

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}
