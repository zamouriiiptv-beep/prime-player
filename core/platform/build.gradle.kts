plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.core.platform"
}

dependencies {
    // `api`, not `implementation`: SystemLocales returns LocaleQuery, so every
    // caller needs the type.
    api(project(":core:common"))
    // The trace vocabulary lives here, so the dependency does too. `api` because
    // `CastivioTrace.instant` is inline and its callers link against `trace`.
    api(libs.androidx.tracing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // `PerformanceLog` reads `SystemClock`, which is Android's. The stopwatch
    // that produces Castivio's baseline is not going to be the one thing in
    // this repository that nothing tests.
    testImplementation(libs.robolectric)
}
