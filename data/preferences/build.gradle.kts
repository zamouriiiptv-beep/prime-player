plugins {
    id("castivio.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.castivio.data.preferences"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.androidx.datastore.preferences)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
