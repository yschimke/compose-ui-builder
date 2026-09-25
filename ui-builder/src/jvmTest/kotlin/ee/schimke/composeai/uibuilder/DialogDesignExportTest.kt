package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.preview.designFixtureDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's own dialog mocks, through the gate the server's Compose export and the design render
 * ask.
 *
 * `m3/dialog` had no entry in the component record, so every design holding one was refused with
 * `UNPROVEN_CALL_SITE: no component m3/dialog in this catalog` — the four dialog mocks were the
 * only m3 fixtures the Design Renders lane could not draw.
 */
class DialogDesignExportTest {

  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  private fun export(designId: String) =
    ScreenExportGate.export(
      designFixtureDocument(designId).toProtocolDocument(),
      catalog.exportRecord(embeddedComponentRecord()),
    )

  @Test
  fun `every dialog mock exports as an AlertDialog`() {
    val failures = DIALOG_DESIGNS.mapNotNull { designId ->
      when (val outcome = export(designId)) {
        is ScreenExportGate.Outcome.Emitted -> {
          val source = outcome.source
          if ("AlertDialog(onDismissRequest = {}, confirmButton = {" !in source) {
            "$designId: no AlertDialog call\n$source"
          } else null
        }
        is ScreenExportGate.Outcome.Refused ->
          "$designId:\n" + outcome.reasons.joinToString("\n") { "  - $it" }
      }
    }
    assertEquals(emptyList(), failures, failures.joinToString("\n"))
  }

  /** `shapeDp` is the surface's name for `AlertDialog`'s `shape`, as it is for `m3/surface`. */
  @Test
  fun `the dialog's corner radius reaches its shape`() {
    val source = (export("ui-builder-new-design-dialog") as ScreenExportGate.Outcome.Emitted).source
    assertTrue("shape = RoundedCornerShape(28.dp)" in source, source)
  }

  private companion object {
    val DIALOG_DESIGNS =
      listOf(
        "ui-builder-new-design-dialog",
        "ui-builder-new-design-screen",
        "ui-builder-packs-dialog",
        "ui-builder-shortcuts-dialog",
      )
  }
}
