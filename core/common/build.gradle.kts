plugins {
    id("castivio.jvm.library")
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
