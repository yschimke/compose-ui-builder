package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The exporter refuses what would make invalid Watch Face Format, with the same words the design
 * service refuses the edit in, instead of emitting it.
 */
class WatchFaceDesignValidationTest {
  private val xml =
    """
        <WatchFace width="450" height="450">
          <Scene>
            <Group name="g" x="0" y="0" width="450" height="450">
              <PartImage x="0" y="0" width="450" height="450"><Image resource="dial"/></PartImage>
            </Group>
            <AnalogClock x="0" y="0" width="450" height="450">
              <HourHand resource="h" x="220" y="60" width="10" height="165" pivotX="0.5" pivotY="1.0"/>
            </AnalogClock>
            <ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT" x="0" y="0" width="90" height="90">
              <BoundingOval x="0" y="0" width="90" height="90"/>
              <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="90" height="90"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText></Complication>
    </ComplicationSlot>
            <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT" x="100" y="0" width="90" height="90">
              <BoundingOval x="0" y="0" width="90" height="90"/>
              <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="90" height="90"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText></Complication>
    </ComplicationSlot>
          </Scene>
        </WatchFace>
    """
      .trimIndent()

  private fun document(version: Int = 2) =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(xml, WatchFaceXmlImporter.Options(wffVersion = version)),
      )
      .document

  private fun UiBuilderDocument.with(
    component: String,
    name: String,
    type: String,
    value: JsonPrimitive,
    nth: Int = 0,
  ): UiBuilderDocument {
    val node = nodes.values.filter { it.componentId == component }[nth]
    return copy(
      nodes =
        nodes +
          (node.id to
            node.copy(
              properties =
                JsonObject(
                  node.properties +
                    (name to
                      buildJsonObject {
                        put("type", type)
                        put("value", value)
                      }),
                ),
            )),
    )
  }

  private fun reasons(document: UiBuilderDocument): List<String> =
    assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(document)).reasons

  @Test
  fun `the untouched face exports`() {
    assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document()))
  }

  @Test
  fun `a negative size, a word for a number and a malformed colour are refused`() {
    val negative =
      reasons(
        document().with(WatchFaceXmlExporter.COMPLICATION_SLOT, "width", "int", JsonPrimitive(-5)),
      )
    assertTrue(negative.single().let { "width" in it && "negative" in it }, negative.toString())

    val word =
      reasons(
        document().with(WatchFaceXmlExporter.CLOCK_HAND, "width", "string", JsonPrimitive("abc")),
      )
    assertTrue(word.single().contains("`abc`"), word.toString())

    val colour =
      reasons(
        document()
          .with(WatchFaceXmlExporter.CLOCK_HAND, "tintColor", "color", JsonPrimitive("notacolor")),
      )
    assertTrue(colour.single().contains("tintColor"), colour.toString())

    // A Material role is not a Watch Face Format colour: refused, never written into the XML.
    val role =
      reasons(
        document()
          .with(WatchFaceXmlExporter.CLOCK_HAND, "tintColor", "color", JsonPrimitive("primary")),
      )
    assertTrue(role.single().contains("tintColor"), role.toString())

    // A colour option index that is not an Int is refused rather than left to fail later.
    val hugeIndex =
      reasons(
        document()
          .with(
            WatchFaceXmlExporter.CLOCK_HAND,
            "tintColor",
            "string",
            JsonPrimitive("[CONFIGURATION.theme.2147483648]"),
          ),
      )
    assertTrue(hugeIndex.single().contains("tintColor"), hugeIndex.toString())

    // An expression is the format's own language: written as it is.
    val expression =
      document()
        .with(
          WatchFaceXmlExporter.CLOCK_HAND,
          "tintColor",
          "string",
          JsonPrimitive("[CONFIGURATION.theme.3]"),
        )
    assertTrue(
      assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(expression))
        .xml
        .contains("""tintColor="[CONFIGURATION.theme.3]""""),
    )
    val decimal =
      document().with(WatchFaceXmlExporter.CLOCK_HAND, "width", "string", JsonPrimitive("12.50"))
    // The format's own types now judge the written value: a hand's width is a whole number.
    assertTrue(reasons(decimal).single().contains("width"), reasons(decimal).toString())
  }

  @Test
  fun `a duplicate slot id and a complication type newer than the version are refused`() {
    val duplicate =
      reasons(
        document()
          .with(WatchFaceXmlExporter.COMPLICATION_SLOT, "slotId", "int", JsonPrimitive(0), nth = 1),
      )
    assertTrue(duplicate.single().contains("slotId 0"), duplicate.toString())

    val goal =
      document(version = 1)
        .with(
          WatchFaceXmlExporter.COMPLICATION_SLOT,
          "supportedTypes",
          "string",
          JsonPrimitive("SHORT_TEXT GOAL_PROGRESS"),
        )
    val refused = reasons(goal)
    assertTrue(
      refused.any { "GOAL_PROGRESS" in it && "version 2" in it && "version 1" in it },
      refused.toString(),
    )
    // The same face at version 2 is fine.
    assertIs<WatchFaceXmlExporter.Result.Emitted>(
      WatchFaceXmlExporter.export(goal, WatchFaceXmlExporter.Options(wffVersion = 2)),
    )
    assertTrue(
      reasons(
          document()
            .with(
              WatchFaceXmlExporter.COMPLICATION_SLOT,
              "supportedTypes",
              "string",
              JsonPrimitive("BOGUS"),
            ),
        )
        .single()
        .contains("not a complication type"),
    )
  }

  @Test
  fun `an unknown property is refused, not dropped`() {
    val unknown =
      reasons(
        document().with(WatchFaceXmlExporter.GROUP, "noSuchProp", "string", JsonPrimitive("x")),
      )
    assertTrue(unknown.single().contains("noSuchProp is not declared"), unknown.toString())
  }

  @Test
  fun `invalid format is refused rather than written, and an import still holds it`() {
    // A ComplicationSlot only goes directly in the Scene.
    val inGroup =
      """<WatchFace width="450" height="450"><Scene><Group name="g" x="0" y="0" width="1" height="1"><ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT" x="0" y="0" width="1" height="1"><BoundingOval x="0" y="0" width="1" height="1"/>  <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="90" height="90"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText></Complication>
</ComplicationSlot></Group></Scene></WatchFace>"""
    val imported =
      assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(inGroup)).document
    assertTrue(
      reasons(imported).single().contains("<ComplicationSlot> is not allowed inside <Group>"),
    )
    // An element newer than the version.
    val newer =
      checkNotNull(javaClass.getResource("/wear-os-samples/PhotosMulti/raw/watchface.xml"))
        .readText()
    val v2 =
      assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(newer, WatchFaceXmlImporter.Options(wffVersion = 2)),
      )
    assertTrue(
      v2.warnings.any { "needs Watch Face Format version 4" in it },
      v2.warnings.toString(),
    )
    assertTrue(
      reasons(v2.document).any { "requires WFF version 4" in it },
      reasons(v2.document).toString(),
    )
    assertIs<WatchFaceXmlExporter.Result.Emitted>(
      WatchFaceXmlExporter.export(v2.document, WatchFaceXmlExporter.Options(wffVersion = 4)),
    )
    // Held as it was, written without the judging.
    assertIs<WatchFaceXmlExporter.Result.Emitted>(
      WatchFaceXmlExporter.export(v2.document, WatchFaceXmlExporter.Options(lenient = true)),
    )
  }

  @Test
  fun `the whole-document check finds what an edit broke, and passes what it did not`() {
    assertNull(WatchFaceDesignValidation.check(document()))
    val noRoot = document().copy(roots = emptyList())
    assertEquals("INVALID_ROOT", WatchFaceDesignValidation.check(noRoot)?.code)
    val duplicate =
      document()
        .with(WatchFaceXmlExporter.COMPLICATION_SLOT, "slotId", "int", JsonPrimitive(0), nth = 1)
    assertEquals(
      WatchFaceDesignValidation.VALUE_ISSUE_CODE,
      WatchFaceDesignValidation.check(duplicate)?.code,
    )
  }
}
