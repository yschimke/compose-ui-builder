package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.wff.WffXmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The elements the importer takes as builtins, each from a minimal face of its own: the import is
 * tree-identical to the XML, the element is a node with properties (not a `wff/raw` string), an
 * edit of one of those properties reaches the XML, and an element that still cannot be a builtin
 * says why.
 */
class WatchFaceBuiltinCoverageTest {
  private fun face(scene: String) =
    "<WatchFace width=\"450\" height=\"450\">\n<Scene>\n$scene\n</Scene>\n</WatchFace>"

  private fun import(xml: String) =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
      WatchFaceXmlImporter.import(xml, WatchFaceXmlImporter.Options(wffVersion = 5)),
    )

  private fun export(document: UiBuilderDocument): String {
    val result = WatchFaceXmlExporter.export(document)
    return assertIs<WatchFaceXmlExporter.Result.Emitted>(
        result,
        (result as? WatchFaceXmlExporter.Result.Refused)?.reasons.toString(),
      )
      .xml
  }

  /** Imports [scene] as a face, checks the tree comes back identical, and returns the import. */
  private fun roundTrip(scene: String): WatchFaceXmlImporter.Result.Imported {
    val xml = face(scene)
    val imported = import(xml)
    assertEquals(WffXmlParser.parse(xml), WffXmlParser.parse(export(imported.document)))
    return imported
  }

  private fun WatchFaceXmlImporter.Result.Imported.nodes(component: String) =
    document.nodes.values.filter { it.componentId == component }

  private fun WatchFaceXmlImporter.Result.Imported.raws() = nodes(WatchFaceXmlExporter.RAW).size

  private fun UiBuilderNode.text(name: String): String? =
    ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

  private fun UiBuilderDocument.set(
    node: UiBuilderNode,
    name: String,
    type: String,
    value: JsonPrimitive,
  ): UiBuilderDocument =
    copy(
      nodes =
        nodes +
          (node.id to
            nodes
              .getValue(node.id)
              .copy(
                properties =
                  JsonObject(
                    nodes.getValue(node.id).properties +
                      (name to
                        buildJsonObject {
                          put("type", type)
                          put("value", value)
                        }),
                  ),
              )),
    )

  @Test
  fun `a complication slot whose policy comes before its bounds is a builtin with editable bounds and provider`() {
    val imported =
      roundTrip(
        """
        <ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT RANGED_VALUE" x="60" y="175" width="90" height="90" name="left">
          <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="RANGED_VALUE"/>
          <BoundingOval x="0" y="0" width="90" height="90"/>
          <Complication type="SHORT_TEXT">
            <PartText x="0" y="25" width="90" height="40">
              <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="24" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
            </PartText>
          </Complication>
        </ComplicationSlot>
        """
          .trimIndent(),
      )
    assertEquals(0, imported.raws(), imported.warnings.toString())
    val slot = imported.nodes(WatchFaceXmlExporter.COMPLICATION_SLOT).single()
    assertEquals("WATCH_BATTERY", slot.text("defaultSystemProvider"))
    assertEquals("60", slot.text("x"))
    assertEquals(1, imported.nodes(WatchFaceXmlExporter.COMPLICATION).size)
    assertEquals(1, imported.nodes(WatchFaceXmlExporter.PART_TEXT).size)

    val edited =
      export(
        imported.document
          .set(slot, "x", "int", JsonPrimitive(70))
          .set(slot, "defaultSystemProvider", "string", JsonPrimitive("STEP_COUNT")),
      )
    assertTrue("""x="70" y="175"""" in edited, edited)
    // Still in the face's order: the policy before the bounds, with the new provider.
    assertTrue(
      edited.indexOf("""defaultSystemProvider="STEP_COUNT"""") < edited.indexOf("<BoundingOval"),
      edited,
    )
  }

  @Test
  fun `a bounds element of another size or shape is kept, not turned into raw xml`() {
    val imported =
      roundTrip(
        """
        <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT" x="0" y="0" width="100" height="100">
          <BoundingRoundBox x="5" y="6" width="90" height="80" outlinePadding="2"/>
        </ComplicationSlot>
        """
          .trimIndent(),
      )
    assertEquals(0, imported.raws(), imported.warnings.toString())
    assertEquals(
      "ROUND_RECTANGLE",
      imported.nodes(WatchFaceXmlExporter.COMPLICATION_SLOT).single().text("boundsType"),
    )
  }

  @Test
  fun `a part text template is a text property and its parameters, CDATA and spacing kept`() {
    val imported =
      roundTrip(
        """
        <PartText x="0" y="88" width="450" height="40" name="date">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Text align="CENTER" ellipsis="true">
            <Font family="SYNC_TO_DEVICE" size="30" weight="BOLD" color="[CONFIGURATION.accent.0]">
              <Template>%s %d<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY_OF_MONTH]"/></Template>
            </Font>
          </Text>
        </PartText>
        <PartText x="0" y="10" width="450" height="40" name="title">
          <Text><Font family="SYNC_TO_DEVICE" size="24" color="#ffcccccc">
            <Template><![CDATA[%s]]>
              <Parameter expression="[COMPLICATION.TITLE]"/>
            </Template>
          </Font></Text>
        </PartText>
        """
          .trimIndent(),
      )
    assertEquals(0, imported.raws(), imported.warnings.toString())
    val date = imported.nodes(WatchFaceXmlExporter.PART_TEXT).first { it.text("text") == "%s %d" }
    assertEquals("%s %d", date.text("text"))
    assertEquals("[DAY_OF_WEEK_S]\n[DAY_OF_MONTH]", date.text("parameters"))
    val edited = export(imported.document.set(date, "text", "string", JsonPrimitive("%d/%s")))
    assertTrue("<Template>%d/%s<Parameter" in edited, edited)
    // The ambient variant stays before the text.
    assertTrue(edited.indexOf("<Variant") < edited.indexOf("<Text"), edited)
  }

  @Test
  fun `part draw shapes are builtins, fill before stroke and an empty part draw included`() {
    val imported =
      roundTrip(
        """
        <PartDraw x="0" y="0" width="450" height="450">
          <Arc centerX="225" centerY="225" width="436" height="436" startAngle="-150" endAngle="150" direction="CLOCKWISE"><Stroke thickness="5" color="#ff4488ff" cap="ROUND"/></Arc>
        </PartDraw>
        <PartDraw x="10" y="10" width="100" height="100">
          <Line startX="0" startY="0" endX="100" endY="100"><Stroke color="#ffffffff" thickness="2"/></Line>
        </PartDraw>
        <PartDraw x="10" y="120" width="100" height="40">
          <RoundRectangle x="0" y="0" width="100" height="40" cornerRadiusX="8" cornerRadiusY="8"><Fill color="#ff2a2a2a"/><Stroke color="#ffffffff" thickness="1"/></RoundRectangle>
        </PartDraw>
        <PartDraw x="120" y="120" width="40" height="40">
          <Ellipse x="1" y="2" width="38" height="36"><Fill color="#ff00ff00"/></Ellipse>
        </PartDraw>
        <PartDraw x="0" y="0" width="450" height="450" name="nothing"/>
        """
          .trimIndent(),
      )
    assertEquals(0, imported.raws(), imported.warnings.toString())
    val draws = imported.nodes(WatchFaceXmlExporter.PART_DRAW)
    assertEquals(
      listOf("arc", "line", "round-rectangle", "ellipse", "none"),
      draws.map { it.text("shape") },
    )
    val arc = draws.first()
    assertEquals("-150", arc.text("startAngle"))
    val edited = export(imported.document.set(arc, "endAngle", "int", JsonPrimitive(90)))
    assertTrue("""startAngle="-150" endAngle="90"""" in edited, edited)
    assertEquals("8", draws[2].text("cornerRadiusX"))
  }

  @Test
  fun `a property of another shape is refused rather than dropped`() {
    val imported =
      roundTrip(
        """<PartDraw x="0" y="0" width="40" height="40"><Ellipse x="0" y="0" width="40" height="40"><Fill color="#ff00ff00"/></Ellipse></PartDraw>""",
      )
    val draw = imported.nodes(WatchFaceXmlExporter.PART_DRAW).single()
    val refused =
      WatchFaceXmlExporter.export(
        imported.document.set(draw, "startAngle", "int", JsonPrimitive(10)),
      )
    assertTrue(
      assertIs<WatchFaceXmlExporter.Result.Refused>(refused)
        .reasons
        .single()
        .contains("startAngle"),
    )
  }

  @Test
  fun `condition, compare, default and launch are builtins holding builtin parts`() {
    val imported =
      roundTrip(
        """
        <Group name="root" x="0" y="0" width="450" height="450">
          <Condition>
            <Expressions><Expression name="low"><![CDATA[[BATTERY_PERCENT] < 20]]></Expression></Expressions>
            <Compare expression="low">
              <PartDraw x="195" y="385" width="60" height="30"><RoundRectangle x="0" y="0" width="60" height="30" cornerRadiusX="8" cornerRadiusY="8"><Fill color="#ffff5a5f"/></RoundRectangle></PartDraw>
            </Compare>
            <Default>
              <PartDraw x="195" y="385" width="60" height="30"><RoundRectangle x="0" y="0" width="60" height="30" cornerRadiusX="8" cornerRadiusY="8"><Fill color="#ff2a2a2a"/></RoundRectangle></PartDraw>
            </Default>
          </Condition>
          <Group name="tap" x="40" y="295" width="370" height="85"><Launch target="com.example.fitness/.MainActivity"/></Group>
        </Group>
        """
          .trimIndent(),
      )
    // Only <Expressions> stays raw, and the warning says so.
    assertEquals(1, imported.raws(), imported.warnings.toString())
    assertTrue(imported.warnings.any { "Expressions x1" in it }, imported.warnings.toString())
    val compare = imported.nodes(WatchFaceXmlExporter.COMPARE).single()
    assertEquals("low", compare.text("expression"))
    assertEquals(1, imported.nodes(WatchFaceXmlExporter.DEFAULT).size)
    assertEquals(2, imported.nodes(WatchFaceXmlExporter.PART_DRAW).size)
    assertEquals(
      "com.example.fitness/.MainActivity",
      imported.nodes(WatchFaceXmlExporter.LAUNCH).single().text("target"),
    )
    val edited =
      export(imported.document.set(compare, "expression", "string", JsonPrimitive("high")))
    assertTrue("""<Compare expression="high">""" in edited, edited)
  }

  @Test
  fun `time text geometry is properties, and pivot, expression tints and decimals stay exact`() {
    val imported =
      roundTrip(
        """
        <DigitalClock x="85" y="58" width="280" height="96">
          <TimeText format="hh:mm" x="0" y="0" width="280.50" height="96" align="CENTER"><Font family="SYNC_TO_DEVICE" size="84" color="#ffffffff"/></TimeText>
        </DigitalClock>
        <AnalogClock x="0" y="0" width="450" height="450">
          <SecondHand resource="s" x="222" y="10" width="6" height="215" pivotX="0.5" pivotY="1.0" tintColor="[CONFIGURATION.theme.3]"/>
        </AnalogClock>
        """
          .trimIndent(),
      )
    assertEquals(0, imported.raws(), imported.warnings.toString())
    val text = imported.nodes(WatchFaceXmlExporter.TIME_TEXT).single()
    assertEquals("0", text.text("x"))
    assertEquals("96", text.text("height"))
    val edited = export(imported.document.set(text, "y", "int", JsonPrimitive(12)))
    assertTrue("""x="0" y="12" width="280.50" height="96"""" in edited, edited)
    assertTrue("""pivotY="1.0" tintColor="[CONFIGURATION.theme.3]"""" in edited, edited)
  }

  @Test
  fun `what stays raw says which child or attribute stopped it`() {
    val imported =
      import(
        face(
          """
          <PartDraw x="0" y="0" width="450" height="450">
            <Arc centerX="225" centerY="225" width="436" height="436" startAngle="0" endAngle="360"><Stroke thickness="2" color="#ff444444"/><Transform target="angle" value="1"/></Arc>
          </PartDraw>
          <PartText x="0" y="0" width="450" height="40">
            <Text><Font family="SYNC_TO_DEVICE" size="30" color="#ffffffff"><Template>%s<Parameter expression="[X]"/>%s<Parameter expression="[Y]"/></Template><Outline color="#ff000000" width="2"/></Font></Text>
          </PartText>
          """
            .trimIndent(),
        ),
      )
    val reasons = imported.warnings.joinToString("\n")
    assertTrue("<Arc> has a <Transform> child" in reasons, reasons)
    assertTrue("<Font> does not hold exactly one <Template>" in reasons, reasons)
  }

  @Test
  fun `the dogfood shapes and expression parts round trip`() {
    // A face written from the briefs: every construct above together, tree-identical.
    roundTrip(
      """
      <Group name="all" x="0" y="0" width="450" height="450">
        <PartImage x="0" y="0" width="450" height="450" tintColor="[CONFIGURATION.theme.0]"><Image resource="dial"/></PartImage>
        <PartDraw x="0" y="0" width="450" height="450"><Variant mode="AMBIENT" target="alpha" value="0"/><Ellipse x="0" y="0" width="450" height="450"><Fill color="#ff000000"/></Ellipse><Launch target="a/.B"/></PartDraw>
      </Group>
      """
        .trimIndent(),
    )
  }
}
