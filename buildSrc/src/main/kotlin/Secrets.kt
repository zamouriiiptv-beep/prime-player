import org.gradle.api.Project
import java.util.Properties

/**
 * Credentials that must never be in the repository, read at configuration time.
 *
 * ## Where a secret is allowed to live
 *
 * In `local.properties`, which `.gitignore` has excluded since the first commit, or in the
 * environment. Nowhere else — not in a Kotlin constant, not in a resource, not in a
 * `gradle.properties` that gets committed by habit, and not in a commit message.
 *
 * CI deliberately supplies neither. An APK built by the workflow is therefore always the
 * unconfigured one, which is the point: wiring repository secrets in would bake an
 * account's password into every published artefact, where anyone holding the file can read
 * it out. The subtitle search is configured on a developer's own machine and nowhere else.
 *
 * ## Missing is a valid answer
 *
 * [read] returns an empty string rather than failing the build. A clone with no credentials
 * must still compile, run, and pass its tests; every feature that needs one is responsible
 * for saying so at runtime — see `OpenSubtitlesCredentials`, which reports itself
 * unconfigured rather than throwing, so the subtitle search says "not set up" instead of
 * taking the player down with it.
 *
 * A build that failed here would mean nobody could build Castivio without an OpenSubtitles
 * account, to compile a player that does not need one.
 *
 * ## Why the values come through `providers` and not `File.readText`
 *
 * Because of the way this feature is switched on. Somebody builds the project, sees the
 * search report itself as not set up, writes three lines into `local.properties`, and
 * builds again — and with a plain file read Gradle has no idea anything it depends on has
 * changed. The configuration cache hands back the previous run's *empty* values, the APK is
 * byte-for-byte the unconfigured one, and the only symptom is that adding the key did
 * nothing. There is nothing to see in a log and nothing to search for.
 *
 * `providers.fileContents` and `providers.environmentVariable` are inputs Gradle tracks, so
 * the configuration is invalidated by the edit that is supposed to invalidate it.
 */
object Secrets {

    /**
     * The value for [key], from `local.properties` first and the environment second.
     *
     * The file wins because it is the more specific of the two: a developer who has written
     * a key into their own file means it, and an environment variable inherited from a
     * parent shell is the more likely of the two to be stale.
     */
    fun read(project: Project, key: String): String {
        val fromFile = localProperties(project)?.getProperty(key)
        if (!fromFile.isNullOrBlank()) return fromFile.trim()

        return project.providers.environmentVariable(key).orNull?.trim().orEmpty()
    }

    /**
     * The same value, as a Java string literal for `buildConfigField`.
     *
     * `buildConfigField` writes its third argument into generated source verbatim, so a
     * value has to arrive already quoted — and already escaped, because a password is
     * exactly the sort of string that contains a backslash or a quotation mark and a naive
     * `"\"$value\""` would produce a generated file that does not compile. The failure would
     * be reported as a syntax error in a file nobody wrote, which is a bad hour.
     */
    fun quoted(project: Project, key: String): String {
        val escaped = read(project, key)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * `local.properties`, parsed, or null when there is none.
     *
     * Read through `providers.fileContents` rather than by opening the file, so that Gradle
     * records it as an input — see the note above about the edit that switches this feature
     * on being invisible to a cached configuration.
     */
    private fun localProperties(project: Project): Properties? {
        val file = project.rootProject.layout.projectDirectory.file(LOCAL_PROPERTIES)
        val text = project.providers.fileContents(file).asText.orNull ?: return null
        return Properties().apply { load(text.reader()) }
    }

    private const val LOCAL_PROPERTIES = "local.properties"
}
