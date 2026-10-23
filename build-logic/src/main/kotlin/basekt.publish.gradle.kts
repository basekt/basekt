// Publishes a BaseKt library to Maven Central as io.github.basekt:<module name>:<version>.
//
// Apply after basekt.jvm-library or basekt.kmp-library, with a `description` set in the module. The version comes from
// the `basekt.version` Gradle property: a SNAPSHOT locally, and the git tag's version in the release workflow.
// Publications are signed when a key is present (ORG_GRADLE_PROJECT_signingInMemoryKey), so a local
// publishToMavenLocal needs none. See RELEASING.md.
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()

    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
    if (hasSigningKey) signAllPublications()

    coordinates(
        providers.gradleProperty("basekt.group").get(),
        project.name,
        providers.gradleProperty("basekt.version").get(),
    )

    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "BaseKt: ${project.name}" })
        url.set("https://github.com/basekt/basekt")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ronjunevaldoz")
                name.set("Ron June Valdoz")
                url.set("https://github.com/ronjunevaldoz")
            }
        }
        scm {
            url.set("https://github.com/basekt/basekt")
            connection.set("scm:git:git://github.com/basekt/basekt.git")
            developerConnection.set("scm:git:ssh://git@github.com/basekt/basekt.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/basekt/basekt/issues")
        }
    }
}
