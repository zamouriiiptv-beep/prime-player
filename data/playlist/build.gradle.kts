plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.playlist"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    // For `PerformanceLog` only: the importer owns the measurement window and
    // publishes its counts, rather than a screen reaching into `:data:networking`.
    implementation(project(":core:platform"))
    api(project(":data:networking"))
    implementation(project(":data:parsing"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
