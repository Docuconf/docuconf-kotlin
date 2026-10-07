plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

// GitHub Packages, next to Maven Central: every module with the maven-publish plugin gets a
// GitHubPackages repository, so `./gradlew publishAllPublicationsToGitHubPackagesRepository`
// deploys them there. The release workflow passes the credentials as
// ORG_GRADLE_PROJECT_GitHubPackagesUsername and ORG_GRADLE_PROJECT_GitHubPackagesPassword
// (its GITHUB_TOKEN). See RELEASING.md.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Docuconf/docuconf-kotlin")
                    credentials(PasswordCredentials::class)
                }
            }
        }
    }
}
