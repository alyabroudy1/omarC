version = 5

// Include shared source directory
android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDir("../shared/src/test/kotlin")
        }
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Anime", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=laroza.co&sz=%size%"
}

