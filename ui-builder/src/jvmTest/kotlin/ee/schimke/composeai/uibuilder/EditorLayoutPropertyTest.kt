package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout is authored through catalog properties, and most of them were unreachable.
 *
 * A numeric property with no bounds is `Unsupported`, so every spacing, size, elevation and
 * thickness in the catalog refused to be edited while `m3/text` had six working number fields. A
 * property the catalog declares as `"object"` was refused outright, which covered every
 * `contentPadding`. These are the tests for both.
 */
class EditorLayoutPropertyTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun fields(nodeId: String) =
    reducer.propertyFields(reducer.initial(document, selectedNodeId = nodeId))

  private fun field(nodeId: String, name: String) = fields(nodeId).firstOrNull { it.name == name }

  @Test
  fun `a dimension named in dp is a number field rather than unsupported`() {
    // The unit is in the name, which is enough for a range, and without a range the inspector
    // refused to edit the spacing of every container in the catalog.
    val spacing = assertNotNull(field("discover-grid", "verticalSpacingDp"))

    assertEquals(EditorPropertyControl.Number, spacing.control)
    val bounds = assertNotNull(spacing.numberBounds)
    assertTrue(bounds.maximum >= 1024.0)
  }

  @Test
  fun `a dimension no emitter reads is not offered at all`() {
    // `m3/search-bar.shapeDp` is declared by the catalog and read by nothing: the renderer pins a
    // search bar to `CircleShape` and the exporter's emitter takes no shape. A number field for it
    // is worse than none — every value it accepts is discarded, and the design then looks authored
    // and draws as if it were not.
    val shape = assertNotNull(field("search-bar", "shapeDp"))
    assertEquals(EditorPropertyControl.Unsupported, shape.control)
    assertNull(shape.numberBounds)

    // Its sibling on the same component is emitted, so the rule is about coverage rather than a
    // component-wide opt-out.
    val tonal = assertNotNull(field("search-bar", "tonalElevationDp"))
    assertEquals(EditorPropertyControl.Number, tonal.control)
  }

  @Test
  fun `arrangement spacing may be negative, padding may not`() {
    // Negative spacing is how children are made to overlap, and `Arrangement.spacedBy` and the
    // exporter both take the signed value. One blanket floor of zero refused a value both
    // projections round trip.
    val spacing = assertNotNull(field("discover-grid", "verticalSpacingDp"))
    assertTrue(assertNotNull(spacing.numberBounds).minimum < 0.0)

    val overlapped =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "discover-grid"),
        UiBuilderEditorEvent.CommitProperty("discover-grid", "verticalSpacingDp", "-8"),
      )
    assertIs<CommandOutcome.Accepted>(overlapped.lastOutcome)

    // A padding edge keeps its floor: there is no such thing as negative space around content.
    val edge = assertNotNull(field("discover-grid", "contentPadding.topDp"))
    assertEquals(0.0, assertNotNull(edge.numberBounds).minimum)
  }

  @Test
  fun `an explicit override still beats the rule`() {
    // `m3/text.fontSizeSp` is registered at 1..512 and must not be widened by anything generic.
    val fontSize = assertNotNull(field("search-placeholder", "fontSizeSp"))
    val bounds = assertNotNull(fontSize.numberBounds)

    assertEquals(1.0, bounds.minimum)
    assertEquals(512.0, bounds.maximum)
  }

  @Test
  fun `content padding is four number fields, not one refusal`() {
    val edges = fields("discover-grid").filter { it.name.startsWith("contentPadding.") }

    assertEquals(
      listOf(
        "contentPadding.startDp",
        "contentPadding.topDp",
        "contentPadding.endDp",
        "contentPadding.bottomDp",
      ),
      edges.map { it.name },
    )
    assertTrue(edges.all { it.control == EditorPropertyControl.Number })
    // The authored value on this node is 0/0/0/88, and each edge shows its own number.
    assertEquals("88", edges.last().value)
    assertEquals("0", edges.first().value)
  }

  @Test
  fun `editing one edge keeps the others`() {
    // The wire carries one padding value, not four numbers, so a commit that dropped the edges it
    // was not asked about would silently reset three sides of the screen.
    val edited =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "discover-grid"),
        UiBuilderEditorEvent.CommitProperty("discover-grid", "contentPadding.topDp", "24"),
      )

    val padding =
      assertNotNull(edited.document.nodes.getValue("discover-grid").properties["contentPadding"])
        .jsonObject
    assertEquals("padding", padding.getValue("type").jsonPrimitive.content)
    // The edited edge is written as the number its type declares; the three it carries keep the
    // spelling the document already had, rather than being rewritten on the way past.
    assertEquals("24.0", padding.getValue("topDp").jsonPrimitive.content)
    assertEquals("88", padding.getValue("bottomDp").jsonPrimitive.content)
    assertEquals("0", padding.getValue("startDp").jsonPrimitive.content)
    assertEquals("0", padding.getValue("endDp").jsonPrimitive.content)
  }

  @Test
  fun `the adaptive grid's cell width is editable and keeps its own floor`() {
    val columns = assertNotNull(field("discover-grid", "columns.minimumCellWidthDp"))
    assertEquals(EditorPropertyControl.Number, columns.control)
    assertEquals("362.0", columns.value)

    // A grid of zero-width cells is not a grid, and the edge carries its own minimum rather than
    // inheriting padding's zero.
    val rejected =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "discover-grid"),
        UiBuilderEditorEvent.CommitProperty("discover-grid", "columns.minimumCellWidthDp", "0"),
      )
    val outcome = assertIs<CommandOutcome.Rejected>(assertNotNull(rejected.lastOutcome))
    assertTrue(outcome.message.contains("1..4096"), outcome.message)
  }

  @Test
  fun `an object shape the editor does not author stays refused`() {
    // `itemSpans` maps child ids to spans. There is no honest four-field control for it, and
    // guessing one would be worse than saying so.
    assertNull(field("discover-grid", "itemSpans.startDp"))
    val itemSpans = assertNotNull(field("discover-grid", "itemSpans"))
    assertEquals(EditorPropertyControl.Unsupported, itemSpans.control)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
