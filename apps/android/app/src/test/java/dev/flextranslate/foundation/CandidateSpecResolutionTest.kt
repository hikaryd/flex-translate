package dev.flextranslate.foundation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторожит контракт registry↔spec: каждый кандидат, который UI предлагает как устанавливаемый/
 * скачиваемый, обязан разрешаться в конкретный spec на устройстве. Без этого строка кандидата может
 * рекламировать модель, которую рантайм никогда не найдёт и не установит (мёртвый ASR-пакет
 * `en-zipformer-20m-low-tier-2023-02-17`, на который указал ревью WS0–WS4). Запись в registry,
 * отвязавшаяся от своего spec, теперь падает здесь, а не тихо уезжает в неустанавливаемую строку.
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
        // У облачных / опциональных кандидатов modelId == null намеренно (нет пакета на устройстве);
        // в spec обязаны разрешаться только те, кто РЕАЛЬНО заявляет modelId на устройстве.
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
