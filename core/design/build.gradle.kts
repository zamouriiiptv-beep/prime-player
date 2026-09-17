plugins {
    id("castivio.android.library")
    id("castivio.android.compose")
}

android {
    namespace = "com.castivio.core.design"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    // Kept `implementation`, not `api`: the artwork component is the only thing
    // that may see it, and a feature module that imports Coil directly will not
    // compile. That is the seam, enforced by the build rather than by review.
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
}
