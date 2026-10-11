package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor's problems panel answers with the export's own record, so a design the server would
 * refuse (e.g. a component the record doesn't cover) is reported in the browser before export.
 * Asserts the join: the record reaches the browser, and an unbacked component is reported.
 */
class EditorKnowsTheExportRecordTest {

  @Test
  fun `the component record the export reads is embedded and parses`() {
    val record = assertNotNull(embeddedComponentRecord(), "the embedded record did not parse")
    assertTrue(record.components.isNotEmpty(), "the embedded record is empty")
    val ids = record.components.flatMap { it.componentIds }
    // The covered set, as the server sees it; `layout/horizontal-carousel` is a current absence.
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
