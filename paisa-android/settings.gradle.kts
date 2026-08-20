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

rootProject.name = "Paisa"

/* `core` is a standalone Gradle build holding every calculation in the app. It
 * has no Android dependencies, so `cd core && gradle test` runs its whole suite
 * on any machine with a JDK — no SDK or emulator needed. */
includeBuild("core")

include(":app")
