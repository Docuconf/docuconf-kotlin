plugins {
    // Applies java-gradle-plugin and maven-publish, and adds publishPlugins for the Gradle Plugin Portal.
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "dev.docuconf"
version = providers.gradleProperty("VERSION_NAME").get()

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    website = "https://docuconf.dev/languages/kotlin/"
    vcsUrl = "https://github.com/docuconf/docuconf-kotlin"
    plugins {
        create("docuconf") {
            id = "dev.docuconf"
            implementationClass = "dev.docuconf.gradle.DocuconfPlugin"
            displayName = "docuconf"
            description = "Exports a service's docuconf contract (docuconfExport) and checks the committed one is current (docuconfCheck)."
            tags = listOf("configuration", "kubernetes", "contract", "cue", "hoplite")
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
