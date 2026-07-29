plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.entitlement"

    defaultConfig {
        // Whether the local trial may be granted at all. False in a release build:
        // without a licence server there is nothing that can honestly hand out a free
        // week, and a release APK that grants one would be a licence system with no
        // licences in it.
        buildConfigField("boolean", "LOCAL_TRIAL", "true")
    }

    buildFeatures.buildConfig = true

    buildTypes {
        release {
            buildConfigField("boolean", "LOCAL_TRIAL", "false")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    // The cipher, the codec and the repository are all verified on the JVM. What is
    // not is the twenty lines that ask AndroidKeyStore for a key -- Robolectric has no
    // keystore, and neither has any JVM. Everything around that line is testable, which
    // is why the key is behind a lambda.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
