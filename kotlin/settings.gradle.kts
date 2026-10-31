pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "carmem"
include(":carmem-core")
include(":carmem-car")

// The Android module needs an SDK. Include it only when one is configured so the
// JVM modules (and the conformance tests) build anywhere.
val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
if (sdkDir != null && file(sdkDir).exists()) {
    include(":carmem-android")
} else {
    logger.lifecycle("carmem: no Android SDK found (ANDROID_HOME or local.properties sdk.dir); skipping :carmem-android")
}
