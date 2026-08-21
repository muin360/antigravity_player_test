plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tensorix.antigravityplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tensorix.antigravityplayer"
        minSdk = 27
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0-audiophile-release"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Oboe for direct hardware DAC access (16 KB page-aligned for Android 15+)
    implementation("com.google.oboe:oboe:1.9.3")

    // Media3 / ExoPlayer -> Phase 1 & 2 (player engine + DSP)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    // implementation("androidx.media3:media3-exoplayer-ffmpeg:1.3.1") // Requires manual JNI build for most devices
    
    // External high-performance decoder support via MediaCodec hardening
    // Phase 15: High-Precision 64-bit Audio DSP Architecture Enhancement

    // Room DB -> Phase 1 (library) & Phase 3 (YT cache)
    implementation("androidx.room:room-runtime:2.7.0-alpha13")
    implementation("androidx.room:room-ktx:2.7.0-alpha13")
    kapt("androidx.room:room-compiler:2.7.0-alpha13")

    // Networking -> Phase 3 (backend calls) & Phase 4 (LLM APIs)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Security -> Phase 4 (encrypted BYOK key storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
}
