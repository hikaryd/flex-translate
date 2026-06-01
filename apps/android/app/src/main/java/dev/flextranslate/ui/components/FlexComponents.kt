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

// Semantic status used by badges/state lines. Maps to the aquacard difficulty palette.
enum class BadgeTone { ACCENT, GREEN, AMBER, RED, NEUTRAL }

/**
 * Pill badge — tinted background (tone @ 16% alpha) + tone-colored text. Radius 5dp,
 * monospace optional (for data like "RU → EN"). Depth via surface tint only, no shadow.
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
 * A layered surface panel (Bg -> Surface -> SurfaceElevated). Optional title.
 * No elevation/shadow — depth comes from the surface color step only.
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
 * Monospace key/value readout row for diagnostics + data. The value is rendered in
 * FontFamily.Monospace per the design tokens. Use "—" / "pending" for unavailable values.
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

/** Secondary explanatory text (honest disclosure copy, helper text). */
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
