import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.playback.media3"

    testOptions {
        // Robolectric builds a real ExoPlayer in EngineProfileTest, and a real ExoPlayer
        // resolves resources while it constructs its renderers.
        unitTests.isIncludeAndroidResources = true

        // Full stacks, because the default form gave us three identical lines —
        // "NullPointerException at EngineProfileTest.kt:53" — which name the construction
        // site we already knew about and say nothing about what inside ExoPlayer was null.
        // A failure that cannot be read is a failure that gets guessed at.
        unitTests.all {
            it.testLogging {
                events("started", "passed", "failed", "skipped")
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":playback:engine-api"))
    implementation(project(":playback:engine-vlc"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}