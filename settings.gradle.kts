pluginManagement {
    // The dev.docuconf Gradle plugin, built from source; the orders example applies it.
    includeBuild("docuconf-gradle-plugin")
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
include("docuconf-kotlin-core")
// The Hoplite integration for server-side Kotlin: annotations, reflection, boot validation, TLS checks.
include("docuconf-hoplite")
// Ktor integration: docuconfServer, Application.docuconfConfig.
include("docuconf-ktor")
// The "orders" example service (examples/orders), built against the SDK in this repository.
include("orders")
project(":orders").projectDir = file("examples/orders")
