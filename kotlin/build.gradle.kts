plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("com.android.library") version "8.9.1" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
