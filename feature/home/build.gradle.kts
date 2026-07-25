plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.feature.home"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data:playlist"))
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
