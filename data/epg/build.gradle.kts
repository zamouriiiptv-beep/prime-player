plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.epg"

    testOptions {
        // `android.jar` on the unit-test classpath is stubs that throw, so one `Log.i`
        // on a path a test reaches fails that test at the logging line, before any
        // assertion runs. Defaults instead, as `feature:home` and `feature:player`
        // already do: nothing in this module asserts on a platform return value, and
        // the alternative -- dropping the guide diagnostics -- would remove the numbers
        // they exist to produce.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
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
