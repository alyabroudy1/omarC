version = 8

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
    // Same 1.7.1 the compile classpath resolves kotlinx-coroutines-core to (strictly 1.7.1 via
    // the cloudstream plugin), so setMain/StandardTestDispatcher match the runtime under test.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    // Same OkHttp as the compile classpath (5.0.0-alpha.12); the wire test asserts what
    // OkHttp itself puts on the request, so the versions must match.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.0.0-alpha.12")
}

// The cloudstream gradle plugin adds its stub jar as a compileOnly *file* dependency
// (CloudstreamConfigurationProvider: dependencies.add("compileOnly", files(jarFile))), so
// MainAPI — BaseProvider's supertype — is absent from the unit-test runtime classpath and any
// test that reflects over BaseProvider dies with NoClassDefFoundError. Mirror the same files
// onto testRuntimeOnly; nothing about the shipped plugin changes.
afterEvaluate {
    configurations.getByName("compileOnly").dependencies
        .filterIsInstance<org.gradle.api.artifacts.FileCollectionDependency>()
        .forEach { dependencies.add("testRuntimeOnly", it.files) }
}

cloudstream {
    authors = listOf("omarflex")
    language = "ar"
    status = 3  // Beta
    tvTypes = listOf("TvSeries", "Movie", "Anime", "AsianDrama")
    iconUrl = "https://www.google.com/s2/favicons?domain=laroza.co&sz=%size%"
}

