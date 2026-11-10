/** Single source of truth for the Android configuration every module shares. */
object Config {
    // 36 and 23 are Media3 1.11's own numbers, and they are not ours to choose: its AARs declare that a consumer must
    // compile against API 36 (`minCompileSdk`), and since 1.9 its minSdk is 23 -- so a manifest that says 21 is
    // refused at merge. What 23 costs is Android 5.0-5.1 (API 21-22): old TV boxes and the first Fire TV sticks.
    // Going back to 21 means going back to Media3 1.8.0, the last line that supports it.
    const val COMPILE_SDK = 36
    const val MIN_SDK = 23
    const val TARGET_SDK = 34
    const val JAVA_VERSION = 17
}
