plugins {
    id("com.android.application")
    kotlin("android")
}

// Development host for carmem-android: installs CarMemoryService as a privileged
// system app on the AAOS emulator and exposes the AIDL surface to adb broadcasts.
android {
    namespace = "carmem.devhost"
    compileSdk = 35

    defaultConfig {
        applicationId = "carmem.devhost"
        minSdk = 31
        // >= 33 so UserManager calls answer for the context's user (the driver), not the calling user 0
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":carmem-android"))
}
