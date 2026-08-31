package com.castivio.data.subtitles

/**
 * The three values the subtitle search needs, and whether they are there.
 *
 * ## Not read from `BuildConfig` here
 *
 * This is a plain value type, constructed by the DI module from `BuildConfig` and by tests
 * from literals. That is the whole reason it exists: `BuildConfig` is generated per module
 * and per build type, so a class that read it directly could not be exercised in a test
 * without the build having credentials in it — which is exactly the situation this design
 * exists to avoid.
 *
 * ## Missing is a state, not an error
 *
 * A clone with no credentials builds, runs and passes its tests, so [configured] is a
 * question every caller has to be able to ask. The alternative — throwing on construction —
 * would mean the player's Hilt graph failed to build on a developer's machine because they
 * had no OpenSubtitles account, taking down playback to protect a search.
 *
 * ## Both halves are needed, and neither alone
 *
 * The key gets a search. A *download* link is only issued to a session, and a session comes
 * from the username and password: that is how OpenSubtitles counts a person's daily
 * downloads. Two of the three is a search whose every result leads to a refusal, which is
 * worse than no search — so [configured] requires all three.
 */
data class OpenSubtitlesCredentials(
    val apiKey: String,
    val username: String,
    val password: String,
) {
    val configured: Boolean
        get() = apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        /** What an unconfigured clone has. Named, so a test can say what it is testing. */
        val NONE = OpenSubtitlesCredentials(apiKey = "", username = "", password = "")
    }
}
