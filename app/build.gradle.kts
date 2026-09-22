plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.androidvirtualcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidvirtualcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Simplified to arm64-v8a only for now to fix native build
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = layout.projectDirectory.file("src/main/cpp/CMakeLists.txt").asFile
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
            pickFirsts += "lib/armeabi-v7a/libc++_shared.so"
        }
        jniLibs {
            useLegacyPackaging = false
            // Native .so duplicates – same as resources but for jniLibs (AGP 8+)
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
            pickFirsts += "lib/armeabi-v7a/libc++_shared.so"
        }
    }

    // Force Kotlin stdlib version to avoid duplicate class conflicts
    project.configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22")
        }
    }

    configurations.all {
        resolutionStrategy {
            force(
                "com.google.guava:listenablefuture:" +
                "9999.0-empty-to-avoid-conflict-with-guava"
            )
        }
    }
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22")
        // Force guava to avoid listenablefuture duplication
        force("com.google.guava:guava:32.1.2-android")
        force(
            "com.google.guava:listenablefuture:" +
            "9999.0-empty-to-avoid-conflict-with-guava"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.camera:camera-video:1.3.1")

    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.google.guava:guava:32.1.2-android") {
        exclude(group = "com.google.guava",
                module = "listenablefuture")
    }

    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Xposed APIs – now using local stub classes in app/src/main/java/de/robv/android/xposed/
    // No JARs needed – real Xposed API is provided at runtime by LSPosed
    // This is standard practice for Xposed modules to avoid JitPack 401 and fake JAR issues
    // Stub classes compile cleanly and are replaced at runtime
    // hiddenapibypass is on Maven Central (not JitPack) – implementation
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    implementation("be.tarsos.dsp:core:2.5")

    // ONNX – keep only Android artifact to avoid duplicate classes
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // RootEncoder – previously from JitPack (causing 401), now local stub JAR in libs/
    // Real implementation would be com.github.pedroSG94.RootEncoder:library:2.4.4 from JitPack
    // Using local RootEncoder.jar for CI without JitPack; contains stubs for RtmpClient, RtspClient, SrtClient, ConnectChecker, VideoCodec, AudioCodec
    implementation(fileTree(mapOf(
        "dir" to "libs",
        "include" to listOf("RootEncoder.jar")
    )))

    // WebRTC – SINGLE library only as requested (remove any other WebRTC)
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.11.1")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
