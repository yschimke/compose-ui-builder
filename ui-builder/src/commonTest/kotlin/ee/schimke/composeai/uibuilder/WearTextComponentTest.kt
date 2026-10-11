package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.renderer.sdk.isUiBuilderTextComponent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isUiBuilderTextComponent] recognises Wear text. Wear components are drawn by the real Wear
 * Compose library via the CMP port (`ee.schimke.wearcmp:*`), so the old Material 3 rename table is
 * gone; this pins the one job that table also did for `UiBuilderInspection`. Drawing is covered by
 * `WearCanvasDrawsRealComponentsTest`.
 */
class WearTextComponentTest {
  @Test
  fun `text nodes are recognised on both platforms`() {
    assertTrue("material3/Text".isUiBuilderTextComponent())
    assertTrue("m3/text".isUiBuilderTextComponent())
    // `wear-m3/text` must be named explicitly, or Wear text stops reporting its layout to the
    // inspector.
    assertTrue("wear-m3/text".isUiBuilderTextComponent())
    // A launcher widget's text, which the canvas draws by id: no catalog names an adapter for it.
    assertTrue("remote-widgets/remote-text".isUiBuilderTextComponent())
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
