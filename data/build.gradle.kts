plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "lv.bolwarra.wetter.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ksp {
        // The schema is checked in so a migration can be diffed in review rather
        // than discovered by a user losing their data.
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        // The router logs its ranking in debug builds only.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The router logs through android.util.Log, which is a stub on the
            // JVM. Returning defaults lets the routing logic be tested without
            // wrapping the logger in an interface whose only purpose would be
            // to be mocked.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // api, not implementation: everything :data hands back is a domain type, so
    // a consumer of :data necessarily needs to see them.
    api(project(":domain"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor over OkHttp rather than the built-in Android engine: connection reuse
    // and proper coroutine cancellation matter when two providers may be asked
    // in one refresh. Both are Apache-2.0 and build from source.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":domain")))
}
