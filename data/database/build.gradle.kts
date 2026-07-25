plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.database"

    // Room queries are verified on the JVM against real SQLite, which needs the
    // Android resource pipeline available to the unit tests.
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    // The generated schema is checked in, so a migration is never written blind:
    // the diff shows exactly which columns and indices changed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    api(project(":domain"))
    implementation(project(":data:parsing"))

    api(libs.room.runtime)
    api(libs.room.ktx)
    api(libs.room.paging)
    api(libs.paging.runtime)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    // Room queries are verified against real SQLite on the JVM. Without this the
    // only proof a query compiles is running the app on a TV.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.paging.common)
    testImplementation(libs.room.testing)
}
