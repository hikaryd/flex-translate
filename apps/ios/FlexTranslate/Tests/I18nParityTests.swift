import Foundation
import Testing
@testable import FlexTranslate

// Tests for i18n RU/EN parity, DialogueTurn model, and MtRoutingMode default.
// All pure logic — no model files, no network, no UI required.

// MARK: - Strings RU/EN parity

@Suite("Strings RU/EN parity")
struct StringsParityTests {

    private let ru = StringsRu()
    private let en = StringsEn()

    // App shell
    @Test("tabLive non-empty in both languages")
    func tabLive() {
        #expect(!ru.tabLive.isEmpty)
        #expect(!en.tabLive.isEmpty)
        #expect(ru.tabLive != en.tabLive)
    }

    @Test("tabLanguages non-empty in both languages")
    func tabLanguages() {
        #expect(!ru.tabLanguages.isEmpty)
        #expect(!en.tabLanguages.isEmpty)
    }

    @Test("tabModels non-empty in both languages")
    func tabModels() {
        #expect(!ru.tabModels.isEmpty)
        #expect(!en.tabModels.isEmpty)
    }

    @Test("tabCloud non-empty in both languages")
    func tabCloud() {
        #expect(!ru.tabCloud.isEmpty)
        #expect(!en.tabCloud.isEmpty)
    }

    @Test("tabDiagnostics non-empty in both languages")
    func tabDiagnostics() {
        #expect(!ru.tabDiagnostics.isEmpty)
        #expect(!en.tabDiagnostics.isEmpty)
    }

    @Test("demoBanner non-empty in both languages")
    func demoBanner() {
        #expect(!ru.demoBanner.isEmpty)
        #expect(!en.demoBanner.isEmpty)
    }

    // Live screen
    @Test("stop and listen non-empty in both languages")
    func stopAndListen() {
        #expect(!ru.stop.isEmpty)
        #expect(!en.stop.isEmpty)
        #expect(!ru.listen.isEmpty)
        #expect(!en.listen.isEmpty)
        #expect(ru.stop != en.stop)
        #expect(ru.listen != en.listen)
    }

    @Test("translationTitle non-empty in both languages")
    func translationTitle() {
        #expect(!ru.translationTitle.isEmpty)
        #expect(!en.translationTitle.isEmpty)
        #expect(ru.translationTitle != en.translationTitle)
    }

    @Test("micReady non-empty in both languages")
    func micReady() {
        #expect(!ru.micReady.isEmpty)
        #expect(!en.micReady.isEmpty)
    }

    @Test("speech and silence non-empty in both languages")
    func speechAndSilence() {
        #expect(!ru.speech.isEmpty)
        #expect(!en.speech.isEmpty)
        #expect(!ru.silence.isEmpty)
        #expect(!en.silence.isEmpty)
    }

    @Test("missingPackBadge interpolates packId")
    func missingPackBadge() {
        let id = "sherpa-ru-t-one"
        #expect(ru.missingPackBadge(id).contains(id))
        #expect(en.missingPackBadge(id).contains(id))
    }

    // Languages screen
    @Test("languagePairTitle non-empty in both languages")
    func languagePairTitle() {
        #expect(!ru.languagePairTitle.isEmpty)
        #expect(!en.languagePairTitle.isEmpty)
        #expect(ru.languagePairTitle != en.languagePairTitle)
    }

    @Test("pairSupportTitle interpolates pair label")
    func pairSupportTitle() {
        let pair = "RU → EN"
        #expect(ru.pairSupportTitle(pair).contains(pair))
        #expect(en.pairSupportTitle(pair).contains(pair))
    }

    @Test("supportFromBenchmarksFooter non-empty in both languages")
    func supportFromBenchmarksFooter() {
        #expect(!ru.supportFromBenchmarksFooter.isEmpty)
        #expect(!en.supportFromBenchmarksFooter.isEmpty)
    }

    // Routing mode
    @Test("mtRoutingModeTitle non-empty in both languages")
    func mtRoutingModeTitle() {
        #expect(!ru.mtRoutingModeTitle.isEmpty)
        #expect(!en.mtRoutingModeTitle.isEmpty)
        #expect(ru.mtRoutingModeTitle != en.mtRoutingModeTitle)
    }

    @Test("mtRoutingModeAuto non-empty in both languages")
    func mtRoutingModeAuto() {
        #expect(!ru.mtRoutingModeAuto.isEmpty)
        #expect(!en.mtRoutingModeAuto.isEmpty)
    }

