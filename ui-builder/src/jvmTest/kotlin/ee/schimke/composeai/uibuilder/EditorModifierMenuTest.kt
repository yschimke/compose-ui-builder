package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout, from the menu that opens on the thing being laid out.
 *
 * The rows are the catalog's answer rather than a fixed list: a component that does not declare
 * `fillMaxSize` is never offered it, because the reducer would refuse the write and a menu row that
 * cannot be pressed twice is a row that lies.
 */
class EditorModifierMenuTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `only the modifiers the component declares are offered`() {
    val box = reducer.modifierToggles(reducer.initial(document, selectedNodeId = "main-background"))
    assertEquals(
      listOf(
        "fillMaxSize",
        "fillMaxWidth",
        "fillMaxHeight",
        "matchParentSize",
        "verticalScroll",
        "horizontalScroll",
        "padding",
      ),
      box.map { it.type },
    )

    // A text declares two of them and not the other two, so its menu is shorter by exactly those:
    // filling a parent and matching its size are container verbs and are never offered here.
    val text =
      reducer.modifierToggles(reducer.initial(document, selectedNodeId = "detail-podcast-title"))
    assertEquals(listOf("fillMaxWidth", "padding"), text.map { it.type })
  }

  @Test
  fun `a toggle says what the node already carries`() {
    val state = reducer.initial(document, selectedNodeId = "main-background")
    val before = reducer.modifierToggles(state).single { it.type == "fillMaxSize" }
    assertTrue(before.applied, "the fixture's background fills its parent")

    val cleared =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleModifier("main-background", "fillMaxSize"))
    assertFalse(
      cleared.document.nodes.getValue("main-background").modifiers.any {
        it.jsonObject["type"]?.jsonPrimitive?.content == "fillMaxSize"
      }
    )
    assertFalse(reducer.modifierToggles(cleared).single { it.type == "fillMaxSize" }.applied)
  }

  @Test
  fun `a modifier the node lacks is appended, and appears in the chain once`() {
    val state = reducer.initial(document, selectedNodeId = "main-background")
    val padded =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleModifier("main-background", "padding"))

    val chain = padded.document.nodes.getValue("main-background").modifiers
    assertEquals(
      1,
      chain.count { it.jsonObject["type"]?.jsonPrimitive?.content == "padding" },
    )
    assertEquals(
      "padding",
      chain.last().jsonObject.getValue("type").jsonPrimitive.content,
      "an added modifier goes on the end of the chain",
    )
    assertEquals(
      16,
      chain.last().jsonObject.getValue("topDp").jsonPrimitive.content.toInt(),
    )
  }

  @Test
  fun `a modifier the catalog does not declare is refused with a located diagnostic`() {
    val state = reducer.initial(document, selectedNodeId = "detail-podcast-title")
    val refused =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.ToggleModifier("detail-podcast-title", "fillMaxSize"),
      )

    val outcome = refused.lastOutcome
    assertTrue(outcome is CommandOutcome.Rejected, outcome.toString())
    assertEquals(RejectionCode.INVALID_PROPERTY, outcome.code)
    assertEquals("detail-podcast-title", outcome.nodeId)
    assertEquals("modifiers", outcome.field)
    assertEquals(document, refused.document)
  }

  @Test
  fun `the numbers inside a chain are listed and edited one at a time`() {
    val padded =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-background"),
        UiBuilderEditorEvent.ToggleModifier("main-background", "padding"),
      )

    assertEquals(
      listOf("Start" to "16", "Top" to "16", "End" to "16", "Bottom" to "16"),
      reducer.modifierFields(padded).filter { it.type == "padding" }.map { it.label to it.value },
    )

    val edited =
      reducer.reduce(
        padded,
        UiBuilderEditorEvent.SetModifierValue("main-background", "padding", "topDp", "24"),
      )
    // One number moves and the rest of the chain is carried through: the wire writes the chain
    // whole, so everything not being edited has to survive being rewritten.
    assertEquals(
      listOf("16", "24", "16", "16"),
      reducer.modifierFields(edited).filter { it.type == "padding" }.map { it.value },
    )
    assertEquals(
      padded.document.nodes.getValue("main-background").modifiers.size,
      edited.document.nodes.getValue("main-background").modifiers.size,
    )
  }

  @Test
  fun `a draft that is not a number is refused rather than written`() {
    val padded =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "main-background"),
        UiBuilderEditorEvent.ToggleModifier("main-background", "padding"),
      )
    val refused =
      reducer.reduce(
        padded,
        UiBuilderEditorEvent.SetModifierValue("main-background", "padding", "topDp", "a bit"),
      )

    val outcome = refused.lastOutcome
    assertTrue(outcome is CommandOutcome.Rejected, outcome.toString())
    assertEquals("modifiers", outcome.field)
    assertEquals(padded.document, refused.document)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
