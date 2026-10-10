
version = 1

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.youtube"

    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}

dependencies {

    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")

    implementation("androidx.preference:preference-ktx:1.2.1")
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=lodynet.com&sz=%size%"
}