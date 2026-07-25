plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.networking"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":domain"))
    api(project(":data:parsing"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // Real HTTP against a local server: conditional requests, gzip bodies and
    // status handling are not things to verify by inspection.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
