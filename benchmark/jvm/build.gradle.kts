plugins {
    id("castivio.jvm.library")
}

// JVM-only on purpose: these run on every commit in a few seconds with no
// emulator. The device-tier metrics (startup, jank, memory) live in a
// macrobenchmark module and run on real hardware, not here.
dependencies {
    implementation(project(":data:parsing"))
    implementation(project(":domain"))
    testImplementation(libs.junit)
}
