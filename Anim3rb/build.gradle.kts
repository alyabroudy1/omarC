version = 1

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anime3rb"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
}

android.sourceSets {
    getByName("main") {
        kotlin.srcDir("../shared/src/main/kotlin")
    }
}

dependencies {
    val cloudstream by configurations
    implementation("androidx.preference:preference-ktx:1.2.1")
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=anime3rb.com&sz=%size%"
}
