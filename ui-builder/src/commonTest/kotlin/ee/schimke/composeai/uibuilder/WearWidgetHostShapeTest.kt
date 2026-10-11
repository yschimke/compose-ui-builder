package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentValidationError
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host container table, pinned against what `androidx.glance.wear:wear-tooling-preview` ships.
 * A transcription of `WearWidgetParams`, not policy: the editor canvas and the native lane both
 * read it, so they can't drift. Changes should follow upstream's spec, re-read from the provider.
 */
class WearWidgetHostShapeTest {

  @Test
  fun `widget host dimensions remain valid when selecting export devices`() {
    val environment =
      JsonObject(
        mapOf(
          "widthDp" to JsonPrimitive(216),
          "heightDp" to JsonPrimitive(124),
          "density" to JsonPrimitive(2.0),
          "fontScale" to JsonPrimitive(1.0),
          "locale" to JsonPrimitive("en-US"),
          "theme" to JsonPrimitive("dark"),
          "layoutDirection" to JsonPrimitive("ltr"),
        )
      )
    val widget =
      wearWidgetUiBuilderDocument(
        designId = "widget",
        catalogPin = JsonObject(emptyMap()),
        environment = environment,
        size = WearWidgetScaffoldSize.Large,
      )

    assertNull(widget.screenEnvironmentValidationError(widget.screenEnvironmentSettings()))

    val screen =
      widget.copy(
        nodes = widget.nodes.mapValues { (_, node) -> node.copy(componentId = "m3/text") }
      )
    assertEquals(
      "Height must be between 180 and 3840 dp.",
      screen.screenEnvironmentValidationError(screen.screenEnvironmentSettings()),
    )
  }

  @Test
  fun `the squircle spec is the published 240dp-screen footprint`() {
    val small = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Squircle)
    assertEquals(200, small.contentWidthDp)
    assertEquals(60, small.contentHeightDp)
    assertEquals(8f, small.horizontalPaddingDp)
    assertEquals(8f, small.verticalPaddingDp)
    assertEquals(26f, small.cornerRadiusDp)

