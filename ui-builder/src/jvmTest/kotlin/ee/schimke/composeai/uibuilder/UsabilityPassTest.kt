package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.catalogFormFactorLabel
import ee.schimke.composeai.uibuilder.editor.firstLineAfterImports
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The small answers the usability pass (#356, #358) added, pinned. */
class UsabilityPassTest {
  @Test
  fun `the code pane opens at the code, past the package and imports`() {
    val source =
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.material3.Text

      @Composable
      fun Hello() = Text("Hi")
      """
        .trimIndent()
    assertEquals(5, firstLineAfterImports(source))
  }

  @Test
  fun `source with no header is shown from its first line`() {
    assertEquals(0, firstLineAfterImports("@Composable\nfun Hello() {}"))
    assertEquals(0, firstLineAfterImports("import a.B\n\n"))
  }

  @Test
  fun `a design names its form factor the way the new design form does`() {
    val widget =
      AdaptiveWearWidget.newDocument(
        "adaptive",
        JsonObject(mapOf("systemId" to JsonPrimitive("remote-m3"))),
        JsonObject(emptyMap()),
      )
    assertEquals("Wear widget", catalogFormFactorLabel(widget))
    assertNull(
      catalogFormFactorLabel(
        widget.copy(catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("unknown"))))
      )
    )
  }
}
