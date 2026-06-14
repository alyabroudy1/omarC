version = 1

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
    status = 3
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=cimaleek.pw&sz=%size%"
}
