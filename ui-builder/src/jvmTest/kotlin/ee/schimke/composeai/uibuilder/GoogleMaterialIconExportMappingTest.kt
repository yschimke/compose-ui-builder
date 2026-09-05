package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The canvas and the export must name the same icon for a key.
 *
 * Two modules hold the wire-to-Kotlin mapping for `m3/icon`'s 46 keys and neither can read the
 * other's. [GoogleMaterialIcons] holds real `ImageVector`s, so it needs Compose;
 * `:ui-builder-export` deliberately has no Compose dependency, because `:server` depends on it and
 * a server classpath should not carry Compose UI. So the table is restated in
 * `ScreenDocumentProjection.ICON_MEMBERS`, and restated knowledge drifts unless something fails.
 *
 * This is that something. It is the cheap half of the check — that both sides agree which member a
 * key names — and it is the half that catches the mistakes actually available here: a key added to
 * the palette and not to the export, a `genres` that resolves to `Category` in one table and to
 * nothing in the other, an icon moved into `Icons.AutoMirrored` on one side only. What it does not
 * check is that the member exists, which the compiler already does for [GoogleMaterialIcons] on
 * every build of this module.
 */
class GoogleMaterialIconExportMappingTest {

  @Test
  fun `the export table names the same member as the palette, for every key`() {
    assertEquals(
      GoogleMaterialIcons.associate { it.key to it.composeExpression.removePrefix("Icons.") },
      ScreenDocumentProjection.ICON_MEMBERS,
      "the builder palette and the Compose export disagree about an icon key",
    )
  }
}
