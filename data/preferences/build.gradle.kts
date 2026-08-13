plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.preferences"
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
}
