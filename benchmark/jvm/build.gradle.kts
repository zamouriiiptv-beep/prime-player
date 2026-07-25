import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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

tasks.withType<Test>().configureEach {
    // Gradle swallows test stdout by default, which made the measurements
    // invisible even on a passing run. The gate is only half the job — the
    // numbers have to be reported too.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }

    // A fixed heap makes the retention probe deterministic and keeps it honest:
    // a parser that accumulates a 300k-entry catalogue cannot hide inside a
    // generously sized JVM.
    maxHeapSize = "512m"

    // Never skip benchmarks as "up to date" — a cached pass silently stops
    // measuring, which is indistinguishable from passing.
    outputs.upToDateWhen { false }
}
