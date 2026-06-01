package dev.flextranslate.ui.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Asserts that [StringsRu] and [StringsEn] provide the same complete key set and that no
 * English string is blank. This prevents a missing-translation regression where a new key is
 * added to the interface (and one implementation) but forgotten in the other.
 *
 * The test uses reflection to iterate every declared property and zero-argument method on
 * [Strings] so it stays self-updating: adding a key to the interface without filling in both
 * implementations causes a compile-time failure (interface contract) — this test then additionally
 * checks the English values are non-blank at runtime.
 */
class StringsParityTest {

    // ---- val properties -------------------------------------------------------------------------

    @Test
    fun `all Strings val properties are non-blank in StringsEn`() {
        val ruProps = StringsRu::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
        val enProps = StringsEn::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }

        // Same set of getter names (compile already guarantees this for interface vals, but
        // a runtime check gives a human-readable diff when something goes wrong).
        val ruNames = ruProps.map { it.name }.toSet()
        val enNames = enProps.map { it.name }.toSet()
        val missingInEn = ruNames - enNames
        val missingInRu = enNames - ruNames
        assert(missingInEn.isEmpty()) { "StringsEn is missing getters: $missingInEn" }
        assert(missingInRu.isEmpty()) { "StringsRu is missing getters: $missingInRu" }

        // Every EN getter must return a non-blank String.
        enProps.forEach { method ->
            val value = method.invoke(StringsEn)
            assertNotNull("StringsEn.${method.name} returned null", value)
            assertFalse(
                "StringsEn.${method.name} is blank — add the English translation",
                (value as String).isBlank(),
            )
        }
    }

    // ---- fun functions with parameters ----------------------------------------------------------

    @Test
    fun `all Strings parameterised functions produce non-blank output in StringsEn`() {
        // Invoke each single-String-param function with a representative placeholder argument so we
        // can verify the English template itself isn't blank or accidentally empty.
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

    // ---- known registered cloud provider ids have non-blank copy in both languages --------------

    @Test
    fun `known cloud provider ids have non-blank copy in both languages`() {
        val knownIds = listOf(
            "gemini-flash-mt",
            "cloud-stt-recognition-fallback",
            "gemini-live-assistant",
            "gemini-batch-audio-enrichment",
        )
        knownIds.forEach { id ->
            // It's OK if the id isn't registered yet (returns null) — but if it IS registered it
            // must not be blank.
            StringsRu.cloudProviderTitle(id)?.let { assertNonBlank("RU title[$id]", it) }
            StringsEn.cloudProviderTitle(id)?.let { assertNonBlank("EN title[$id]", it) }
        }
    }

    // ---- helper ---------------------------------------------------------------------------------

    private fun assertNonBlank(name: String, value: String) {
        assertFalse("$name is blank", value.isBlank())
    }
}
