// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Play upload key, from keystore.properties when present. It is never in the
// repository; F-Droid builds without it and signs the APK itself.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystore) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    // Unused resources and version checks below the minimum SDK fail `check`.
    lint {
        error += setOf("UnusedResources", "ObsoleteSdkInt")
    }
    namespace = "lv.bolwarra.wetter"
    compileSdk = 36

    defaultConfig {
        applicationId = "lv.bolwarra.wetter"
        minSdk = 26
        targetSdk = 36

        // Written by hand and matched to the git tag. Derived from git, they would
        // depend on clone depth, which F-Droid does not guarantee (docs/RELEASING.md).
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Suffixed so a debug build can sit next to an installed release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // For carrying around: the debug build's identity and key, so it installs
        // over one and keeps its data, but not debuggable, since a debuggable
        // runtime runs the radar projection's tight loops many times slower.
        create("daily") {
            initWith(getByName("debug"))
            isDebuggable = false
            versionNameSuffix = "-daily"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned without a keystore, as F-Droid's build expects. An unsigned
            // Play upload is stopped below, on bundleRelease alone.
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null

            // Leaves out META-INF/version-control-info.textproto, the one file
            // that differed between two builds of the same commit, so the release
            // APK is reproducible byte for byte.
            vcsInfo {
                include = false
            }
        }
    }

    // The dependency-metadata blob AGP adds to release artefacts is signed with a
    // Google key and is not reproducible from source, which F-Droid rejects.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // Fixed to the translated locales, so the build does not vary with the
        // machine it runs on.
        localeFilters += listOf("en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, shown in About.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }

    testOptions {
        // The widget tests draw with the app's own resources and assets.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // A warning is a defect that has not been fixed yet; it fails the build instead.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // The background refresh worker lives here rather than in :data, because it
    // needs the object graph and :data cannot see the application.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // Instrumented tests. The widget draws to a Bitmap through the platform's
    // Canvas, which has no behaviour on a JVM - android.jar's stubs return
    // zero and draw nothing - so the only place its geometry can actually be
    // checked is on a device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real android.graphics on the JVM, for drawing the widgets in tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Robolectric fails to load its native graphics runtime from a Maven repository
// whose path contains a space. A machine whose home directory has one can point
// it elsewhere with robolectricRepository=<path> in ~/.gradle/gradle.properties.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricRepository").orNull?.let {
        systemProperty("maven.repo.local", it)
    }
    // ReleaseReadinessTest reads the repository's documents, so a change to one
    // of them has to rerun the tests rather than leave them up to date.
    inputs.files(
        rootProject.fileTree(rootDir) {
            include(
                "README.md",
                "CHANGELOG.md",
                "docs/**",
                "fastlane/**",
                "fdroid/**",
                ".github/**",
            )
        },
    ).withPropertyName("releaseDocuments").withPathSensitivity(PathSensitivity.RELATIVE)
}

// An AAB is only ever built to be uploaded to Play, and Play will reject an
// unsigned one after a long upload. Failing here takes a second instead.
//
// Deliberately narrower than the release build type: `assembleRelease` must keep
// working without a keystore, because that is the command F-Droid runs.
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(hasKeystore) {
            "keystore.properties not found. A Play upload must be signed with the " +
                "upload key - see docs/RELEASING.md. (F-Droid builds use " +
                "assembleRelease and need no keystore.)"
        }
    }
}
