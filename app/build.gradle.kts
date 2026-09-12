plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

/* ------------------------------------------------------------------ debug signing
 *
 * Declared here rather than beside the `signingConfigs` block that reads them, and as
 * plain `val` rather than `const val`, because a Gradle Kotlin DSL build script is not
 * a Kotlin file: its top level is the script class's own body. `const val` is rejected
 * there outright, and a `val` written below `android { }` is still uninitialised when
 * that block runs, since the block is configured eagerly as the script executes.
 */

/** Written by CI from a secret when one is configured. Takes precedence when present. */
val debugKeystoreFromCi = "keystore/ci-debug.keystore"

/** The key committed to this repository, so every build signs identically by default. */
val debugKeystorePinned = "keystore/castivio-debug.keystore"

/** Android's well-known debug password. Deliberately not a secret -- see keystore/README.md. */
val debugKeystorePassword = "android"

val debugKeystoreAlias = "androiddebugkey"

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

    /**
     * The debug signing key, pinned.
     *
     * ## What this fixes, which was not a build problem
     *
     * Android refuses to install an APK over an app already installed under a
     * *different* signing certificate. The Android Gradle Plugin generates
     * `~/.android/debug.keystore` on the build machine when it does not find one — and
     * a GitHub Actions runner is a fresh machine every run with no such file and no
     * cache of it. So **every CI build was signed with a different, freshly invented
     * key**, every new APK refused to install over the last one, and the only way to
     * install it was to uninstall first.
     *
     * Uninstalling deletes the app's database. That is where the provider record and
     * the imported catalogue live, so each delivered build silently wiped the user's
     * subscription and their channels, and dropped them back at the activation screen.
     * The symptom looked like a data-layer regression and was entirely a signing one.
     *
     * ## Why the key is in the repository, and why that is not a credential leak
     *
     * This is a *debug* key. It cannot sign a release: `RELEASE_CHECKLIST.md` governs
     * that, release signing is not configured here and must never be. Its password is
     * the well-known Android debug password, deliberately — a debug key whose secrecy
     * mattered would be a release key in the wrong place.
     *
     * [debugKeystoreFromCi] takes precedence when it exists, which is how CI can supply
     * one from a secret instead (see `build.yml`). Nothing needs to change here for
     * that to work, and if the file is ever removed the build still succeeds — it just
     * goes back to a per-machine key, which is the behaviour this replaced.
     */
    signingConfigs {
        getByName("debug") {
            val supplied = rootProject.file(debugKeystoreFromCi)
            val pinned = rootProject.file(debugKeystorePinned)
            val chosen = if (supplied.exists()) supplied else pinned
            if (chosen.exists()) {
                storeFile = chosen
                // Env first so a CI-supplied keystore can carry its own password
                // without the value ever being written down here.
                storePassword = System.getenv("DEBUG_STORE_PASSWORD") ?: debugKeystorePassword
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: debugKeystoreAlias
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: debugKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }

        /**
         * The build a macrobenchmark measures, and the only one it can.
         *
         * Neither existing type works. A **debug** build carries the debuggable flag,
         * which disables ART's optimising compiler and inflates every timing by an
         * amount that varies by device -- Google's own guidance is that debug numbers
         * are not measurements. A **release** build is worse for the opposite reason:
         * `Licensing.Production` is bound with no `EntitlementSource`, so it fails
         * closed on purpose (see `RELEASE_CHECKLIST.md`) and a startup benchmark would
         * be timing how fast the licence screen says no.
         *
         * So: release's runtime characteristics, debug's licensing.
         * `matchingFallbacks = ["debug"]` makes every library module compile its debug
         * variant, which is what puts `BuildConfig.DEBUG == true` in front of
         * `EntitlementModule.licensing` and gives the build a working local trial. The
         * app's own `BuildConfig.DEBUG` is false, so StrictMode and the crash sheet stay
         * out of the measurement.
         *
         * `profileable` rather than `debuggable` is what lets the platform sample it:
         * see this module's manifest.
         */
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            isDebuggable = false
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
    implementation(project(":data:localmedia"))
    implementation(project(":data:activation"))
    implementation(project(":data:preferences"))
    implementation(project(":data:entitlement"))

    // Features are wired here too, so their view models are on the Hilt graph and are
    // built by assembleDebug rather than only by whoever remembers to name them.
    implementation(project(":playback:engine-api"))
    implementation(project(":playback:engine-media3"))
    implementation(project(":feature:activation"))
    implementation(project(":feature:home"))
    implementation(project(":feature:player"))
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
