package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WatchFaceXmlExporterTest {
  private val root: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/wff-watchface").isDirectory }
      .let { File(it, "docs/design/fixtures/ui-builder/wff-watchface") }

  private val published =
    Json.parseToJsonElement(
      File(root.parentFile, "wff-watchface-published-v1.json").readText(),
    ) as JsonObject

  private fun statusSemantics() = published.getValue("statusSemantics") as JsonObject

  private fun template(name: String): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      File(root, "designs/$name.json").readText(),
    )

  /**
   * The analog face as it was drawn from bitmaps: an `AnalogClock` with three hands and a dial
   * image. The published template draws with shapes now, and these tests need the bitmap elements.
   */
  private fun bitmapTemplate(): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      File(root, "fixtures/bitmap-analog-watch-face.json").readText(),
    )

  private fun emitted(
    document: UiBuilderDocument,
    options: WatchFaceXmlExporter.Options = WatchFaceXmlExporter.Options(),
  ) = assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document, options))

  private fun refused(document: UiBuilderDocument) =
    assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(document)).reasons

  private fun golden(name: String, actual: String) {
    val file = File(root, "golden/$name.xml")
    if (System.getenv("WFF_UPDATE_GOLDENS") == "1") file.writeText(actual)
    assertEquals(file.readText(), actual, "golden $name; rerun with WFF_UPDATE_GOLDENS=1 to update")
  }

  @Test
  fun `the analog template exports its golden WFF XML`() {
    golden("analog-watch-face", emitted(template("analog-watch-face")).xml)
  }

  @Test
  fun `the digital template exports its golden WFF XML`() {
    golden("digital-watch-face", emitted(template("digital-watch-face")).xml)
  }

  @Test
  fun `export is deterministic and survives a persisted reload`() {
    val document = template("analog-watch-face")
    val reloaded =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        Json.encodeToString(UiBuilderDocument.serializer(), document),
      )
    assertEquals(emitted(document).xml, emitted(reloaded).xml)
  }

  @Test
  fun `the wff version defaults to 2, comes from the scene and is overridden by options`() {
    val document = template("digital-watch-face")
    assertEquals(2, emitted(document).wffVersion)
    val v4 =
      document.copy(
        nodes =
          document.nodes +
            ("scene" to
              document.nodes.getValue("scene").let {
                it.copy(
                  properties =
                    JsonObject(
                      it.properties +
                        ("wffVersion" to
                          buildJsonObject {
                            put("type", "int")
                            put("value", 4)
                          }),
                    ),
                )
              }),
      )
    assertEquals(4, emitted(v4).wffVersion)
    assertTrue("version 4" in emitted(v4).xml)
    assertEquals(1, emitted(v4, WatchFaceXmlExporter.Options(wffVersion = 1)).wffVersion)
    val bad = refusedOptions(v4, WatchFaceXmlExporter.Options(wffVersion = 9))
    assertTrue(bad.any { "wffVersion 9" in it }, bad.toString())
  }

  private fun withProperty(
    document: UiBuilderDocument,
    id: String,
    name: String,
    type: String,
    value: kotlinx.serialization.json.JsonElement,
  ) =
    document.copy(
      nodes =
        document.nodes +
          (id to
            document.nodes.getValue(id).let {
              it.copy(
                properties =
                  JsonObject(
                    it.properties +
                      (name to
                        buildJsonObject {
                          put("type", type)
                          put("value", value)
                        }),
                  ),
              )
            }),
    )

  @Test
  fun `an ellipse part keeps its leftover corner radii without refusing the export`() {
    val round = template("digital-watch-face")
    assertTrue("cornerRadiusX" in emitted(round).xml)
    // The part is switched to an ellipse in the editor; its round-rectangle properties stay behind.
    val ellipse = withProperty(round, "steps-back", "shape", "string", JsonPrimitive("ellipse"))
    assertTrue("cornerRadiusX" in ellipse.nodes.getValue("steps-back").properties)
    val xml = emitted(ellipse).xml
    assertTrue("<Ellipse" in xml, xml)
    assertFalse("cornerRadius" in xml, xml)
    assertEquals(
      setOf("cornerRadiusX", "cornerRadiusY"),
      WatchFaceXmlExporter.inapplicableProperties(ellipse.nodes.getValue("steps-back"))
        .filter { it.startsWith("cornerRadius") }
        .toSet(),
    )
    assertTrue(
      WatchFaceXmlExporter.inapplicableProperties(round.nodes.getValue("steps-back")).none {
        it.startsWith("cornerRadius")
      },
    )
  }

  @Test
  fun `a stroke width with no stroke colour is dropped, not refused`() {
    val round = template("digital-watch-face")
    emitted(withProperty(round, "steps-back", "strokeWidth", "float", JsonPrimitive(3)))
  }

  /** Every x, y, width and height the document holds, moved off the integers. */
  private fun fractional(document: UiBuilderDocument, seed: Int): UiBuilderDocument {
    val random = kotlin.random.Random(seed)
    return document.copy(
      nodes =
        document.nodes.mapValues { (_, node) ->
          if (node.componentId == WatchFaceXmlExporter.SCENE) node
          else
            node.copy(
              properties =
                JsonObject(
                  node.properties.mapValues { (name, value) ->
                    val number =
                      ((value as? JsonObject)?.get("value") as? JsonPrimitive)
                        ?.content
                        ?.toDoubleOrNull()
                    if (name in setOf("x", "y", "width", "height") && number != null)
                      buildJsonObject {
                        put("type", "float")
                        put(
                          "value",
                          (number + random.nextInt(1, 100) / 100.0).let {
                            kotlin.math.round(it * 100) / 100.0
                          },
                        )
                      }
                    else value
                  },
                ),
            )
        },
    )
  }

  @Test
  fun `fractional geometry exports to XML the format validator accepts`() {
    // `emitted` runs the same WffValidator the user's failing export ran; a refusal is an error.
    var notes = 0
    listOf("analog-watch-face", "digital-watch-face").forEach { name ->
      for (seed in 1..40) {
        val outcome = WatchFaceXmlExporter.export(fractional(template(name), seed))
        val result =
          outcome as? WatchFaceXmlExporter.Result.Emitted
            ?: error(
              "$name seed $seed: ${(outcome as WatchFaceXmlExporter.Result.Refused).reasons.take(5)}",
            )
        notes += result.notes.size
        assertFalse(
          Regex("""(x|y|width|height)="[-0-9]*\.[0-9]+"""")
            .containsMatchIn(
              Regex(
                  "<(PartDraw|Group|PartText|PartImage|ComplicationSlot|AnalogClock|DigitalClock)\\b[^>]*>",
                )
                .findAll(result.xml)
                .joinToString("\n") { it.value },
            ),
          result.xml,
        )
      }
    }
    // The elements that cannot absorb an offset are rounded, and each says so.
    assertTrue(notes > 0)
  }

  @Test
  fun `a fractional part draw keeps its shapes at the same pixels`() {
    val round = template("digital-watch-face")
    val part = round.nodes.getValue("steps-back")
    fun set(doc: UiBuilderDocument, name: String, v: Double) =
      withProperty(doc, "steps-back", name, "float", JsonPrimitive(v))
    val moved =
      listOf("x" to 211.3, "y" to 20.6, "width" to 60.5, "height" to 30.2).fold(round) { d, (n, v)
        ->
        set(d, n, v)
      }
    val xml = emitted(moved).xml
    // floor(211.3)=211, floor(20.6)=20, ceil(271.8)-211=61, ceil(50.8)-20=31
    assertTrue("""<PartDraw x="211" y="20" width="61" height="31"""" in xml, xml)
    assertTrue("""x="0.3"""" in xml && """y="0.6"""" in xml, xml)
    assertTrue(part.properties.isNotEmpty())
  }

  @Test
  fun `the design check and the export agree on a design the export refuses`() {
    val good = template("digital-watch-face")
    assertTrue(WatchFaceDesignValidation.exportIssues(good).isEmpty())
    // A shape the format has no element for: the export refuses it.
    val bad = withProperty(good, "steps-back", "shape", "string", JsonPrimitive("not-a-shape"))
    val issues = WatchFaceDesignValidation.exportIssues(bad)
    assertEquals(refused(bad).size, issues.size)
    assertEquals("steps-back", issues.first().nodeId)
    assertEquals(refused(bad), issues.map { it.message })
  }

  @Test
  fun `a rotated fractional part draw keeps its centre of rotation`() {
    val round = template("digital-watch-face")
    val moved =
      listOf(
          "x" to 10.9,
          "y" to 20.0,
          "width" to 10.0,
          "height" to 30.0,
          "angle" to 180.0,
          "pivotX" to 0.0,
        )
        .fold(round) { d, (n, v) -> withProperty(d, "steps-back", n, "float", JsonPrimitive(v)) }
    val xml = emitted(moved).xml
    // X=10, W=ceil(20.9)-10=11; the centre stays at 10.9: (0*10 + 0.9) / 11
    assertTrue("""<PartDraw x="10" y="20" width="11" height="30"""" in xml, xml)
    val pivot = Regex("""pivotX="([0-9.]+)"""").find(xml)!!.groupValues[1].toDouble()
    assertEquals(0.9 / 11, pivot, 1e-5)
  }

  @Test
  fun `a fractional group imports losslessly and is only rounded on export`() {
    val xml =
      """<WatchFace width="450" height="450"><Scene><Group name="g" x="0.5" y="0" width="450" height="450"/></Scene></WatchFace>"""
    val imported = WatchFaceXmlImporter.import(xml)
    val document = assertIs<WatchFaceXmlImporter.Result.Imported>(imported).document
    assertTrue(emitted(document).notes.isNotEmpty())
  }

  private fun refusedOptions(document: UiBuilderDocument, options: WatchFaceXmlExporter.Options) =
    assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(document, options))
      .reasons

  @Test
  fun `a node that is not a watch face element is refused by name and location`() {
    val document = template("digital-watch-face")
    val foreign =
      document.copy(
        nodes =
          document.nodes +
            ("title" to document.nodes.getValue("title").copy(componentId = "m3/button")),
      )
    val reasons = refused(foreign)
    assertEquals(1, reasons.size, reasons.toString())
    assertTrue(
      reasons.single().startsWith("nodes.title (`m3/button`): is not a Watch Face Format element"),
      reasons.single(),
    )
  }

  @Test
  fun `a hand outside an analog clock, a missing resource and bindings are refused`() {
    val document = bitmapTemplate()
    val misplaced =
      document.copy(
        nodes =
          document.nodes +
            ("scene" to
              document.nodes.getValue("scene").let {
                it.copy(slots = mapOf("content" to listOf("dial", "battery", "clock", "hour")))
              }),
      )
    assertTrue(
      refused(misplaced).any { "nodes.hour (`wff/clock-hand`)" in it },
      refused(misplaced).toString(),
    )

    val noResource =
      document.copy(
        nodes =
          document.nodes +
            ("dial" to document.nodes.getValue("dial").copy(properties = JsonObject(emptyMap()))),
      )
    assertTrue(refused(noResource).any { "nodes.dial (`wff/part-image`): resource" in it })

    val bound =
      document.copy(
        nodes =
          document.nodes +
            ("dial" to
              document.nodes
                .getValue("dial")
                .copy(
                  properties =
                    JsonObject(
                      mapOf(
                        "resource" to
                          buildJsonObject {
                            put("type", "binding")
                            put("value", "someState")
                          },
                      ),
                    ),
                )),
      )
    assertTrue(refused(bound).any { "only literal values" in it })

    val withState = document.copy(stateVariables = JsonObject(mapOf("x" to JsonObject(emptyMap()))))
    assertTrue(refused(withState).any { it.startsWith("stateVariables") })
  }

  @Test
  fun `a document must have exactly one scene root`() {
    val document = template("analog-watch-face")
    assertTrue(refused(document.copy(roots = emptyList())).single().startsWith("roots"))
    assertTrue(
      refused(document.copy(roots = listOf("dial"))).single().contains("must be `wff/scene`"),
    )
  }

  @Test
  fun `the published catalog is a watch face catalog and the templates are readable`() {
    assertEquals(
      UiBuilderCatalogPlatform.WATCH_FACE,
      UiBuilderCatalogPlatform.from(statusSemantics()),
    )
    val frame = statusSemantics().getValue("frame") as JsonObject
    assertEquals(JsonPrimitive("frame/round-screen"), frame["adapter"])
    val surfaces = statusSemantics().getValue("previewSurfaces") as JsonObject
    assertEquals(
      JsonPrimitive("android-dwf"),
      (surfaces["native"] as JsonObject)["backend"],
    )
    val builtins = (statusSemantics().getValue("builtins") as JsonObject).keys
    assertEquals(
      setOf(
        WatchFaceXmlExporter.SCENE,
        WatchFaceXmlExporter.GROUP,
        WatchFaceXmlExporter.PART_DRAW,
        WatchFaceXmlExporter.PART_IMAGE,
        WatchFaceXmlExporter.PART_TEXT,
        WatchFaceXmlExporter.ANALOG_CLOCK,
        WatchFaceXmlExporter.CLOCK_HAND,
        WatchFaceXmlExporter.DIGITAL_CLOCK,
        WatchFaceXmlExporter.TIME_TEXT,
        WatchFaceXmlExporter.COMPLICATION_SLOT,
        WatchFaceXmlExporter.VARIANT,
        WatchFaceXmlExporter.TRANSFORM,
        WatchFaceXmlExporter.GYRO,
        WatchFaceXmlExporter.CONDITION,
        WatchFaceXmlExporter.COMPARE,
        WatchFaceXmlExporter.DEFAULT,
        WatchFaceXmlExporter.LAUNCH,
        WatchFaceXmlExporter.COMPLICATION,
        WatchFaceXmlExporter.SHAPE,
        WatchFaceXmlExporter.FILL,
        WatchFaceXmlExporter.STROKE,
        WatchFaceXmlExporter.USER_CONFIGURATIONS,
        WatchFaceXmlExporter.COLOR_CONFIGURATION,
        WatchFaceXmlExporter.COLOR_OPTION,
        WatchFaceXmlExporter.LIST_CONFIGURATION,
        WatchFaceXmlExporter.LIST_OPTION,
        WatchFaceXmlExporter.BOOLEAN_CONFIGURATION,
        WatchFaceXmlExporter.FLAVORS,
        WatchFaceXmlExporter.FLAVOR,
        WatchFaceXmlExporter.FLAVOR_CONFIGURATION,
        WatchFaceXmlExporter.SCENE_BOOLEAN_CONFIGURATION,
        WatchFaceXmlExporter.SCENE_BOOLEAN_OPTION,
        WatchFaceXmlExporter.SCENE_LIST_CONFIGURATION,
        WatchFaceXmlExporter.SCENE_LIST_OPTION,
        WatchFaceXmlExporter.RAW,
      ),
      builtins,
    )
    val paths =
      (statusSemantics().getValue("templates") as JsonArray).map { (it as JsonPrimitive).content }
    val read =
      assertIs<CatalogSeedTemplates.Result.Read>(
        CatalogSeedTemplates.read(WatchFaceXmlExporter.CATALOG_SYSTEM_ID, paths) {
          File(root, it.removePrefix("ui-builder/")).takeIf(File::isFile)?.readText()
        },
      )
    assertEquals(listOf("analog-watch-face", "digital-watch-face"), read.templates.ids)
    // Every node of both templates is a component the catalog declares.
    read.templates.templates.forEach { template ->
      template.document.nodes.values.forEach {
        assertTrue(it.componentId in builtins, it.componentId)
      }
    }
  }

  @Test
  fun `routing sends wff-watchface to the watch face platform and never offers compose`() {
    val route =
      CatalogExportRouting.Route.RecordFree(
        CatalogExportRouting.WATCH_FACE,
        UiBuilderCatalogPlatform.WATCH_FACE,
      )
    assertEquals(
      UiBuilderCatalogPlatform.WATCH_FACE,
      CatalogExportRouting.recordFreePlatform(route),
    )
    assertFalse(CatalogExportRouting.exportsCompose(route) { true })
    assertFalse(
      CatalogExportRouting.exportsCompose(
        CatalogExportRouting.Route.BuiltIn(UiBuilderCatalogPlatform.WATCH_FACE),
      ) {
        true
      },
    )
    assertEquals("watchface", UiBuilderCatalogPlatform.WATCH_FACE.wireValue)
    assertEquals(
      UiBuilderCatalogPlatform.WATCH_FACE,
      UiBuilderCatalogPlatform.fromWord("watchface"),
    )
  }
}
