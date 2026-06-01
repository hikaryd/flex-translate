plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.flextranslate"
    compileSdk = 35

    // MiLMMT native build deferred — see task #30; re-enable when libllama.so is available.
    // The NDK 26.1.10909125 install via sdkmanager hung/failed (left a 4KB stub), so pinning it
    // here would make AGP fail at configure/build time. The MiLMMT (Gemma-3) quality MT tier
    // degrades gracefully without the native runtime (LlamaCppBridge.isAvailable == false →
    // honest "runtime not installed" gating; never a fabricated translation). Restore this pin
    // together with the externalNativeBuild blocks below once a usable arm64 libllama.so exists.
    // ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "dev.flextranslate"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // SM-S937B (Galaxy S25-class) is arm64. The vendored sherpa-onnx AAR ships JNI for
        // arm64-v8a/armeabi-v7a/x86/x86_64; we only ship the two ARM ABIs to keep the APK lean.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // MiLMMT native build deferred — see task #30; re-enable when libllama.so is available.
        // This block would build the vendored llama.cpp from source (app/src/main/cpp/CMakeLists.txt
        // → libllama.so + libggml*.so + libmilmmt_jni.so) for arm64-v8a. It is disabled because the
        // pinned NDK 26.1.10909125 install failed, so the source compile cannot run. Re-enable
        // together with the ndkVersion pin above and the path block below once the toolchain (or a
        // prebuilt arm64 libllama.so) is in place.
        // externalNativeBuild {
        //     cmake {
        //         arguments += listOf("-DANDROID_STL=c++_shared")
        //         abiFilters += listOf("arm64-v8a")
        //     }
        // }
    }

    // MiLMMT native build deferred — see task #30; re-enable when libllama.so is available.
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

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

    // No jniLibs collision: the vendored sherpa AAR used below is the repacked copy whose private
    // ONNX Runtime was renamed to libsherpaort13.so (unique SONAME). It therefore coexists with the
    // Microsoft onnxruntime-android libonnxruntime.so used for MT — each JNI binds its own runtime.
}

dependencies {
    // Real sherpa-onnx Android runtime. No Maven Central artifact exists for
    // com.k2fsa.sherpa.onnx (verified: solrsearch numFound=0), so we vendor the official
    // prebuilt AAR from the v1.13.2 GitHub release. It bundles the com.k2fsa.sherpa.onnx
    // Kotlin API (OnlineRecognizer/OnlineStream/...) plus the sherpa JNI libs. Apache-2.0.
    //
    // We use the repacked AAR (libs/README-sherpa-noort.md): identical to upstream except sherpa's
    // private libonnxruntime.so is renamed to libsherpaort13.so (unique SONAME) and the sherpa JNIs'
    // DT_NEEDED are repointed at it. The original AAR's libonnxruntime.so shared a SONAME with the
    // Microsoft onnxruntime-android runtime used for MT but is NOT interchangeable with it (each JNI
    // resolves OrtGetApiBase only against its own ORT build). Renaming lets both runtimes coexist:
    // sherpa ASR binds libsherpaort13.so, MT binds the Microsoft libonnxruntime.so. The original
    // sherpa-onnx-1.13.2.aar is kept in libs/ for provenance; regenerate via scripts/repack_sherpa_aar.py.
    implementation(files("libs/sherpa-onnx-1.13.2-noort.aar"))

    // Generic ONNX Runtime for on-device machine translation (M2M-100 encoder/decoder).
    // The vendored sherpa-onnx AAR ships its own private libonnxruntime.so for the sherpa
    // C-API only — it does NOT expose the ai.onnxruntime Java/OrtSession API. So we add the
    // official Microsoft ONNX Runtime Android artifact (Apache-2.0) to run the MT graphs via
    // OrtEnvironment/OrtSession. arm64-v8a + armeabi-v7a JNI ship inside this AAR.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // NOTE on the tokenizer: ai.djl.huggingface:tokenizers was evaluated and REJECTED for the
    // device path — its Maven artifact bundles only desktop natives (linux/osx/win .so/.dylib);
    // it has NO Android arm64-v8a binary and falls back to a runtime download that has no Android
    // build. There is no official HF `tokenizers` Android artifact (Maven numFound=0). So the
    // M2M-100 tokenizer is implemented faithfully in pure Kotlin (BPE + metaspace + lang tokens)
    // from the model's own tokenizer.json — see [M2m100Tokenizer]. No extra dependency needed.

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM unit-test classpath. Android's bundled org.json is a stub in unit
    // tests (returns defaults / throws) so the WS5 request-build + response-parse helpers, which use
    // org.json, are unverifiable without the real impl. Same Apache-2.0 API as the device runtime.
    testImplementation("org.json:json:20240303")
}
