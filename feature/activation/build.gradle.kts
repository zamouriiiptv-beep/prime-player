import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.feature.activation"

    // The debug fixtures -- the six-digit key and the QR that encodes nothing --
    // are gated on BuildConfig.DEBUG, which is the one gate a release build
    // cannot be talked past. That needs the class generated here rather than
    // only in :app.
    buildFeatures {
        buildConfig = true
    }

    // The sentinel test resolves real strings out of the real resource table, so
    // it needs the compiled resources on the JVM classpath.
    testOptions {
        unitTests.isIncludeAndroidResources = true

        // Gradle's default console prints "java.lang.AssertionError at Foo.kt:75"
        // and puts the message in an HTML report, which nobody on a CI runner can
        // open. A layout assertion whose whole value is the list of what is
        // missing is useless under that default, and it has already cost two
        // round trips of guessing. Failures print in full, with stdout.
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

    // The activation QR encodes one thing: the central activation URL. The
    // decoder side is a test dependency only -- ActivationQrTest reads the
    // symbol back and fails if a MAC address or device key is anywhere in it.
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
