import Foundation
import Testing
@testable import FlexTranslate

// Unit tests for MilmmtMtProvider gating and MilmmtDirection parsing.
// These tests run without the GGUF model file present — they exercise the
// honest gating paths (missing model → unsupportedReason, never nil+nil).
//
// Real inference is exercised via the simulator sideload flow (GGUF copied
// into the app container); this file covers only the Swift-layer logic.

// MARK: - MilmmtDirection parsing

@Suite("MilmmtDirection")
struct MilmmtDirectionTests {

    @Test("Parses canonical arrow format")
    func parsesArrow() {
        let d = MilmmtDirection.parse("ru->en")
        #expect(d?.source == "ru")
        #expect(d?.target == "en")
        #expect(d?.sourceName == "Russian")
        #expect(d?.targetName == "English")
    }

    @Test("Parses dash format")
    func parsesDash() {
        let d = MilmmtDirection.parse("en-ru")
        #expect(d?.source == "en")
        #expect(d?.target == "ru")
    }

    @Test("Parses underscore format")
    func parsesUnderscore() {
        let d = MilmmtDirection.parse("ru_en")
        #expect(d?.source == "ru")
        #expect(d?.target == "en")
    }

    @Test("Parses Chinese Simplified")
    func parsesChineseSimplified() {
        let d = MilmmtDirection.parse("zh->en")
        #expect(d?.sourceName == "Chinese (Simplified)")
        #expect(d?.targetName == "English")
    }

    @Test("Rejects unknown language")
    func rejectsUnknown() {
        #expect(MilmmtDirection.parse("fr->en") == nil)
        #expect(MilmmtDirection.parse("ru->de") == nil)
    }

    @Test("Rejects same source and target")
    func rejectsSameLanguage() {
        #expect(MilmmtDirection.parse("ru->ru") == nil)
        #expect(MilmmtDirection.parse("en->en") == nil)
    }

    @Test("Rejects malformed input")
    func rejectsMalformed() {
        #expect(MilmmtDirection.parse("") == nil)
        #expect(MilmmtDirection.parse("ruen") == nil)
        #expect(MilmmtDirection.parse("ru") == nil)
    }
}

// MARK: - MilmmtMtProvider gating

@Suite("MilmmtMtProvider gating")
struct MilmmtMtProviderGatingTests {

    private func providerWithNoModel() -> MilmmtMtProvider {
        let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("milmmt-test-\(UUID().uuidString)")
        let cfg = MtModelSpec.GgufConfig(
            modelId: "milmmt-46-4b-q6",
            gguf: "MiLMMT-46-4B-v0.1.Q6_K.gguf"
        )
        return MilmmtMtProvider(spec: cfg, modelDir: tmpDir)
    }

    @Test("Missing model returns honest unsupportedReason, never fabricated text")
    func missingModelGating() {
        let provider = providerWithNoModel()
        defer { provider.close() }

        let result = provider.translate(
            text: "сейчас к тебе приедет бригада давай",
            languagePair: "ru->en",
            deviceTier: "mid"
        )
        // A2 discipline: text must be nil, reason must explain honestly.
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
        let reason = result.unsupportedReason ?? ""
        #expect(reason.contains("milmmt-46-4b-q6") || reason.contains("установлена") || reason.contains("installed"))
    }

    @Test("Unsupported direction returns reason, not fabricated text")
    func unsupportedDirection() {
        let provider = providerWithNoModel()
        defer { provider.close() }

        let result = provider.translate(
            text: "bonjour",
            languagePair: "fr->en",
            deviceTier: "high"
        )
        #expect(result.text == nil)
        #expect(result.unsupportedReason != nil)
    }

    @Test("Empty text returns nil/nil (not an error)")
    func emptyTextReturnsNilNil() {
        let provider = providerWithNoModel()
        defer { provider.close() }

        let result = provider.translate(text: "   ", languagePair: "ru->en", deviceTier: "high")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == nil)
    }
}

// MARK: - MtModelSpec.gguf isInstalled

@Suite("MtModelSpec GGUF isInstalled")
struct MtModelSpecGgufTests {

    @Test("Reports not installed when file missing")
    func notInstalledWhenMissing() {
        let spec = MtModelSpecs.milmmt46b4q6
        let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("milmmt-spec-\(UUID().uuidString)")
        #expect(spec.isInstalled(in: tmpDir) == false)
    }

    @Test("Reports installed when GGUF stub file present")
    func installedWhenFilePresent() throws {
        let spec = MtModelSpecs.milmmt46b4q6
        let tmpDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("milmmt-spec-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        guard case .gguf(let cfg) = spec else {
            Issue.record("Expected .gguf spec")
            return
        }
        let stub = tmpDir.appendingPathComponent(cfg.gguf)
        try Data("stub".utf8).write(to: stub)

        #expect(spec.isInstalled(in: tmpDir) == true)
    }
}
