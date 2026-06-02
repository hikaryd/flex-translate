import XCTest

// UI-тест: жмёт кнопку "▶ тест-перевод (M2M-100 offline MT)" и ждёт, пока появится
// настоящий результат перевода. Нужны файлы модели M2M-100, сайдлоаженные в контейнер
// приложения на симуляторе по пути:
//   <Application Support>/models/m2m100-418m/
//
// В CI тест намеренно пропускается (файлов модели нет) и запускается только когда
// выставлена переменная окружения RUN_MT_SIMULATOR_TEST=1.
final class MtSimulatorUITest: XCTestCase {

    override func setUpWithError() throws {
        // Пропускаем, если явно не попросили запустить (нужны файлы модели).
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["RUN_MT_SIMULATOR_TEST"] == "1",
            "Skipping MT simulator test — set RUN_MT_SIMULATOR_TEST=1 to enable"
        )
        continueAfterFailure = false
    }

    func testM2M100TranslationProducesRealOutput() throws {
        let app = XCUIApplication()
        app.launch()

        // Ждём, пока появится главный экран Live.
        let liveScreen = app.staticTexts["Эфир"]
        XCTAssertTrue(liveScreen.waitForExistence(timeout: 5))

        // Прокручиваем вниз, чтобы добраться до тест-кнопки MT.
        let scrollView = app.scrollViews.firstMatch
        if scrollView.exists {
            scrollView.swipeUp()
        } else {
            app.swipeUp()
        }

        // Находим и жмём кнопку тест-перевода.
        let mtButton = app.buttons["live.testMT"]
        XCTAssertTrue(mtButton.waitForExistence(timeout: 5), "MT test button not found")
        mtButton.tap()

        // Ждём результат в лейбле testAudioResult (он же переиспользуется под результат MT).
        // На симуляторе первый прогон M2M-100 может занять до 120с (загрузка модели + encode + decode).
        let resultLabel = app.staticTexts.matching(identifier: "live.testAudioResult").firstMatch
        let appeared = resultLabel.waitForExistence(timeout: 120)
        XCTAssertTrue(appeared, "MT result never appeared within 120s")

        let resultText = resultLabel.label
        print("[MtSimulatorUITest] MT result: \(resultText)")

        // Не должно быть ошибкой.
        XCTAssertFalse(resultText.hasPrefix("⚠"),
            "MT result was an error: \(resultText)")

        // И не должно быть пустым.
        XCTAssertFalse(resultText.trimmingCharacters(in: .whitespaces).isEmpty,
            "MT result was empty")

        // Снимаем скриншот как доказательство.
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "flex-ios-mt-result"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
