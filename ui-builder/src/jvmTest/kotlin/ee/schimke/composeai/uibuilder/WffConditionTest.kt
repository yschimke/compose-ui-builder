package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.wffConditionBranch
import ee.schimke.composeai.uibuilder.canvas.wffConfigurationValues
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A `<Condition>` previews the branch the watch would take: its compares evaluated against the
 * clock, the complication sample and the user settings at their defaults.
 */
class WffConditionTest {
  private fun eval(expression: String, context: WffPreviewContext = WffPreviewContext()) =
    evaluateWffExpression(expression, context)?.asNumber()

  @Test
  fun `comparisons and logic evaluate to one or zero`() {
    assertEquals(1.0, eval("[HOUR_0_23] < 12"))
    assertEquals(0.0, eval("[HOUR_0_23] >= 12"))
    assertEquals(1.0, eval("[MINUTE] == 8 && [SECOND] != 0"))
    assertEquals(1.0, eval("[MINUTE] > 50 || [SECOND] <= 32"))
    assertEquals(0.0, eval("!([MINUTE] == 8)"))
    assertEquals(1.0, eval("1 + 2 * 3 == 7"))
    assertEquals(1.0, eval("true && !false"))
    val settings = WffPreviewContext().withConfiguration(mapOf("ring" to "TRUE", "style" to "bold"))
    assertEquals(1.0, eval("[CONFIGURATION.ring] == true", settings))
    assertEquals(1.0, eval("[CONFIGURATION.style] == 'bold'", settings))
    assertNull(eval("[CONFIGURATION.unknown] == 1", settings))
  }

  private val face =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <UserConfigurations>
        <BooleanConfiguration id="showRing" displayName="Ring" defaultValue="TRUE"/>
        <ListConfiguration id="style" displayName="Style" defaultValue="bold">
          <ListOption id="bold" displayName="Bold"/>
          <ListOption id="thin" displayName="Thin"/>
        </ListConfiguration>
      </UserConfigurations>
      <Scene backgroundColor="#ff000000">
        <Condition>
          <Expressions>
            <Expression name="morning"><![CDATA[[HOUR_0_23] < 12]]></Expression>
          </Expressions>
          <Compare expression="morning">
            <PartDraw x="0" y="0" width="10" height="10"><Rectangle x="0" y="0" width="10" height="10"><Fill color="#ffff0000"/></Rectangle></PartDraw>
          </Compare>
          <Default>
            <PartDraw x="0" y="0" width="10" height="10"><Rectangle x="0" y="0" width="10" height="10"><Fill color="#ff0000ff"/></Rectangle></PartDraw>
          </Default>
        </Condition>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private fun document(): UiBuilderDocument =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(face, WatchFaceXmlImporter.Options(wffVersion = 2)),
      )
      .document

  /** [document] with the condition's compare reading [expression]. */
  private fun UiBuilderDocument.comparing(expression: String): UiBuilderDocument {
    val compare = nodes.values.single { it.componentId == "wff/compare" }
    return copy(
      nodes =
        nodes +
          (compare.id to
            compare.copy(
              properties =
                kotlinx.serialization.json.JsonObject(
                  compare.properties +
                    ("expression" to
                      kotlinx.serialization.json.JsonObject(
                        mapOf(
                          "type" to kotlinx.serialization.json.JsonPrimitive("string"),
                          "value" to kotlinx.serialization.json.JsonPrimitive(expression),
                        ),
                      )),
                ),
            )),
    )
  }

  private fun UiBuilderDocument.branchAt(context: WffPreviewContext): String? {
    val condition = nodes.values.single { it.componentId == "wff/condition" }
    return wffConditionBranch(this, condition, context)?.let { nodes.getValue(it).componentId }
  }

  @Test
  fun `the true compare is drawn, else the default`() {
    val doc = document()
    assertEquals(mapOf("showRing" to "TRUE", "style" to "bold"), doc.wffConfigurationValues())
    // As imported: the compare names the `morning` expression of the condition's Expressions.
    assertEquals("wff/compare", doc.branchAt(WffPreviewContext(hour = 9)))
    assertEquals("wff/default", doc.branchAt(WffPreviewContext(hour = 21)))
    val morning = doc.comparing("[HOUR_0_23] < 12")
    assertEquals("wff/compare", morning.branchAt(WffPreviewContext(hour = 10)))
    assertEquals("wff/default", morning.branchAt(WffPreviewContext(hour = 15)))

    val settings = WffPreviewContext().withConfiguration(doc.wffConfigurationValues())
    assertEquals(
      "wff/compare",
      doc.comparing("[CONFIGURATION.showRing] == true").branchAt(settings),
    )
    assertEquals("wff/default", doc.comparing("[CONFIGURATION.style] == 'thin'").branchAt(settings))

    val battery =
      WffPreviewContext()
        .withComplication(WffComplicationSample.forProvider("WATCH_BATTERY", "RANGED_VALUE"))
    assertEquals(
      "wff/compare",
      doc.comparing("[COMPLICATION.RANGED_VALUE_VALUE] > 50").branchAt(battery),
    )
    // A source the preview cannot read draws the first branch, as before.
    assertEquals("wff/compare", doc.comparing("[WEATHER.IS_AVAILABLE]").branchAt(settings))
  }
}
