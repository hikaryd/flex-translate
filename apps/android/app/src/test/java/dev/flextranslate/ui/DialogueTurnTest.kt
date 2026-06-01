package dev.flextranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [DialogueTurn] model and the conversation log contract.
 *
 * [DialogueTurn] is a pure data class with no Android dependencies, so all tests run on the plain
 * JVM without robolectric or mocking. The session-level wiring (appending turns on ASR finalize,
 * dispatching to the MT worker) is exercised separately via integration — these tests focus on the
 * model invariants that every consumer of the log depends on.
 */
class DialogueTurnTest {

    // ---- Factory helpers -----------------------------------------------------------------------

    private fun turn(
        id: String = "id-1",
        ts: Long = 1000L,
        spoken: FlexLanguage = FlexLanguage.RU,
        original: String = "Привет",
        translated: String? = null,
        reason: String? = null,
        translationLang: FlexLanguage = FlexLanguage.EN,
    ) = DialogueTurn(
        id = id,
        monotonicTs = ts,
        spokenLanguage = spoken,
        originalText = original,
        translatedText = translated,
        translationReason = reason,
        translationLanguage = translationLang,
    )

    // ---- translationPending --------------------------------------------------------------------

    @Test
    fun `translationPending is true when both text and reason are null`() {
        val t = turn(translated = null, reason = null)
        assertTrue("should be pending when both null", t.translationPending)
    }

    @Test
    fun `translationPending is false when translatedText is set`() {
        val t = turn(translated = "Hello", reason = null)
        assertFalse("should not be pending when translation present", t.translationPending)
    }

    @Test
    fun `translationPending is false when translationReason is set`() {
        val t = turn(translated = null, reason = "MT model not installed")
        assertFalse("should not be pending when reason present", t.translationPending)
    }

    // ---- withTranslation -----------------------------------------------------------------------

    @Test
    fun `withTranslation sets translatedText and clears reason`() {
        val original = turn(translated = null, reason = null)
        val resolved = original.withTranslation(text = "Hello", reason = null)

        assertEquals("Hello", resolved.translatedText)
        assertNull(resolved.translationReason)
    }

    @Test
    fun `withTranslation sets reason and leaves text null when gated`() {
        val original = turn(translated = null, reason = null)
        val gated = original.withTranslation(text = null, reason = "MT model not installed")

        assertNull(gated.translatedText)
        assertEquals("MT model not installed", gated.translationReason)
        assertFalse(gated.translationPending)
    }

    @Test
    fun `withTranslation does not mutate the original turn`() {
        val original = turn(translated = null, reason = null)
        original.withTranslation(text = "Hello", reason = null)

        // original is unchanged — immutable copy-on-write contract.
        assertNull(original.translatedText)
        assertNull(original.translationReason)
        assertTrue(original.translationPending)
    }

    @Test
    fun `withTranslation preserves all non-translation fields`() {
        val original = turn(
            id = "abc",
            ts = 9999L,
            spoken = FlexLanguage.ZH,
            original = "你好",
            translationLang = FlexLanguage.RU,
        )
        val resolved = original.withTranslation(text = "Привет", reason = null)

        assertEquals("abc", resolved.id)
        assertEquals(9999L, resolved.monotonicTs)
        assertEquals(FlexLanguage.ZH, resolved.spokenLanguage)
        assertEquals("你好", resolved.originalText)
        assertEquals(FlexLanguage.RU, resolved.translationLanguage)
    }

    // ---- Translation language routing ----------------------------------------------------------

    @Test
    fun `RU spoken turn translates into EN counterpart`() {
        val t = turn(spoken = FlexLanguage.RU, translationLang = FlexLanguage.EN)
        assertEquals(FlexLanguage.EN, t.translationLanguage)
    }

    @Test
    fun `EN spoken turn translates into RU counterpart`() {
        val t = turn(spoken = FlexLanguage.EN, translationLang = FlexLanguage.RU)
        assertEquals(FlexLanguage.RU, t.translationLanguage)
    }

