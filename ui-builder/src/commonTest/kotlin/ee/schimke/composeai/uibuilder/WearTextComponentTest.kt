package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.renderer.sdk.isUiBuilderTextComponent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Material 3 borrow table is gone, and this is what is left of the rule that governed it.
 *
 * ## What this test used to be
 *
 * It was called "only the three renamed borrows are drawn as Material 3", and it existed to stop a
 * fourth being added. Its reasoning: the canvas is Compose Multiplatform for Wasm,
 * `androidx.wear.compose:compose-material3` is an Android AAR it cannot link, and therefore a Wear
 * component with no Material 3 counterpart could only be hand-assembled from Material pieces at
 * sizes read off a screenshot. It named `CheckboxButton`, `SwitchButton`, `Slider`, `DatePicker`
 * and others as the shapes a future change would reach for, and refused every one.
 *
 * The premise about the AAR was true and remains checkable. The conclusion was not: the canvas
 * never had to link that artifact. `ee.schimke.wearcmp:*` is the same library's source compiled for
 * Compose Multiplatform with `jvm` and `wasmJs` variants — exactly this module's targets.
 *
 * Every component the old test named is now drawn by the real thing, the rename table
 * (`wearScreenStandIn`) has been deleted, and what replaced it is [isUiBuilderTextComponent] — the
 * one question that table had quietly started answering for `UiBuilderInspection` on the side.
 *
 * ## What it asserts now
 *
 * That the table's second job survived its deletion. The drawing is covered where drawing belongs,
 * by `WearCanvasDrawsRealComponentsTest` composing a real design; there is no mapping left here to
 * assert against.
 */
class WearTextComponentTest {
  @Test
  fun `text nodes are recognised on both platforms`() {
    assertTrue("material3/Text".isUiBuilderTextComponent())
    assertTrue("m3/text".isUiBuilderTextComponent())
    // The one that regressed before. `wear-m3/text` was recognised only because the borrow table
    // mapped it onto `m3/text`; with the table gone it has to be named, or Wear text silently
    // stops reporting its layout to the inspector.
    assertTrue("wear-m3/text".isUiBuilderTextComponent())
  }

  @Test
  fun `components that merely contain text are not text nodes`() {
    // Each of these draws a label and none of them reports a text layout of its own. A predicate
    // that said yes here would have the inspector waiting for measurements that never arrive.
    listOf(
        "wear-m3/card",
        "wear-m3/button",
        "wear-m3/list-header",
        "wear-m3/list-sub-header",
        "wear-m3/switch-button",
        "m3/card",
        "layout/column",
      )
      .forEach { assertFalse(it.isUiBuilderTextComponent(), it) }
  }
}
