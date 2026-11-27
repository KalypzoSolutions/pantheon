plugins {
    id("pantheon.publishing-conventions")
}

description = "gRPC client for Agora, the Pantheon network service."

// No dependency on core, api or Adventure.
dependencies {
    api(libs.agora.proto)
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
