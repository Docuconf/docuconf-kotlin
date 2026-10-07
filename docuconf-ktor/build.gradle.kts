import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    // API docs for the javadoc jar (the maven-publish plugin uses Dokka when it is applied).
    alias(libs.plugins.dokka)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":docuconf-hoplite"))
    api(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.hoplite.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // ReadmeTest reads README.md and the files its code blocks come from.
    systemProperty("docuconf.rootDir", rootProject.projectDir.absolutePath)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "docuconf-ktor")
    pom {
        name.set("docuconf for Ktor")
        description.set("Ktor integration for docuconf-hoplite: load and validate the config before the server starts, bind its port, and reach the config from modules and tests.")
    }
}
