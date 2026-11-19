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

  @Test
  fun `transforms are applied before judging, as the canvas places parts`() {
    // A 20×20 label at the group's origin; the 100×100 group at 252,306 turned 180° moves it to
    // the group's far corner, which is inside the 96×96 box only once the turn is applied.
    fun turned(angle: Int) =
      face(
        """<Group name="g" x="248" y="302" width="100" height="100" angle="$angle">""" +
          text(0, 0, 20, 20) +
          "</Group>",
      )
    // Unturned, the label sits at 248,302: past the box's top-left corner.
    assertEquals(
      WatchFaceComplicationBounds.Area(248f, 302f, 20f, 20f),
      WatchFaceComplicationBounds.clipped(document(turned(0))).single().outside.single().area,
    )
    // Turned, it sits at 328,382 to 348,402: inside.
    assertEquals(emptyList(), WatchFaceComplicationBounds.issues(document(turned(180))))
    // A part scaled up about its centre reaches past a box it would otherwise fit.
    val scaled =
      """<PartText x="260" y="320" width="80" height="20" scaleX="2" scaleY="1">""" +
        text(0, 0, 80, 20).removePrefix("""<PartText x="0" y="0" width="80" height="20">""")
    assertEquals(
      WatchFaceComplicationBounds.Area(220f, 320f, 160f, 20f),
      WatchFaceComplicationBounds.clipped(document(face(scaled))).single().outside.single().area,
    )
  }

  @Test
  fun `a digital clock is judged by its time text`() {
    fun clock(timeText: String) =
      face(
        """<DigitalClock x="252" y="306" width="96" height="96">""" +
          """<TimeText format="hh:mm" $timeText><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"/></TimeText>""" +
          "</DigitalClock>",
      )
    // The clock fits; its text does not.
    val past =
      WatchFaceComplicationBounds.clipped(
          document(clock("""x="0" y="-30" width="96" height="30"""")),
        )
        .single()
    assertEquals(WatchFaceXmlExporter.TIME_TEXT, past.outside.single().componentId)
    assertEquals(WatchFaceComplicationBounds.Area(252f, 276f, 96f, 30f), past.outside.single().area)
    assertEquals(
      emptyList(),
      WatchFaceComplicationBounds.issues(
        document(clock("""x="0" y="30" width="96" height="30"""")),
      ),
    )
  }

  @Test
  fun `a part-draw is judged by the shapes it paints, not its box`() {
    fun drawn(shape: String) =
      face("""<PartDraw x="0" y="0" width="450" height="450">$shape</PartDraw>""")
    // A full-face part holding one small rectangle inside the box: nothing is cut.
    val inside =
      """<Rectangle x="260" y="320" width="80" height="20"><Fill color="#ffffffff"/></Rectangle>"""
    assertEquals(emptyList(), WatchFaceComplicationBounds.issues(document(drawn(inside))))
    // An arc whose circle reaches above the box is.
    val arc =
      """<Arc centerX="300" centerY="320" width="80" height="80" startAngle="0" endAngle="360"><Stroke color="#ffffffff" thickness="4"/></Arc>"""
    val cut = WatchFaceComplicationBounds.clipped(document(drawn(arc))).single().outside.single()
    assertEquals(WatchFaceXmlExporter.PART_DRAW, cut.componentId)
    assertEquals(WatchFaceComplicationBounds.Area(260f, 280f, 80f, 80f), cut.area)
    // A line is its ends widened by half its stroke.
    val line =
      """<Line startX="260" startY="304" endX="300" endY="304"><Stroke color="#ffffffff" thickness="6"/></Line>"""
    assertEquals(
      WatchFaceComplicationBounds.Area(257f, 301f, 46f, 6f),
      WatchFaceComplicationBounds.clipped(document(drawn(line))).single().outside.single().area,
    )
  }

  @Test
  fun `an analog clock is judged by the circles its hands sweep`() {
    fun clock(hand: String) =
      face(
        """<AnalogClock x="0" y="0" width="450" height="450">""" +
          """<MinuteHand resource="m" $hand pivotX="0.5" pivotY="1.0"/>""" +
          "</AnalogClock>",
      )
    // A full-face clock whose short hand turns about 300,354, the box's centre: 6×40 reaches
    // 40.1 px, inside the 96×96 box at any time.
    assertEquals(
      emptyList(),
      WatchFaceComplicationBounds.issues(
        document(clock("""x="297" y="314" width="6" height="40"""")),
      ),
    )
    // A longer hand on the same pivot reaches past the box at some time of day.
    val long =
      WatchFaceComplicationBounds.clipped(
        document(clock("""x="297" y="294" width="6" height="60"""")),
      )
    assertEquals(WatchFaceXmlExporter.CLOCK_HAND, long.single().outside.single().componentId)
  }
}
