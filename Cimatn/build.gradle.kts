
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

version = 3

cloudstream {
    authors = listOf("Cloudburst", "omarflex")
    language = "ar"
    description = "Watch content from cimatn (cimatn frontend)"
    status = 0
    tvTypes = listOf("Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=cimatn.io&sz=%size%"
}
android {
    namespace = "com.youtube"

    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

dependencies {

    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")

    implementation("androidx.preference:preference-ktx:1.2.1")

        cloudstream("com.lagradost:cloudstream3:pre-release")


}