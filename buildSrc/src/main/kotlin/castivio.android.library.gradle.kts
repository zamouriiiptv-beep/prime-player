import com.android.build.gradle.LibraryExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

pluginManager.apply("com.android.library")
pluginManager.apply("org.jetbrains.kotlin.android")

extensions.configure<LibraryExtension> {
    compileSdk = Config.COMPILE_SDK
    defaultConfig.minSdk = Config.MIN_SDK
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
        targetCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
