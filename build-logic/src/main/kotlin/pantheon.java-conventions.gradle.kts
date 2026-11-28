plugins {
    `java-library`
}

group = "de.kalypzo"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        // It proxies Central snapshots: an Agora -SNAPSHOT must come from mavenLocal (agora-local), not from there.
        content { excludeGroup("de.kalypzo") }
    }
    mavenLocal()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

base {
    archivesName = "pantheon-${project.name}"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
