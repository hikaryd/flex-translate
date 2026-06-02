import Foundation
import llama

// Тонкая Swift-обёртка над C API llama.cpp из вендоренного
// llama.xcframework (device arm64 + simulator arm64/x86_64, динамический фреймворк).
//
// В xcframework есть готовый module map (framework module llama { ... }),
// поэтому Swift делает `import llama` напрямую — bridging header не нужен.
//
// Каждый метод — 1:1 к C-функции и таскает с собой непрозрачный handle модели:
// это LlamaCppSession в куче, отдаваемый как OpaquePointer.
//
// Только настоящий вывод модели: generate() отдаёт реально раздекоженный поток
// токенов или nil при любом сбое на уровне C — вызывающий гейтит честно, ничего не выдумывает.
//
// Потокобезопасность: llama_decode НЕ потокобезопасен в рамках сессии. Вызовы
// надо сериализовать — MilmmtMtProvider гарантирует это через NSLock.
enum LlamaCppBridge {

    // MARK: - Constants (повторяют дефолты Android)

    static let defaultCtx: Int32 = 1024
    static let defaultThreads: Int32 = 4
    static let maxNewTokens: Int32 = 128

    // MARK: - Load

    // Грузит GGUF-модель по пути. Отдаёт непрозрачный handle или nil при сбое. Только CPU.
    // nThreads — потоки генерации, nCtx — окно контекста в токенах.
    static func load(path: String, nThreads: Int32, nCtx: Int32) -> OpaquePointer? {
        var mparams = llama_model_default_params()
        mparams.n_gpu_layers = 0   // только CPU и на устройстве, и в симуляторе

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

        // Упаковываем model + ctx в Session в куче и отдаём как непрозрачный handle.
        let session = LlamaCppSession(model: model, ctx: ctx, nCtx: Int(ctxSize))
        let ptr = Unmanaged.passRetained(session).toOpaque()
        return OpaquePointer(ptr)
    }

    // MARK: - Generate

    // Одна жадная генерация по prompt (до maxNewTokens новых токенов) на модели за handle.
    // Отдаёт раздекоженный ответ (настоящий вывод модели) или nil при сбое.
    // Останавливается на первом end-of-generation токене или переводе строки.
    static func generate(handle: OpaquePointer, prompt: String, maxNewTokens: Int32) -> String? {
        let session = Unmanaged<LlamaCppSession>
            .fromOpaque(UnsafeRawPointer(handle))
            .takeUnretainedValue()
        return session.generate(prompt: prompt, maxNewTokens: Int(maxNewTokens))
    }

    // MARK: - Free

    // Освобождает модель и контекст за handle.
    static func free(handle: OpaquePointer) {
        Unmanaged<LlamaCppSession>.fromOpaque(UnsafeRawPointer(handle)).release()
    }
}

// MARK: - Session

// Объект в куче: одна загруженная llama-модель + контекст. Освобождается через ARC release.
// Не @MainActor — рассчитан только на фоновые потоки.
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

        // Чистим KV-кэш перед новым запросом — переводы независимы друг от друга.
        if let mem = llama_get_memory(ctx) {
            llama_memory_clear(mem, true)
        }

        // Токенизируем prompt (добавляем BOS, чтобы сработал chat-шаблон Gemma).
        var tokens = tokenize(text: prompt, addSpecial: true)
        guard !tokens.isEmpty else { return nil }
        guard tokens.count < nCtx - maxNewTokens else { return nil }

        // Жадная цепочка семплера (эквивалент top_k=1 по model card MiLMMT).
        let chainParams = llama_sampler_chain_default_params()
        guard let sampler = llama_sampler_chain_init(chainParams) else { return nil }
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy())
        defer { llama_sampler_free(sampler) }

        var answer = ""
        var nDecoded = 0

        // Прогоняем prompt (prefill).
        let prefillOk = tokens.withUnsafeMutableBufferPointer { buf -> Bool in
            let batch = llama_batch_get_one(buf.baseAddress, Int32(buf.count))
            return llama_decode(ctx, batch) == 0
        }
        guard prefillOk else { return nil }

        while nDecoded < maxNewTokens {
            let next = llama_sampler_sample(sampler, ctx, -1)
            if llama_vocab_is_eog(vocab, next) { break }

            let piece = tokenToPiece(token: next)
            // Перевод однострочный — обрываемся на первом переводе строки, с которого
            // начинается следующее эхо-поле (точно как в Android JNI).
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
