plugins {
    id("castivio.jvm.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
