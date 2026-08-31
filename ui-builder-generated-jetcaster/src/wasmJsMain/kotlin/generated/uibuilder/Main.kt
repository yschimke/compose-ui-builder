@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package generated.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

private val JetcasterColors =
  darkColorScheme(
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainer = Color(0xFF1D1F25),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B),
  )

fun main() {
  ComposeViewport(viewportContainerId = "composeApp") {
    MaterialTheme(colorScheme = JetcasterColors) {
      Surface(Modifier.fillMaxSize()) { JetcasterDiscoverExpandedSupportingPane() }
    }
    LaunchedEffect(Unit) {
      document.documentElement?.setAttribute("data-ui-builder-generated-jetcaster-ready", "true")
    }
  }
}
