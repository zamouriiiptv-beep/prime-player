plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.castivio.feature.player"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":playback:engine-api"))
    implementation(project(":data:epg"))
    implementation(project(":core:platform"))
    implementation(project(":core:design"))
    implementation(project(":core:navigation"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
