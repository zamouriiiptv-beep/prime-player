import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.feature.licence"

    testOptions {
        // The sentinel and completeness checks resolve real strings out of the
        // real resource table, so they need the compiled resources on the JVM.
        unitTests.isIncludeAndroidResources = true

        // Gradle's default puts an assertion's message in an HTML report nobody
        // on a CI runner can open. A layout failure whose value is the list of
        // what is missing is useless under that default.
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

    // The licence QR carries the portal URL and nothing else, exactly as the
    // activation one does. The decoder is a test dependency: LicenceQrTest reads
    // the symbol back and fails if a device identifier is anywhere in it.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
