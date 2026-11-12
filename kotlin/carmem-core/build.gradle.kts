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
    useJUnitPlatform { excludeTags("latency") }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    systemProperty("carmem.fixtures", rootProject.projectDir.resolve("../fixtures").absolutePath)
}

// Timing depends on the machine, so it is not part of `test`: run `gradle :carmem-core:latency` and read the table.
tasks.register<Test>("latency") {
    description = "Per-call latency against a six-month database (LatencyTest)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("latency") }
    testLogging { events("passed", "failed"); showStandardStreams = true }
    listOf("carmem.latency.budgetMs", "carmem.latency.weeks").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    outputs.upToDateWhen { false }
}
