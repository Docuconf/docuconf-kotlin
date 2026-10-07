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
    // The SDK from this repository, not a published version.
    implementation(project(":docuconf-hoplite"))
}

application {
    mainClass.set("dev.docuconf.examples.orders.MainKt")
}

// Writes contract.cue with the SDK's export command (Docuconf.exportCue). CI re-runs it and fails
// when the committed file differs.
val exportContract by tasks.registering(JavaExec::class) {
    group = "docuconf"
    description = "Exports the orders contract to contract.cue."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.docuconf.hoplite.Export")
    args("--class", "dev.docuconf.examples.orders.OrdersConfig", "--service", "orders", "--out", file("contract.cue").path)
}
