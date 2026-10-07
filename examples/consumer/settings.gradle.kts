// A first-time user's project: it consumes docuconf from source, exactly as the README's Install
// section says (with ../.. standing for ../docuconf-kotlin). CI builds and tests it.
pluginManagement {
    includeBuild("../../docuconf-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
includeBuild("../..")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "consumer"
