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
 * The numbers that make the Wear canvas the same picture as a real Wear render.
 *
 * ## Why a test and not a comment
 *
 * Every constant behind the Wear screen stand-in was measured — the content padding from
 * `ScreenScaffoldContentPaddingTest` in yschimke/wear-m3-catalog, which composes the real
 * `ScreenScaffold` under Robolectric, and the rest off that repository's stitched `ScrollMode.LONG`
 * capture of its `TransformingLazyColumn` component. A measured number that nothing asserts is a
 * number somebody rounds later, and the failure mode is a canvas that is quietly a few dp out with
 * no test to say so.
 *
 * This pins the template's half. The renderer's half — the padding table, the clock offset, the
 * card corner and the Wear colours — is `internal` to the renderer by design, so what this can
 * check is that the design the two are compared through has not drifted: the row content, the
 * spacing, the type sizes and the shape that reaches the theme's corner radius.
 *
 * The parity itself is evidenced by the render in
 * `docs/design/evidence/ui-builder-wear-screen/wear-screen-parity.png`, which is the two side by
 * side, and by the measurement table in `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
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
   * 4dp between rows, which is what the reference measures and what the emitted Kotlin says.
   *
   * The one number that has to agree in three places at once: the canvas draws it, the generator
   * writes it as `Arrangement.spacedBy`, and the reference has it between every pair of cards.
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
   * Wear's type scale is not Material 3's, and `wear-m3/text` is drawn by a Material 3 Text.
   *
   * Sizing each label explicitly is what makes "Session 1" measure the reference's 66dp rather than
   * 75.5dp. Dropping these puts the mobile scale back and the canvas silently stops matching.
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
   * The rows carry no `shape`, because Wear publishes exactly one.
   *
   * They used to ask for `"large"` to route the card through the canvas's corner-radius local,
   * which was how a *borrowed* mobile card reached the reference's 26dp. Wear's own `TitleCard`
   * takes `CardDefaults.shape` — one shape, and it is that 26dp — so the property decided nothing
   * on either lane, and it is dropped from the catalog's declaration rather than left as a control
   * an author can move that moves nothing. This assertion is what catches its return.
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
   * The rows carry NO padding of their own, and the absence is what keeps them 64dp.
   *
   * They used to carry a measured 12.2 / 9.7 / 14.7dp, authored when the canvas drew `wear-m3/card`
   * as a borrowed mobile Material 3 card — a card with none of Wear's padding. The canvas draws the
   * real Wear `TitleCard` now, which carries `CardDefaults.ContentPadding` (12dp on every edge) and
   * is the reference's 64dp on its own, so the same numbers landed on top of it and the canvas drew
   * an 82dp row whose text sat 24.2dp in. The generated Kotlin never carried them — the exporter
   * collapses the two-text column into `TitleCard(title = …, subtitle = …)` — so the canvas was the
   * half that disagreed, by 18dp a row.
   *
   * A design padding cannot be asserted away by rendering alone, because a padded card still
   * renders; this is the assertion that catches its return. The measured proof is the render in
   * `docs/design/UI_BUILDER_WEAR_SCREEN.md`, where the row is 64dp and the title 12dp in.
   *
   * The header is not here either, and that is the same fix one component over: it used to be a
   * padded text faking `ListHeader`'s 48dp, which made the canvas right and the generated screen
   * 31.5dp short. It is `wear-m3/list-header` now, which carries the height on both sides.
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
