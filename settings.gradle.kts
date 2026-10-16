// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
// HTTP client even by accident, and :data cannot see the UI.
include(":domain")
include(":data")
include(":app")
