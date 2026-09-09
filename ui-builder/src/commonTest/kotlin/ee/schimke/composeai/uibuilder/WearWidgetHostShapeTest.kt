package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The host container table, pinned against what `androidx.glance.wear:wear-tooling-preview` ships.
 *
 * These five numbers per shape and size are a transcription, not a policy: the launcher hands them
 * to a widget as `WearWidgetParams`, and nothing in this repository is free to tune them. They are
 * checked here because two surfaces now read the table — the editor canvas draws the frame and the
 * native lane builds the params — and the whole point of one table is that those two cannot drift.
 * A change to any of these is upstream changing its spec, and should be a deliberate edit with the
 * provider re-read, not a number nudged until a render looked right.
 */
class WearWidgetHostShapeTest {

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
   * The rectangular container is a different frame, not the squircle with square corners.
   *
   * Worth asserting as its own fact because it is the assumption most likely to be made by someone
   * adding the third shape: both the content box and the padding move, on both axes and differently
   * per size.
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
   * The round container, at the widest footprint its providers ship.
   *
   * Round was left out of the first version of this table on the stated grounds that its spec
   * varies per screen diameter. It does — and so does the squircle's, which this table has always
   * resolved by taking the widest entry, exactly as the generated `@Preview` does with `.maxBy {
   * it.widthDp }`. The exclusion was wrong rather than conservative, and these are the numbers it
   * was hiding.
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
   * Round Small shares the squircle's content box and differs by padding and radius.
   *
   * Asserted because it is the counter-example to "every shape reserves a different box", which is
   * what the rectangular case would otherwise suggest: at Small the round host gives a widget the
   * same 200×60dp and simply pads it wider, so the two frames differ (216×76 against 230×76) while
   * the room for content does not.
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

  /**
   * Round Large is the tightest container any shape offers, which is the case worth looking at.
   *
   * A widget that fills the squircle comfortably is the one most likely to clip here: the frame has
   * to fit inside a circle rather than beside one, so it reserves the least width of the six.
   */
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

  /**
   * Every shape names a real provider per size, so the generated `@Preview` cannot cite one that
   * does not exist — the failure mode being source that reads fine and does not compile.
   */
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
   * An unknown or absent wire spelling is the default rather than a failure.
   *
   * The shape rides in a native-render request body, and a host that cannot parse one should draw
   * the frame the editor opens on rather than leave the pane empty: it is a view over the design,
   * and no view at all is the worse answer.
   */
  @Test
  fun `an unknown shape id falls back to the squircle`() {
    assertEquals(WearWidgetHostShape.Squircle, WearWidgetHostShape.fromId("squircle"))
    assertEquals(WearWidgetHostShape.Rectangular, WearWidgetHostShape.fromId("rectangular"))
    assertEquals(WearWidgetHostShape.Rectangular, WearWidgetHostShape.fromId(" Rectangular "))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId(null))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId(""))
    assertEquals(WearWidgetHostShape.Round, WearWidgetHostShape.fromId("round"))
    assertEquals(WearWidgetHostShape.Default, WearWidgetHostShape.fromId("stadium"))
    assertEquals(WearWidgetHostShape.Squircle, WearWidgetHostShape.Default)
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
