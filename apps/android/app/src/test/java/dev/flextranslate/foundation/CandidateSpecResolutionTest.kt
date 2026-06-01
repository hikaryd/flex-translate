package dev.flextranslate.foundation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the registry↔spec contract: every candidate the UI offers as installable/downloadable must
 * resolve to a concrete on-device spec. Without this, a candidate row can advertise a model that the
 * runtime can never locate or install (the dead `en-zipformer-20m-low-tier-2023-02-17` ASR pack that
 * the WS0–WS4 review flagged). A registry entry that drifts away from its spec now fails here instead
 * of silently shipping an un-installable picker row.
 */
class CandidateSpecResolutionTest {

    @Test
    fun `every ASR candidate id resolves to a concrete spec`() {
        val unresolved = AsrCandidateRegistry.candidates.filter { candidate ->
            AsrModelSpecs.forCandidate(candidate) == null
        }
        assertTrue(
            "ASR candidates with no AsrModelSpec (dead/un-installable rows): " +
                unresolved.joinToString { it.id },
            unresolved.isEmpty(),
        )
    }

    @Test
    fun `every downloadable MT candidate modelId resolves to a concrete spec`() {
        // Cloud / optional candidates carry modelId == null on purpose (no on-device pack); only the
        // ones that DO claim an on-device modelId must resolve to a spec.
        val unresolved = MtCandidateRegistry.candidates
            .mapNotNull { it.modelId }
            .filter { modelId -> MtModelSpecs.forModelId(modelId) == null }
        assertTrue(
            "MT candidates with a modelId but no MtModelSpec: ${unresolved.joinToString()}",
            unresolved.isEmpty(),
        )
    }

    @Test
    fun `the previously dead en 20M low-tier pack now resolves`() {
        val candidate = AsrCandidateRegistry.candidates
            .first { it.id == "en-zipformer-20m-low-tier-2023-02-17" }
        assertNotNull(
            "the low-tier EN device-lab candidate must be installable, not a dead row",
            AsrModelSpecs.forCandidate(candidate),
        )
    }
}
