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

rootProject.name = "Wetter"

// Three modules, and the dependency arrow only points one way:
//
//     :app  ->  :data  ->  :domain
//
// :domain is a plain Kotlin library, so it cannot depend on Android or on an
// HTTP client even by accident. :data cannot see the UI. What used to be a
// convention a reviewer had to hold in their head is now a compile error.
include(":domain")
include(":data")
include(":app")
