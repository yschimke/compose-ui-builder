package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.SyntheticCatalogs
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A catalog in a declared document format is handed the builder's vocabulary like every published
 * catalog, and keeps it, so a stored design holding a drawing still validates and opens. Its insert
 * panel offers only what the format writes: Kobento component JSON refuses a drawing.
 */
class DocumentFormatPaletteTest {
  private val template = SyntheticCatalogs.remote.components.first()

  private fun catalog(statusSemantics: String) =
    SyntheticCatalogs.remote.copy(
      statusSemantics = Json.parseToJsonElement(statusSemantics).jsonObject,
      components =
        listOf("kobento/button", "layout/column", "draw/rect", "asset/image").map {
          template.copy(componentId = it)
        },
    )

  @Test
  fun `a declared format's palette offers what it writes, and the catalog keeps the rest`() {
    val kobento = catalog("""{"kobento":{}}""")
    assertEquals(setOf("draw/rect", "asset/image"), kobento.paletteHiddenComponentIds)
    assertEquals(
      listOf("kobento/button", "layout/column"),
      kobento.paletteComponents.map { it.componentId },
    )
    assertTrue("draw/rect" in kobento.componentsById)
  }

  @Test
  fun `a declared format's thumbnail is centred by its frame, with values its params accept`() {
    val box = SyntheticCatalogs.remote.componentsById.getValue("layout/box")
    val gauge =
      template.copy(
        componentId = "kobento/gauge",
        modifierCapabilities = listOf("align"),
        properties =
          listOf(
            PropertyCapability(name = "value", jsonType = JsonPrimitive("number"), required = true),
            PropertyCapability(name = "labels", jsonType = JsonPrimitive("array"), required = true),
            PropertyCapability(name = "max", jsonType = JsonPrimitive("number"), required = true),
          ),
      )
    val kobento =
      SyntheticCatalogs.remote.copy(
        statusSemantics = Json.parseToJsonElement("""{"kobento":{}}""").jsonObject,
        components = listOf(box, gauge),
      )
    val document =
      assertNotNull(
        UiBuilderEditorReducer(kobento, actorId = "a", clientId = "c")
          .previewDocument("kobento/gauge"),
      )
    val (frame, inserted) = document.nodes.values.partition { it.componentId == "layout/box" }
    // The frame centres the call, which takes no modifiers in this format.
    assertEquals(
      JsonPrimitive("center"),
      frame.single().properties["contentAlignment"]?.jsonObject?.get("value"),
    )
    val node = inserted.single()
    assertEquals(emptyList(), node.modifiers.toList())
    // A required number starts at zero and a list empty, as each param accepts.
    assertEquals(JsonPrimitive(0), node.properties.getValue("value").jsonObject["value"])
    assertEquals(JsonPrimitive(0), node.properties.getValue("max").jsonObject["value"])
    assertEquals(
      Json.parseToJsonElement("""{"type":"list","values":[]}"""),
      node.properties.getValue("labels"),
    )
  }

  @Test
  fun `a catalog with no document format hides nothing for one`() {
    val compose = catalog("""{"platform":"mobile"}""")
    assertTrue(compose.paletteComponents.any { it.componentId == "draw/rect" })
  }
}
