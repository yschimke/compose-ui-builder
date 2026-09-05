package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cap on canvas lookalikes: three Wear ids are drawn as Material 3 components, and no more.
 *
 * The canvas is Compose Multiplatform for Wasm and `androidx.wear.compose:compose-material3` is an
 * Android AAR it cannot link, so a Wear component is either drawn as something else or not drawn.
 * The three below are renames of borrows this canvas was already drawing — `wear-m3/card` was
 * literally `m3/card` until the borrow rule landed — which is what makes them tolerable.
 *
 * A fourth is not that. Wear's `CheckboxButton`, `Slider` and `DatePicker` have no Material 3
 * counterpart to rename, so adding one means hand-assembling a replica at sizes read off a
 * screenshot: an impression of upstream with nothing in this build to check it against, wrong
 * silently in the surface an author trusts, and one more thing to maintain against a library nobody
 * here compiles. That is the change this test exists to stop —
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` states the rule and what replaces it (the streaming
 * preview lane, which compiles the generated Wear Kotlin for real).
 *
 * Asserted as a whole map rather than three membership checks, so growing it is an edit somebody
 * makes on purpose.
 */
class WearCanvasStandInTest {
  @Test
  fun `only the three renamed borrows are drawn as Material 3`() {
    val wearIds =
      listOf(
        "wear-m3/text",
        "wear-m3/card",
        "wear-m3/button",
        "wear-m3/list-header",
        "wear-m3/screen-scaffold",
        "wear-m3/transforming-lazy-column",
        // Not components. Named here as the shapes a future change is most likely to reach for.
        "wear-m3/checkbox-button",
        "wear-m3/switch-button",
        "wear-m3/radio-button",
        "wear-m3/slider",
        "wear-m3/stepper",
        "wear-m3/date-picker",
        "wear-m3/time-picker",
        "wear-m3/alert-dialog",
      )

    val standIns = wearIds.associateWith { it.wearScreenStandIn() }.filterNot { it.key == it.value }

    assertEquals(
      mapOf(
        "wear-m3/text" to "m3/text",
        "wear-m3/card" to "m3/card",
        "wear-m3/button" to "m3/button",
      ),
      standIns,
    )
  }

  /** A mobile id is untouched: the mapping is one direction, for Wear ids only. */
  @Test
  fun `a mobile id maps to itself`() {
    assertEquals("m3/card", "m3/card".wearScreenStandIn())
    assertEquals("layout/column", "layout/column".wearScreenStandIn())
  }
}
