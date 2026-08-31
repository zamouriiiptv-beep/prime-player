plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.subtitles"

    testOptions {
        // `org.json` and `android.util.Log` are stubs in a plain JVM unit test — the first
        // throws on every call and the second on every log line. This module parses its
        // three responses with the platform's own JSON, so its tests run on Robolectric,
        // which provides both for real.
        unitTests.isIncludeAndroidResources = true
    }

    // The three OpenSubtitles credentials reach the code through here and nowhere else.
    // `Secrets` reads them from `local.properties` — which `.gitignore` has excluded since
    // the first commit — or from the environment on CI, and returns an empty string when
    // there is neither. An unconfigured clone therefore compiles and its tests pass; the
    // search reports itself as not set up, which is `OpenSubtitlesCredentials`' whole job.
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "OPENSUBTITLES_API_KEY", Secrets.quoted(project, "OPENSUBTITLES_API_KEY"))
        buildConfigField("String", "OPENSUBTITLES_USERNAME", Secrets.quoted(project, "OPENSUBTITLES_USERNAME"))
        buildConfigField("String", "OPENSUBTITLES_PASSWORD", Secrets.quoted(project, "OPENSUBTITLES_PASSWORD"))
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Real HTTP against a local server, for the reason `:data:networking` gives: status
    // handling, headers and a JSON body are not things to verify by inspection.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
