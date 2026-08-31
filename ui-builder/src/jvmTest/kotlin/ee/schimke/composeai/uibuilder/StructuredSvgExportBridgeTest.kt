package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
  fun `checked in Jetcaster catalog exports the full document with catalog icons as paths`() {
    val job = document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS)
    val firstRaw = JvmSkiaStructuredSvgRecorder.record(document)
    val secondRaw = JvmSkiaStructuredSvgRecorder.record(document)
    assertEquals(firstRaw, secondRaw)
    assertEquals(SAME_RUNTIME_DETERMINISM_SCOPE, firstRaw.determinismScope)
    assertTrue(Regex("<(?:g|path|rect|text)\\b").containsMatchIn(firstRaw.svg))
    assertFalse(firstRaw.svg.contains("<image"))
    assertTrue(firstRaw.rasterRecords.isEmpty())
    assertFalse(Regex("(?:href|src)=\"https?://").containsMatchIn(firstRaw.svg))

    val result = executeSavedDocumentSvgExport(job, catalog, JvmSkiaStructuredSvgRecorder)
    val exported = assertIs<SavedDocumentSvgExportResult.Ok>(result)
    val declaredAssets =
      document.nodes.values.filter { it.componentId == "asset/image" }.map { it.id }.sorted()
    assertEquals(declaredAssets.size, Regex("<image\\b").findAll(exported.svg).count())
    declaredAssets.forEach { nodeId ->
      assertTrue(exported.svg.contains("data-compose-node-id=\"$nodeId\""))
    }
    assertTrue(exported.svg.contains("<path"))
  }

  @Test
  fun `known catalog icon exports as a vector path without raster provenance`() {
    val icon = document.nodes.getValue("search-leading-icon")
    val iconDocument =
      document.copy(
        id = "known-catalog-icon",
        revision = 43,
        environment =
          JsonObject(
            document.environment +
              mapOf(
                "widthDp" to Json.parseToJsonElement("24"),
                "heightDp" to Json.parseToJsonElement("24"),
              )
          ),
        roots = listOf(icon.id),
        nodes = mapOf(icon.id to icon),
      )
    val iconCatalog = catalog.copy(components = listOf(catalog.componentsById.getValue("m3/icon")))

    val result =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          iconDocument.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
          iconCatalog,
          JvmSkiaStructuredSvgRecorder,
        )
      )

    assertTrue(result.svg.contains("<path"))
    assertFalse(result.svg.contains("<image"))
    assertTrue(result.svg.contains("rasterFallbackNodeIds=;"))
    assertSvgRasterCloseToCompose(
      result.svg,
      iconDocument,
      maxDifferingRatio = 0.02,
      channelTolerance = 26,
    )
  }

  @Test
  fun `unknown filtered image remains fail closed instead of being assigned to an icon`() {
    val icon = document.nodes.getValue("search-leading-icon")
    val iconDocument =
      document.copy(
        id = "anonymous-filtered-icon-image",
        revision = 44,
        roots = listOf(icon.id),
        nodes = mapOf(icon.id to icon),
      )
    val iconCatalog =
      catalog
        .copy(components = listOf(catalog.componentsById.getValue("m3/icon")))
        .asVectorVerified()

    val result =
      executeSavedDocumentSvgExport(
        iconDocument.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
        iconCatalog,
        recorder {
          StructuredSvgRecording(
            "<svg width=\"1280\" height=\"800\"><path d=\"M0 0h1v1z\"/>" +
              "<image x=\"2\" y=\"2\" width=\"24\" height=\"24\" " +
              "href=\"data:image/png;base64,AA==\"/></svg>",
            "anonymous-filter-fixture",
          )
        },
      )

    val rejected = assertIs<SavedDocumentSvgExportResult.Rejected>(result)
    assertTrue(rejected.blockers.any { it.code == "RASTER_RECORD_COUNT_MISMATCH" })
    assertTrue(rejected.blockers.any { it.code == "RASTER_RECORD_PAYLOAD_MISMATCH" })
  }

  @Test
  fun `match parent raster size comes from stable Compose layout provenance`() {
    val card =
      document.nodes
        .getValue("podcast-card-android")
        .copy(slots = mapOf("content" to listOf("podcast-card-android-image")))
    val image = document.nodes.getValue("podcast-card-android-image")
    val matchParentDocument =
      document.copy(
        id = "match-parent-raster-provenance",
        revision = 42,
        roots = listOf(card.id),
        nodes = mapOf(card.id to card, image.id to image),
      )
    val matchParentCatalog =
      catalog
        .copy(
          components =
            catalog.components.filter { it.componentId in setOf("m3/card", "asset/image") }
        )
        .asVectorVerified()
        .withComponentSvg("asset/image") { svg ->
          svg.copy(
            status = "raster-fallback-required",
            fallback = "embedded-raster",
            blocksExport = false,
          )
        }

    val result =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          matchParentDocument.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
          matchParentCatalog,
          JvmSkiaStructuredSvgRecorder,
        )
      )

    assertEquals(1, Regex("<image\\b").findAll(result.svg).count())
    assertTrue(result.svg.contains("data-compose-node-id=\"podcast-card-android-image\""))
    assertTrue(result.svg.contains("data-compose-raster-size-px=\"128x128\""))
    assertTrue(
      result.svg.contains(
        "generated-placeholder/v1/jetcaster.cover.android-developers-backstage/128x128"
      )
    )
  }

  @Test
  fun `match parent raster refuses to infer missing layout provenance`() {
    val matchParent = document.nodes.getValue("podcast-card-android-image")
    val isolated =
      document.copy(
        id = "unmeasured-match-parent-raster",
        roots = listOf(matchParent.id),
        nodes = mapOf(matchParent.id to matchParent),
      )

    val failure =
      assertFailsWith<IllegalArgumentException> {
        JvmStructuredSvgRasterAssets.create(isolated, listOf(matchParent.id))
      }

    assertTrue(failure.message.orEmpty().contains("no measured layout provenance"))
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
  fun `raster correlation rejects ambiguous source provenance`() {
    val href = "data:image/png;base64,AA=="
    val parsed =
      checkNotNull(
        parseStrictSvg(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2\" height=\"2\">" +
              "<rect width=\"1\" height=\"1\"/><image width=\"1\" height=\"1\" href=\"$href\"/></svg>"
          )
          .document
      )
    val blockers =
      validateRasterRecords(
        parsed,
        listOf(
          StructuredSvgRasterRecord(
            nodeId = "asset",
            sourceIdentity = "",
            sourceIdentitySha256 = "not-a-digest",
            renderedWidthPx = 0,
            renderedHeightPx = 1,
            embeddedPayloadSha256 = sha256Hex(href),
            reason = "fixture",
          )
        ),
        listOf("asset"),
      )

    assertTrue(blockers.any { it.code == "RASTER_RECORD_SOURCE_IDENTITY_INVALID" })
  }

  @Test
  fun `representative saved card exports nested text clip elevation and correlated raster`() {
    val representative = representativeDocument()
    val representativeCatalog =
      catalog
        .copy(
          components =
            catalog.components.filter {
              it.componentId in
                setOf("m3/card", "layout/row", "layout/column", "m3/text", "asset/image")
            }
        )
        .asVectorVerified()
        .withComponentSvg("asset/image") { svg ->
          svg.copy(
            status = "raster-fallback-required",
            fallback = "embedded-raster",
            blocksExport = false,
          )
        }

    val result =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          representative.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
          representativeCatalog,
          JvmSkiaStructuredSvgRecorder,
        )
      )
    val repeated =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          representative.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
          representativeCatalog,
          JvmSkiaStructuredSvgRecorder,
        )
      )

    assertEquals(result.svg, repeated.svg)
    assertEquals("gate0-representative-card", result.provenance.designId)
    assertEquals(41, result.provenance.designRevision)
    assertEquals(listOf("detail-artwork:embedded-raster"), result.provenance.declaredFallbacks)
    assertEquals(1, Regex("<image\\b").findAll(result.svg).count())
    assertTrue(Regex("<(?:g|path|rect)\\b").containsMatchIn(result.svg))
    assertTrue(result.svg.contains("<text"))
    assertTrue(result.svg.contains("Gate 0 structured export"))
    assertTrue(result.svg.contains("<clipPath"), "the artwork clip must remain structured")
    assertTrue(result.svg.contains("data-compose-node-id=\"detail-artwork\""))
    assertTrue(
      result.svg.contains(
        "data-compose-raster-source=\"generated-placeholder/v1/jetcaster.cover.android-developers-backstage/152x152\""
      )
    )
    assertTrue(result.svg.contains("data-compose-raster-size-px=\"152x152\""))
    val expectedSourceDigest =
      sha256Hex(
        "generated-placeholder/v1/jetcaster.cover.android-developers-backstage/152x152|" +
          "argb=ff0b57d0,ff00a896,ff101828"
      )
    assertTrue(result.svg.contains("data-compose-raster-source-sha256=\"$expectedSourceDigest\""))
    assertTrue(result.svg.contains("rasterSources=detail-artwork@generated-placeholder/v1/"))
    assertTrue(result.svg.contains("data-compose-raster-reason="))
    assertTrue(
      result.svg.contains("documentContentSha256=${sha256Hex(canonicalDocument(representative))}")
    )
    assertFalse(Regex("(?:href|src)=\"(?:https?:|file:|/)").containsMatchIn(result.svg))
    assertSvgRasterCloseToCompose(
      result.svg,
      representative,
      rasterNodeIds = listOf("detail-artwork"),
      maxDifferingRatio = 0.03,
      channelTolerance = 26,
    )
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
                "<image x=\"200\" y=\"8\" width=\"56\" height=\"56\" href=\"data:image/png;base64,AQ==\"/>" +
                "<image x=\"300\" y=\"8\" width=\"128\" height=\"128\" href=\"data:image/png;base64,Ag==\"/>" +
                "<image x=\"450\" y=\"8\" width=\"128\" height=\"128\" href=\"data:image/png;base64,Aw==\"/>" +
                "</svg>",
              "fixture-structured-recorder",
              rasterRecords =
                listOf(
                  StructuredSvgRasterRecord(
                    nodeId = "detail-artwork",
                    sourceIdentity = "fixture/v1/detail-artwork",
                    sourceIdentitySha256 = sha256Hex("fixture/v1/detail-artwork"),
                    renderedWidthPx = 152,
                    renderedHeightPx = 152,
                    embeddedPayloadSha256 = sha256Hex("data:image/png;base64,AA=="),
                    reason = "asset image",
                  ),
                  StructuredSvgRasterRecord(
                    nodeId = "main-episode-image",
                    sourceIdentity = "fixture/v1/main-episode-image",
                    sourceIdentitySha256 = sha256Hex("fixture/v1/main-episode-image"),
                    renderedWidthPx = 56,
                    renderedHeightPx = 56,
                    embeddedPayloadSha256 = sha256Hex("data:image/png;base64,AQ=="),
                    reason = "asset image",
                  ),
                  StructuredSvgRasterRecord(
                    nodeId = "podcast-card-android-image",
                    sourceIdentity = "fixture/v1/podcast-card-android-image",
                    sourceIdentitySha256 = sha256Hex("fixture/v1/podcast-card-android-image"),
                    renderedWidthPx = 128,
                    renderedHeightPx = 128,
                    embeddedPayloadSha256 = sha256Hex("data:image/png;base64,Ag=="),
                    reason = "asset image",
                  ),
                  StructuredSvgRasterRecord(
                    nodeId = "podcast-card-google-image",
                    sourceIdentity = "fixture/v1/podcast-card-google-image",
                    sourceIdentitySha256 = sha256Hex("fixture/v1/podcast-card-google-image"),
                    renderedWidthPx = 128,
                    renderedHeightPx = 128,
                    embeddedPayloadSha256 = sha256Hex("data:image/png;base64,Aw=="),
                    reason = "asset image",
                  ),
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

      override fun record(request: StructuredSvgRecordingRequest) = block(request.document)
    }

  private fun executeFixtureSvg(svg: String): SavedDocumentSvgExportResult =
    executeSavedDocumentSvgExport(
      document.job(StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS),
      catalog.asVectorVerified(),
      recorder { StructuredSvgRecording(svg, "adversarial-fixture") },
    )

  private fun representativeDocument(): UiBuilderDocument {
    val ids =
      setOf(
        "detail-hero",
        "detail-hero-row",
        "detail-artwork",
        "detail-hero-copy",
        "detail-podcast-title",
        "detail-author",
      )
    val nodes = document.nodes.filterKeys { it in ids }.toMutableMap()
    val card = nodes.getValue("detail-hero")
    nodes[card.id] =
      card.copy(
        properties =
          JsonObject(
            card.properties +
              ("elevationDp" to Json.parseToJsonElement("""{"type":"float","value":6.0}"""))
          )
      )
    val title = nodes.getValue("detail-podcast-title")
    nodes[title.id] =
      title.copy(
        properties =
          JsonObject(
            title.properties +
              ("text" to
                Json.parseToJsonElement("""{"type":"string","value":"Gate 0 structured export"}"""))
          )
      )
    val copy = nodes.getValue("detail-hero-copy")
    nodes[copy.id] =
      copy.copy(slots = mapOf("children" to listOf("detail-podcast-title", "detail-author")))
    return document.copy(
      id = "gate0-representative-card",
      title = "Gate 0 representative structured SVG card",
      revision = 41,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("detail-hero"),
      nodes = nodes,
    )
  }

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

  private fun assertSvgRasterCloseToCompose(
    svg: String,
    source: UiBuilderDocument,
    rasterNodeIds: List<String> = emptyList(),
    maxDifferingRatio: Double = 0.01,
    channelTolerance: Int = 0,
  ) {
    val density = source.environment.getValue("density").toString().toFloat()
    val fontScale = source.environment.getValue("fontScale").toString().toFloat()
    val width = (source.environment.getValue("widthDp").toString().toFloat() * density).roundToInt()
    val height =
      (source.environment.getValue("heightDp").toString().toFloat() * density).roundToInt()
    val rasterAssets = JvmStructuredSvgRasterAssets.create(source, rasterNodeIds)
    val direct =
      try {
        renderComposeScene(width, height, Density(density, fontScale)) {
          CompositionLocalProvider(LocalUiBuilderExportRasterAssets provides rasterAssets.bitmaps) {
            UiBuilderSurface(document = source, editorOverlay = false)
          }
        }
      } finally {
        rasterAssets.close()
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
        if (
          colorsDifferBeyondTolerance(
            directBitmap.getColor(x, y),
            svgBitmap.getColor(x, y),
            channelTolerance,
          )
        ) {
          differing++
        }
      }
    }
    val differingRatio = differing.toDouble() / (width.toDouble() * height.toDouble())
    assertTrue(
      differingRatio < maxDifferingRatio,
      "SVG raster differs on $differingRatio of pixels at channel tolerance $channelTolerance",
    )
    svgBitmap.close()
    directBitmap.close()
    roundTrip.close()
    surface.close()
    dom.close()
    svgData.close()
    direct.close()
  }

  private fun colorsDifferBeyondTolerance(first: Int, second: Int, tolerance: Int): Boolean =
    listOf(24, 16, 8, 0).any { shift ->
      kotlin.math.abs(((first ushr shift) and 0xff) - ((second ushr shift) and 0xff)) > tolerance
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
