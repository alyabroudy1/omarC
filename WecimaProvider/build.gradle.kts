version = 4

android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}

cloudstream {
    authors = listOf("omarC")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=wecima.ac&sz=%size%"
}
