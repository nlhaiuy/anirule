plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// 纯 JVM 模块：不依赖 Android API，后续 Android 应用直接依赖它。
kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
