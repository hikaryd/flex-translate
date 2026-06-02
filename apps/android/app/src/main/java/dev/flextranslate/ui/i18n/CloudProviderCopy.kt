package dev.flextranslate.ui.i18n

/**
 * Локализованные тексты для одной карточки облачного провайдера: заголовок, короткая строка роли и
 * полный абзац про раскрытие данных. Берутся из активного каталога [Strings] по id провайдера —
 * так экран Облако рисуется на RU/EN заодно со всем остальным интерфейсом.
 */
data class CloudProviderCopy(
    val title: String,
    val role: String,
    val disclosure: String,
)
