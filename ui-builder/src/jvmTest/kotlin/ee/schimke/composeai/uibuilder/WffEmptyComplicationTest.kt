package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A slot showing `EMPTY` (no provider chosen yet) draws its own `<Complication type="EMPTY">`, as
 * the watch does: yschimke/watchface-tools#43, item 4.
 */
class WffEmptyComplicationTest {
  private fun face(emptyContent: Boolean): UiBuilderDocument {
    val empty =
      if (emptyContent)
        """
        <Complication type="EMPTY">
          <PartDraw x="80" y="20" width="40" height="160">
            <Rectangle x="0" y="0" width="40" height="160"><Fill color="#ffffffff"/></Rectangle>
          </PartDraw>
        </Complication>
        """
      else ""
    val xml =
      """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <Scene backgroundColor="#ff000000">
          <ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT EMPTY" x="125" y="125" width="200" height="200">
            <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>
            <BoundingBox x="0" y="0" width="200" height="200"/>
            <Complication type="SHORT_TEXT">
              <PartText x="0" y="0" width="200" height="200">
                <Text><Font family="SYNC_TO_DEVICE" size="40" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
              </PartText>
            </Complication>
            $empty
          </ComplicationSlot>
        </Scene>
      </WatchFace>
      """
        .trimIndent()
    val document = assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml))
    return document.document.let {
      it.copy(
        environment =
          JsonObject(it.environment + ("fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z"))),
      )
    }
  }

  @Test
  fun `an empty slot draws its EMPTY complication`() {
    val bitmap = renderWffFace(face(emptyContent = true), "empty-complication-with-content")
    // The bar sits at 205..245 x 145..305 on the face: its middle is white.
    assertEquals(listOf(255, 255, 255, 255), bitmap.argb(227, 227))
  }

  @Test
  fun `an empty slot with no EMPTY complication draws nothing`() {
    val bitmap = renderWffFace(face(emptyContent = false), "empty-complication-without-content")
    var lit = 0
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      val (_, r, g, b) = bitmap.argb(x, y)
      if (r + g + b > 60) lit++
    }
    assertEquals(0, lit, "an EMPTY slot with nothing to show drew $lit pixels")
  }
}
