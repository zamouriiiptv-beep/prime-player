import com.android.build.gradle.LibraryExtension
import org.gradle.kotlin.dsl.configure

// Apply on top of `castivio.android.library` for any module that has UI.
pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

extensions.configure<LibraryExtension> {
    buildFeatures.compose = true
}
