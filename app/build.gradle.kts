plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing key comes from GitHub Secrets (see .github/workflows/build.yml). Never stored in code.
val keystoreFile = System.getenv("BRAIN_KEYSTORE_FILE")

android {
    namespace = "com.alexi.softface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexi.softface"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.${System.getenv("BUILD_NUMBER") ?: "1"}"
        ndk { abiFilters += setOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("BRAIN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BRAIN_KEY_ALIAS")
                keyPassword = System.getenv("BRAIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // Models are memory-mapped, so keep them uncompressed.
    androidResources { noCompress += listOf("tflite", "task") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.tensorflow.lite)
}
