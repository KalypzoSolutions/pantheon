// P-28: dev-only smoke plugin. Not published, not part of a release; built into build/libs for dev/docker-compose.yml.
plugins {
    id("pantheon.java-conventions")
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.paper.api)

    testImplementation(project(":api"))
    testImplementation(testFixtures(project(":api")))
    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    jar {
        archiveFileName = "pantheon-smoke.jar"
    }
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("plugin.yml") { expand("version" to version) }
    }
    withType<PublishToMavenRepository>().configureEach { enabled = false }
    withType<PublishToMavenLocal>().configureEach { enabled = false }
}
