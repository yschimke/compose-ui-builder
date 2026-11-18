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
