plugins {
    alias(libs.plugins.kotlin.jvm)
    // The fake provider is needed by this module's tests and by :data's. A
    // published fixtures source set shares it; the alternative is two copies
    // that drift.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

// Deliberately almost empty, and it should stay that way. Weather models,
// provider ports, selection policy and solar geometry are all plain Kotlin and
// need nothing but the standard library. An Android or Ktor dependency appearing
// here is the signal that something has been put in the wrong module.
dependencies {
    testImplementation(libs.junit)
}
