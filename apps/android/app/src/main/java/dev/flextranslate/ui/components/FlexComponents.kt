package dev.flextranslate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.flextranslate.ui.theme.SemanticAmber
import dev.flextranslate.ui.theme.SemanticGreen
import dev.flextranslate.ui.theme.SemanticRed
import dev.flextranslate.ui.theme.SurfaceElevated

// Семантический статус для бейджей и строк состояния. Ложится на палитру сложности aquacard.
enum class BadgeTone { ACCENT, GREEN, AMBER, RED, NEUTRAL }

/**
 * Бейдж-таблетка — тонированный фон (tone @ 16% alpha) + текст в цвет tone. Скругление 5dp,
 * моноширинный шрифт опционально (для данных вроде «RU → EN»). Глубина только за счёт тона
 * поверхности, без тени.
 */
@Composable
fun Badge(
    text: String,
    tone: BadgeTone = BadgeTone.ACCENT,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color = tone.color()
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(5.dp),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/**
 * Слоистая панель поверхности (Bg -> Surface -> SurfaceElevated). Заголовок опционален.
 * Без elevation/тени — глубину даёт только шаг цвета поверхности.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    radius: Int = 12,
    container: Color = SurfaceElevated,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(radius.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/**
 * Моноширинная строка ключ/значение для диагностики и данных. Значение рисуется в
 * FontFamily.Monospace согласно дизайн-токенам. Для недоступных значений используй «—» / «pending».
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    valueTone: Color? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueTone ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Второстепенный поясняющий текст (честные раскрытия, подсказки). */
@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BadgeTone.color(): Color = when (this) {
    BadgeTone.ACCENT -> MaterialTheme.colorScheme.primary
    BadgeTone.GREEN -> SemanticGreen
    BadgeTone.AMBER -> SemanticAmber
    BadgeTone.RED -> SemanticRed
    BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}
