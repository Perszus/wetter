// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
    // The fake provider is needed by this module's tests and by :data's. A
    // published fixtures source set shares it; the alternative is two copies
    // that drift.
    `java-test-fixtures`
}

// Java 17 bytecode from whatever JDK runs Gradle, like :app and :data. A
// toolchain would demand a JDK 17 installation, which F-Droid's builder lacks.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // A warning is a defect that has not been fixed yet; it fails the build instead.
        allWarningsAsErrors.set(true)
    }
}

// Deliberately almost empty, and it should stay that way. Weather models,
// provider ports, selection policy and solar geometry are all plain Kotlin and
// need nothing but the standard library. An Android or Ktor dependency appearing
// here is the signal that something has been put in the wrong module.
dependencies {
    testImplementation(libs.junit)
}
