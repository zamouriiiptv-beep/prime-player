plugins {
    id("castivio.jvm.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.coroutines.core)
    // paging-common is pure Kotlin — no Android dependency — so the paged
    // contracts can live in the domain where features can see them without
    // anyone depending on the database module.
    api(libs.paging.common)
    testImplementation(libs.junit)
    // The activation sequence is a Flow, so its tests need runTest. Declared here
    // rather than inherited from anywhere: Gradle scopes test dependencies per module
    // and a test that compiles locally on a wider classpath still fails the build.
    testImplementation(libs.coroutines.test)
}