    val large = WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Squircle)
    assertEquals(200, large.contentWidthDp)
    assertEquals(108, large.contentHeightDp)
    assertEquals(8f, large.horizontalPaddingDp)
    assertEquals(8f, large.verticalPaddingDp)
    assertEquals(26f, large.cornerRadiusDp)
  }

  /**
   * The rectangular container is a different frame, not the squircle with square corners: content
   * box and padding both move, per axis and size.
   */
  @Test
  fun `the rectangular spec moves the content box and the padding, not only the radius`() {
    val small = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Rectangular)
    assertEquals(192, small.contentWidthDp)
    assertEquals(60, small.contentHeightDp)
    assertEquals(16f, small.horizontalPaddingDp)
    assertEquals(12f, small.verticalPaddingDp)
    assertEquals(0f, small.cornerRadiusDp)

    val large = WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Rectangular)
    assertEquals(168, large.contentWidthDp)
    assertEquals(112, large.contentHeightDp)
    assertEquals(32f, large.horizontalPaddingDp)
    assertEquals(16f, large.verticalPaddingDp)
    assertEquals(0f, large.cornerRadiusDp)

    WearWidgetScaffoldSize.entries.forEach { size ->
      assertNotEquals(
        size.hostSpec(WearWidgetHostShape.Squircle).contentWidthDp,
        size.hostSpec(WearWidgetHostShape.Rectangular).contentWidthDp,
        "$size: the squircle and rectangular content boxes differ",
      )
    }
  }

  /**
   * The round container, at the widest footprint its providers ship, as the squircle (and the
   * generated `@Preview`'s `.maxBy { it.widthDp }`) does.
   */
  @Test
  fun `the round spec is the widest footprint its providers ship`() {
    val small = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Round)
    assertEquals(200, small.contentWidthDp)
    assertEquals(60, small.contentHeightDp)
    assertEquals(15f, small.horizontalPaddingDp)
    assertEquals(8f, small.verticalPaddingDp)
    assertEquals(999f, small.cornerRadiusDp)

    val large = WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Round)
    assertEquals(160, large.contentWidthDp)
    assertEquals(136, large.contentHeightDp)
    assertEquals(35f, large.horizontalPaddingDp)
    assertEquals(16f, large.verticalPaddingDp)
    assertEquals(999f, large.cornerRadiusDp)
  }

  /**
   * Round Small shares the squircle's 200×60dp content box and differs only by padding and radius
   * (216×76 vs 230×76).
   */
  @Test
  fun `round small shares the squircle content box but not its frame`() {
    val squircle = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Squircle)
    val round = WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Round)

    assertEquals(squircle.contentWidthDp, round.contentWidthDp)
    assertEquals(squircle.contentHeightDp, round.contentHeightDp)
    assertNotEquals(squircle.frameWidthDp, round.frameWidthDp)
    assertNotEquals(squircle.cornerRadiusDp, round.cornerRadiusDp)
    assertEquals(216, squircle.frameWidthDp)
    assertEquals(230, round.frameWidthDp)
  }

  /** Round Large is the tightest container of the six, fitting inside a circle. */
  @Test
  fun `round large reserves the least content width of any container`() {
    val widths =
      WearWidgetHostShape.entries.associateWith {
        WearWidgetScaffoldSize.Large.hostSpec(it).contentWidthDp
      }

    assertEquals(WearWidgetHostShape.Round, widths.minByOrNull { it.value }?.key)
    assertEquals(160, widths[WearWidgetHostShape.Round])
  }

  /**
   * The frame is the content box plus padding on both edges — what a `@Preview` canvas measures.
   */
  @Test
  fun `the frame is the content box plus twice the padding`() {
    assertEquals(
      216,
      WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Squircle).frameWidthDp,
    )
    assertEquals(
      76,
      WearWidgetScaffoldSize.Small.hostSpec(WearWidgetHostShape.Squircle).frameHeightDp,
    )
    assertEquals(
      216,
      WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Squircle).frameWidthDp,
    )
    assertEquals(
      124,
      WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Squircle).frameHeightDp,
    )
    val rectangularLarge = WearWidgetScaffoldSize.Large.hostSpec(WearWidgetHostShape.Rectangular)
    assertEquals(232, rectangularLarge.frameWidthDp)
    assertEquals(144, rectangularLarge.frameHeightDp)
  }

  /** Every shape names a real provider per size, so the generated `@Preview` compiles. */
  @Test
  fun `every shape and size names a shipped provider`() {
    val expected =
      mapOf(
        (WearWidgetHostShape.Squircle to WearWidgetScaffoldSize.Small) to
          "SquircleSmallWidgetPreviewParams",
        (WearWidgetHostShape.Squircle to WearWidgetScaffoldSize.Large) to
          "SquircleLargeWidgetPreviewParams",
        (WearWidgetHostShape.Rectangular to WearWidgetScaffoldSize.Small) to
          "RectangularSmallWidgetPreviewParams",
        (WearWidgetHostShape.Rectangular to WearWidgetScaffoldSize.Large) to
          "RectangularLargeWidgetPreviewParams",
        (WearWidgetHostShape.Round to WearWidgetScaffoldSize.Small) to
          "RoundSmallWidgetPreviewParams",
        (WearWidgetHostShape.Round to WearWidgetScaffoldSize.Large) to
          "RoundLargeWidgetPreviewParams",
      )
    expected.forEach { (key, provider) ->
      assertEquals(provider, key.first.paramsProviderFor(key.second))
    }
    assertEquals(
      expected.size,
      WearWidgetHostShape.entries.size * WearWidgetScaffoldSize.entries.size,
    )
  }

  /**
   * An unknown or absent wire spelling is the default rather than a failure, so the pane still
   * draws.
   */
  @Test
  fun `an unknown shape id falls back to the rectangular editing host`() {
    assertEquals(WearWidgetHostShape.Squircle, WearWidgetHostShape.fromId("squircle"))
    assertEquals(WearWidgetHostShape.Rectangular, WearWidgetHostShape.fromId("rectangular"))
    assertEquals(WearWidgetHostShape.Rectangular, WearWidgetHostShape.fromId(" Rectangular "))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId(null))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId(""))
    assertEquals(WearWidgetHostShape.Round, WearWidgetHostShape.fromId("round"))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId("stadium"))
    assertEquals(WearWidgetHostShape.Rectangular, WearWidgetHostShape.Default)
  }

  /**
   * The label is what a designer reads twice: on the editor's control and in the `@Preview` name.
   */
  @Test
  fun `the labels match the names the generated previews carry`() {
    assertEquals("Squircle", WearWidgetHostShape.Squircle.label)
    assertEquals("Rectangular", WearWidgetHostShape.Rectangular.label)
    assertEquals("Round", WearWidgetHostShape.Round.label)
    WearWidgetHostShape.entries.forEach {
      assertTrue(it.id.isNotBlank() && it.id == it.label.lowercase(), "${it.name}: id vs label")
    }
  }
}
