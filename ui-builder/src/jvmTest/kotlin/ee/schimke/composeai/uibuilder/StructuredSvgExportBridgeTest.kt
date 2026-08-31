package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

@OptIn(ExperimentalComposeUiApi::class)
class StructuredSvgExportBridgeTest {
  private val document by lazy {
    UiBuilderReducer.replay(resourceJson("/jetcaster-discover-operations-v1.json")).document
  }
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  }

  @Test
  fun `JVM Skia bridge exposes the same-runtime Jetcaster anonymous raster blocker`() {
    val verified = catalog.asVectorVerified()
    val job = document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)

    val firstRaw = JvmSkiaStructuredSvgRecorder.record(document)
    val secondRaw = JvmSkiaStructuredSvgRecorder.record(document)
    assertEquals(firstRaw, secondRaw)
    assertEquals(SAME_RUNTIME_DETERMINISM_SCOPE, firstRaw.determinismScope)
    assertTrue(Regex("<(?:g|path|rect|text)\\b").containsMatchIn(firstRaw.svg))
    assertTrue(firstRaw.svg.contains("<image"), "Skia rasterizes at least the filtered icon mask")
    assertFalse(Regex("(?:href|src)=\"https?://").containsMatchIn(firstRaw.svg))

    val result = executeSavedDocumentSvgExport(job, verified, JvmSkiaStructuredSvgRecorder)
    val rejected = assertIs<SavedDocumentSvgExportResult.Rejected>(result)
    assertTrue(rejected.blockers.any { it.code == "RASTER_RECORD_COUNT_MISMATCH" })
  }

  @Test
  fun `JVM Skia bridge exports a supported text saved document as structured SVG`() {
    val node = document.nodes.getValue("search-placeholder")
    val textDocument =
      document.copy(
        id = "saved-text-design",
        revision = 7,
        roots = listOf(node.id),
        nodes = mapOf(node.id to node),
      )
    val textCatalog =
      catalog
        .copy(components = listOf(catalog.componentsById.getValue("m3/text")))
        .asVectorVerified()
    val job = textDocument.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)

    val first =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(job, textCatalog, JvmSkiaStructuredSvgRecorder)
      )
    val second =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(job, textCatalog, JvmSkiaStructuredSvgRecorder)
      )

    assertEquals(first.svg, second.svg)
    assertTrue(first.svg.contains("<text"))
    assertTrue(first.svg.contains("Search for a podcast"))
    assertTrue(first.svg.contains("id=\"compose-ui-builder-export\""))
    assertTrue(first.svg.contains("revision=7"))
    assertFalse(first.svg.contains("<image"))
    assertFalse(Regex("(?:href|src)=\"https?://").containsMatchIn(first.svg))
    assertEquals("skia-svg-canvas/0.144.6", first.producer)
    assertTrue(first.svg.contains("determinismScope=$SAME_RUNTIME_DETERMINISM_SCOPE"))
    assertSvgRasterCloseToCompose(first.svg, textDocument)
  }

  @Test
  fun `revision mismatch rejects before recorder execution`() {
    var called = false
    val job =
      document
        .job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)
        .copy(pin = SavedDocumentRevisionPin.from(document).copy(revision = document.revision - 1))

    val result =
      executeSavedDocumentSvgExport(
        job,
        catalog.asVectorVerified(),
        recorder {
          called = true
          StructuredSvgRecording("<svg><rect/></svg>", "must-not-run")
        },
      )

    val rejected = assertIs<SavedDocumentSvgExportResult.Rejected>(result)
    assertFalse(called)
    assertEquals("SAVED_REVISION_PIN_MISMATCH", rejected.blockers.single().code)
  }

  @Test
  fun `content digest rejects changed document bytes at the same identity and revision`() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      sha256Hex("abc"),
    )
    var called = false
    val originalPin = SavedDocumentRevisionPin.from(document)
    val changed = document.copy(title = "same revision, different saved bytes")
    val result =
      executeSavedDocumentSvgExport(
        SavedDocumentSvgExportJob(
          originalPin,
          changed,
          StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS,
        ),
        catalog.asVectorVerified(),
        recorder {
          called = true
          StructuredSvgRecording("<svg><rect/></svg>", "must-not-run")
        },
      )

    assertFalse(called)
    assertEquals(
      "SAVED_REVISION_PIN_MISMATCH",
      assertIs<SavedDocumentSvgExportResult.Rejected>(result).blockers.single().code,
    )
  }

  @Test
  fun `recorder kind mismatch and recorder failures are typed`() {
    var called = false
    val job = document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)
    val mismatch =
      executeSavedDocumentSvgExport(
        job,
        catalog.asVectorVerified(),
        recorder(StructuredSvgRecorderKind.WASM_CANVASKIT) {
          called = true
          StructuredSvgRecording("<svg><rect/></svg>", "must-not-run")
        },
      )
    assertFalse(called)
    assertEquals(
      "SVG_RECORDER_KIND_MISMATCH",
      assertIs<SavedDocumentSvgExportResult.Rejected>(mismatch).blockers.single().code,
    )

    val failed =
      executeSavedDocumentSvgExport(
        job,
        catalog.asVectorVerified(),
        recorder { error("deliberate recorder failure") },
      )
    assertEquals("SVG_RECORDER_FAILED", assertIs<SavedDocumentSvgExportResult.Failed>(failed).code)
  }

  @Test
  fun `declared raster fallback is self-contained and named in deterministic metadata`() {
    val hybrid =
      catalog.asVectorVerified().withComponentSvg("asset/image") { svg ->
        svg.copy(
          status = "raster-fallback-required",
          fallback = "embedded-raster",
          blocksExport = false,
        )
      }
    val result =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
          hybrid,
          recorder {
            StructuredSvgRecording(
              "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1280\" height=\"800\">" +
                "<g><rect width=\"1\" height=\"1\"/></g>" +
                "<image x=\"8\" y=\"8\" width=\"152\" height=\"152\" href=\"data:image/png;base64,AA==\"/>" +
                "<image x=\"200\" y=\"8\" width=\"56\" height=\"56\" href=\"data:image/png;base64,AA==\"/>" +
                "<image x=\"300\" y=\"8\" width=\"128\" height=\"128\" href=\"data:image/png;base64,AA==\"/>" +
                "<image x=\"450\" y=\"8\" width=\"128\" height=\"128\" href=\"data:image/png;base64,AA==\"/>" +
                "</svg>",
              "fixture-structured-recorder",
              rasterRecords =
                listOf(
                  StructuredSvgRasterRecord("detail-artwork", 0, "asset image"),
                  StructuredSvgRasterRecord("main-episode-image", 1, "asset image"),
                  StructuredSvgRasterRecord("podcast-card-android-image", 2, "asset image"),
                  StructuredSvgRasterRecord("podcast-card-google-image", 3, "asset image"),
                ),
            )
          },
        )
      )

    assertTrue(
      result.svg.contains(
        "rasterFallbackNodeIds=detail-artwork,main-episode-image,podcast-card-android-image,podcast-card-google-image"
      )
    )
    assertTrue(result.svg.contains("data:image/png;base64,AA=="))
    assertTrue(result.svg.contains("data-compose-node-id=\"detail-artwork\""))
    assertFalse(Regex("href=\"https?://").containsMatchIn(result.svg))
  }

  @Test
  fun `external references and full-screen raster wrappers are rejected`() {
    val job = document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)
    val verified = catalog.asVectorVerified()
    val external =
      executeSavedDocumentSvgExport(
        job,
        verified,
        recorder {
          StructuredSvgRecording(
            "<svg><g><rect/></g><image href=\"https://example.test/a.png\"/></svg>",
            "bad",
          )
        },
      )
    assertTrue(
      assertIs<SavedDocumentSvgExportResult.Rejected>(external).blockers.any {
        it.code == "SVG_EXTERNAL_REFERENCE"
      }
    )

    val wrapper =
      executeSavedDocumentSvgExport(
        job,
        verified,
        recorder {
          StructuredSvgRecording("<svg><image href=\"data:image/png;base64,AA==\"/></svg>", "bad")
        },
      )
    val wrapperCodes =
      assertIs<SavedDocumentSvgExportResult.Rejected>(wrapper).blockers.map { it.code }.toSet()
    assertTrue("SVG_VIEWPORT_UNVERIFIED" in wrapperCodes)
  }

  @Test
  fun `strict SVG boundary rejects active XML and every external reference form`() {
    val cases =
      mapOf(
        "xml wrapper" to "<?xml version=\"1.0\"?><html><svg><rect/></svg></html>",
        "xml stylesheet instruction" to "<?xml-stylesheet href=\"other.css\"?><svg><rect/></svg>",
        "doctype" to "<!DOCTYPE svg><svg><rect/></svg>",
        "entity declaration" to
          "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///tmp/secret\">]><svg><text>&xxe;</text></svg>",
        "unknown entity" to "<svg><text>&xxe;</text><rect/></svg>",
        "script" to "<svg><script>bad()</script><rect/></svg>",
        "foreign object" to "<svg><foreignObject><div>bad</div></foreignObject><rect/></svg>",
        "relative use" to "<svg><rect/><use href=\"other.svg#shape\"/></svg>",
        "file use" to "<svg><rect/><use href=\"file:///tmp/shape.svg#shape\"/></svg>",
        "relative css url" to "<svg><rect style=\"fill:url(other.svg#paint)\"/></svg>",
        "css import" to "<svg><style>@import url('other.css');</style><rect/></svg>",
        "active embedded svg image" to
          "<svg width=\"10\" height=\"10\"><rect/><image x=\"1\" y=\"1\" width=\"2\" height=\"2\" href=\"data:image/svg+xml;base64,AA==\"/></svg>",
      )
    cases.forEach { (label, svg) ->
      val result = executeFixtureSvg(svg)
      assertIs<SavedDocumentSvgExportResult.Rejected>(result, label)
    }
  }

  @Test
  fun `full viewport raster with trivial vector decoration rejects`() {
    val result =
      executeFixtureSvg(
        "<svg width=\"1280\" height=\"800\"><rect width=\"1\" height=\"1\"/>" +
          "<image x=\"0\" y=\"0\" width=\"1280\" height=\"800\" " +
          "href=\"data:image/png;base64,AA==\"/></svg>"
      )
    assertTrue(
      assertIs<SavedDocumentSvgExportResult.Rejected>(result).blockers.any {
        it.code == "FULL_SCREEN_RASTER_WRAPPER"
      }
    )
  }

  @Test
  fun `unsupported JVM environment rejects before recorder execution`() {
    var called = false
    val unsupported =
      document.copy(
        environment =
          JsonObject(
            document.environment.toMutableMap().apply {
              put("locale", Json.parseToJsonElement("\"fr-FR\""))
            }
          )
      )
    val result =
      executeSavedDocumentSvgExport(
        unsupported.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
        catalog.asVectorVerified(),
        recorder {
          called = true
          StructuredSvgRecording("<svg><rect/></svg>", "must-not-run")
        },
      )
    assertFalse(called)
    assertTrue(
      assertIs<SavedDocumentSvgExportResult.Rejected>(result).blockers.any {
        it.code == "UNSUPPORTED_SVG_ENVIRONMENT"
      }
    )
  }

  private fun UiBuilderDocument.job(kind: StructuredSvgRecorderKind) =
    SavedDocumentSvgExportJob(SavedDocumentRevisionPin.from(this), this, kind)

  private fun recorder(
    kind: StructuredSvgRecorderKind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS,
    block: (UiBuilderDocument) -> StructuredSvgRecording,
  ) =
    object : StructuredSvgSceneRecorder {
      override val kind = kind

      override fun record(document: UiBuilderDocument) = block(document)
    }

  private fun executeFixtureSvg(svg: String): SavedDocumentSvgExportResult =
    executeSavedDocumentSvgExport(
      document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
      catalog.asVectorVerified(),
      recorder { StructuredSvgRecording(svg, "adversarial-fixture") },
    )

  private fun CapabilityCatalog.asVectorVerified() =
    copy(
      components =
        components.map { component ->
          component.copy(
            svg =
              checkNotNull(component.svg)
                .copy(status = "verified", fallback = "none", blocksExport = false)
          )
        }
    )

  private fun assertSvgRasterCloseToCompose(svg: String, source: UiBuilderDocument) {
    val density = source.environment.getValue("density").toString().toFloat()
    val fontScale = source.environment.getValue("fontScale").toString().toFloat()
    val width = (source.environment.getValue("widthDp").toString().toFloat() * density).roundToInt()
    val height =
      (source.environment.getValue("heightDp").toString().toFloat() * density).roundToInt()
    val direct =
      renderComposeScene(width, height, Density(density, fontScale)) {
        UiBuilderSurface(document = source, editorOverlay = false)
      }
    val svgData = Data.makeFromBytes(svg.encodeToByteArray())
    val dom = SVGDOM(svgData).apply { setContainerSize(width.toFloat(), height.toFloat()) }
    val surface = Surface.makeRasterN32Premul(width, height)
    dom.render(surface.canvas)
    val roundTrip = surface.makeImageSnapshot()
    val directBitmap = Bitmap().apply { allocN32Pixels(width, height) }
    val svgBitmap = Bitmap().apply { allocN32Pixels(width, height) }
    assertTrue(direct.readPixels(directBitmap))
    assertTrue(roundTrip.readPixels(svgBitmap))
    var differing = 0
    for (y in 0 until height) {
      for (x in 0 until width) {
        if (directBitmap.getColor(x, y) != svgBitmap.getColor(x, y)) differing++
      }
    }
    val differingRatio = differing.toDouble() / (width.toDouble() * height.toDouble())
    assertTrue(differingRatio < 0.01, "SVG raster differs on $differingRatio of pixels")
    svgBitmap.close()
    directBitmap.close()
    roundTrip.close()
    surface.close()
    dom.close()
    svgData.close()
    direct.close()
  }

  private fun CapabilityCatalog.withComponentSvg(
    componentId: String,
    transform:
      (
        ee.schimke.composeai.uibuilder.capability.SvgCapability
      ) -> ee.schimke.composeai.uibuilder.capability.SvgCapability,
  ) =
    copy(
      components =
        components.map { component ->
          if (component.componentId == componentId) {
            component.copy(svg = transform(checkNotNull(component.svg)))
          } else component
        }
    )

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()

  private fun resourceJson(path: String): JsonObject =
    Json.parseToJsonElement(resource(path)).jsonObject
}
