plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hymt2vulkantest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hymt2vulkantest"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-vulkan"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Locally-built ARM64 AAR with GGML_VULKAN=ON and GGML_CPU=ON.
    // Do not add the Maven CPU-only llama-android artifact here: it would
    // duplicate the same dev.ffmpegkit.llama classes and native library names.
    implementation(files("libs/llama-android-vulkan.aar"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
