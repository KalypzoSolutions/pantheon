plugins {
    id("pantheon.publishing-conventions")
    `java-test-fixtures`
}

description = "Pantheon API for Paper plugins; test fixtures contain LanguageAssertions."

dependencies {
    compileOnlyApi(libs.paper.api)
    compileOnlyApi(libs.jspecify)

    testFixturesImplementation(project(":core"))
    testFixturesCompileOnly(libs.jspecify)
    testFixturesCompileOnly(libs.paper.api)

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
