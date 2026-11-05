// StreamFlix for Windows and macOS: a separate Gradle build next to the Android app. It compiles
// the app's providers, extractors and models straight from ../app (see build.gradle.kts) and adds
// a Compose Desktop UI and a VLC-backed player on top.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StreamFlixDesktop"
