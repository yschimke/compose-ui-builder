package ee.schimke.composeai.uibuilder.export

import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `<Transform>` and `<Gyro>` as the `wff/transform` and `wff/gyro` builtins: imported as nodes,
 * written back as they were, the "Rotate with" presets as their expressions, and valid faces.
 */
class WatchFaceTransformTest {
  private val face =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <Scene backgroundColor="#ff000000">
        <Group name="hour" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5">
          <Transform target="angle" value="([HOUR_0_11]+[MINUTE]/60)*30"/>
          <PartDraw x="215" y="100" width="20" height="125">
            <Rectangle x="0" y="0" width="20" height="125"><Fill color="#ffffffff"/></Rectangle>
          </PartDraw>
        </Group>
        <Group name="drift" x="0" y="0" width="450" height="450">
          <Transform target="x" value="[SECOND] % 10" mode="BY"/>
          <Gyro x="[ACCELEROMETER_ANGLE_X] * 0.5" y="[ACCELEROMETER_ANGLE_Y] * 0.5"/>
          <PartText x="100" y="300" width="250" height="60">
            <Gyro angle="[ACCELEROMETER_ANGLE_X]"/>
            <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="32" color="#ffffffff"><Template>%s<Parameter expression="[SECOND]"/></Template></Font></Text>
          </PartText>
        </Group>
        <Group name="pulse" x="0" y="0" width="450" height="450">
          <Transform target="alpha" value="255">
            <Animation duration="1" interpolation="LINEAR"/>
          </Transform>
        </Group>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private fun imported(xml: String = face) =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(xml, WatchFaceXmlImporter.Options(wffVersion = 2)),
      )
      .document

  private fun emitted(document: UiBuilderDocument): WatchFaceXmlExporter.Result.Emitted =
    when (val result = WatchFaceXmlExporter.export(document)) {
      is WatchFaceXmlExporter.Result.Emitted -> result
      is WatchFaceXmlExporter.Result.Refused -> error("refused: ${result.reasons}")
    }

  private fun UiBuilderNode.text(name: String): String? =
    ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

  private fun UiBuilderDocument.ofComponent(component: String) =
    nodes.values.filter { it.componentId == component }

  private fun string(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun UiBuilderDocument.with(node: UiBuilderNode, vararg properties: Pair<String, String>) =
    copy(
      nodes =
        nodes +
          (node.id to
            node.copy(
              properties =
                JsonObject(node.properties + properties.map { (k, v) -> k to string(v) }),
            )),
    )

  @Test
  fun `transforms and gyros import as builtins and export to the same tree`() {
    val document = imported()
    assertEquals(3, document.ofComponent(WatchFaceXmlExporter.TRANSFORM).size)
    assertEquals(2, document.ofComponent(WatchFaceXmlExporter.GYRO).size)
    // Only the transform's animation, which has no builtin, is raw: inside the transform.
    val raws = document.ofComponent(WatchFaceXmlExporter.RAW)
    assertEquals(
      listOf("Animation"),
      raws.map { it.text("xml")!!.substringAfter('<').substringBefore(' ') },
    )
    val pulse =
      document.ofComponent(WatchFaceXmlExporter.TRANSFORM).single { it.text("target") == "alpha" }
    assertEquals(listOf(raws.single().id), pulse.slots["content"])

    val emitted = emitted((document))
    assertEquals(WffXmlParser.parse(face), WffXmlParser.parse(emitted.xml))
  }

  @Test
  fun `an angle driven by a preset's expression imports as that preset`() {
    val transforms = imported().ofComponent(WatchFaceXmlExporter.TRANSFORM)
    val hour = transforms.single { it.text("target") == "angle" }
    assertEquals("hour", hour.text("rotateWith"))
    assertNull(hour.text("value"))
    val drift = transforms.single { it.text("target") == "x" }
    assertNull(drift.text("rotateWith"))
    assertEquals("[SECOND] % 10", drift.text("value"))
    assertEquals("BY", drift.text("mode"))
  }

  @Test
  fun `each rotate-with preset writes its angle expression`() {
    val document = imported()
    val hour =
      document.ofComponent(WatchFaceXmlExporter.TRANSFORM).single {
        it.text("rotateWith") == "hour"
      }
    val expected =
      mapOf(
        "hour" to "([HOUR_0_11]+[MINUTE]/60)*30",
        "minute" to "([MINUTE]+[SECOND]/60)*6",
        "second-ticking" to "[SECOND]*6",
        "second-sweep" to "[SECOND_MILLISECOND]*6",
      )
    assertEquals(expected, WatchFaceXmlExporter.ROTATE_WITH)
    for ((preset, expression) in expected) {
      val xml =
        emitted(
            (document.with(hour, "rotateWith" to preset)),
          )
          .xml
      assertTrue("<Transform target=\"angle\" value=\"$expression\"/>" in xml, xml)
    }
    // Custom writes the transform's own value.
    val custom =
      emitted(document.with(hour, "rotateWith" to "custom", "value" to "[MINUTE] * 6")).xml
    assertTrue("<Transform target=\"angle\" value=\"[MINUTE] * 6\"/>" in custom, custom)
  }

  @Test
  fun `a transform that cannot be written is refused by name`() {
    val document = imported()
    val hour =
      document.ofComponent(WatchFaceXmlExporter.TRANSFORM).single {
        it.text("rotateWith") == "hour"
      }
    fun refusal(edited: UiBuilderDocument) =
      assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(edited))
        .reasons
        .joinToString()
    assertTrue("drives the angle" in refusal(document.with(hour, "target" to "alpha")))
    assertTrue("set rotateWith to custom" in refusal(document.with(hour, "value" to "[SECOND]")))
    assertTrue("rotateWith `daily`" in refusal(document.with(hour, "rotateWith" to "daily")))
    assertTrue(
      "target and value" in refusal(document.with(hour, "rotateWith" to "custom")),
      refusal(document.with(hour, "rotateWith" to "custom")),
    )
  }

  @Test
  fun `a gyro sits only where the format allows one`() {
    val document = imported()
    val gyro = document.ofComponent(WatchFaceXmlExporter.GYRO).first()
    val scene = document.nodes.getValue(document.roots.single())
    val moved =
      document.copy(
        nodes =
          document.nodes.mapValues { (_, node) ->
            node.copy(slots = node.slots.mapValues { (_, ids) -> ids - gyro.id })
          } +
            (scene.id to
              scene.copy(
                slots = scene.slots + ("content" to scene.slots.getValue("content") + gyro.id),
              )),
      )
    val reasons =
      assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(moved)).reasons
    assertTrue(reasons.any { gyro.id in it && "cannot be placed" in it }, reasons.toString())
  }

  @Test
  fun `the analog template's hands are typed transforms and export a valid face`() {
    val template =
      generateSequence(File("").absoluteFile) { it.parentFile }
        .map {
          File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs/analog-watch-face.json")
        }
        .first { it.isFile }
        .readText()
        .let { Json.decodeFromString(UiBuilderDocument.serializer(), it) }
    val presets =
      template
        .ofComponent(WatchFaceXmlExporter.TRANSFORM)
        .mapNotNull { it.text("rotateWith") }
        .sorted()
    assertEquals(listOf("hour", "minute", "second-sweep"), presets)
    assertTrue(
      template.ofComponent(WatchFaceXmlExporter.RAW).none { "Transform" in it.text("xml")!! },
    )
    for (document in listOf(template, imported())) {
      val emitted = emitted((document))
      val errors =
        WffValidator.errors(
          WffXmlParser.parse(emitted.xml),
          WffVersion.fromNumber(emitted.wffVersion)!!,
        )
      assertEquals(emptyList(), errors.map { "${it.path}: ${it.message}" })
      // And it reads back as the same design's XML.
      val again =
        assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(emitted.xml))
      assertEquals(
        WffXmlParser.parse(emitted.xml),
        WffXmlParser.parse(
          emitted((again.document)).xml,
        ),
      )
    }
  }

  @Test
  fun `a raw transform reads as the node an import makes of it`() {
    val node =
      assertNotNull(
        WatchFaceXmlImporter.transformNode("<Transform target=\"angle\" value=\"[SECOND]*6\"/>"),
      )
    assertEquals(WatchFaceXmlExporter.TRANSFORM, node.componentId)
    assertEquals("second-ticking", node.text("rotateWith"))
    assertNull(
      WatchFaceXmlImporter.transformNode(
        "<Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/>",
      ),
    )
    assertNull(WatchFaceXmlImporter.transformNode("<Transform"))
  }
}
