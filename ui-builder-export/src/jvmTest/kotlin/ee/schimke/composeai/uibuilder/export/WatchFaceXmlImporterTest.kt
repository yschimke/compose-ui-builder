package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.wff.WffXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WatchFaceXmlImporterTest {
  private val fixtures: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/wff-watchface").isDirectory }
      .let { File(it, "docs/design/fixtures/ui-builder/wff-watchface") }

  private val samples =
    listOf(
      "SimpleAnalog",
      "SimpleDigital",
      "Complications",
      "Flavors",
      "Weather",
      "PhotosMulti",
      "PhotosMask",
    )

  private fun sample(name: String): String =
    checkNotNull(javaClass.getResource("/wear-os-samples/$name/raw/watchface.xml")).readText()

  private fun imported(
    xml: String,
    options: WatchFaceXmlImporter.Options = WatchFaceXmlImporter.Options(),
  ) = assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml, options))

  private fun exported(document: UiBuilderDocument) =
    assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document))

  @Test
  fun `every wear-os-samples watch face imports and exports to the same XML tree`() {
    for (name in samples) {
      val xml = sample(name)
      val result = imported(xml)
      val emitted = exported(result.document)
      // Parsed with the same parser: element names, attribute order and values, text, comments.
      assertEquals(WffXmlParser.parse(xml), WffXmlParser.parse(emitted.xml), name)
      val builtins =
        result.document.nodes.values.count { it.componentId != WatchFaceXmlExporter.RAW }
      val raws = result.document.nodes.values.count { it.componentId == WatchFaceXmlExporter.RAW }
      println("WFF-IMPORT $name: $builtins builtin nodes, $raws raw nodes; ${result.warnings}")
      assertTrue(builtins >= 1, name)
    }
  }

  @Test
  fun `import is stable under a second round trip and a persisted reload`() {
    for (name in samples) {
      val first = exported(imported(sample(name)).document).xml
      val again = imported(first)
      assertEquals(first, exported(again.document).xml, name)
      val reloaded =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          Json.encodeToString(UiBuilderDocument.serializer(), again.document),
        )
      assertEquals(first, exported(reloaded).xml, name)
    }
  }

  @Test
  fun `the licence header and other comments survive as raw nodes`() {
    val document = imported(sample("SimpleAnalog")).document
    val scene = document.nodes.getValue(WatchFaceXmlImporter.SCENE_ID)
    val prolog = scene.slots.getValue("prolog").map { document.nodes.getValue(it) }
    assertEquals(2, prolog.size)
    assertTrue(prolog.all { it.componentId == WatchFaceXmlExporter.RAW })
    assertTrue("Copyright 2024 Google LLC" in prolog.first().properties.toString())
    // And in place, between elements: SimpleDigital comments sit inside its DigitalClock.
    val digital = imported(sample("SimpleDigital")).document
    assertTrue(
      digital.nodes.values.any {
        it.componentId == WatchFaceXmlExporter.RAW &&
          "SYNC_TO_DEVICE specifies" in it.properties.toString()
      },
    )
  }

  @Test
  fun `the templates round-trip export to import to export byte for byte`() {
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val template =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          File(fixtures, "designs/$name.json").readText(),
        )
      val xml = exported(template).xml
      val result = imported(xml)
      assertEquals(xml, exported(result.document).xml, name)
      // The parts the exporter writes are taken back as nodes, not kept as XML.
      val components = result.document.nodes.values.map { it.componentId }.toSet()
      assertTrue(WatchFaceXmlExporter.CLOCK_HAND in components || name.startsWith("digital"), name)
      assertTrue(WatchFaceXmlExporter.COMPLICATION_SLOT in components, name)
      assertTrue(WatchFaceXmlExporter.PART_TEXT in components || name.startsWith("analog"), name)
    }
  }

  @Test
  fun `an imported node stays editable and keeps its place and attribute order`() {
    val result = imported(sample("PhotosMulti"))
    val group =
      result.document.nodes.values.first {
        it.componentId == WatchFaceXmlExporter.GROUP &&
          "photos_container" in it.properties.toString()
      }
    val edited =
      result.document.copy(
        nodes =
          result.document.nodes +
            (group.id to
              group.copy(
                properties =
                  JsonObject(
                    group.properties +
                      ("x" to
                        buildJsonObject {
                          put("type", "int")
                          put("value", 12)
                        }),
                  ),
              )),
      )
    val xml = exported(edited).xml
    assertTrue(
      Regex(
          """<Group x="12" y="0" width="450" height="450" angle="0" pivotX="0.5" pivotY="0.5" alpha="255" name="photos_container">""",
        )
        .containsMatchIn(xml),
      xml,
    )
    assertEquals(
      WffXmlParser.parse(exported(result.document).xml).root.childElements.size,
      WffXmlParser.parse(xml).root.childElements.size,
    )
  }

  @Test
  fun `an element with no builtin survives as raw xml at the same position`() {
    val xml =
      """
      <WatchFace width="450" height="450">
        <FutureThing a="1"><Nested b="x &amp; y">text</Nested></FutureThing>
        <Scene>
          <Group x="0" y="0" width="450" height="450">
            <Gyro name="g"><Parameter expression="[ACCELEROMETER_X]"/></Gyro>
            <PartDraw x="0" y="0" width="10" height="10"><Rectangle x="0" y="0" width="10" height="10"><Fill color="#fff"/></Rectangle></PartDraw>
          </Group>
          <Condition><Compare expression="1"><Arm/></Compare></Condition>
        </Scene>
      </WatchFace>
      """
        .trimIndent()
    val result = imported(xml)
    val raws = result.document.nodes.values.filter { it.componentId == WatchFaceXmlExporter.RAW }
    assertEquals(3, raws.size)
    assertTrue(raws.any { "FutureThing" in it.properties.toString() })
    assertTrue(result.warnings.single { "no wff/ element" in it }.contains("Gyro x1"))
    assertEquals(WffXmlParser.parse(xml), WffXmlParser.parse(exported(result.document).xml))
    // The Group is still a Group, and keeps the raw Gyro before its PartDraw.
    val group = result.document.nodes.values.single { it.componentId == WatchFaceXmlExporter.GROUP }
    assertEquals(
      listOf(WatchFaceXmlExporter.RAW, WatchFaceXmlExporter.PART_DRAW),
      group.slots.getValue("content").map { result.document.nodes.getValue(it).componentId },
    )
  }

  @Test
  fun `the version is an option and lands on the scene`() {
    val xml = sample("SimpleDigital")
    val default = imported(xml).document.nodes.getValue("scene").properties
    assertEquals(JsonPrimitive(2), (default["wffVersion"] as JsonObject)["value"])
    val v4 = imported(xml, WatchFaceXmlImporter.Options(wffVersion = 4))
    assertEquals(
      JsonPrimitive(4),
      (v4.document.nodes.getValue("scene").properties["wffVersion"] as JsonObject)["value"],
    )
    assertEquals(4, exported(v4.document).wffVersion)
    val bad = WatchFaceXmlImporter.import(xml, WatchFaceXmlImporter.Options(wffVersion = 9))
    assertTrue(
      assertIs<WatchFaceXmlImporter.Result.Failed>(bad).reasons.single().contains("wffVersion 9"),
    )
  }

  @Test
  fun `malformed xml fails with a line and column and never throws`() {
    val malformed = "<WatchFace width=\"450\">\n  <Scene>\n    <Group>\n  </Scene>\n</WatchFace>"
    val failed =
      assertIs<WatchFaceXmlImporter.Result.Failed>(WatchFaceXmlImporter.import(malformed))
    assertNotNull(failed.line)
    assertNotNull(failed.column)
    assertEquals(4, failed.line)
    assertTrue("Mismatched closing tag" in failed.reasons.single(), failed.reasons.toString())

    for (bad in
      listOf("", "not xml", "<A/>", "<WatchFace/>", "<WatchFace><Scene/><Scene/></WatchFace>")) {
      assertIs<WatchFaceXmlImporter.Result.Failed>(WatchFaceXmlImporter.import(bad), bad)
    }
  }

  @Test
  fun `a property set after import is written even though the face never had the attribute`() {
    val template =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        File(fixtures, "designs/analog-watch-face.json").readText(),
      )
    val result = imported(exported(template).xml)
    val dial =
      result.document.nodes.values.first {
        it.componentId == WatchFaceXmlExporter.PART_IMAGE && "dial" in it.properties.toString()
      }
    assertTrue(!exported(result.document).xml.contains("tintColor=\"#ff00ff00\""))
    val tinted =
      result.document.copy(
        nodes =
          result.document.nodes +
            (dial.id to
              dial.copy(
                properties =
                  JsonObject(
                    dial.properties +
                      ("tintColor" to
                        buildJsonObject {
                          put("type", "string")
                          put("value", "#ff00ff00")
                        }),
                  ),
              )),
      )
    assertTrue(
      exported(tinted).xml.contains("<Image resource=\"dial\" tintColor=\"#ff00ff00\"/>"),
      exported(tinted).xml,
    )
  }

  @Test
  fun `imported nodes carry only properties the published catalog declares`() {
    val catalog =
      Json.parseToJsonElement(
        File(fixtures.parentFile, "wff-watchface-published-v1.json").readText(),
      ) as JsonObject
    val builtins =
      ((catalog["statusSemantics"] as JsonObject)["builtins"] as JsonObject).mapValues {
        (it.value as JsonObject)["properties"] as kotlinx.serialization.json.JsonArray
      }
    // The importer's table is the catalog.
    assertEquals(builtins.keys, WatchFaceCatalogShape.DECLARED.keys)
    for ((component, properties) in builtins) {
      val declared = WatchFaceCatalogShape.DECLARED.getValue(component)
      assertEquals(
        properties
          .map { (it as JsonObject)["name"]!!.let { n -> (n as JsonPrimitive).content } }
          .toSet(),
        declared.keys,
        component,
      )
      properties.forEach { property ->
        val obj = property as JsonObject
        val name = (obj["name"] as JsonPrimitive).content
        assertEquals(
          (obj["jsonType"] as JsonPrimitive).content,
          declared.getValue(name).type,
          "$component.$name",
        )
        assertEquals(
          (obj["allowedValues"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as JsonPrimitive).content }
            ?.toSet()
            .orEmpty(),
          declared.getValue(name).allowed,
          "$component.$name",
        )
      }
    }
    for (name in samples) {
      for (node in imported(sample(name)).document.nodes.values) {
        val declared = WatchFaceCatalogShape.DECLARED.getValue(node.componentId)
        assertTrue(
          node.properties.keys.all { it in declared },
          "$name ${node.id} ${node.properties.keys}",
        )
      }
    }
  }
}