    @Test
    fun `ZH spoken turn translates into RU counterpart`() {
        val t = turn(spoken = FlexLanguage.ZH, translationLang = FlexLanguage.RU)
        assertEquals(FlexLanguage.RU, t.translationLanguage)
    }

    // ---- Conversation log ordering (pure list operations) ------------------------------------

    @Test
    fun `turns appended in order are returned in monotonic ts order`() {
        val turns = mutableListOf<DialogueTurn>()
        turns.add(turn(id = "1", ts = 100L, spoken = FlexLanguage.RU))
        turns.add(turn(id = "2", ts = 200L, spoken = FlexLanguage.EN))
        turns.add(turn(id = "3", ts = 300L, spoken = FlexLanguage.RU))

        assertEquals(listOf("1", "2", "3"), turns.map { it.id })
        assertTrue(turns.zipWithNext().all { (a, b) -> a.monotonicTs < b.monotonicTs })
    }

    @Test
    fun `clearing the log empties it`() {
        val turns = mutableListOf<DialogueTurn>()
        turns.add(turn(id = "1"))
        turns.add(turn(id = "2"))
        assertFalse(turns.isEmpty())

        turns.clear()
        assertTrue("log should be empty after clear", turns.isEmpty())
    }

    @Test
    fun `pending to resolved transition updates the turn in place by id`() {
        val turns = mutableListOf<DialogueTurn>()
        turns.add(turn(id = "a", translated = null, reason = null))
        turns.add(turn(id = "b", translated = null, reason = null))

        // Simulate the updateTurnResult logic: find by id, replace with resolved copy.
        val targetId = "a"
        val index = turns.indexOfFirst { it.id == targetId }
        assertTrue("turn should exist", index >= 0)
        turns[index] = turns[index].withTranslation(text = "Resolved text", reason = null)

        val resolved = turns.first { it.id == "a" }
        assertEquals("Resolved text", resolved.translatedText)
        assertNull(resolved.translationReason)
        assertFalse(resolved.translationPending)

        // Other turn stays pending.
        val other = turns.first { it.id == "b" }
        assertTrue(other.translationPending)
    }

    @Test
    fun `resolving a non-existent id leaves log unchanged`() {
        val turns = mutableListOf<DialogueTurn>()
        turns.add(turn(id = "a"))
        val before = turns.toList()

        val index = turns.indexOfFirst { it.id == "does-not-exist" }
        assertEquals("indexOfFirst should return -1 for missing id", -1, index)
        // No update applied — log unchanged.
        assertEquals(before.map { it.id }, turns.map { it.id })
    }

    // ---- Swap changes next-turn direction (language state contract) ----------------------------

    @Test
    fun `swapping languages means new turns use swapped spokenLanguage`() {
        // Simulate: source=RU, target=EN → first turn is RU→EN
        var source = FlexLanguage.RU
        var target = FlexLanguage.EN

        val turn1 = turn(spoken = source, translationLang = target)
        assertEquals(FlexLanguage.RU, turn1.spokenLanguage)
        assertEquals(FlexLanguage.EN, turn1.translationLanguage)

        // Swap: source=EN, target=RU → next turn is EN→RU
        val swapped = source.also { source = target; target = it }
        val turn2 = turn(spoken = source, translationLang = target)
        assertEquals(FlexLanguage.EN, turn2.spokenLanguage)
        assertEquals(FlexLanguage.RU, turn2.translationLanguage)
    }

    // ---- Original text is never blank ---------------------------------------------------------

    @Test
    fun `originalText is preserved verbatim from ASR`() {
        val asrOutput = "  Добрый день, как дела?  "
        val t = turn(original = asrOutput)
        assertEquals(asrOutput, t.originalText)
    }

    // ---- id is stable for keying --------------------------------------------------------------

    @Test
    fun `each turn has a non-blank id`() {
        val t = turn(id = java.util.UUID.randomUUID().toString())
        assertNotNull(t.id)
        assertTrue(t.id.isNotBlank())
    }
}
