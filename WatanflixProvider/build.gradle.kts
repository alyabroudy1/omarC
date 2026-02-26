version = 1

android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
            kotlin.srcDir("../YoutubeProvider/src/main/kotlin")
        }
    }
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=watanflix.com&sz=%size%"
}
