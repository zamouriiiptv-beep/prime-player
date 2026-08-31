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
 * Two sources rather than one because they serve two people. `local.properties` is the
 * developer's own machine, where a file is easier than an environment variable that has to
 * be exported into every shell. The environment is CI, where there is no file to write and
 * the secret arrives from the runner's own store.
 *
 * ## Missing is a valid answer
 *
 * [read] returns an empty string rather than failing the build, and that is deliberate: a
 * clone with no credentials must still compile, run, and pass its tests. Every feature that
 * needs one is responsible for saying so at runtime — see `OpenSubtitlesCredentials`, which
 * reports itself unconfigured rather than throwing, so the subtitle search says "not set up"
 * instead of taking the player down with it.
 *
 * A build that failed here would mean nobody could build Castivio without an OpenSubtitles
 * account, to compile a player that does not need one.
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
        val file = project.rootProject.file(LOCAL_PROPERTIES)
        if (file.exists()) {
            val properties = Properties()
            file.inputStream().use(properties::load)
            val stored = properties.getProperty(key)
            if (!stored.isNullOrBlank()) return stored.trim()
        }
        return System.getenv(key)?.trim().orEmpty()
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

    private const val LOCAL_PROPERTIES = "local.properties"
}
