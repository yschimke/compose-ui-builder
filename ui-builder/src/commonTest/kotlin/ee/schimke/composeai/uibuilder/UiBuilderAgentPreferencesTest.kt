package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class UiBuilderAgentPreferencesTest {
  @Test
  fun `document instructions override general instructions and blank restores them`() {
    val general = UiBuilderAgentPreferences(generalInstructions = "Use the brand colors")
    assertEquals("Use the brand colors", general.instructions())
    assertEquals(
      "Keep this screen monochrome",
      general.copy(documentInstructions = "Keep this screen monochrome").instructions(),
    )
    assertEquals("Use the brand colors", general.copy(documentInstructions = "  ").instructions())
  }
}
