plugins {
    `java-gradle-plugin`
    `maven-publish`
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
    plugins {
        create("docuconf") {
            id = "dev.docuconf"
            implementationClass = "dev.docuconf.gradle.DocuconfPlugin"
            displayName = "docuconf"
            description = "Exports a service's docuconf contract (docuconfExport) and checks the committed one is current (docuconfCheck)."
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
