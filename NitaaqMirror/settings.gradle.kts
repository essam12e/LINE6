// Standard Android Studio / Android Gradle Plugin build.
// (litebuild/ builds the same sources without AGP, for environments without Google's Maven/SDK hosts.)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NitaaqMirror"
include(":app")
