plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

// Coordinates on the projects themselves (not only on the publications), so a build that does
// includeBuild("../docuconf-kotlin") gets dev.docuconf:docuconf-hoplite substituted from source.
subprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// The Gradle plugin is an included build (docuconf-gradle-plugin/); run its checks and publishing
// with this build's.
val pluginBuild = gradle.includedBuild("docuconf-gradle-plugin")
tasks.named("check") { dependsOn(pluginBuild.task(":check")) }
tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes the Gradle plugin to the local Maven repository (the libraries publish themselves)."
    dependsOn(pluginBuild.task(":publishToMavenLocal"))
}
