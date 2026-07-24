plugins {
    id("castivio.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.castivio.data.networking"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
