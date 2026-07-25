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
}
