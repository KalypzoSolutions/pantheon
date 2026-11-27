pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pantheon"

include("api", "core", "agora-client", "paper", "velocity")

// P-28: dev-only smoke plugin for the local E2E network (dev/docker-compose.yml); never published or shipped.
include("smoke-plugin")
project(":smoke-plugin").projectDir = file("dev/smoke-plugin")
