plugins {
    id("castivio.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.data.entitlement"

    // Only for BuildConfig.DEBUG, which is the single input to the Development vs
    // Production choice in EntitlementModule. There is deliberately no LOCAL_TRIAL flag
    // to get wrong: which licensing world a build lives in is a type, not a boolean.
    buildFeatures.buildConfig = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)

    // The cipher, the codec and the repository are all verified on the JVM. What is
    // not is the twenty lines that ask AndroidKeyStore for a key -- Robolectric has no
    // keystore, and neither has any JVM. Everything around that line is testable, which
    // is why the key is behind a lambda.
    //
    // What a JVM *can* hold is the keystore's contract, which is what actually broke: a
    // key that refuses a caller-supplied nonce. KeystoreLikeCipher models it, and
    // FirstLaunchTest runs the whole startup path behind it -- which needs the real
    // Android adapters for identity and clock signals, hence the project dependency.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":data:activation"))
}
