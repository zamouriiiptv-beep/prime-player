plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.castivio.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.castivio.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:platform"))
    implementation(project(":core:common"))
    implementation(project(":domain"))

    // The data layer is wired here so that its Hilt bindings are on the graph;
    // features depend on the domain contracts, never on these implementations.
    implementation(project(":data:database"))
    implementation(project(":data:networking"))
    implementation(project(":data:playlist"))
    implementation(project(":data:epg"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.zxing.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
