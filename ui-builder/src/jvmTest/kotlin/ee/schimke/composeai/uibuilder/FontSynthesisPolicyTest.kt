package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.FontSynthesis
import ee.schimke.composeai.uibuilder.canvas.withoutWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/** `wght` turns weight synthesis off and leaves style synthesis as the text's style had it. */
class FontSynthesisPolicyTest {
  @Test
  fun `style synthesis survives where the style allowed it and stays off where it did not`() {
    assertEquals(FontSynthesis.Style, null.withoutWeight())
    assertEquals(FontSynthesis.Style, FontSynthesis.All.withoutWeight())
    assertEquals(FontSynthesis.Style, FontSynthesis.Style.withoutWeight())
    assertEquals(FontSynthesis.None, FontSynthesis.Weight.withoutWeight())
    assertEquals(FontSynthesis.None, FontSynthesis.None.withoutWeight())
  }
}
