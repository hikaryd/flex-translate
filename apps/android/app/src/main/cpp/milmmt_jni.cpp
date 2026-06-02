// JNI bridge between [dev.flextranslate.foundation.LlamaCppBridge] (Kotlin) and the vendored
// llama.cpp runtime (libllama.so + libggml*.so), built for arm64-v8a by the app's CMake.
//
// Scope: exactly what the MiLMMT-46-4B (Gemma-3 architecture) quality MT path needs — load a GGUF
// once, run a single greedy completion for a translate prompt, return the decoded text. There is
// NO fabricated output: every returned string is the genuine token stream the model produced. A
// load/decode failure returns an empty/negative result that the Kotlin layer gates honestly.
//
// The model is instruction-tuned for completion-style MT (model card prompt):
//   Translate this from <SourceName> to <TargetName>:\n<SourceName>: <text>\n<TargetName>:
// We tokenize that prompt (add_special=true so the Gemma BOS is prepended), decode greedily
// (top_k=1 / temp=0 per the card), and stop at the first end-of-generation token or newline that
// closes the single-line answer.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include "llama.h"
#include "ggml-backend.h"

#include <string>
#include <vector>
#include <mutex>

#define LOG_TAG "MilmmtJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// One loaded GGUF -> one model + reusable context. Guarded so a stray second call cannot race the
// (heavy) generation. The Kotlin side already serializes on a worker thread; the mutex is defense
// in depth.
struct MilmmtSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    std::mutex lock;
};

bool g_backend_ready = false;

// Redirect llama.cpp internal log to Android logcat so failures appear in adb logcat.
static void llama_log_callback(ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                    prio = ANDROID_LOG_DEBUG; break;
    }
    // Trim trailing newline that llama.cpp appends.
    int len = static_cast<int>(strlen(text));
    if (len > 0 && text[len - 1] == '\n') {
        char buf[2048];
        int n = len - 1 < 2047 ? len - 1 : 2047;
        memcpy(buf, text, n);
        buf[n] = '\0';
        __android_log_print(prio, "llama.cpp", "%s", buf);
    } else {
        __android_log_print(prio, "llama.cpp", "%s", text);
    }
}

void ensure_backend() {
    if (!g_backend_ready) {
        llama_log_set(llama_log_callback, nullptr);
        // b9453+ requires at least one backend registered before model load.
        // libggml-cpu-*.so has no embedded SONAME so we cannot static-link it (linker writes
        // full host path into DT_NEEDED). Instead: Kotlin pre-loads the CPU .so via
        // System.loadLibrary, so ggml_backend_cpu_reg is already in the process; find it via
        // dlsym(RTLD_DEFAULT) to avoid --no-undefined link failure.
        using cpu_reg_fn = ggml_backend_reg_t (*)();
        auto *cpu_reg = reinterpret_cast<cpu_reg_fn>(dlsym(RTLD_DEFAULT, "ggml_backend_cpu_reg"));
        if (cpu_reg != nullptr) {
            ggml_backend_register(cpu_reg());
            LOGI("CPU backend registered via dlsym");
        } else {
            LOGE("ggml_backend_cpu_reg not found via dlsym — model load will likely fail");
        }
        llama_backend_init();
        g_backend_ready = true;
    }
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n <= 0) return std::string();
    return std::string(buf, n);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    const int n_max = static_cast<int>(text.size()) + 16;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           tokens.data(), n_max, add_special, /*parse_special=*/true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int>(text.size()),
                           tokens.data(), static_cast<int>(tokens.size()), add_special, true);
    }
    if (n < 0) return {};
    tokens.resize(n);
    return tokens;
}

} // namespace

extern "C" {

// Load a GGUF from [pathStr]. Returns an opaque handle (>0) or 0 on failure. CPU-only; the number
// of threads is chosen by the caller (mobile cores are limited).
JNIEXPORT jlong JNICALL
Java_dev_flextranslate_foundation_LlamaCppBridge_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring pathStr, jint nThreads, jint nCtx) {
    ensure_backend();

    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    if (path == nullptr) return 0;
    std::string modelPath(path);
    env->ReleaseStringUTFChars(pathStr, path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only on device.

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? static_cast<uint32_t>(nCtx) : 1024;
    cparams.n_batch = cparams.n_ctx;
    const int threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto *session = new MilmmtSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int>(cparams.n_ctx);
    LOGI("model loaded: ctx=%d threads=%d", session->n_ctx, threads);
    return reinterpret_cast<jlong>(session);
}

// Run a single greedy completion for [promptStr], up to [maxNewTokens] new tokens. Returns the
// decoded answer text (genuine model output), or null on failure. Decoding stops at the first
// end-of-generation token; the answer is also trimmed at the first newline (the model card prompt
// produces a single translated line).
JNIEXPORT jstring JNICALL
Java_dev_flextranslate_foundation_LlamaCppBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring promptStr, jint maxNewTokens) {
    auto *session = reinterpret_cast<MilmmtSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) return nullptr;

    std::lock_guard<std::mutex> guard(session->lock);

    const char *prompt = env->GetStringUTFChars(promptStr, nullptr);
    if (prompt == nullptr) return nullptr;
    std::string promptText(prompt);
    env->ReleaseStringUTFChars(promptStr, prompt);

    // Fresh KV state per request — translations are independent.
    llama_memory_t mem = llama_get_memory(session->ctx);
    if (mem != nullptr) llama_memory_clear(mem, true);

    std::vector<llama_token> tokens = tokenize(session->vocab, promptText, /*add_special=*/true);
    if (tokens.empty()) {
        LOGE("tokenize produced no tokens");
        return nullptr;
    }
    if (static_cast<int>(tokens.size()) >= session->n_ctx - maxNewTokens) {
        LOGE("prompt too long: %zu tokens (ctx=%d)", tokens.size(), session->n_ctx);
        return nullptr;
    }

    // Greedy sampler chain (top_k=1 / temp=0 equivalent per the model card).
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string answer;
    int n_decoded = 0;

    // Prefill the prompt.
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));
    bool ok = llama_decode(session->ctx, batch) == 0;

    while (ok && n_decoded < maxNewTokens) {
        llama_token next = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, next)) break;

        std::string piece = token_to_piece(session->vocab, next);
        // The model card prompt yields a single translated line; stop at the first newline that
        // begins the next (echoed) field so we return only the translation.
        if (!piece.empty() && piece.find('\n') != std::string::npos) {
            answer += piece.substr(0, piece.find('\n'));
            break;
        }
        answer += piece;
        n_decoded++;

        llama_batch step = llama_batch_get_one(&next, 1);
        ok = llama_decode(session->ctx, step) == 0;
    }

    llama_sampler_free(sampler);

    if (!ok && answer.empty()) {
        LOGE("decode failed");
        return nullptr;
    }
    LOGI("generated %d tokens", n_decoded);
    return env->NewStringUTF(answer.c_str());
}

// Free the model + context behind [handle].
JNIEXPORT void JNICALL
Java_dev_flextranslate_foundation_LlamaCppBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *session = reinterpret_cast<MilmmtSession *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

} // extern "C"
