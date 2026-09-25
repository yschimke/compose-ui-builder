package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.uibuilder.editor.EditorThemeSettings
import ee.schimke.composeai.uibuilder.editor.inspectorColorScheme
import ee.schimke.composeai.uibuilder.editor.parseHexColor
import ee.schimke.composeai.uibuilder.editor.swatchColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InspectorVisualsTest {
  @Test
  fun `hex colours parse with or without alpha`() {
    assertEquals(Color(0xFF336699), parseHexColor("#336699"))
    assertEquals(Color(0x80336699), parseHexColor("#80336699"))
    assertEquals(Color(0xFF336699), parseHexColor(" #ff336699 "))
    assertNull(parseHexColor("336699"))
    assertNull(parseHexColor("#12345"))
    assertNull(parseHexColor("#nothex"))
  }

  @Test
  fun `a theme role resolves against the design's own theme`() {
    val scheme = inspectorColorScheme(EditorThemeSettings(primaryColor = "#FF1A73E8"))
    assertEquals(Color(0xFF1A73E8), swatchColor("primary", scheme))
    assertEquals(Color(0xFF111318), swatchColor("background", scheme))
    assertEquals(Color(0xFFD93025), swatchColor("#FFD93025", scheme))
    assertNull(swatchColor("not-a-colour", scheme))
  }
}
