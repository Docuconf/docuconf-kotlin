import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The SDK from this repository, not a published version. Contract-first mode is in
    // docuconf-kotlin-core; docuconf-hoplite adds the file inputs (Docuconf.checkContract).
    implementation(project(":docuconf-hoplite"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.docuconf.examples.shop.MainKt")
}

// The checked-in contract.json is the source of truth (contract-first): the app loads it from the
// classpath at startup, so it is packaged as is.
tasks.processResources {
    from("contract.json")
}
