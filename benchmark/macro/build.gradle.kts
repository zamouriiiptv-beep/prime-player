plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

/**
 * Measurement on real hardware, and nothing else.
 *
 * A `com.android.test` module is not a library and not a variant of the app: it builds
 * its own APK, installs beside `:app`, drives it from outside and reads the platform's
 * own counters. Nothing in here can be linked into a shipped build, which is what makes
 * it safe to put a measurement harness in the repository at all.
 *
 * Why this exists: `PerformanceBudgets` has declared `COLD_START_MS_MAX = 1200` and four
 * other on-device numbers since the project began, with a comment saying they are
 * "asserted by the macrobenchmark tier on real hardware". That tier was never built, so
 * five budgets have been unenforced comments. This is the tier.
 */
android {
    namespace = "com.castivio.benchmark.macro"
    compileSdk = Config.COMPILE_SDK

    defaultConfig {
        // Macrobenchmark reads the platform's own startup and frame counters, which
        // arrived in API 23 and 24 respectively. Not related to the app's own minSdk of
        // 21: this APK never reaches a user's device.
        minSdk = 24
        targetSdk = Config.COMPILE_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
        targetCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
    }

    kotlinOptions {
        jvmTarget = Config.JAVA_VERSION.toString()
    }

    // Only the `benchmark` build type of `:app` is worth measuring — see the long note
    // on that type in `app/build.gradle.kts` for why neither debug nor release is.
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.benchmark.macro.junit4)
    // The section names the app emits, so a benchmark cannot ask for a name that no
    // longer exists and report zero occurrences as though that were a measurement.
    implementation(project(":core:platform"))
    // The declared budgets, so a breach fails here rather than being read off a table.
    implementation(project(":benchmark:jvm"))
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
