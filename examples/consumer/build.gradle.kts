plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("dev.docuconf")
}

dependencies {
    implementation("dev.docuconf:docuconf-hoplite:0.1.0-SNAPSHOT") // x-release-please-version
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.example.MainKt") }

docuconf {
    configClass.set("com.example.AppConfig")
}

tasks.test { useJUnitPlatform() }
