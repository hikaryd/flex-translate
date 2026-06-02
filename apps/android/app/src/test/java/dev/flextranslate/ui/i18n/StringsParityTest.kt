package dev.flextranslate.ui.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Проверяет, что [StringsRu] и [StringsEn] дают один и тот же полный набор ключей и что ни одна
 * английская строка не пустая. Ловит регрессию пропущенного перевода: когда новый ключ добавили
 * в интерфейс (и в одну реализацию), а во вторую забыли.
 *
 * Тест через рефлексию обходит каждое объявленное свойство и метод без аргументов у [Strings],
 * поэтому сам себя обновляет: добавить ключ в интерфейс, не заполнив обе реализации, не даст
 * скомпилироваться (контракт интерфейса) — а этот тест вдобавок проверяет в рантайме, что
 * английские значения не пустые.
 */
class StringsParityTest {

    // ---- val-свойства -------------------------------------------------------------------------

    @Test
    fun `all Strings val properties are non-blank in StringsEn`() {
        val ruProps = StringsRu::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
        val enProps = StringsEn::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }

        // Один и тот же набор имён геттеров (для val интерфейса это и так гарантирует компилятор,
        // но рантайм-проверка даёт читаемый дифф, если что-то пошло не так).
        val ruNames = ruProps.map { it.name }.toSet()
        val enNames = enProps.map { it.name }.toSet()
        val missingInEn = ruNames - enNames
        val missingInRu = enNames - ruNames
        assert(missingInEn.isEmpty()) { "StringsEn is missing getters: $missingInEn" }
        assert(missingInRu.isEmpty()) { "StringsRu is missing getters: $missingInRu" }