    @Test("mtRoutingModeOnDevice non-empty in both languages")
    func mtRoutingModeOnDevice() {
        #expect(!ru.mtRoutingModeOnDevice.isEmpty)
        #expect(!en.mtRoutingModeOnDevice.isEmpty)
    }

    @Test("mtRoutingModeCloud non-empty in both languages")
    func mtRoutingModeCloud() {
        #expect(!ru.mtRoutingModeCloud.isEmpty)
        #expect(!en.mtRoutingModeCloud.isEmpty)
    }

    @Test("engineBadgeOnDevice interpolates modelId")
    func engineBadgeOnDevice() {
        let modelId = "m2m100-418m"
        #expect(ru.engineBadgeOnDevice(modelId).contains(modelId))
        #expect(en.engineBadgeOnDevice(modelId).contains(modelId))
    }

    // Models screen
    @Test("download and cancel non-empty in both languages")
    func downloadAndCancel() {
        #expect(!ru.download.isEmpty)
        #expect(!en.download.isEmpty)
        #expect(!ru.cancel.isEmpty)
        #expect(!en.cancel.isEmpty)
        #expect(ru.download != en.download)
    }

    @Test("statusNotInstalled non-empty in both languages")
    func statusNotInstalled() {
        #expect(!ru.statusNotInstalled.isEmpty)
        #expect(!en.statusNotInstalled.isEmpty)
    }

    @Test("downloadingFile interpolates filename")
    func downloadingFile() {
        let file = "encoder_model.onnx"
        #expect(ru.downloadingFile(file).contains(file))
        #expect(en.downloadingFile(file).contains(file))
    }

    // Cloud screen
    @Test("cloudTitle non-empty in both languages")
    func cloudTitle() {
        #expect(!ru.cloudTitle.isEmpty)
        #expect(!en.cloudTitle.isEmpty)
    }

    @Test("interfaceLanguageTitle non-empty in both languages")
    func interfaceLanguageTitle() {
        #expect(!ru.interfaceLanguageTitle.isEmpty)
        #expect(!en.interfaceLanguageTitle.isEmpty)
        #expect(ru.interfaceLanguageTitle != en.interfaceLanguageTitle)
    }

    @Test("readyToStart non-empty in both languages")
    func readyToStart() {
        #expect(!ru.readyToStart.isEmpty)
        #expect(!en.readyToStart.isEmpty)
    }

    @Test("hideDisclosure and showDisclosure non-empty in both languages")
    func disclosureButtons() {
        #expect(!ru.hideDisclosure.isEmpty)
        #expect(!en.hideDisclosure.isEmpty)
        #expect(!ru.showDisclosure.isEmpty)
        #expect(!en.showDisclosure.isEmpty)
    }

    @Test("missingConsent/Disclosure/Online/Token non-empty in both languages")
    func missingPreconditions() {
        #expect(!ru.missingConsent.isEmpty)
        #expect(!en.missingConsent.isEmpty)
        #expect(!ru.missingDisclosure.isEmpty)
        #expect(!en.missingDisclosure.isEmpty)
        #expect(!ru.missingOnline.isEmpty)
        #expect(!en.missingOnline.isEmpty)
        #expect(!ru.missingEphemeralToken.isEmpty)
        #expect(!en.missingEphemeralToken.isEmpty)
    }

    // Diagnostics screen
    @Test("captureSectionTitle non-empty in both languages")
    func captureSectionTitle() {
        #expect(!ru.captureSectionTitle.isEmpty)
        #expect(!en.captureSectionTitle.isEmpty)
        #expect(ru.captureSectionTitle != en.captureSectionTitle)
    }

    @Test("asrSupportNotClaimed non-empty in both languages")
    func asrSupportNotClaimed() {
        #expect(!ru.asrSupportNotClaimed.isEmpty)
        #expect(!en.asrSupportNotClaimed.isEmpty)
    }

    // LiveSessionModel reasons
    @Test("mtEngineUnavailable interpolates model name")
    func mtEngineUnavailable() {
        let name = "m2m100-418m"
        #expect(ru.mtEngineUnavailable(name).contains(name))
        #expect(en.mtEngineUnavailable(name).contains(name))
    }

    @Test("mtModelNotInstalledReason interpolates model id")
    func mtModelNotInstalledReason() {
        let id = "m2m100-418m"
        #expect(ru.mtModelNotInstalledReason(id).contains(id))
        #expect(en.mtModelNotInstalledReason(id).contains(id))
    }

