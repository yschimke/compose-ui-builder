package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a copy may and may not inherit: concatenated ids can collide, and identity properties must
 * not be copied.
 */
class EditorCopyIdentityTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun stringProperty(state: UiBuilderEditorState, nodeId: String, name: String) =
    state.document.nodes
      .getValue(nodeId)
      .properties[name]
      ?.jsonObject
      ?.get("value")
      ?.jsonPrimitive
      ?.content

  @Test
  fun `a copied lazy item does not share its neighbour's key`() {
    // `stableKey` becomes the lazy item's `key(…)` in the exported Compose. Two siblings in one
    // lazy slot under the same key is a runtime failure, not a cosmetic clash.
    val duplicated =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-episode-card"),
        UiBuilderEditorEvent.DuplicateSelected,
      )
    assertIs<CommandOutcome.Accepted>(duplicated.lastOutcome)

    val copyId = assertNotNull(duplicated.selectedNodeId)
    val original = stringProperty(duplicated, "main-episode-card", "stableKey")
    val copy = stringProperty(duplicated, copyId, "stableKey")

    assertEquals("episode-140", original)
    assertEquals(copyId, copy)
    assertTrue(original != copy)
  }

  @Test
  fun `content keys are not instance keys and are carried across`() {
    // `iconKey` and `assetKey` name catalog content every copy keeps; the rule is the exporter's
    // short list of what becomes a `key(…)`, not a name suffix.
    val duplicated =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-episode-card"),
        UiBuilderEditorEvent.DuplicateSelected,
      )
    assertIs<CommandOutcome.Accepted>(duplicated.lastOutcome)

    val sourceIcons =
      document.nodes.values
        .filter { it.componentId == "m3/icon" && it.id.startsWith("main-episode-") }
        .associate { it.id to stringProperty(duplicated, it.id, "iconKey") }
    assertTrue(sourceIcons.isNotEmpty(), "the card's subtree should carry icons")
    sourceIcons.forEach { (id, iconKey) ->
      val copyId = duplicated.document.nodes.keys.first { it.endsWith("-$id") && it != id }
      assertEquals(iconKey, stringProperty(duplicated, copyId, "iconKey"), copyId)
    }
  }

  @Test
  fun `every copied id is allocated rather than concatenated`() {
    // Ids are allocated against the document and everything the batch already took, so `x-y` plus
    // `y` under `x` can't yield one id twice (the reducer rejects duplicate batches).
    val duplicated =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-episode-card"),
        UiBuilderEditorEvent.DuplicateSelected,
      )
    assertIs<CommandOutcome.Accepted>(duplicated.lastOutcome)

    val ids = duplicated.document.nodes.keys
    assertEquals(ids.size, ids.distinct().size)
    assertEquals(document.nodes.size + subtreeSize("main-episode-card"), ids.size)
  }

  @Test
  fun `pasting the same subtree twice collides with neither the document nor itself`() {
    val copied =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-episode-card"),
        UiBuilderEditorEvent.CopySelected,
      )
    val once = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    assertIs<CommandOutcome.Accepted>(once.lastOutcome)
    val twice = reducer.reduce(once, UiBuilderEditorEvent.Paste)
    assertIs<CommandOutcome.Accepted>(twice.lastOutcome)

    val ids = twice.document.nodes.keys
    assertEquals(ids.size, ids.distinct().size)
    // Three cards now, and no two of them claim the same lazy key.
    val keys =
      twice.document.nodes.values
        .filter { it.componentId == "m3/card" }
        .mapNotNull { it.properties["stableKey"]?.jsonObject?.get("value")?.jsonPrimitive?.content }
    assertEquals(keys.size, keys.distinct().size, keys.toString())
  }

  private fun subtreeSize(nodeId: String): Int {
    var count = 1
    document.nodes.getValue(nodeId).slots.values.flatten().forEach { count += subtreeSize(it) }
    return count
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
