plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saathi.app"
    compileSdk = 36 // CameraX 1.6 needs 36

    defaultConfig {
        applicationId = "com.saathi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-live"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("task", "litertlm") }
    // Extract native libs to nativeLibraryDir so LiteRT can find the NPU dispatch + QNN libs.
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { aidl = true; buildConfig = true } // IBrain: the models run in their own ":brain" process
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "PRO", "false") }
        getByName("release") { buildConfigField("boolean", "PRO", "false") }
        // Saathi Pro: the same app plus an opt-in cloud brain (OpenRouter) for power-user tasks. Only this build has the
        // INTERNET permission (src/pro/AndroidManifest.xml); the normal Saathi stays fully offline.
        create("pro") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "PRO", "true")
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // On-device LLM runtimes. LiteRT-LM = the only path to the Snapdragon NPU (.litertlm);
    // MediaPipe = guaranteed GPU/CPU fallback (.task).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1") // 0.17.x needs a newer dispatch than any LiteRT release (get_hooks); 0.16.1 matches the v2.1.6 dispatch
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    // Hexagon V81 HTP libs; pinned to 2.47 because the bundled dispatch .so (LiteRT 2.2.0, v81) was built against QAIRT 2.47.
    implementation("com.qualcomm.qti:qnn-runtime:2.47.0")
    // Camera (Read this · medicine strip) + offline OCR (bundled models, no download).
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
