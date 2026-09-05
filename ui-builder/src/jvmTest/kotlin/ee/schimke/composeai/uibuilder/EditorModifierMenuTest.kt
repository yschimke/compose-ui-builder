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
    // filling a parent and matching its size are container verbs and are never offered here. The
    // two that follow are not the text's own: it sits in a column, and a column is what makes an
    // alignment across the cross axis and a weight along the main one mean anything.
    val text =
      reducer.modifierToggles(reducer.initial(document, selectedNodeId = "detail-podcast-title"))
    assertEquals(
      listOf("fillMaxWidth", "padding", "alignHorizontal", "weight"),
      text.map { it.type },
    )
  }

  @Test
  fun `the scoped modifiers are the parent's answer, not the child's`() {
    // `Modifier.align` and `Modifier.weight` are receivers a parent supplies. The catalog declares
    // them on every component because any component can be a child; which one is offered — and
    // whether any is — comes from what the node is a child of.
    fun scoped(nodeId: String) =
      reducer
        .modifierToggles(reducer.initial(document, selectedNodeId = nodeId))
        .map { it.type }
        .filter { it in setOf("align", "alignHorizontal", "alignVertical", "weight") }

    // A root has no parent, so neither means anything and neither is offered.
    assertEquals(emptyList(), scoped("main-background"))
    // A box's child aligns in two dimensions at once and has no main axis to weight.
    assertEquals(listOf("align"), scoped("discover-grid"))
    // A row's child aligns vertically; a column's, horizontally. Both weight.
    assertEquals(listOf("alignVertical", "weight"), scoped("main-episode-meta"))
    assertEquals(listOf("alignHorizontal", "weight"), scoped("detail-podcast-title"))
  }

  @Test
  fun `a scoped modifier is refused where nothing supplies the scope`() {
    // The catalog declares `weight` on a box, because a box in a row can carry one. This box is a
    // root, and a chain the renderer has no receiver for is code the exporter could not emit.
    val state = reducer.initial(document, selectedNodeId = "main-background")
    val refused =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleModifier("main-background", "weight"))

    val outcome = refused.lastOutcome
    assertTrue(outcome is CommandOutcome.Rejected, outcome.toString())
    assertEquals(RejectionCode.INVALID_PROPERTY, outcome.code)
    assertEquals("modifiers", outcome.field)
    assertEquals(document, refused.document)
  }

  @Test
  fun `an alignment is chosen from the values its scope defines`() {
    val state = reducer.initial(document, selectedNodeId = "detail-podcast-title")
    val aligned =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.ToggleModifier("detail-podcast-title", "alignHorizontal"),
      )

    val field = reducer.modifierFields(aligned).single { it.type == "alignHorizontal" }
    assertEquals("centerHorizontally", field.value)
    assertEquals(listOf("start", "centerHorizontally", "end"), field.choices)

    val moved =
      reducer.reduce(
        aligned,
        UiBuilderEditorEvent.SetModifierValue(
          "detail-podcast-title",
          "alignHorizontal",
          "alignment",
          "end",
        ),
      )
    assertEquals(
      "end",
      reducer.modifierFields(moved).single { it.type == "alignHorizontal" }.value,
    )

    // A column offers three of the nine, and the six a box would offer are refused here rather
    // than written: `Alignment.CenterEnd` is not an `Alignment.Horizontal` and never applies.
    val refused =
      reducer.reduce(
        moved,
        UiBuilderEditorEvent.SetModifierValue(
          "detail-podcast-title",
          "alignHorizontal",
          "alignment",
          "centerEnd",
        ),
      )
    val outcome = refused.lastOutcome
    assertTrue(outcome is CommandOutcome.Rejected, outcome.toString())
    assertEquals("modifiers", outcome.field)
    assertEquals(moved.document, refused.document)
  }

  @Test
  fun `a weight is a number the menu starts at one and the field then moves`() {
    val state = reducer.initial(document, selectedNodeId = "detail-podcast-title")
    val weighted =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleModifier("detail-podcast-title", "weight"))

    val field = reducer.modifierFields(weighted).single { it.type == "weight" }
    assertEquals("Weight" to "1", field.label to field.value)
    assertTrue(field.choices.isEmpty(), "a weight is typed, not picked")

    val heavier =
      reducer.reduce(
        weighted,
        UiBuilderEditorEvent.SetModifierValue("detail-podcast-title", "weight", "weight", "2"),
      )
    assertEquals("2", reducer.modifierFields(heavier).single { it.type == "weight" }.value)
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
