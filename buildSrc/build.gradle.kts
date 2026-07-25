plugins {
    `kotlin-dsl`
}

// These put the plugins on the build classpath for every module. Because of
// that, module build files must apply them by bare id — requesting a version
// again (via `alias(libs.plugins…)`) fails with "already on the classpath".
dependencies {
    implementation("com.android.tools.build:gradle:8.7.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.25")
    implementation("com.google.dagger:hilt-android-gradle-plugin:2.52")
}
