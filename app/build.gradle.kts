plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.rulegen"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.rulegen"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/capture-assets"))
}

// 注入脚本只维护一份（../capture/capture.js），构建时复制进 assets
val copyCapture by tasks.registering(Copy::class) {
    from("../capture/capture.js")
    into(layout.buildDirectory.dir("generated/capture-assets"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyCapture) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
