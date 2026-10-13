version = 10

// Include shared source directory
android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    authors = listOf("omarC", "Antigravity")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=tuktukhd.com&sz=%size%"
}
