package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorClipboard
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A remote-m3 design takes nothing from another catalog, by either of the ways one could arrive.
 *
 * The clipboard outlives the design it was copied from, and a host chooses which catalog a design
 * is opened with. Either could put m3 components — which remote-m3's runtime cannot draw and its
 * export cannot write — into a remote-m3 widget.
 */
class CrossCatalogPasteTest {
  private fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()

  private val remote = CapabilityCatalogParser.parse(resource("/remote-m3-capabilities-v1.json"))
  private val m3 = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(remote)
  private val widget =
    UiBuilderNewDesignSeed.document(
      designId = "widget",
      catalogSystemId = "remote-m3",
      templateId = "wear-widget-small",
      catalogRevision = "test",
      nativeRuntimeId = "test",
      fixture =
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
    )

  /** A Column (remote-m3 has one) holding a Button (it does not), as copied out of an m3 screen. */
  private val m3Clipboard =
    EditorClipboard(
      rootNodeIds = listOf("copied-column"),
      nodes =
        mapOf(
          "copied-column" to
            UiBuilderNode(
              "copied-column",
              "layout/column",
              slots = mapOf("children" to listOf("copied-button")),
            ),
          "copied-button" to UiBuilderNode("copied-button", "m3/button"),
        ),
    )

  @Test
  fun `a subtree holding another catalog's component is not offered for paste`() {
    assertTrue("layout/column" in remote.componentsById && "m3/button" !in remote.componentsById)
    val state =
      reducer.initial(widget, selectedNodeId = widget.roots.single()).copy(clipboard = m3Clipboard)
    // Before, the root alone was checked: Paste was offered, and refused only once pressed.
    assertFalse(reducer.canPaste(state))
    val after = reducer.reduce(state, UiBuilderEditorEvent.Paste)
    assertTrue(after.document.nodes.values.none { it.componentId == "m3/button" })
    assertEquals(widget.nodes.keys, after.document.nodes.keys)
  }

  @Test
  fun `a subtree of this catalog's components still pastes`() {
    val own =
      m3Clipboard.copy(
        nodes = m3Clipboard.nodes + ("copied-button" to UiBuilderNode("copied-button", "m3/text"))
      )
    val state =
      reducer.initial(widget, selectedNodeId = widget.roots.single()).copy(clipboard = own)
    assertTrue(reducer.canPaste(state))
  }

  @Test
  fun `a design is not opened against a catalog it is not pinned to`() {
    assertNull(catalogPinMismatch(widget, remote))
    val refusal = assertNotNull(catalogPinMismatch(widget, m3))
    assertTrue("remote-m3" in refusal && "m3-catalog" in refusal, refusal)
  }
}
