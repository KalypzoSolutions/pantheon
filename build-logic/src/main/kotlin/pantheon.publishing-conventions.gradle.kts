import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Maven Central publishing for the modules plugin developers need (api, core, agora-client).
// -SNAPSHOT versions go to the Central snapshot repository, others are released automatically.
// Coordinates: de.kalypzo:pantheon-<module>, except agora-client, which keeps its own name.
plugins {
    id("pantheon.java-conventions")
    id("com.vanniktech.maven.publish")
}

val artifact = if (project.name.startsWith("agora-")) project.name else "pantheon-${project.name}"

configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    // Signing needs signingInMemoryKey (~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* in CI);
    // without it publishToMavenLocal still works, unsigned.
    if (providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true) {
        signAllPublications()
    }
    coordinates("de.kalypzo", artifact, project.version.toString())
    pom {
        name = artifact
        description = provider { project.description }
        inceptionYear = "2026"
        url = "https://github.com/KalypzoSolutions/pantheon"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "kalypzo"
                name = "Kalypzo"
                url = "https://github.com/KalypzoSolutions"
            }
        }
        scm {
            url = "https://github.com/KalypzoSolutions/pantheon"
            connection = "scm:git:https://github.com/KalypzoSolutions/pantheon.git"
            developerConnection = "scm:git:ssh://git@github.com/KalypzoSolutions/pantheon.git"
        }
    }
}

// Gradle derives the test-fixtures capability from the project name (de.kalypzo:api-test-fixtures),
// not from the renamed artifact, so testFixtures("de.kalypzo:pantheon-api:…") would not resolve.
// Add the correct capability; the derived one stays (harmless, keeps requireCapability workarounds working).
pluginManager.withPlugin("java-test-fixtures") {
    configurations.matching { it.name.startsWith("testFixtures") && it.name.endsWith("Elements") }
        .configureEach {
            outgoing.capability("de.kalypzo:$artifact-test-fixtures:${project.version}")
        }
}

// Central needs a Javadoc jar; report real Javadoc errors, not every undocumented member.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}
