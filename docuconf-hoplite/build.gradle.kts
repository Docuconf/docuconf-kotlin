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

    // The shared conformance suite (SPEC §12, ConformanceTest): DOCUCONF_CONFORMANCE points at
    // docuconf-go's conformance/cases.json; without it the runner looks in a sibling checkout of
    // docuconf-go. CI sets DOCUCONF_REQUIRE_CONFORMANCE=1 so a missing file fails instead of skipping.
    // The shared export fixture (ConformanceExportTest) reads golden.cue from DOCUCONF_GO_DIR (else the
    // directory above cases.json) and runs the docuconf CLI: DOCUCONF_CLI, else `docuconf` on PATH.
    systemProperty("docuconf.rootDir", rootProject.projectDir.absolutePath)
    // A relative path is resolved against the repository root, where ./gradlew runs.
    val cases = System.getenv("DOCUCONF_CONFORMANCE")?.takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
    cases?.let { environment("DOCUCONF_CONFORMANCE", it.absolutePath) }
    System.getenv("DOCUCONF_GO_DIR")?.takeIf { it.isNotEmpty() }?.let { environment("DOCUCONF_GO_DIR", rootProject.file(it).absolutePath) }
    System.getenv("DOCUCONF_REQUIRE_CONFORMANCE")?.let { environment("DOCUCONF_REQUIRE_CONFORMANCE", it) }
    System.getenv("DOCUCONF_CLI")?.takeIf { it.isNotEmpty() }?.let { environment("DOCUCONF_CLI", it) }
    // Re-run when the cases change (a missing file is allowed: the runner skips or fails itself).
    inputs.files(cases ?: rootProject.file("../docuconf-go/conformance/cases.json"))
        .withPropertyName("conformanceCases").withPathSensitivity(PathSensitivity.NONE)
    testLogging { events("failed", "skipped") }
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
