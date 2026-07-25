plugins {
    id("castivio.jvm.library")
}

// Pure Kotlin on purpose: the parsers are the hottest code in the app, and
// keeping them off Android means they can be benchmarked on every commit
// without an emulator.
dependencies {
    api(project(":domain"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
