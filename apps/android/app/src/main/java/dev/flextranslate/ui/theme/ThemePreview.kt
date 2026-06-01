package dev.flextranslate.ui.theme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// Compile-time proof that the Compose toolchain + FlexTheme resolve correctly.
// Intentionally minimal: real screens arrive in WS1.
@Preview(showBackground = true)
@Composable
fun FlexThemePreview() {
    FlexTheme {
        Surface {
            Text("Flex Translate")
        }
    }
}
