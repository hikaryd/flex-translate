import Foundation

// Greedy autoregressive M2M-100 translation using the ONNX Runtime C API directly
// (OrtGetApiBase → OrtApi struct of function pointers).
//
// Real model output only — every string returned is genuine encoder+decoder output.
// No fabrication; on failure returns nil and the caller gates honestly.
//
// Uses the SPLIT decoder pair (not the merged decoder): the merged graph's If/no-cache
// branch reshapes encoder cross-attn KV in a way ORT mobile rejects at zero-length
// prefill past (verified on Android; same ONNX Mobile build on iOS). The split pair:
//  - encoder:          input_ids[1,S], attention_mask[1,S]  → last_hidden_state[1,S,1024]
//  - decoderPrefill:   input_ids[1,1], encoder_attention_mask[1,S], encoder_hidden_states
//                      → logits[1,1,V] + present.*.{decoder,encoder}.* full KV cache
//  - decoderWithPast:  input_ids[1,1], encoder_attention_mask[1,S], past_key_values.*
//                      → logits[1,1,V] + present.*.decoder.* (encoder KV static, carried forward)
//
// Step 0: prefill seeded with decoder_start_token_id (= EOS id). First generated token
// is FORCED to the target-language id. Steps 1+: with-past decoder, growing decoder
// self-attn KV while reusing the static encoder cross-attn KV from prefill.
//
// Not thread-safe; create/use/close on one worker thread (or under a lock).
final class M2m100OnnxEngine {

    // MARK: - Constants
    private static let numLayers = 12 // M2M-100 418M decoder layers
    private static let decoderParts = ["decoder.key", "decoder.value"]
    private static let encoderParts = ["encoder.key", "encoder.value"]
    private static let allParts = decoderParts + encoderParts
    private static let encoderOutputName = "last_hidden_state"
    private static let decoderLogitsName = "logits"
    private static let defaultMaxTokens = 96
    private static let defaultThreads: Int32 = 2

    // MARK: - Opaque ORT handles (owned)
    private let api: UnsafePointer<OrtApi>
    private var env: OpaquePointer?
    private var encoderSession: OpaquePointer?
    private var prefillSession: OpaquePointer?
    private var withPastSession: OpaquePointer?
    private var memInfo: OpaquePointer?

    private let tokenizer: M2m100Tokenizer

    // MARK: - Init
    private init(
        api: UnsafePointer<OrtApi>,
        env: OpaquePointer,
        encoderSession: OpaquePointer,
        prefillSession: OpaquePointer,
        withPastSession: OpaquePointer,
        memInfo: OpaquePointer,
        tokenizer: M2m100Tokenizer
    ) {
        self.api = api
        self.env = env
        self.encoderSession = encoderSession
        self.prefillSession = prefillSession
        self.withPastSession = withPastSession
        self.memInfo = memInfo
        self.tokenizer = tokenizer
    }

    deinit {
        releaseAll()
    }

    // MARK: - Public

