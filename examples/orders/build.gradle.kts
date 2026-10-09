import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("dev.docuconf")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The SDK from this repository, not a published version.
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
    mainClass.set("dev.docuconf.examples.orders.MainKt")
}

// docuconfExport rewrites contract.cue; docuconfCheck (part of `check`) fails with a diff when the
// committed file differs from a fresh export. The service name comes from @DocuconfService.
docuconf {
    configClass.set("dev.docuconf.examples.orders.OrdersConfig")
}
