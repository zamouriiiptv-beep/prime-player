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
    // media3-decoder-ffmpeg was removed: Google does not publish this artifact to any
    // Maven repository (see androidx/media's decoder_ffmpeg README and ExoPlayer issue
    // #2781) — it only exists as source you build yourself with the NDK, or as a
    // separately-licensed prebuilt from a third party (e.g. org.jellyfin.media3:
    // media3-ffmpeg-decoder, GPL-3.0 — a licensing choice for legal review, not a drop-in
    // swap). `Media3Engine.isFfmpegAvailable` already checks for the class via
    // `Class.forName` at runtime rather than importing it, so removing this line does not
    // touch Media3Engine.kt or break compilation — it only makes `isFfmpegAvailable`
    // honestly report `false`, since there is currently no build of this class on the
    // classpath. `EngineProfileTest`'s assertion that ffmpeg is available will now fail
    // correctly, as a marker that this capability needs one of the two paths above.
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // A real ExoPlayer is constructed in `EngineProfileTest`, because the claim being
    // tested is about this build's actual Media3 and not about an idea of it.
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
