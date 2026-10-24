pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "animeko-rulegen"
include(":core")

// 只有本机配置了 Android SDK 时才包含 app 模块，这样没有 SDK 的环境也能单独跑 :core:test
if (file("local.properties").exists() || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) {
    include(":app")
}
