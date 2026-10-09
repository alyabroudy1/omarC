version = 2
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}


cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Asia Drama")
    iconUrl = "https://www.google.com/s2/favicons?domain=asia2tv.com&sz=%size%"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.asia2tv"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}


dependencies {
    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    testImplementation("junit:junit:4.13.2")
}
