import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    // API docs for the javadoc jar (the maven-publish plugin uses Dokka when it is applied).
    alias(libs.plugins.dokka)
}

// Kotlin Multiplatform with only the JVM target for now. Everything in commonMain is plain Kotlin
// (no reflection, no java.*), so Android, iOS or JS targets can be added for build-time contracts.
kotlin {
    explicitApi()
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.launcher)
        }
    }
}

// The shared conformance suite (SPEC §12) runs in docuconf-hoplite's tests, through the contract-first
// mode with files (Docuconf.checkContract), since its file inputs and overlays need the JVM.

mavenPublishing {
    publishToMavenCentral()
    // Release builds sign with the in-memory key from ORG_GRADLE_PROJECT_signingInMemoryKey (see RELEASING.md).
    // Local builds (publishToMavenLocal) skip signing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "docuconf-kotlin-core")
    pom {
        name.set("docuconf Kotlin core")
        description.set("docuconf declaration model, CUE contract writer and value checks (Kotlin Multiplatform).")
    }
}
