// Plugin versions live in buildSrc/build.gradle.kts, which places them on the
// build classpath. Modules apply them by bare id; declaring versions here too
// would fail with "plugin is already on the classpath".

// Kotlin's standard library is the one of the Kotlin this project compiles with, whatever a dependency asks for.
//
// Media3 1.11 is built with Kotlin 2.2. On the build that moved to it, the dexing step printed a Kotlin-metadata warning
// for every standard-library class it dexed (R8 8.7 does not know 2.2 metadata, and the build before it printed none)
// and `:playback:engine-media3:kspDebugKotlin` failed. A 2.0.21 compiler given a 2.2 standard library refuses it
// outright -- "module was compiled with an incompatible version of Kotlin. The binary version of its metadata is
// 2.2.0, expected version is 2.0.0", reproduced against kotlin-stdlib 2.2.0 where 2.1.0 compiles -- which is the same
// kind of failure. Nothing the app takes from Media3 is Kotlin code (its modules are Java), so the older library is
// the one that fits. Remove this when the Kotlin of this build reaches the one Media3 is built with.
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.name in setOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")
            ) {
                useVersion(libs.versions.kotlin.get())
                because("the standard library must not be newer than the Kotlin that compiles against it")
            }
        }
    }
}