        // Каждый EN-геттер обязан вернуть непустую строку.
        enProps.forEach { method ->
            val value = method.invoke(StringsEn)
            assertNotNull("StringsEn.${method.name} returned null", value)
            assertFalse(
                "StringsEn.${method.name} is blank — add the English translation",
                (value as String).isBlank(),
            )
        }
    }

    // ---- fun-функции с параметрами ----------------------------------------------------------

    @Test
    fun `all Strings parameterised functions produce non-blank output in StringsEn`() {
        // Дёргаем каждую функцию с одним String-параметром на показательном плейсхолдере, чтобы
        // убедиться: сам английский шаблон не пустой и не схлопнулся случайно.
        val placeholder = "TEST"
        with(StringsEn) {
            assertNonBlank("missingPackBadge",      missingPackBadge(placeholder))
            assertNonBlank("asrNotClaimedHint",     asrNotClaimedHint(placeholder))
            assertNonBlank("listeningHint",         listeningHint(placeholder))
            assertNonBlank("readyToListenHint",     readyToListenHint(placeholder))
            assertNonBlank("translationPendingCloud", translationPendingCloud(placeholder))
            assertNonBlank("translationPendingLocal", translationPendingLocal(placeholder))
            assertNonBlank("missingPackHint",       missingPackHint(placeholder))
            assertNonBlank("demoRecognizeButton",   demoRecognizeButton(placeholder))
            assertNonBlank("pairSupportTitle",      pairSupportTitle(placeholder))
            assertNonBlank("qualityBadge",          qualityBadge(placeholder))
            assertNonBlank("speedBadge",            speedBadge(placeholder))
            assertNonBlank("downloadingFile",       downloadingFile(placeholder))
            assertNonBlank("downloadFailedLine",    downloadFailedLine(placeholder))
            assertNonBlank("disabledMissing",       disabledMissing(placeholder))
            assertNonBlank("cloudProviderTitle(gemini-flash-mt)",
                cloudProviderTitle("gemini-flash-mt") ?: "<null — ok for unknown id>")
            assertNonBlank("mtEngineUnavailable",   mtEngineUnavailable(placeholder))
            assertNonBlank("mtModelNotInstalledReason", mtModelNotInstalledReason(placeholder))
            assertNonBlank("dialogueSpeakingLabel", dialogueSpeakingLabel(placeholder))
            assertNonBlank("engineBadgeOnDevice",   engineBadgeOnDevice(placeholder))
        }
    }

    @Test
    fun `all Strings parameterised functions produce non-blank output in StringsRu`() {
        val placeholder = "TEST"
        with(StringsRu) {
            assertNonBlank("missingPackBadge",      missingPackBadge(placeholder))
            assertNonBlank("asrNotClaimedHint",     asrNotClaimedHint(placeholder))
            assertNonBlank("listeningHint",         listeningHint(placeholder))
            assertNonBlank("readyToListenHint",     readyToListenHint(placeholder))
            assertNonBlank("translationPendingCloud", translationPendingCloud(placeholder))
            assertNonBlank("translationPendingLocal", translationPendingLocal(placeholder))
            assertNonBlank("missingPackHint",       missingPackHint(placeholder))
            assertNonBlank("demoRecognizeButton",   demoRecognizeButton(placeholder))
            assertNonBlank("pairSupportTitle",      pairSupportTitle(placeholder))
            assertNonBlank("qualityBadge",          qualityBadge(placeholder))
            assertNonBlank("speedBadge",            speedBadge(placeholder))
            assertNonBlank("downloadingFile",       downloadingFile(placeholder))
            assertNonBlank("downloadFailedLine",    downloadFailedLine(placeholder))
            assertNonBlank("disabledMissing",       disabledMissing(placeholder))
            assertNonBlank("mtEngineUnavailable",   mtEngineUnavailable(placeholder))
            assertNonBlank("mtModelNotInstalledReason", mtModelNotInstalledReason(placeholder))
            assertNonBlank("dialogueSpeakingLabel", dialogueSpeakingLabel(placeholder))
            assertNonBlank("engineBadgeOnDevice",   engineBadgeOnDevice(placeholder))
        }
    }

    @Test
    fun `AppLanguage fromSystem returns RU for Russian locale`() {
        val ruLocale = java.util.Locale("ru")
        assert(AppLanguage.fromSystem(ruLocale) == AppLanguage.RU) {
            "Expected RU for locale 'ru', got ${AppLanguage.fromSystem(ruLocale)}"
        }
    }

    @Test
    fun `AppLanguage fromSystem returns EN for non-Russian locale`() {
        val enLocale = java.util.Locale("en")
        assert(AppLanguage.fromSystem(enLocale) == AppLanguage.EN) {
            "Expected EN for locale 'en', got ${AppLanguage.fromSystem(enLocale)}"
        }
    }

    @Test
    fun `AppLanguage fromCode round-trips`() {
        AppLanguage.entries.forEach { lang ->
            assert(AppLanguage.fromCode(lang.code) == lang) {
                "fromCode(${lang.code}) should return $lang"
            }
        }
    }

    @Test
    fun `stringsFor returns correct implementation`() {
        assert(stringsFor(AppLanguage.RU) === StringsRu)
        assert(stringsFor(AppLanguage.EN) === StringsEn)
    }

    // ---- у известных id облачных провайдеров есть непустой текст на обоих языках --------------

    @Test
    fun `known cloud provider ids have non-blank copy in both languages`() {
        val knownIds = listOf(
            "gemini-flash-mt",
            "cloud-stt-recognition-fallback",
            "gemini-live-assistant",
            "gemini-batch-audio-enrichment",
        )
        knownIds.forEach { id ->
            // Если id ещё не зарегистрирован (вернёт null) — ничего страшного, но если ЗАрегистрирован,
            // текст не должен быть пустым.
            StringsRu.cloudProviderTitle(id)?.let { assertNonBlank("RU title[$id]", it) }
            StringsEn.cloudProviderTitle(id)?.let { assertNonBlank("EN title[$id]", it) }
        }
    }

    // ---- хелпер ---------------------------------------------------------------------------------

    private fun assertNonBlank(name: String, value: String) {
        assertFalse("$name is blank", value.isBlank())
    }
}
