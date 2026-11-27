plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // pantheon.publishing-conventions: Maven Central publishing and signing
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")
}
