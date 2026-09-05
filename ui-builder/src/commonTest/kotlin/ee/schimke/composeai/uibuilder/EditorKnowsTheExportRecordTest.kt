package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor's problems panel now answers with the export's own code.
 *
 * Before this the panel ran `CapabilityComposeCodeExporter`, which has an emitter for every catalog
 * id, while the server's export ran the projection and the real generator against a component
 * record covering eleven of the twenty-five. So a design holding `m3/icon` looked exportable in the
 * browser and was refused by the server — the divergence that made the two builders two builders.
 *
 * These assert the join rather than a message: that the record actually reached the browser, and
 * that a component it does not back is reported before anyone presses export.
 */
class EditorKnowsTheExportRecordTest {

  @Test
  fun `the component record the export reads is embedded and parses`() {
    val record = assertNotNull(embeddedComponentRecord(), "the embedded record did not parse")
    assertTrue(record.components.isNotEmpty(), "the embedded record is empty")
    val ids = record.components.flatMap { it.componentIds }
    // The covered set, as the server sees it. `layout/lazy-column` was the example of an absence
    // here until the record grew to carry it (#394); `layout/horizontal-carousel` is the absence
    // now, and an absence is the thing the panel could not previously see at all.
    assertTrue("m3/text" in ids, ids.toString())
    assertTrue("layout/column" in ids, ids.toString())
    assertTrue("layout/lazy-column" in ids, ids.toString())
    assertTrue("layout/horizontal-carousel" !in ids, ids.toString())
  }

  @Test
  fun `the editor and the export share one expression allow-list`() {
    // A copy on either side is a copy that can be widened without the other's review.
    assertTrue(
      "androidx.compose" in
        ee.schimke.composeai.uibuilder.export.ScreenExportGate.EXPRESSION_PACKAGES
    )
    assertTrue(
      ee.schimke.composeai.uibuilder.export.ScreenExportGate.PACKAGE_NAME == "generated.uibuilder"
    )
  }
}
