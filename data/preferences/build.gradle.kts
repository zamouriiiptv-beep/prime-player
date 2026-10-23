plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.preferences"

    // The lock and the password are kept in real preferences files, and what they promise -- that they
    // are still there when the app is started again -- is only worth asserting against real ones.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":playback:engine-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.androidx.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
