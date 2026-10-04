import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
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
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(artifactId = "docuconf-core")
    pom {
        name.set("docuconf core")
        description.set("docuconf declaration model, CUE contract writer and value checks (Kotlin Multiplatform).")
        // Licence pending: see README.
    }
}
