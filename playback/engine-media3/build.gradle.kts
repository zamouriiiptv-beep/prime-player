plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.playback.media3"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":playback:engine-api"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // A real ExoPlayer is constructed in `EngineProfileTest`, because the claim being
    // tested is about this build's actual Media3 and not about an idea of it.
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