    // Dialogue
    @Test("dialogueClearButton non-empty in both languages")
    func dialogueClearButton() {
        #expect(!ru.dialogueClearButton.isEmpty)
        #expect(!en.dialogueClearButton.isEmpty)
        #expect(ru.dialogueClearButton != en.dialogueClearButton)
    }

    @Test("dialogueEmptyHint non-empty in both languages")
    func dialogueEmptyHint() {
        #expect(!ru.dialogueEmptyHint.isEmpty)
        #expect(!en.dialogueEmptyHint.isEmpty)
    }

    @Test("dialogueSpeakingLabel passes through language label")
    func dialogueSpeakingLabel() {
        let lang = "Русский"
        #expect(ru.dialogueSpeakingLabel(lang).contains(lang))
        #expect(en.dialogueSpeakingLabel(lang).contains(lang))
    }

    @Test("dialoguePendingTranslation non-empty in both languages")
    func dialoguePendingTranslation() {
        #expect(!ru.dialoguePendingTranslation.isEmpty)
        #expect(!en.dialoguePendingTranslation.isEmpty)
    }
}

// MARK: - AppLanguage

@Suite("AppLanguage")
struct AppLanguageTests {

    @Test("fromCode ru returns .ru")
    func fromCodeRu() {
        #expect(AppLanguage.fromCode("ru") == .ru)
        #expect(AppLanguage.fromCode("RU") == .ru)
    }

    @Test("fromCode en returns .en")
    func fromCodeEn() {
        #expect(AppLanguage.fromCode("en") == .en)
        #expect(AppLanguage.fromCode("EN") == .en)
    }

    @Test("fromCode nil falls back to system default")
    func fromCodeNil() {
        let result = AppLanguage.fromCode(nil)
        #expect(result == .ru || result == .en)
    }

    @Test("allCases contains both languages")
    func allCases() {
        #expect(AppLanguage.allCases.count == 2)
        #expect(AppLanguage.allCases.contains(.ru))
        #expect(AppLanguage.allCases.contains(.en))
    }

    @Test("nativeLabel is non-empty for each language")
    func nativeLabels() {
        for lang in AppLanguage.allCases {
            #expect(!lang.nativeLabel.isEmpty)
        }
    }

    @Test("stringsFor returns StringsRu for .ru")
    func stringsForRu() {
        let s = stringsFor(.ru)
        #expect(s.tabLive == StringsRu().tabLive)
    }

    @Test("stringsFor returns StringsEn for .en")
    func stringsForEn() {
        let s = stringsFor(.en)
        #expect(s.tabLive == StringsEn().tabLive)
    }
}

// MARK: - DialogueTurn model

@Suite("DialogueTurn")
struct DialogueTurnTests {

    @Test("Initial turn is pending (both nil)")
    func initialTurnIsPending() {
        let turn = DialogueTurn(
            monotonicTs: 1000,
            spokenLanguage: .ru,
            originalText: "привет",
            translationLanguage: .en
        )
        #expect(turn.translationPending)
        #expect(turn.translatedText == nil)
        #expect(turn.translationReason == nil)
    }

    @Test("withTranslation text resolves pending")
    func withTranslationText() {
        let turn = DialogueTurn(
            monotonicTs: 1000,
            spokenLanguage: .ru,
            originalText: "привет",
            translationLanguage: .en
        )
        let resolved = turn.withTranslation(text: "hello", reason: nil, engineLabel: "m2m100-418m")
        #expect(!resolved.translationPending)
        #expect(resolved.translatedText == "hello")
        #expect(resolved.translationReason == nil)
        #expect(resolved.mtEngineUsed == "m2m100-418m")
    }

    @Test("withTranslation reason resolves pending with gating reason")
    func withTranslationReason() {
        let turn = DialogueTurn(
            monotonicTs: 2000,
            spokenLanguage: .en,
            originalText: "hello",
            translationLanguage: .ru
        )
        let resolved = turn.withTranslation(text: nil, reason: "model not installed", engineLabel: nil)
        #expect(!resolved.translationPending)
        #expect(resolved.translatedText == nil)
        #expect(resolved.translationReason == "model not installed")
        #expect(resolved.mtEngineUsed == nil)
    }

