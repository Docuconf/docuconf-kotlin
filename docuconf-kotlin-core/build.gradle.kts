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
        jvmTest.dependencies {
            // The conformance runner reports each case as its own JUnit test.
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    // The shared conformance suite (SPEC §12): DOCUCONF_CONFORMANCE points at docuconf-go's
    // conformance/cases.json; without it the runner looks in a sibling checkout of docuconf-go.
    // CI sets DOCUCONF_REQUIRE_CONFORMANCE=1 so a missing file fails instead of skipping.
    systemProperty("docuconf.rootDir", rootProject.projectDir.absolutePath)
    // A relative DOCUCONF_CONFORMANCE is resolved against the repository root, where ./gradlew runs.
    val cases = System.getenv("DOCUCONF_CONFORMANCE")?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    cases?.let { environment("DOCUCONF_CONFORMANCE", it.absolutePath) }
    System.getenv("DOCUCONF_REQUIRE_CONFORMANCE")?.let { environment("DOCUCONF_REQUIRE_CONFORMANCE", it) }
    // Re-run when the cases change (a missing file is allowed: the runner skips or fails itself).
    inputs.files(cases ?: rootProject.file("../docuconf-go/conformance/cases.json"))
        .withPropertyName("conformanceCases").withPathSensitivity(PathSensitivity.NONE)
    testLogging { events("failed", "skipped"); showStandardStreams = true }
}

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
