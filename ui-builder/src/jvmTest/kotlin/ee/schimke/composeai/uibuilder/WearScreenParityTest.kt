package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The numbers that make the Wear canvas the same picture as a real Wear render, measured in
 * yschimke/wear-m3-catalog (`ScreenScaffoldContentPaddingTest` and its stitched `ScrollMode.LONG`
 * capture). Asserted so nobody rounds them later.
 *
 * This pins the template's half (row content, spacing, type sizes, shape); the renderer's constants
 * are internal. Parity evidence:
 * `docs/design/evidence/ui-builder-wear-screen/wear-screen-parity.png` and the table in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
 */
class WearScreenParityTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())
  private val document = wearScreenUiBuilderDocument("parity", pin, environment)

  /** Character for character wear-m3-catalog's own rows, or the comparison compares two designs. */
  @Test
  fun `the template carries the reference list`() {
    val titles =
      document.nodes.values
        .filter { it.id.endsWith("-title") }
        .sortedBy { it.id }
        .map { it.property("text") }

    assertEquals((1..6).map { "Session $it" }, titles)
    assertEquals("Activity", document.nodes.getValue("list-header").property("text"))
  }

  /**
   * 4dp between rows, agreed by the canvas, the generated `Arrangement.spacedBy`, and the
   * reference.
   */
  @Test
  fun `the list spacing is the reference's 4dp`() {
    val list = document.nodes.getValue("wear-list")

    assertEquals("4", list.property("verticalSpacingDp").removeSuffix(".0"))

    val source =
      (WearScreenCodeExporter.export(document) as WearScreenCodeExporter.Result.Emitted).source
    assertTrue("Arrangement.spacedBy(4.dp)" in source, source)
  }

  /**
   * Wear's type scale is not Material 3's, and `wear-m3/text` is drawn by a Material 3 Text, so
   * each label is sized explicitly (e.g. "Session 1" at the reference's 66dp).
   */
  @Test
  fun `every label carries a measured Wear type size`() {
    listOf("row-0-title" to "14", "row-0-subtitle" to "13").forEach { (nodeId, expected) ->
      assertEquals(
        expected,
        document.nodes.getValue(nodeId).property("fontSizeSp").trimZero(),
        nodeId,
      )
    }
  }

  /**
   * The rows carry no `shape`: Wear's `TitleCard` has one shape (`CardDefaults.shape`, 26dp), so
   * the property was dropped from the catalog; this catches its return.
   */
  @Test
  fun `the rows carry no card shape of their own`() {
    (0..5).forEach { index ->
      assertTrue(
        "shape" !in document.nodes.getValue("row-$index").properties,
        "row-$index still declares a shape: ${document.nodes.getValue("row-$index").properties}",
      )
    }
  }

  /**
   * The rows carry NO padding of their own: Wear's `TitleCard` already has
   * `CardDefaults.ContentPadding` (12dp) and is the reference's 64dp; extra design padding would
   * make the canvas row taller than the generated `TitleCard(title = …, subtitle = …)`. The header
   * is `wear-m3/list-header`, which carries its 48dp on both sides. See
   * `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
   */
  @Test
  fun `the rows carry no padding of their own, and the header needs none`() {
    (0..5).forEach { index ->
      assertEquals(
        emptyList(),
        document.nodes.getValue("row-$index-lines").paddingEdges(),
        "row-$index-lines",
      )
    }
    val header = document.nodes.getValue("list-header")
    assertEquals("wear-m3/list-header", header.componentId)
    assertTrue(header.modifiers.isEmpty(), header.modifiers.toString())
  }

  private fun UiBuilderNode.property(name: String): String =
    properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()

  private fun UiBuilderNode.paddingEdges(): List<String> =
    modifiers
      .map { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.content == "padding" }
      .flatMap { padding ->
        listOf("startDp", "topDp", "endDp", "bottomDp").map {
          padding.getValue(it).jsonPrimitive.content.trimZero()
        }
      }

  private fun String.trimZero(): String = removeSuffix(".0")
}
