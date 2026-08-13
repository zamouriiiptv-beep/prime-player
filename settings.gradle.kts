pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Castivio"

include(":app")

// core — shared foundations, no feature knowledge
include(":core:common")
include(":core:navigation")
include(":core:design")
include(":core:platform")

// domain — pure Kotlin business rules
include(":domain")

// data — repositories and sources
include(":data:networking")
include(":data:preferences")
include(":data:activation")
include(":data:entitlement")
include(":data:parsing")
include(":data:database")
include(":data:playlist")
include(":data:epg")
include(":data:localmedia")

// playback — the engine boundary
include(":playback:engine-api")
include(":playback:engine-media3")

// features — independently replaceable
include(":feature:activation")
include(":feature:licence")
include(":feature:home")
include(":feature:search")
include(":feature:player")
include(":feature:settings")

// performance gates — run on every commit
include(":benchmark:jvm")
