pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "docuconf-kotlin"

// Declaration model, contract writer and value checks. No JVM-server dependencies, so a later
// build-time contract for Android and iOS can reuse it.
include("docuconf-core")
// The Hoplite integration for server-side Kotlin: annotations, reflection, boot validation, TLS checks.
include("docuconf-hoplite")
