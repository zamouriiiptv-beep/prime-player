import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.feature.player"

    // The diagnostic block under the error card is compiled out of a release build by a
    // constant that is false, which needs the class generated here rather than only in
    // :app. A stack trace on screen is a tool, not a product feature.
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true

        // PlayerPathTest is a plain JUnit test on purpose — the fallback budget is checked
        // against a virtual clock, and Robolectric's looper would only make that slower and
        // less deterministic. But the view model logs which engine it opened on, and in a
        // plain unit test `android.util.Log` is the stub android.jar, which throws rather
        // than returning. Defaults turn those calls into no-ops.
        //
        // This weakens nothing: no assertion in this module reads a framework return value,
        // and the alternative — deleting the logging that tells us which engine ran — would
        // remove the diagnostic the player exists to produce.
        unitTests.isReturnDefaultValues = true

        // The player's gates are placement gates, and a placement failure whose whole
        // value is the list of what is outside the safe area is useless if the list ends
        // up in an HTML report nobody on a CI runner can open. Failures print in full.
        unitTests.all {
            it.testLogging {
                events("failed")
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":core:design"))
    implementation(project(":core:navigation"))

    // The engine *contract*, and deliberately not `:playback:engine-media3`. The player
    // must have no route to an ExoPlayer type; the implementation is bound in `:app`.
    implementation(project(":playback:engine-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
