package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A layer is named after what its node says, and `required` alone does not find that.
 *
 * A component can require a string it needs in order to work rather than one a person would
 * recognise it by. In this catalog five of the six required free-text properties are exactly that,
 * so the panel offered an asset key, a scroll key and a base64 blob as layer names.
 */
class EditorLayerNameTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun row(nodeId: String) =
    assertNotNull(reducer.treeRows(document).firstOrNull { it.nodeId == nodeId })

  @Test
  fun `a lazy container is not named after its scroll state key`() {
    val grid = row("discover-grid")

    assertEquals("Adaptive lazy vertical grid", grid.label)
    assertTrue(!grid.named, "a scroll key is plumbing, not a name")
  }

  @Test
  fun `an image falls through its asset key to its description`() {
    // `asset/image` requires `assetKey` and declares an optional `contentDescription`. The key is
    // what makes the image work; the description is what a person would call it.
    val image =
      assertNotNull(reducer.treeRows(document).firstOrNull { it.componentId == "asset/image" })
    val assetKey =
      (document.nodes.getValue(image.nodeId).properties["assetKey"]
          as? kotlinx.serialization.json.JsonObject)
        ?.get("value")
    assertNotNull(assetKey, "the fixture image should carry an asset key")
    assertTrue(image.label != assetKey.toString().trim('"'), "named after the asset key")
  }

  @Test
  fun `a text node is still named after its text`() {
    // The rule that had to survive the fix: `m3/text.text` is the one required free-text property
    // in this catalog that really is content.
    assertEquals("Search for a podcast", row("search-placeholder").label)
    assertTrue(row("search-placeholder").named)
  }

  @Test
  fun `no layer is named after a key, an id or a payload`() {
    val offenders =
      reducer.treeRows(document).filter { row ->
        val node = document.nodes.getValue(row.nodeId)
        catalog.componentsById[node.componentId]
          ?.properties
          .orEmpty()
          .filter { p ->
            listOf("Key", "Id", "Base64").any { p.name.endsWith(it, ignoreCase = true) }
          }
          .any { p ->
            (node.properties[p.name] as? kotlinx.serialization.json.JsonObject)
              ?.get("value")
              ?.toString()
              ?.trim('"') == row.label
          }
      }

    assertEquals(emptyList(), offenders.map { it.nodeId to it.label })
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
