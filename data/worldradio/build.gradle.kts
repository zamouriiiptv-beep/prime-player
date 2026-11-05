plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// The world radio's own store. A database of its own, in a module of its own, and nothing in it
// reaches the subscription's catalogue, favourites or history: a catalogue import, a refresh or the
// deletion of a subscription cannot touch what the viewer kept of the world's stations, and a fault
// in this schema cannot touch the catalogue.
android {
    namespace = "com.castivio.data.worldradio"

    // Queries are verified on the JVM against real SQLite, which needs the Android resource
    // pipeline available to the unit tests.
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    // The generated schema is checked in, like the catalogue's, so a migration is never written blind.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    api(project(":domain"))

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
}
