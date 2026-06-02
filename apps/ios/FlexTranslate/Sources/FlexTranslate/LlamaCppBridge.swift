import Foundation
import llama

// Thin Swift wrapper over the llama.cpp C API exposed through the vendored
// llama.xcframework (device arm64 + simulator arm64/x86_64 dynamic framework).
//
// The xcframework ships a proper module map (framework module llama { ... }) so
// Swift can `import llama` directly — no bridging header required.
//
// Each method maps 1:1 to a C function and carries an opaque model handle stored
// in a heap-allocated LlamaCppSession referenced as an OpaquePointer.
//
// Real model output only: generate() returns the genuine decoded token stream
// or nil on any C-level failure (the caller gates honestly — never fabricated).
//
// Thread-safety: llama_decode is NOT thread-safe per session. Callers must
// serialize — MilmmtMtProvider uses an NSLock to guarantee this.
enum LlamaCppBridge {

    // MARK: - Constants (mirrors Android defaults)

    static let defaultCtx: Int32 = 1024
    static let defaultThreads: Int32 = 4
    static let maxNewTokens: Int32 = 128

    // MARK: - Load

    // Load a GGUF model from path. Returns an opaque handle or nil on failure. CPU-only.
    // nThreads: generation threads; nCtx: context window in tokens.
    static func load(path: String, nThreads: Int32, nCtx: Int32) -> OpaquePointer? {
        var mparams = llama_model_default_params()
        mparams.n_gpu_layers = 0   // CPU-only on device / simulator

        guard let model = llama_model_load_from_file(path, mparams) else {
            return nil
        }

        var cparams = llama_context_default_params()
        let ctxSize = UInt32(nCtx > 0 ? nCtx : LlamaCppBridge.defaultCtx)
        cparams.n_ctx = ctxSize
        cparams.n_batch = ctxSize
        let threads = Int32(nThreads > 0 ? nThreads : LlamaCppBridge.defaultThreads)
        cparams.n_threads = threads
        cparams.n_threads_batch = threads

        guard let ctx = llama_init_from_model(model, cparams) else {
            llama_model_free(model)
            return nil
        }

        // Pack model + ctx into a heap-allocated Session and return as opaque handle.
        let session = LlamaCppSession(model: model, ctx: ctx, nCtx: Int(ctxSize))
        let ptr = Unmanaged.passRetained(session).toOpaque()
        return OpaquePointer(ptr)
    }

    // MARK: - Generate

    // Run one greedy completion for prompt (up to maxNewTokens new tokens) on the
    // model behind handle. Returns the decoded answer (genuine model output) or nil
    // on failure. Stops at the first end-of-generation token or newline.
    static func generate(handle: OpaquePointer, prompt: String, maxNewTokens: Int32) -> String? {
        let session = Unmanaged<LlamaCppSession>
            .fromOpaque(UnsafeRawPointer(handle))
            .takeUnretainedValue()
        return session.generate(prompt: prompt, maxNewTokens: Int(maxNewTokens))
    }

    // MARK: - Free

    // Free the model + context behind handle.
    static func free(handle: OpaquePointer) {
        Unmanaged<LlamaCppSession>.fromOpaque(UnsafeRawPointer(handle)).release()
    }
}

// MARK: - Session

// Heap object that holds one loaded llama model + context. Freed via ARC release.
// Not @MainActor — designed to be used from background threads only.
private final class LlamaCppSession {
    private let model: OpaquePointer
    private let ctx: OpaquePointer
    private let vocab: OpaquePointer
    private let nCtx: Int
    private let lock = NSLock()

    init(model: OpaquePointer, ctx: OpaquePointer, nCtx: Int) {
        self.model = model
        self.ctx = ctx
        self.vocab = llama_model_get_vocab(model)
        self.nCtx = nCtx
    }

    deinit {
        llama_free(ctx)
        llama_model_free(model)
    }

    func generate(prompt: String, maxNewTokens: Int) -> String? {
        lock.lock()
        defer { lock.unlock() }

        // Clear KV cache for a fresh request (translations are independent).
        if let mem = llama_get_memory(ctx) {
            llama_memory_clear(mem, true)
        }

        // Tokenize the prompt (add BOS so the Gemma chat template fires).
        var tokens = tokenize(text: prompt, addSpecial: true)
        guard !tokens.isEmpty else { return nil }
        guard tokens.count < nCtx - maxNewTokens else { return nil }

        // Greedy sampler chain (top_k=1 equivalent per the MiLMMT model card).
        let chainParams = llama_sampler_chain_default_params()
        guard let sampler = llama_sampler_chain_init(chainParams) else { return nil }
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy())
        defer { llama_sampler_free(sampler) }

        var answer = ""
        var nDecoded = 0

        // Prefill the prompt.
        let prefillOk = tokens.withUnsafeMutableBufferPointer { buf -> Bool in
            let batch = llama_batch_get_one(buf.baseAddress, Int32(buf.count))
            return llama_decode(ctx, batch) == 0
        }
        guard prefillOk else { return nil }

        while nDecoded < maxNewTokens {
            let next = llama_sampler_sample(sampler, ctx, -1)
            if llama_vocab_is_eog(vocab, next) { break }

            let piece = tokenToPiece(token: next)
            // Single-line translation — stop at the first newline that begins the
            // next echoed field (mirrors the Android JNI logic exactly).
            if piece.contains("\n") {
                let beforeNewline = String(piece.prefix(while: { $0 != "\n" }))
                answer += beforeNewline
                break
            }
            answer += piece
            nDecoded += 1

            var nextToken = next
            let stepOk = withUnsafeMutablePointer(to: &nextToken) { ptr -> Bool in
                let batch = llama_batch_get_one(ptr, 1)
                return llama_decode(ctx, batch) == 0
            }
            if !stepOk { break }
        }

        return answer.isEmpty && nDecoded == 0 ? nil : answer
    }

    // MARK: - Helpers

    private func tokenize(text: String, addSpecial: Bool) -> [llama_token] {
        let nMax = Int32(text.utf8.count) + 16
        var tokens = [llama_token](repeating: 0, count: Int(nMax))
        var n = llama_tokenize(vocab, text, Int32(text.utf8.count),
                               &tokens, nMax, addSpecial, true)
        if n < 0 {
            tokens = [llama_token](repeating: 0, count: Int(-n))
            n = llama_tokenize(vocab, text, Int32(text.utf8.count),
                               &tokens, Int32(tokens.count), addSpecial, true)
        }
        guard n > 0 else { return [] }
        return Array(tokens.prefix(Int(n)))
    }

    private func tokenToPiece(token: llama_token) -> String {
        var buf = [CChar](repeating: 0, count: 256)
        let n = llama_token_to_piece(vocab, token, &buf, 256, 0, false)
        guard n > 0 else { return "" }
        return String(bytes: buf.prefix(Int(n)).map { UInt8(bitPattern: $0) },
                      encoding: .utf8) ?? ""
    }
}
