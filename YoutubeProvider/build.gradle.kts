version = 2

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
}

cloudstream {
    authors = listOf("omarflex")
    language = "en"
    description = "Standalone YouTube Provider"
    status = 0
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/ic_launcher-playstore.png"
}

android {
    namespace = "com.youtube"
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
        }
    }
}
