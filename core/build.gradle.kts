plugins {
    id("pantheon.publishing-conventions")
    `java-test-fixtures`
}

description = "Pantheon core implementation (backends, language, presence, messaging)."

dependencies {
    api(project(":api"))
    implementation(project(":agora-client"))
    implementation(libs.snakeyaml)
    compileOnly(libs.sqlite.jdbc) // Paper ships sqlite-jdbc
    compileOnly(libs.jspecify)
    compileOnly(libs.adventure.api) // Paper provides Adventure at runtime
    compileOnly(libs.adventure.text.minimessage)
    compileOnly(libs.adventure.text.serializer.plain)
    compileOnly(libs.adventure.text.serializer.gson)

    testFixturesCompileOnly(libs.adventure.api)
    testFixturesCompileOnly(libs.jspecify)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)
    testFixturesImplementation(libs.awaitility)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.awaitility)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.paper.api) // P-25: a fake LocalPanPlayer needs Player in its signature
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.text.minimessage)
    testImplementation(libs.adventure.text.serializer.gson)
    testImplementation(libs.adventure.text.serializer.plain)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.agora.server) // P-11: the real Agora, in process
    testImplementation(libs.grpc.netty.shaded)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// P-11: tests tagged "integration" (real Agora + Postgres via Testcontainers) run in their own task; check runs both.
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the integration tests (real Agora, Testcontainers Postgres)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

// Benchmarks (docs/benchmark.md): JMH in its own source set, on top of the test classes (AgoraContractFixture).
// Never part of check or CI; ./gradlew :core:benchmark [-Pbench=<regex>] [-Pbench.quick]
val jmh = sourceSets.create("jmh") {
    compileClasspath += sourceSets.test.get().output + sourceSets.main.get().output
    runtimeClasspath += sourceSets.test.get().output + sourceSets.main.get().output
}
configurations["jmhImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["jmhRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "jmhImplementation"(libs.jmh.core)
    "jmhImplementation"(libs.hikaricp)
    "jmhImplementation"(libs.postgresql)
    "jmhImplementation"(libs.grpc.services) // health check: the transport floor in OpLatencyBench
    "jmhAnnotationProcessor"(libs.jmh.generator.annprocess)
}

tasks.register<JavaExec>("benchmark") {
    description = "Runs the JMH benchmarks (Testcontainers Postgres, in-process Agora). See docs/benchmark.md."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass = "de.kalypzo.pantheon.core.backend.agora.BenchRunner"
    workingDir = layout.buildDirectory.get().asFile // profiler output (-Pbench.prof=jfr) lands in build/
    val out = layout.buildDirectory.dir("results/jmh")
    args(providers.gradleProperty("bench").getOrElse(".*"))
    systemProperty("bench.out", out.get().asFile.absolutePath)
    systemProperty("bench.quick", providers.gradleProperty("bench.quick").isPresent.toString())
    systemProperty("bench.prof", providers.gradleProperty("bench.prof").getOrElse(""))
    systemProperty("bench.commit", providers.exec { commandLine("git", "describe", "--always", "--dirty") }
        .standardOutput.asText.get().trim())
}

tasks.check {
    dependsOn(tasks.named("compileJmhJava"))
}
