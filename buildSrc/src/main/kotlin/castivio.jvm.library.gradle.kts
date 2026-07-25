import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Pure-Kotlin modules: :domain, :core:common, :core:navigation, :playback:engine-api.
// Keeping them off the Android plugin is what makes them unit-testable on the JVM.
pluginManager.apply("org.jetbrains.kotlin.jvm")

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
    targetCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
}

extensions.configure<KotlinJvmProjectExtension> {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
