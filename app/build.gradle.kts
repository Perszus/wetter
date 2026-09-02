import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing material for the Play upload key. Absent from the repository, absent
// on a fresh clone, and absent on F-Droid's build servers — which is the point:
// F-Droid builds the same source with no keystore and signs the result itself.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystore) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "lv.bolwarra.wetter"
    compileSdk = 36

    defaultConfig {
        applicationId = "lv.bolwarra.wetter"
        minSdk = 26
        targetSdk = 36

        // Both are written out by hand, one release at a time, and both must
        // match the git tag. Deriving them from the repository — a commit count,
        // a `git describe` — would make the build depend on clone depth, which
        // F-Droid's build servers do not guarantee and which would quietly
        // change the version of a build nobody expected to change.
        // See docs/RELEASING.md.
        versionCode = 1
        versionName = "0.1.0"

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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only where the keystore exists. Without it the release build
            // still succeeds and produces an unsigned APK, because that is
            // exactly what F-Droid asks for and failing here would break their
            // build. The guard against an unsigned *Play* upload is below, on
            // bundleRelease alone.
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    // The dependency-metadata blob AGP adds to release artefacts is signed with a
    // Google key and is not reproducible from source, which F-Droid rejects.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // The set of locales is fixed by what is translated, so it does not vary
        // with whatever happens to be installed on the build machine. One less
        // way for two builds of the same commit to differ.
        localeFilters += listOf("en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Needed for BuildConfig.VERSION_NAME, which the About section reads so
        // the version on screen can never drift from the one that was built.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
