plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.android.application") version "8.5.2" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
