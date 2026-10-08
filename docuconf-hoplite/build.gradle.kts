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
    api(project(":docuconf-kotlin-core"))
    api(libs.hoplite.core)
    // Config file formats are Hoplite parser modules, found at runtime. Apps add the ones they use.
    testImplementation(libs.hoplite.yaml)
    testImplementation(libs.hoplite.json)
    testImplementation(libs.hoplite.toml)
    // Only to mint certificates in tests; the library itself uses java.security alone.
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("docuconf.projectDir", projectDir.absolutePath)
    systemProperty("docuconf.version", providers.gradleProperty("VERSION_NAME").get())
    // CI sets DOCUCONF_REQUIRE_CUE=1 so a missing cue binary fails the build instead of skipping.
    System.getenv("DOCUCONF_REQUIRE_CUE")?.let { environment("DOCUCONF_REQUIRE_CUE", it) }
    // Lets CI point the CUE vet test at a checkout of docuconf/docuconf-go.
    environment("DOCUCONF_SPEC_DIR", System.getenv("DOCUCONF_SPEC_DIR") ?: rootProject.file("../docuconf-go/spec/cue").absolutePath)
}

mavenPublishing {
    publishToMavenCentral()
    // Release builds sign with the in-memory key from ORG_GRADLE_PROJECT_signingInMemoryKey (see RELEASING.md).
    // Local builds (publishToMavenLocal) skip signing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "docuconf-hoplite")
    pom {
        name.set("docuconf for Hoplite")
        description.set("Typed configuration contracts for server-side Kotlin, built on Hoplite: export a CUE contract and validate env vars and files at boot.")
    }
}
