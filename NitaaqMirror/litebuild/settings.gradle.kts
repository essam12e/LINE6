// Lite build: produces the APK without the Android Gradle Plugin.
// Used where Google's SDK/Maven hosts are unreachable. See ../BUILD.md.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "nitaaq-mirror-litebuild"
