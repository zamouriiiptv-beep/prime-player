import org.jetbrains.kotlin.gradle.dsl.JvmTarget

pluginManager.apply("org.jetbrains.kotlin.jvm")

extensions.configure<JavaPluginExtension>("java") {
    sourceCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
    targetCompatibility = JavaVersion.toVersion(Config.JAVA_VERSION)
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
