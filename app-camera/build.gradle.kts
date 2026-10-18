import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: create <repo>/keystore.properties (git-ignored) with
//   storeFile=/absolute/path/to/rawboy-release.jks
//   storePassword=...
//   keyAlias=rawboy
// (shared with app-pro)
//   keyPassword=...
// Without it, assembleRelease still works but produces an unsigned APK.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.rawboy.camera"
    compileSdk = 35
    // Needed to strip the engine's native library when packaging (unstripped it is ~9x larger).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.rawboy.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("rawboy.versionCode").get().toInt()
        versionName = providers.gradleProperty("rawboy.versionName").get()

        ndk {
            // Same ABIs as the engine, so other native dependencies don't add more.
            abiFilters += providers.gradleProperty("rawboy.abis").get().split(",")
        }
    }

    // Editions (src/standard, src/film: Edition.kt), matching the engine's by name. standard = RawBoy Camera, the
    // looks on the bundled LUTs. film = RawBoy Film, spektrafilm film stocks; GPLv3 (no Adobe DNG SDK), with its own
    // id so both install side by side. Film lives in its own repository: the flavour only exists where src/film does.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
        }
        if (file("src/film").isDirectory) create("film") {
            dimension = "edition"
            applicationIdSuffix = ".film"
        }
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // MediaPipe memory-maps the engine's models; the app that packages them must store them uncompressed.
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.exifinterface)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
