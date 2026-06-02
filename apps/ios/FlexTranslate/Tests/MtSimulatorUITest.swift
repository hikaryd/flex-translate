import XCTest

// UI test that taps the "▶ тест-перевод (M2M-100 offline MT)" button and waits for a
// real translation result to appear. Requires the M2M-100 model files to be sideloaded
// into the simulator app container at:
//   <Application Support>/models/m2m100-418m/
//
// This test is intentionally skipped when run in CI (no model files) and only runs
// when the environment variable RUN_MT_SIMULATOR_TEST=1 is set.
final class MtSimulatorUITest: XCTestCase {

    override func setUpWithError() throws {
        // Skip unless explicitly requested (model files required).
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["RUN_MT_SIMULATOR_TEST"] == "1",
            "Skipping MT simulator test — set RUN_MT_SIMULATOR_TEST=1 to enable"
        )
        continueAfterFailure = false
    }

    func testM2M100TranslationProducesRealOutput() throws {
        let app = XCUIApplication()
        app.launch()

        // Wait for the main live screen to appear.
        let liveScreen = app.staticTexts["Эфир"]
        XCTAssertTrue(liveScreen.waitForExistence(timeout: 5))

        // Scroll down to reveal the test MT button.
        let scrollView = app.scrollViews.firstMatch
        if scrollView.exists {
            scrollView.swipeUp()
        } else {
            app.swipeUp()
        }

        // Find and tap the test translation button.
        let mtButton = app.buttons["live.testMT"]
        XCTAssertTrue(mtButton.waitForExistence(timeout: 5), "MT test button not found")
        mtButton.tap()

        // Wait for the result to appear in testAudioResult label (reused for MT result).
        // M2M-100 on simulator may take up to 120s for first inference (model load + encode + decode).
        let resultLabel = app.staticTexts.matching(identifier: "live.testAudioResult").firstMatch
        let appeared = resultLabel.waitForExistence(timeout: 120)
        XCTAssertTrue(appeared, "MT result never appeared within 120s")

        let resultText = resultLabel.label
        print("[MtSimulatorUITest] MT result: \(resultText)")

        // Must not be an error.
        XCTAssertFalse(resultText.hasPrefix("⚠"),
            "MT result was an error: \(resultText)")

        // Must not be empty.
        XCTAssertFalse(resultText.trimmingCharacters(in: .whitespaces).isEmpty,
            "MT result was empty")

        // Capture screenshot as evidence.
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "flex-ios-mt-result"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