    @Test("withTranslation preserves original fields")
    func withTranslationPreservesFields() {
        let turn = DialogueTurn(
            id: "test-id-123",
            monotonicTs: 3000,
            spokenLanguage: .zh,
            originalText: "你好",
            translationLanguage: .ru
        )
        let resolved = turn.withTranslation(text: "привет", reason: nil)
        #expect(resolved.id == "test-id-123")
        #expect(resolved.monotonicTs == 3000)
        #expect(resolved.spokenLanguage == .zh)
        #expect(resolved.originalText == "你好")
        #expect(resolved.translationLanguage == .ru)
    }

    @Test("id is stable across withTranslation copies")
    func idStableAcrossCopies() {
        let turn = DialogueTurn(
            id: "stable-id",
            monotonicTs: 0,
            spokenLanguage: .ru,
            originalText: "text",
            translationLanguage: .en
        )
        let copy1 = turn.withTranslation(text: "translated", reason: nil)
        let copy2 = copy1.withTranslation(text: "updated", reason: nil)
        #expect(copy1.id == turn.id)
        #expect(copy2.id == turn.id)
    }

    @Test("Default id is non-empty UUID string")
    func defaultIdIsUUID() {
        let turn = DialogueTurn(
            monotonicTs: 0,
            spokenLanguage: .ru,
            originalText: "x",
            translationLanguage: .en
        )
        #expect(!turn.id.isEmpty)
        #expect(UUID(uuidString: turn.id) != nil)
    }
}

// MARK: - MtRoutingMode

@Suite("MtRoutingMode")
struct MtRoutingModeTests {

    @Test("Default routing mode is AUTO")
    func defaultIsAuto() {
        let defaultMode = MtRoutingMode.auto
        #expect(defaultMode == .auto)
    }

    @Test("All three cases present")
    func allCases() {
        let cases = MtRoutingMode.allCases
        #expect(cases.contains(.auto))
        #expect(cases.contains(.onDevice))
        #expect(cases.contains(.cloud))
        #expect(cases.count == 3)
    }

    @Test("rawValues are distinct strings")
    func rawValuesDistinct() {
        let values = MtRoutingMode.allCases.map(\.rawValue)
        let unique = Set(values)
        #expect(unique.count == MtRoutingMode.allCases.count)
    }

    @Test("Routing mode strings exist in both Strings implementations")
    func routingModeStringsExist() {
        let ru = StringsRu()
        let en = StringsEn()
        // AUTO
        #expect(!ru.mtRoutingModeAuto.isEmpty)
        #expect(!en.mtRoutingModeAuto.isEmpty)
        // ON_DEVICE
        #expect(!ru.mtRoutingModeOnDevice.isEmpty)
        #expect(!en.mtRoutingModeOnDevice.isEmpty)
        // CLOUD
        #expect(!ru.mtRoutingModeCloud.isEmpty)
        #expect(!en.mtRoutingModeCloud.isEmpty)
    }
}

// MARK: - FlexLanguage

@Suite("FlexLanguage")
struct FlexLanguageTests {

    @Test("All phase-0 languages present")
    func allCases() {
        #expect(FlexLanguage.allCases.contains(.ru))
        #expect(FlexLanguage.allCases.contains(.en))
        #expect(FlexLanguage.allCases.contains(.zh))
    }

    @Test("code matches rawValue")
    func codeMatchesRawValue() {
        for lang in FlexLanguage.allCases {
            #expect(lang.code == lang.rawValue)
        }
    }

    @Test("displayCode is uppercased code")
    func displayCodeIsUppercased() {
        #expect(FlexLanguage.ru.displayCode == "RU")
        #expect(FlexLanguage.en.displayCode == "EN")
        #expect(FlexLanguage.zh.displayCode == "ZH")
    }

    @Test("label is non-empty for all languages")
    func labelNonEmpty() {
        for lang in FlexLanguage.allCases {
            #expect(!lang.label.isEmpty)
        }
    }

    @Test("fromCode parses lowercase codes")
    func fromCodeLower() {
        #expect(FlexLanguage.fromCode("ru") == .ru)
        #expect(FlexLanguage.fromCode("en") == .en)
        #expect(FlexLanguage.fromCode("zh") == .zh)
    }

    @Test("fromCode parses uppercase codes")
    func fromCodeUpper() {
        #expect(FlexLanguage.fromCode("RU") == .ru)
        #expect(FlexLanguage.fromCode("EN") == .en)
    }

    @Test("fromCode returns nil for unknown code")
    func fromCodeUnknown() {
        #expect(FlexLanguage.fromCode("fr") == nil)
        #expect(FlexLanguage.fromCode("") == nil)
    }
}
