package ee.schimke.composeai.uibuilder.editor

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class UiBuilderBrandChromeTest {
  @Test
  fun onAccentTakesWhicheverContrastsMore() {
    // Home Assistant's blue: black is ~8:1 on it, white only ~2.6:1.
    assertEquals(Color.Black, onAccent(Color(0xFF03A9F4)))
    assertEquals(Color.Black, onAccent(Color(0xFFFFEB3B)))
    assertEquals(Color.White, onAccent(Color(0xFF1A237E)))
    assertEquals(Color.White, onAccent(Color(0xFF000000)))
    assertEquals(Color.Black, onAccent(Color(0xFFFFFFFF)))
  }
}
