plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anime3rb"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

android.sourceSets {
    getByName("main") {
        kotlin.srcDir("../shared/src/main/kotlin")
    }
}

dependencies {
    val cloudstream by configurations
    implementation("androidx.preference:preference-ktx:1.2.1")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
