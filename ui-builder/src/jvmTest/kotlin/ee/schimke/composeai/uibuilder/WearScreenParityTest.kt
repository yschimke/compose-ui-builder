package ee.schimke.composeai.uibuilder

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
   * A card with no `shape` draws `RoundedCornerShape(0.dp)` — the theme's radius is unreachable.
   *
   * `"large"` is what routes it to the Wear scaffold's 26dp, which is the reference card's corner.
   * This is the assertion that catches its removal, because a square card still renders fine.
   */
  @Test
  fun `the rows ask for the theme's corner radius`() {
    (0..5).forEach { index ->
      assertEquals("large", document.nodes.getValue("row-$index").property("shape"), "row-$index")
    }
  }

  /**
   * 64dp rows, made up by the design's own padding, because a borrowed card has none of Wear's.
   *
   * The header is not here any more, and that is the fix rather than an omission: it used to be a
   * padded text faking `ListHeader`'s 48dp, which made the canvas right and the generated screen
   * 31.5dp short. It is `wear-m3/list-header` now, which carries the height on both sides.
   */
  @Test
  fun `the row padding is the measured one, and the header needs none`() {
    assertEquals(
      listOf("12.2", "9.7", "12.2", "14.7"),
      document.nodes.getValue("row-0-lines").paddingEdges(),
    )
    val header = document.nodes.getValue("list-header")
    assertEquals("wear-m3/list-header", header.componentId)
    assertTrue(header.modifiers.isEmpty(), header.modifiers.toString())
  }

  private fun UiBuilderNode.property(name: String): String =
    properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()

  private fun UiBuilderNode.paddingEdges(): List<String> =
    modifiers
      .map { it.jsonObject }
      .single { it["type"]?.jsonPrimitive?.content == "padding" }
      .let { padding ->
        listOf("startDp", "topDp", "endDp", "bottomDp").map {
          padding.getValue(it).jsonPrimitive.content.trimZero()
        }
      }

  private fun String.trimZero(): String = removeSuffix(".0")
}
