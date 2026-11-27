plugins {
    id("pantheon.java-conventions")
}

// No dependency on core, api or Adventure.
dependencies {
    api("de.kalypzo.agora:agora-proto:0.1.0-SNAPSHOT")
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    compileOnly(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.junit.platform.launcher)
}
