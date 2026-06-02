package dev.flextranslate.ui.theme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// Доказательство на этапе сборки, что тулчейн Compose + FlexTheme собираются как надо.
// Намеренно минимально: настоящие экраны появятся в WS1.
@Preview(showBackground = true)
@Composable
fun FlexThemePreview() {
    FlexTheme {
        Surface {
            Text("Flex Translate")
        }
    }
}
