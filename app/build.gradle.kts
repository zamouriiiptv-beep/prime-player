plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.tv"

    testOptions {
        // The startup gate resolves real strings from the real table and builds
        // the real Hilt graph, so it needs the compiled resources on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // A stack trace in an HTML report nobody on a CI runner can open is
            // a stack trace nobody reads. This one matters more than most.
            it.testLogging {
                events("failed")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
    compileSdk = 34

    defaultConfig {
        applicationId = "com.castivio.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:platform"))
    implementation(project(":core:common"))
    implementation(project(":core:navigation"))
    implementation(project(":domain"))

    // The data layer is wired here so that its Hilt bindings are on the graph;
    // features depend on the domain contracts, never on these implementations.
    implementation(project(":data:database"))
    implementation(project(":data:networking"))
    implementation(project(":data:playlist"))
    implementation(project(":data:epg"))
    implementation(project(":data:activation"))
    implementation(project(":data:preferences"))
    implementation(project(":data:entitlement"))

    // Features are wired here too, so their view models are on the Hilt graph and are
    // built by assembleDebug rather than only by whoever remembers to name them.
    implementation(project(":feature:activation"))
    implementation(project(":feature:licence"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.android)

    // The startup gate needs the real activity, the real Hilt graph and real
    // resources. Nothing in this repository has ever composed MainActivity, and
    // a regression that killed the app before its first frame reached a device
    // with every other gate green -- because every other gate tests a screen in
    // isolation, and no screen is the thing that starts.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
