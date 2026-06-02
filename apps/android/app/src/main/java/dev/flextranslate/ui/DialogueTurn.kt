package dev.flextranslate.ui

/**
 * Одна завершённая реплика в двустороннем диалоге.
 *
 * Неизменяемая, кроме [translatedText] и [translationReason] — они переходят из null/в ожидании
 * в реальное значение, когда MT-воркер отработает. Обновление состояния всегда даёт новую копию
 * через [withTranslation], мутировать на месте нельзя.
 *
 * @param id           стабильный id для ключей Compose (строка UUID).
 * @param monotonicTs  монотонная метка времени (мс) на момент завершения реплики — для сортировки.
 * @param spokenLanguage  [FlexLanguage], на котором говорил собеседник.
 * @param originalText    настоящий вывод ASR (ничего не выдумываем).
 * @param translatedText  реальный перевод, когда готов; null пока ждём или если заблокировано.
 * @param translationReason честная причина блокировки перевода; null если перевод удался или ещё
 *   не пытались (в ожидании).
 * @param translationLanguage [FlexLanguage], на котором выражен [translatedText]
 *   (язык собеседника на момент реплики).
 * @param mtEngineUsed человекочитаемое имя движка, выдавшего [translatedText], напр. "Gemini Flash"
 *   или id локальной модели. Null пока реплика в ожидании.
 */
data class DialogueTurn(
    val id: String,
    val monotonicTs: Long,
    val spokenLanguage: FlexLanguage,
    val originalText: String,
    val translatedText: String?,
    val translationReason: String?,
    val translationLanguage: FlexLanguage,
    val mtEngineUsed: String? = null,
) {
    /** True, пока MT-воркер ещё не закрыл перевод этой реплики. */
    val translationPending: Boolean
        get() = translatedText == null && translationReason == null

    /**
     * Новая копия реплики с проставленным результатом перевода.
     * Ровно одно из [text] и [reason] должно быть не-null (как в [TranslationResult]).
     * [engineLabel] — имя движка, выдавшего [text] (напр. "Gemini Flash" или id локальной модели);
     * null, если перевод заблокирован или упал.
     */
    fun withTranslation(text: String?, reason: String?, engineLabel: String? = null): DialogueTurn =
        copy(translatedText = text, translationReason = reason, mtEngineUsed = engineLabel)
}
