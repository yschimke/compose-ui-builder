package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The watch clips a complication's content to its slot's bounding area; these name what it cuts.
 * The slot is brazen-nugget's `cf-slot1` (compose-preview-server #2016): a full-face slot whose
 * 96×96 `<BoundingBox>` at 252,306 holds none of its label.
 */
class WatchFaceComplicationBoundsTest {
  private fun text(x: Int, y: Int, w: Int, h: Int) =
    """<PartText x="$x" y="$y" width="$w" height="$h"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText>"""

  private fun face(
    slot1Content: String,
    slot1Box: String = """x="252" y="306" width="96" height="96"""",
  ) =
    """
        <WatchFace width="450" height="450">
          <Scene>
            <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT" x="0" y="0" width="450" height="450">
              <BoundingBox $slot1Box/>
              <Complication type="SHORT_TEXT">$slot1Content</Complication>
            </ComplicationSlot>
            <ComplicationSlot slotId="2" supportedTypes="SHORT_TEXT" x="100" y="0" width="90" height="90">
              <BoundingOval x="0" y="0" width="90" height="90"/>
              <Complication type="SHORT_TEXT">${text(0, 0, 90, 90)}</Complication>
            </ComplicationSlot>
          </Scene>
        </WatchFace>
    """
      .trimIndent()

  private fun document(xml: String) =
    assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml)).document

  private fun UiBuilderDocument.slots() =
    nodes.values.filter { it.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT }

  @Test
  fun `the bounding area is the one the export writes`() {
    val document = document(face(text(260, 320, 80, 20)))
    val (big, small) = document.slots().sortedBy { it.properties["slotId"].toString() }
    assertEquals(
      WatchFaceComplicationBounds.Area(252f, 306f, 96f, 96f),
      WatchFaceComplicationBounds.boundingArea(big, 450f, 450f),
    )
    // No `bounds:*`: the slot's own box.
    assertEquals(
      WatchFaceComplicationBounds.Area(0f, 0f, 90f, 90f),
      WatchFaceComplicationBounds.boundingArea(small, 90f, 90f),
    )
    val exported =
      assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document))
    assertTrue("""<BoundingBox x="252" y="306" width="96" height="96"/>""" in exported.xml)
  }

  @Test
  fun `content inside the box is not reported`() {
    assertEquals(
      emptyList(),
      WatchFaceComplicationBounds.issues(document(face(text(260, 320, 80, 20)))),
    )
  }

  @Test
  fun `a label past the box is named with its slot and the fix`() {
    val document = document(face(text(255, 291, 120, 36) + text(260, 320, 80, 20)))
    val issues = WatchFaceComplicationBounds.issues(document)
    val issue = issues.single()
    val slot = document.slots().single { it.id == issue.nodeId }
    val label =
      document.nodes.values.single {
        it.componentId == WatchFaceXmlExporter.PART_TEXT &&
          WatchFaceXmlExporter.Props(it, mutableListOf()).number("y") == "291"
      }
    assertEquals(WatchFaceComplicationBounds.CODE, issue.code)
    assertTrue("`${slot.id}` draws only inside 252,306 96×96" in issue.message, issue.message)
    assertTrue("`${label.id}` at 255,291 120×36 falls outside it" in issue.message, issue.message)
    assertTrue("Grow the slot's bounding box" in issue.message, issue.message)
    assertTrue("or move the part inside it" in issue.message, issue.message)
    // The one inside the box is not named.
    assertEquals(
      listOf(label.id),
      WatchFaceComplicationBounds.clipped(document).single().outside.map { it.nodeId },
    )
  }

  @Test
  fun `a group is judged by the parts it draws`() {
    val grouped =
      """<Group name="g" x="0" y="0" width="450" height="450">${text(260, 320, 80, 20)}</Group>"""
    assertEquals(emptyList(), WatchFaceComplicationBounds.issues(document(face(grouped))))
    val past =
      """<Group name="g" x="0" y="-40" width="450" height="450">${text(260, 320, 80, 20)}</Group>"""
    assertEquals(1, WatchFaceComplicationBounds.clipped(document(face(past))).single().outside.size)
  }

  @Test
  fun `growing the box clears the warning`() {
    val document =
      document(
        face(text(255, 291, 120, 36), slot1Box = """x="240" y="280" width="160" height="160""""),
      )
    assertEquals(emptyList(), WatchFaceComplicationBounds.issues(document))
  }
}
