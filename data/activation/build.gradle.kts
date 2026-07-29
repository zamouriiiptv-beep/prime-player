plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.activation"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(project(":data:networking"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    // The derivation is proved pure in :domain. What needs a real Android is the part
    // that decides whether an address survives a reinstall — SharedPreferences and
    // Settings.Secure, both of which Robolectric provides on the JVM.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
