import java.util.zip.ZipFile

plugins {
    id("pantheon.java-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.jspecify)
    implementation(project(":core"))
    implementation(project(":agora-client"))
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.paper.api.mockbukkit) // MockBukkit needs the Paper build it was made for
    testRuntimeOnly(libs.sqlite.jdbc) // Paper ships sqlite-jdbc at runtime
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks {
    shadowJar {
        archiveClassifier = ""
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        val prefix = "de.kalypzo.pantheon.lib"
        relocate("io.grpc", "$prefix.grpc") { exclude("io.grpc.netty.shaded.**") }
        relocate("com.google.protobuf", "$prefix.protobuf")
        relocate("org.yaml.snakeyaml", "$prefix.snakeyaml")
        relocate("com.google.common", "$prefix.guava")
        // P-01: every other transitive library too, so no other plugin can load (or clash with) Pantheon's copies
        for (pkg in listOf(
            "com.google.api", "com.google.apps", "com.google.cloud", "com.google.errorprone", "com.google.geo",
            "com.google.gson", "com.google.j2objc", "com.google.logging", "com.google.longrunning",
            "com.google.rpc", "com.google.shopping", "com.google.thirdparty", "com.google.type",
            "io.perfmark", "org.checkerframework", "android.annotation", "org.codehaus.mojo",
        )) {
            relocate(pkg, "$prefix.$pkg")
        }
        relocate("javax.annotation", "$prefix.javax.annotation") { exclude("javax.annotation.processing.**") }
    }
    build {
        dependsOn(shadowJar)
    }
    jar {
        archiveClassifier = "plain"
    }
    runServer {
        minecraftVersion("26.3")
    }
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("plugin.yml") { expand("version" to version) }
    }
}

// P-01: the shadow jar ships only Pantheon's own classes, relocated libraries and grpc-netty-shaded
val verifyRelocation = tasks.register("verifyRelocation") {
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jar)
    doLast {
        val file = jar.get().asFile
        val allowed = listOf("de/kalypzo/", "io/grpc/netty/shaded/", "META-INF/")
        val bad = ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && allowed.none(it::startsWith) }
                .map { it.substringBeforeLast('/') }
                .toSortedSet()
        }
        if (bad.isNotEmpty()) throw GradleException("Unrelocated packages in ${file.name}: $bad")
    }
}
tasks.check { dependsOn(verifyRelocation) }
