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
