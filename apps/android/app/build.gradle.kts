plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.flextranslate"
    compileSdk = 35

    // MiLMMT native build ENABLED via the PREBUILT llama.cpp variant (see task #30/#32). The pinned
    // NDK 26.1.10909125 (clang 17) was installed via sdkmanager — the earlier sdkmanager attempt had
    // left a 4KB stub; a fresh install succeeded. We only need this NDK's clang to cross-compile the
    // one-file JNI shim (milmmt_jni.cpp); llama.cpp itself is NOT built from source — the official
    // ggml-org release b9453 arm64 .so are linked as IMPORTED (see CMakeLists.prebuilt.txt).
    ndkVersion = "26.1.10909125"

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

        // MiLMMT native build ENABLED (prebuilt variant). The shim (milmmt_jni.cpp → libmilmmt_jni.so)
        // is compiled ONLY for arm64-v8a — the only ABI the prebuilt llama.cpp .so ship (and the only
        // ABI SM-S937B needs). The from-source CMakeLists.txt stays unused; we point CMake at
        // CMakeLists.prebuilt.txt below, which links the IMPORTED prebuilt libllama.so.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    // MiLMMT native build ENABLED: compile the JNI shim against the prebuilt llama.cpp arm64 libs.
    // AGP requires the cmake entry file to be named exactly `CMakeLists.txt` ([CXX1400]), so the
    // prebuilt variant lives in its own `prebuilt/` subdir. That CMake imports libllama.so (+ its
    // libggml*.so DT_NEEDED chain) from src/main/cpp/prebuilt-libs/<abi>/ and builds ONLY
    // milmmt_jni.cpp → libmilmmt_jni.so. The heavy from-source ../CMakeLists.txt stays UNUSED.
    // (CMakeLists.prebuilt.txt documents the same approach but cannot itself be the entry due to
    // the filename rule.)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/prebuilt/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Package the prebuilt llama.cpp arm64 .so (libllama.so + libggml*.so + libggml-cpu-*.so) into
    // the APK. They live OUTSIDE the default jniLibs dir (src/main/cpp/prebuilt-libs/) so the
    // baseline APK never carried ~78 MB of unused .so; activating this srcDir makes AGP package them
    // alongside the compiled libmilmmt_jni.so. libggml.so dlopen()s the right libggml-cpu-*.so by
    // CPU-feature detection at runtime.
    sourceSets["main"].jniLibs.srcDir("src/main/cpp/prebuilt-libs")

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

    // ── Release signing ─────────────────────────────────────────────────────────────────────────
    // The keystore is NEVER committed. Two paths:
    //   1. CI: env vars injected by the release workflow from repo secrets
    //      (RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD).
    //   2. Local: create apps/android/keystore.properties (gitignored) with the same four keys.
    //
    // Generate a local release keystore (store OUTSIDE the repo, e.g. ~/.flex-translate-release.keystore):
    //   keytool -genkey -v -keystore ~/.flex-translate-release.keystore \
    //     -alias flex-translate -keyalg RSA -keysize 2048 -validity 10000
    // Then create apps/android/keystore.properties pointing at it.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = java.util.Properties().also { props ->
        if (keystorePropsFile.exists()) props.load(java.io.FileInputStream(keystorePropsFile))
    }

    signingConfigs {
        create("release") {
            // CI path: env vars set by release workflow
            val envStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            val envStorePass = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val envKeyAlias  = System.getenv("RELEASE_KEY_ALIAS")
            val envKeyPass   = System.getenv("RELEASE_KEY_PASSWORD")

            if (envStorePath != null && envStorePass != null && envKeyAlias != null && envKeyPass != null) {
                storeFile     = file(envStorePath)
                storePassword = envStorePass
                keyAlias      = envKeyAlias
                keyPassword   = envKeyPass
            } else if (keystoreProps.isNotEmpty()) {
                // Local developer path: read from keystore.properties
                storeFile     = file(keystoreProps.getProperty("storeFile") ?: "")
                storePassword = keystoreProps.getProperty("storePassword") ?: ""
                keyAlias      = keystoreProps.getProperty("keyAlias") ?: ""
                keyPassword   = keystoreProps.getProperty("keyPassword") ?: ""
            }
            // If neither source is available, Gradle falls back to debug signing automatically.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    // EncryptedSharedPreferences — secure storage for the user-supplied Gemini API key (BYOK).
    // The key is encrypted at rest using AES-256-GCM (value) + RSA (keyset master key via KeyStore).
    // We NEVER store the key in plaintext, never log it, never commit it.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM unit-test classpath. Android's bundled org.json is a stub in unit
    // tests (returns defaults / throws) so the WS5 request-build + response-parse helpers, which use
    // org.json, are unverifiable without the real impl. Same Apache-2.0 API as the device runtime.
    testImplementation("org.json:json:20240303")
}
