// This module is the playback contract and nothing else: pure Kotlin, no Android
// framework, no Compose, no application. It is deliberately a `jvm.library` so that a
// dependency on the platform cannot be added here by accident — every engine depends on
// this module, so anything that leaks in leaks everywhere.
//
// Plugins are applied by bare id. buildSrc puts them on the build classpath, so there is
// no `[plugins]` table in the version catalog and `alias(libs.plugins…)` cannot resolve.
plugins {
    id("castivio.jvm.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
