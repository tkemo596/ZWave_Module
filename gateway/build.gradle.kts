plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // Provided by the Ignition platform at runtime; never packaged into the .modl.
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdkVersion"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdkVersion"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:driver-api:${rootProject.extra["sdkVersion"]}")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // No runtime dependencies at all. The WebSocket client is the JDK's own
    // java.net.http.WebSocket and the JSON codec is in zwjs/Json.java.
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}
