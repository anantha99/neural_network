plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "carmem.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 31 // Android Automotive OS 12+
        consumerProguardFiles("consumer-rules.pro")
    }

    // android.car (CarPropertyManager, CarOccupantZoneManager) ships as an optional
    // platform library: platforms/android-35/optional/android.car.jar
    useLibrary("android.car")

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":carmem-core"))
    api(project(":carmem-car"))
    implementation("androidx.core:core-ktx:1.15.0")
    // One known SQLite version on every OEM image, and the driver API our SqlDriver wraps.
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
