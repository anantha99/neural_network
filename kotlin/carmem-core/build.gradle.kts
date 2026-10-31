plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.carmem"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // JVM/tests use JDBC; Android swaps in the AndroidX bundled SQLite driver behind SqlDriver.
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    systemProperty("carmem.fixtures", rootProject.projectDir.resolve("../fixtures").absolutePath)
}