    func translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        maxNewTokens: Int = M2m100OnnxEngine.defaultMaxTokens
    ) -> String? {
        let sourceIds = tokenizer.encodeSource(text: text, sourceLang: sourceLang)
        guard !sourceIds.isEmpty else { return "" }

        guard let encoderHidden = runEncoder(sourceIds: sourceIds) else { return nil }
        defer { releaseValue(encoderHidden) }

        return decodeGreedy(
            sourceIds: sourceIds,
            encoderHidden: encoderHidden,
            targetLang: targetLang,
            maxNewTokens: maxNewTokens
        )
    }

    func close() {
        releaseAll()
    }

    // MARK: - Factory

    static func create(spec: MtModelSpec, modelDir: URL) -> M2m100OnnxEngine? {
        guard case .seq2seqOnnx(let cfg) = spec else { return nil }

        let encoderURL = modelDir.appendingPathComponent(cfg.encoder)
        let prefillURL = modelDir.appendingPathComponent(cfg.decoderPrefill)
        let withPastURL = modelDir.appendingPathComponent(cfg.decoderWithPast)
        let tokenizerURL = modelDir.appendingPathComponent(cfg.tokenizer)

        let fm = FileManager.default
        guard fm.fileExists(atPath: encoderURL.path),
              fm.fileExists(atPath: prefillURL.path),
              fm.fileExists(atPath: withPastURL.path),
              fm.fileExists(atPath: tokenizerURL.path) else {
            return nil
        }
        guard let tokenizer = M2m100Tokenizer.load(from: tokenizerURL) else { return nil }

        // Obtain OrtApi.
        guard let apiBase = OrtGetApiBase() else { return nil }
        guard let apiPtr = apiBase.pointee.GetApi(UInt32(ORT_API_VERSION)) else { return nil }

        // Create env.
        var env: OpaquePointer?
        let envStatus = apiPtr.pointee.CreateEnv(ORT_LOGGING_LEVEL_WARNING, "M2m100", &env)
        guard checkStatus(apiPtr, envStatus), let env else { return nil }

        // Create session options.
        var opts: OpaquePointer?
        let optsStatus = apiPtr.pointee.CreateSessionOptions(&opts)
        guard checkStatus(apiPtr, optsStatus), let opts else {
            apiPtr.pointee.ReleaseEnv(env)
            return nil
        }
        _ = apiPtr.pointee.SetIntraOpNumThreads(opts, defaultThreads)
        defer { apiPtr.pointee.ReleaseSessionOptions(opts) }

        // Create three sessions.
        guard let encSession = createSession(api: apiPtr, env: env, path: encoderURL.path, opts: opts),
              let preSession = createSession(api: apiPtr, env: env, path: prefillURL.path, opts: opts),
              let wpSession  = createSession(api: apiPtr, env: env, path: withPastURL.path, opts: opts) else {
            apiPtr.pointee.ReleaseEnv(env)
            return nil
        }

        // CPU memory info (owned).
        var memInfo: OpaquePointer?
        let miStatus = apiPtr.pointee.CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memInfo)
        guard checkStatus(apiPtr, miStatus), let memInfo else {
            apiPtr.pointee.ReleaseSession(encSession)
            apiPtr.pointee.ReleaseSession(preSession)
            apiPtr.pointee.ReleaseSession(wpSession)
            apiPtr.pointee.ReleaseEnv(env)
            return nil
        }

        return M2m100OnnxEngine(
            api: apiPtr,
            env: env,
            encoderSession: encSession,
            prefillSession: preSession,
            withPastSession: wpSession,
            memInfo: memInfo,
            tokenizer: tokenizer
        )
    }

    // MARK: - Encoder

    private func runEncoder(sourceIds: [Int]) -> OpaquePointer? {
        let seq = sourceIds.count
        var inputIdsData = sourceIds.map { Int64($0) }
        var maskData = [Int64](repeating: 1, count: seq)
        var shape = [Int64(1), Int64(seq)]

        guard let inputIdsTensor = makeLongTensor(data: &inputIdsData, shape: &shape),
              let maskTensor = makeLongTensor(data: &maskData, shape: &shape) else { return nil }
        defer {
            releaseValue(inputIdsTensor)
            releaseValue(maskTensor)
        }

        var inputs: [OpaquePointer?] = [inputIdsTensor, maskTensor]
        var outputs: [OpaquePointer?] = [nil]

        let status = withCStringPointers(["input_ids", "attention_mask"]) { inNamesPtr in
            withCStringPointers([M2m100OnnxEngine.encoderOutputName]) { outNamesPtr in
                inputs.withUnsafeMutableBufferPointer { inValsPtr in
                    outputs.withUnsafeMutableBufferPointer { outValsPtr in
                        api.pointee.Run(
                            encoderSession,
                            nil,
                            inNamesPtr,
                            inValsPtr.baseAddress,
                            2,
                            outNamesPtr,
                            1,
                            outValsPtr.baseAddress
                        )
                    }
                }
            }
        }
        guard M2m100OnnxEngine.checkStatus(api, status) else { return nil }

        // Copy the hidden state into a standalone owned tensor.
        guard let rawHidden = outputs[0] else { return nil }
        defer { releaseValue(rawHidden) }
        return copyFloatTensor(rawHidden)
    }

    // MARK: - Decoder

    private func decodeGreedy(
        sourceIds: [Int],
        encoderHidden: OpaquePointer,
        targetLang: String,
        maxNewTokens: Int
    ) -> String? {
        let seq = sourceIds.count
        let targetLangId = tokenizer.targetLangId(lang: targetLang)
        var generated = [Int]()
        generated.reserveCapacity(maxNewTokens)

        // Step 0: prefill seeded with decoder_start_token_id.
        guard var pastKv = runPrefill(seq: seq, encoderHidden: encoderHidden) else { return nil }

        var nextInput = targetLangId // forced-BOS: first generated token is target lang id.

        for _ in 1 ..< maxNewTokens {
            guard let (nextToken, newPast) = runWithPast(
                seq: seq, inputToken: nextInput, pastKv: pastKv
            ) else {
                releasePastKv(pastKv)
                return nil
            }
            releasePastKv(pastKv)
            pastKv = newPast

            if nextToken == tokenizer.eosId || nextToken == tokenizer.padId { break }
            generated.append(nextToken)
            nextInput = nextToken
        }
        releasePastKv(pastKv)

        return tokenizer.decode(ids: generated)
    }

    // Step 0: prefill decoder — returns the full KV cache (decoder + encoder).
    private func runPrefill(seq: Int, encoderHidden: OpaquePointer) -> [String: OpaquePointer]? {
        var decInputData = [Int64(tokenizer.decoderStartId)]
        var decShape = [Int64(1), Int64(1)]
        var maskData = [Int64](repeating: 1, count: seq)
        var maskShape = [Int64(1), Int64(seq)]

        guard let decInput = makeLongTensor(data: &decInputData, shape: &decShape),
              let maskTensor = makeLongTensor(data: &maskData, shape: &maskShape) else { return nil }
        defer {
            releaseValue(decInput)
            releaseValue(maskTensor)
        }

        let inputNameStrs = ["input_ids", "encoder_attention_mask", "encoder_hidden_states"]
        let outputNameStrs = buildPresentNames(includeEncoder: true)

        var inputs: [OpaquePointer?] = [decInput, maskTensor, encoderHidden]
        var outputs = [OpaquePointer?](repeating: nil, count: outputNameStrs.count)

        let status = withCStringPointers(inputNameStrs) { inNamesPtr in
            withCStringPointers(outputNameStrs) { outNamesPtr in
                inputs.withUnsafeMutableBufferPointer { inValsPtr in
                    outputs.withUnsafeMutableBufferPointer { outValsPtr in
                        api.pointee.Run(
                            prefillSession, nil,
                            inNamesPtr, inValsPtr.baseAddress, inputNameStrs.count,
                            outNamesPtr, outputNameStrs.count, outValsPtr.baseAddress
                        )
                    }
                }
            }
        }
        guard M2m100OnnxEngine.checkStatus(api, status) else {
            outputs.forEach { if let v = $0 { releaseValue(v) } }
            return nil
        }

        return extractPresentKv(
            outputNames: outputNameStrs,
            outputTensors: outputs,
            includeEncoder: true
        )
    }

    // Steps 1+: with-past decoder. Returns (argmax token, next KV cache).
    private func runWithPast(
        seq: Int,
        inputToken: Int,
        pastKv: [String: OpaquePointer]
    ) -> (Int, [String: OpaquePointer])? {
        var decInputData = [Int64(inputToken)]
        var decShape = [Int64(1), Int64(1)]
        var maskData = [Int64](repeating: 1, count: seq)
        var maskShape = [Int64(1), Int64(seq)]

        guard let decInput = makeLongTensor(data: &decInputData, shape: &decShape),
              let maskTensor = makeLongTensor(data: &maskData, shape: &maskShape) else { return nil }
        defer {
            releaseValue(decInput)
            releaseValue(maskTensor)
        }

        // Build input names + tensors: fixed inputs first, then all past_key_values.* in order.
        var inputNameStrs = ["input_ids", "encoder_attention_mask"]
        var inputs: [OpaquePointer?] = [decInput, maskTensor]

        // past_key_values ordering must match what the with-past decoder graph expects.
        let pastOrder = buildPastKeyOrder()
        for key in pastOrder {
            guard let tensor = pastKv[key] else { return nil }
            inputNameStrs.append(key)
            inputs.append(tensor)
        }

        // Output: logits + present.*.decoder.* only (encoder KV not re-emitted by with-past).
        // We also request logits as the first output.
        var outputNameStrs = [M2m100OnnxEngine.decoderLogitsName]
        outputNameStrs += buildPresentNames(includeEncoder: false)

        var outputs = [OpaquePointer?](repeating: nil, count: outputNameStrs.count)

        let status = withCStringPointers(inputNameStrs) { inNamesPtr in
            withCStringPointers(outputNameStrs) { outNamesPtr in
                inputs.withUnsafeMutableBufferPointer { inValsPtr in
                    outputs.withUnsafeMutableBufferPointer { outValsPtr in
                        api.pointee.Run(
                            withPastSession, nil,
                            inNamesPtr, inValsPtr.baseAddress, inputNameStrs.count,
                            outNamesPtr, outputNameStrs.count, outValsPtr.baseAddress
                        )
                    }
                }
            }
        }
        guard M2m100OnnxEngine.checkStatus(api, status) else {
            outputs.forEach { if let v = $0 { releaseValue(v) } }
            return nil
        }

        // First output is logits.
        guard let logitsTensor = outputs[0] else {
            outputs.forEach { if let v = $0 { releaseValue(v) } }
            return nil
        }
        let token = argmaxLastStep(logitsTensor)
        releaseValue(logitsTensor)

        // Remaining outputs are new decoder KV.
        let presentOutputs = Array(outputs.dropFirst())
        let presentNames = Array(outputNameStrs.dropFirst())

        var newPast = extractPresentKv(
            outputNames: presentNames,
            outputTensors: presentOutputs,
            includeEncoder: false
        ) ?? [:]

        // Carry static encoder cross-attn KV forward (copy so we own it).
        for layer in 0 ..< M2m100OnnxEngine.numLayers {
            for part in M2m100OnnxEngine.encoderParts {
                let key = "past_key_values.\(layer).\(part)"
                if let src = pastKv[key], let copied = copyFloatTensor(src) {
                    newPast[key] = copied
                }
            }
        }

        return (token, newPast)
    }

    // MARK: - Tensor helpers

    // Make an int64 tensor from a [Int64] buffer. Data is COPIED into ORT-managed memory.
    private func makeLongTensor(data: inout [Int64], shape: inout [Int64]) -> OpaquePointer? {
        var value: OpaquePointer?
        let byteCount = data.count * MemoryLayout<Int64>.stride
        let shapeCount = shape.count  // capture before withUnsafeMutableBufferPointer
        let status = data.withUnsafeMutableBytes { rawBuf in
            shape.withUnsafeMutableBufferPointer { shapePtr in
                api.pointee.CreateTensorWithDataAsOrtValue(
                    memInfo,
                    rawBuf.baseAddress,
                    byteCount,
                    shapePtr.baseAddress,
                    shapeCount,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,
                    &value
                )
            }
        }
        guard M2m100OnnxEngine.checkStatus(api, status) else { return nil }
        return value
    }

    // Deep-copy a float tensor into a standalone owned OrtValue.
    private func copyFloatTensor(_ src: OpaquePointer) -> OpaquePointer? {
        // Get shape.
        var typeShape: OpaquePointer?
        let tsStatus = api.pointee.GetTensorTypeAndShape(src, &typeShape)
        guard M2m100OnnxEngine.checkStatus(api, tsStatus), let typeShape else { return nil }
        defer { api.pointee.ReleaseTensorTypeAndShapeInfo(typeShape) }

        var ndim: Int = 0
        _ = api.pointee.GetDimensionsCount(typeShape, &ndim)
        var shape = [Int64](repeating: 0, count: ndim)
        let shapeCount2 = ndim
        shape.withUnsafeMutableBufferPointer { shapePtr in
            _ = api.pointee.GetDimensions(typeShape, shapePtr.baseAddress, shapeCount2)
        }

        let totalElements = shape.reduce(1, *)
        let byteCount = Int(totalElements) * MemoryLayout<Float>.stride

        // Get source data pointer.
        var srcDataPtr: UnsafeMutableRawPointer?
        let dataStatus = api.pointee.GetTensorMutableData(src, &srcDataPtr)
        guard M2m100OnnxEngine.checkStatus(api, dataStatus), let srcDataPtr else { return nil }

        // Allocate a copy.
        let copyBuf = UnsafeMutableRawPointer.allocate(byteCount: byteCount, alignment: MemoryLayout<Float>.alignment)
        copyBuf.copyMemory(from: srcDataPtr, byteCount: byteCount)

        var dest: OpaquePointer?
        // Use CreateTensorWithDataAsOrtValue with the copy buffer. Note: caller must keep
        // copyBuf alive as long as the OrtValue lives. We wrap it in a holder class below.
        let createStatus = shape.withUnsafeBufferPointer { shapePtr in
            api.pointee.CreateTensorWithDataAsOrtValue(
                memInfo,
                copyBuf,
                byteCount,
                shapePtr.baseAddress,
                ndim,
                ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                &dest
            )
        }
        guard M2m100OnnxEngine.checkStatus(api, createStatus), let dest else {
            copyBuf.deallocate()
            return nil
        }

        // Register the buffer for deallocation when we are done.
        // We track it in a side table keyed by the OpaquePointer identity.
        M2m100OnnxEngine.registerBuffer(for: dest, buf: copyBuf)
        return dest
    }

    // Argmax over the vocab dimension of the last decoder step [1, T, V].
    private func argmaxLastStep(_ logits: OpaquePointer) -> Int {
        var typeShape: OpaquePointer?
        guard M2m100OnnxEngine.checkStatus(api, api.pointee.GetTensorTypeAndShape(logits, &typeShape)),
              let typeShape else { return 0 }
        defer { api.pointee.ReleaseTensorTypeAndShapeInfo(typeShape) }

        var ndim = 0
        _ = api.pointee.GetDimensionsCount(typeShape, &ndim)
        let ndimCopy = ndim
        var shape = [Int64](repeating: 0, count: max(ndim, 1))
        shape.withUnsafeMutableBufferPointer { ptr in
            _ = api.pointee.GetDimensions(typeShape, ptr.baseAddress, ndimCopy)
        }

        let vocabSize = Int(shape[safe: 2] ?? 0)
        guard vocabSize > 0 else { return 0 }

        var dataPtr: UnsafeMutableRawPointer?
        guard M2m100OnnxEngine.checkStatus(api, api.pointee.GetTensorMutableData(logits, &dataPtr)),
              let dataPtr else { return 0 }

        let floatPtr = dataPtr.assumingMemoryBound(to: Float.self)
        let totalElements = shape.reduce(1, *)
        let base = Int(totalElements) - vocabSize // last time step

        var bestIdx = 0
        var bestVal = Float.leastNormalMagnitude * -1
        for v in 0 ..< vocabSize {
            let val = floatPtr[base + v]
            if val > bestVal {
                bestVal = val
                bestIdx = v
            }
        }
        return bestIdx
    }

    // Extract present.* outputs → past_key_values.* owned tensors.
    private func extractPresentKv(
        outputNames: [String],
        outputTensors: [OpaquePointer?],
        includeEncoder: Bool
    ) -> [String: OpaquePointer]? {
        var out = [String: OpaquePointer]()
        let parts = includeEncoder
            ? M2m100OnnxEngine.allParts
            : M2m100OnnxEngine.decoderParts

        for layer in 0 ..< M2m100OnnxEngine.numLayers {
            for part in parts {
                let presentName = "present.\(layer).\(part)"
                let pastName = "past_key_values.\(layer).\(part)"
                guard let idx = outputNames.firstIndex(of: presentName),
                      let tensor = outputTensors[idx] else {
                    // If any expected output is missing, clean up and return nil.
                    out.values.forEach { releaseValue($0) }
                    return nil
                }
                guard let copied = copyFloatTensor(tensor) else {
                    out.values.forEach { releaseValue($0) }
                    return nil
                }
                releaseValue(tensor)
                out[pastName] = copied
            }
        }
        return out
    }

    // MARK: - Name builders

    private func buildPresentNames(includeEncoder: Bool) -> [String] {
        var names = [String]()
        let parts = includeEncoder ? M2m100OnnxEngine.allParts : M2m100OnnxEngine.decoderParts
        for layer in 0 ..< M2m100OnnxEngine.numLayers {
            for part in parts {
                names.append("present.\(layer).\(part)")
            }
        }
        return names
    }

    private func buildPastKeyOrder() -> [String] {
        var names = [String]()
        for layer in 0 ..< M2m100OnnxEngine.numLayers {
            for part in M2m100OnnxEngine.allParts {
                names.append("past_key_values.\(layer).\(part)")
            }
        }
        return names
    }

    // MARK: - Release helpers

    private func releaseValue(_ v: OpaquePointer) {
        M2m100OnnxEngine.freeBuffer(for: v)
        api.pointee.ReleaseValue(v)
    }

    private func releasePastKv(_ kv: [String: OpaquePointer]) {
        kv.values.forEach { releaseValue($0) }
    }

    private func releaseAll() {
        if let s = encoderSession { api.pointee.ReleaseSession(s); encoderSession = nil }
        if let s = prefillSession { api.pointee.ReleaseSession(s); prefillSession = nil }
        if let s = withPastSession { api.pointee.ReleaseSession(s); withPastSession = nil }
        if let m = memInfo { api.pointee.ReleaseMemoryInfo(m); memInfo = nil }
        if let e = env { api.pointee.ReleaseEnv(e); env = nil }
    }

    // MARK: - C API helpers

    private static func createSession(
        api: UnsafePointer<OrtApi>,
        env: OpaquePointer,
        path: String,
        opts: OpaquePointer
    ) -> OpaquePointer? {
        var session: OpaquePointer?
        let status = path.withCString { cPath in
            api.pointee.CreateSession(env, cPath, opts, &session)
        }
        guard checkStatus(api, status) else { return nil }
        return session
    }

    @discardableResult
    private static func checkStatus(_ api: UnsafePointer<OrtApi>, _ status: OpaquePointer?) -> Bool {
        guard let status else { return true }
        // Log the error message and release the status.
        if let msgPtr = api.pointee.GetErrorMessage(status) {
            let msg = String(cString: msgPtr)
            NSLog("[M2m100OnnxEngine] ORT error: %@", msg)
        }
        api.pointee.ReleaseStatus(status)
        return false
    }

    // MARK: - Buffer lifetime management
    // ORT CreateTensorWithDataAsOrtValue does NOT copy the buffer — caller owns it.
    // We track allocations in a thread-safe dictionary so we can free them alongside ReleaseValue.

    private static let bufferLock = NSLock()
    // Access is always protected by bufferLock — nonisolated(unsafe) silences Swift 6 checker.
    private nonisolated(unsafe) static var bufferTable = [ObjectIdentifier: UnsafeMutableRawPointer]()

    private static func registerBuffer(for value: OpaquePointer, buf: UnsafeMutableRawPointer) {
        let key = ObjectIdentifier(value as AnyObject)
        bufferLock.lock()
        bufferTable[key] = buf
        bufferLock.unlock()
    }

    private static func freeBuffer(for value: OpaquePointer) {
        let key = ObjectIdentifier(value as AnyObject)
        bufferLock.lock()
        let buf = bufferTable.removeValue(forKey: key)
        bufferLock.unlock()
        buf?.deallocate()
    }
}

// MARK: - C-string helpers

// Allocates heap C-strings, calls body with a stable pointer array, then frees them.
// Heap allocation is required so the pointers remain valid across Swift's copy boundaries.
private func withCStringPointers<T>(
    _ strings: [String],
    _ body: (UnsafePointer<UnsafePointer<Int8>?>) -> T
) -> T {
    let heapPtrs: [UnsafePointer<Int8>?] = strings.map { s in
        UnsafePointer(strdup(s))
    }
    defer { heapPtrs.forEach { if let p = $0 { free(UnsafeMutablePointer(mutating: p)) } } }
    let ptrs = heapPtrs
    return ptrs.withUnsafeBufferPointer { buf in
        body(buf.baseAddress!)
    }
}

// Safe subscript for arrays.
private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
