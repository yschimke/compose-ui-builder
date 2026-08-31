package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CapabilityComposeCodeExporterTest {
  private val document by lazy {
    UiBuilderReducer.replay(resourceJson("/jetcaster-discover-operations-v1.json")).document
  }
  private val catalog by lazy {
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  }
  private val artworkAdapter =
    ComposeAssetAdapter(
      id = "test-jetcaster-artwork/v1",
      bindings =
        mapOf(
          "jetcaster.cover.android-developers-backstage" to
            ComposeAssetBinding(listOf("FF0B57D0", "FF00A896", "FF101828")),
          "jetcaster.cover.google-developers-podcast" to
            ComposeAssetBinding(listOf("FFEA4335", "FFFBBC04", "FF174EA6")),
        ),
    )

  @Test
  fun `full frozen Jetcaster document exports deterministically with every node located`() {
    val immutableInputSnapshot =
      document.copy(
        roots = document.roots.toList(),
        nodes =
          document.nodes.mapValues { (_, node) ->
            node.copy(
              properties = JsonObject(node.properties.toMap()),
              slots = node.slots.mapValues { it.value.toList() },
              modifiers = JsonArray(node.modifiers.toList()),
              eventBindings = JsonObject(node.eventBindings.toMap()),
            )
          },
        stateVariables = JsonObject(document.stateVariables.toMap()),
      )
    val first = CapabilityComposeCodeExporter.export(document, catalog, artworkAdapter)
    val second = CapabilityComposeCodeExporter.export(document, catalog, artworkAdapter)
    val source = assertNotNull(first.source)

    assertTrue(first.successful, first.diagnostics.joinToString { it.message })
    assertEquals(first, second)
    assertEquals(immutableInputSnapshot, document, "export must not mutate its input document")
    assertEquals(99, Regex("// node:").findAll(source).count())
    assertTrue(source.contains("fun JetcasterDiscoverExpandedSupportingPane()"))
    assertTrue(source.contains("BuilderSupportingPaneScaffold("))
    assertTrue(source.contains("LazyVerticalGrid("))
    assertTrue(source.contains("BuilderHorizontalCarousel("))
    assertTrue(source.contains("BuilderSearchInputField("))
    assertTrue(source.contains("jetcaster.cover.android-developers-backstage"))
    assertTrue(source.contains("Android Developers Backstage"))
    assertFalse(source.contains("JetcasterScreen("))
    assertBalancedKotlinDelimiters(source)
    assertEquals(document.id, first.provenance.designId)
    assertEquals(document.revision, first.provenance.designRevision)
    assertEquals("candidate", first.provenance.capabilityDigest)
    assertTrue(first.diagnostics.any { it.code == "ADAPTIVE_COMPATIBILITY_HELPER" })
    assertFalse(first.diagnostics.any { it.code == "ASSET_BINDING_REQUIRED" })
    assertEquals(artworkAdapter.id, first.provenance.assetAdapterId)
    assertTrue(first.provenance.declaredFallbacks.isEmpty())
    assertTrue(source.contains("private fun BuilderAssetImage"))
    assertTrue(
      source.contains("modifier.semantics { this.contentDescription = contentDescription }")
    )
    assertEquals(0, first.diagnostics.count { it.code == "UNEMITTED_PROPERTY" })
    assertFalse(source.contains("assetKey.contains"))
    assertFalse(source.contains("// TODO[UNEMITTED_PROPERTY]"))
    assertTrue(source.contains("shape = RoundedCornerShape(16.dp)"))
    assertTrue(source.contains("stateDescription = if (expanded)"))
    assertTrue(source.contains("semantics { selected = selectedDestination == \"Discover\" }"))
    assertTrue(source.contains("centerFraction: Offset"))
    assertTrue(source.contains("contentScale = \"crop\""))
    assertTrue(source.contains("key(\"podcast-detail-scroll\")"))
    assertEquals(1280f, first.provenance.viewportWidthDp)
    assertEquals("dark", first.provenance.theme)
    assertTrue(first.provenance.environmentCanonicalJson.contains("\"fontScale\":1"))
  }

  @Test
  fun `multiple roots are explicitly rejected instead of silently producing an empty screen`() {
    val secondRoot =
      document.nodes
        .getValue("search-placeholder")
        .copy(
          id = "second-root-text",
          properties =
            JsonObject(
              mapOf(
                "text" to
                  JsonObject(
                    mapOf(
                      "type" to JsonPrimitive("string"),
                      "value" to JsonPrimitive("Second root"),
                    )
                  )
              )
            ),
        )
    val multiRoot =
      document.copy(
        roots = document.roots + secondRoot.id,
        nodes = document.nodes + (secondRoot.id to secondRoot),
      )

    val result = CapabilityComposeCodeExporter.export(multiRoot, catalog, artworkAdapter)

    assertNull(result.source)
    assertTrue(
      result.diagnostics.any {
        it.severity == ComposeExportSeverity.ERROR && it.code == "ROOT_CARDINALITY"
      }
    )
  }

  @Test
  fun `unbound assets use a visible declared placeholder with located diagnostics`() {
    val result = ComposeCodeExporter.export(document, catalog)
    val source = assertNotNull(result.source)

    assertTrue(result.successful)
    assertEquals(null, result.provenance.assetAdapterId)
    assertEquals(
      listOf(
        "asset-placeholder:jetcaster.cover.android-developers-backstage",
        "asset-placeholder:jetcaster.cover.google-developers-podcast",
      ),
      result.provenance.declaredFallbacks,
    )
    assertEquals(
      document.nodes.values.count { it.componentId == "asset/image" },
      result.diagnostics.count { it.code == "ASSET_BINDING_REQUIRED" },
    )
    assertTrue(source.contains("none (visible placeholder)"))
    assertTrue(source.contains("Color(0xFFFF00FF)"))
    assertFalse(source.contains("assetKey.contains"))
  }

  @Test
  fun `invalid asset adapter data blocks code generation with a located diagnostic`() {
    val invalid =
      artworkAdapter.copy(
        bindings =
          artworkAdapter.bindings +
            ("jetcaster.cover.android-developers-backstage" to
              ComposeAssetBinding(listOf("not-kotlin")))
      )

    val result = CapabilityComposeCodeExporter.export(document, catalog, invalid)

    assertFalse(result.successful)
    assertNull(result.source)
    assertTrue(
      result.diagnostics.any {
        it.code == "INVALID_ASSET_BINDING" && it.nodeId == "podcast-card-android-image"
      }
    )
  }

  @Test
  fun `unknown component blocks source instead of emitting an opaque fallback`() {
    val badNode = document.nodes.getValue("search-placeholder").copy(componentId = "m3/unknown")
    val bad = document.copy(nodes = document.nodes + (badNode.id to badNode))

    val result = ComposeCodeExporter.export(bad, catalog)

    assertFalse(result.successful)
    assertNull(result.source)
    assertTrue(result.diagnostics.any { it.code == "UNKNOWN_COMPONENT" && it.nodeId == badNode.id })
  }

  @Test
  fun `unknown modifier blocks source with a located diagnostic`() {
    val node =
      document.nodes
        .getValue("search-placeholder")
        .copy(modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("magic"))))))
    val bad = document.copy(nodes = document.nodes + (node.id to node))

    val result = ComposeCodeExporter.export(bad, catalog)

    assertFalse(result.successful)
    assertNull(result.source)
    assertTrue(result.diagnostics.any { it.code == "UNKNOWN_MODIFIER" && it.nodeId == node.id })

    val malformed =
      node.copy(modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonObject(emptyMap()))))))
    val malformedResult =
      ComposeCodeExporter.export(
        document.copy(nodes = document.nodes + (malformed.id to malformed)),
        catalog,
      )
    assertFalse(malformedResult.successful)
    assertTrue(
      malformedResult.diagnostics.any {
        it.code == "MALFORMED_MODIFIER" && it.nodeId == malformed.id
      }
    )
  }

  @Test
  fun `catalog pin and graph violations block Compose source`() {
    val wrongPin =
      document.copy(
        catalogPin = JsonObject(document.catalogPin + ("nativeRuntimeId" to JsonPrimitive("other")))
      )
    assertBlocked(wrongPin, "CATALOG_PIN_MISMATCH")

    val orphan = document.nodes.getValue("search-placeholder").copy(id = "orphan")
    assertBlocked(document.copy(nodes = document.nodes + (orphan.id to orphan)), "UNREACHABLE_NODE")

    val root = document.nodes.getValue(document.roots.single())
    val firstChild = root.slots.getValue("content").single()
    val duplicateRoot =
      root.copy(slots = root.slots + ("content" to listOf(firstChild, firstChild)))
    assertBlocked(
      document.copy(nodes = document.nodes + (root.id to duplicateRoot)),
      "DUPLICATE_NODE_REFERENCE",
    )

    val pane = document.nodes.getValue(firstChild)
    val cyclicPane = pane.copy(slots = pane.slots + ("mainPane" to listOf(root.id)))
    assertBlocked(document.copy(nodes = document.nodes + (pane.id to cyclicPane)), "GRAPH_CYCLE")

    val missingRoot = root.copy(slots = root.slots + ("content" to listOf("missing-child")))
    assertBlocked(document.copy(nodes = document.nodes + (root.id to missingRoot)), "UNKNOWN_CHILD")
  }

  @Test
  fun `checked in SVG capabilities exactly cover the frozen Jetcaster document`() {
    val usedComponentIds = document.nodes.values.map { it.componentId }.toSortedSet()
    assertEquals(usedComponentIds, catalog.componentsById.keys.toSortedSet())
    val asset = catalog.componentsById.getValue("asset/image").svg
    assertEquals("raster-fallback-required", asset?.status)
    assertEquals("embedded-raster", asset?.fallback)
    assertFalse(checkNotNull(asset).blocksExport)
    usedComponentIds.minus("asset/image").forEach { componentId ->
      val svg = checkNotNull(catalog.componentsById.getValue(componentId).svg)
      assertEquals("verified", svg.status, componentId)
      assertEquals("none", svg.fallback, componentId)
      assertFalse(svg.blocksExport, componentId)
    }

    val readiness =
      inspectDocumentSvgExport(
        document,
        catalog,
        DocumentSvgExecutionBridge.GENERATED_COMPOSE_WRAPPER,
      )

    assertTrue(readiness.ready, readiness.blockers.joinToString())
    assertTrue(readiness.blockers.isEmpty())
    assertTrue(readiness.unverifiedNodeIds.isEmpty())
    assertEquals(
      setOf(
        "detail-artwork",
        "main-episode-image",
        "podcast-card-android-image",
        "podcast-card-google-image",
      ),
      readiness.declaredRasterFallbackNodeIds.toSet(),
    )
    assertEquals(document.revision, readiness.request.provenance.designRevision)
    assertEquals(
      DocumentSvgExecutionBridge.GENERATED_COMPOSE_WRAPPER,
      readiness.request.executionBridge,
    )
  }

  @Test
  fun `fully vector verified catalog is ready without a raster fallback`() {
    val vectorCatalog =
      catalog.copy(
        components =
          catalog.components.map { component ->
            component.copy(
              svg =
                checkNotNull(component.svg)
                  .copy(
                    status = "verified",
                    fallback = "none",
                    blocksExport = false,
                  )
            )
          }
      )

    val readiness =
      inspectDocumentSvgExport(
        document,
        vectorCatalog,
        DocumentSvgExecutionBridge.WASM_SCENE_RECORDING,
      )

    assertTrue(readiness.ready, readiness.blockers.joinToString())
    assertTrue(readiness.blockers.isEmpty())
    assertTrue(readiness.unverifiedNodeIds.isEmpty())
    assertTrue(readiness.declaredRasterFallbackNodeIds.isEmpty())
  }

  @Test
  fun `SVG rejects pin mismatch unknown vocabulary and full-screen raster fallback`() {
    val wrongPin =
      document.copy(
        catalogPin = JsonObject(document.catalogPin + ("capabilityDigest" to JsonPrimitive("bad")))
      )
    val pinReadiness =
      inspectDocumentSvgExport(wrongPin, catalog, DocumentSvgExecutionBridge.WASM_SCENE_RECORDING)
    assertTrue(pinReadiness.blockers.any { it.code == "CATALOG_PIN_MISMATCH" })

    val unknownCatalog =
      catalog.withSvg("m3/surface") { it.copy(status = "mystery", fallback = "external-url") }
    val unknownReadiness =
      inspectDocumentSvgExport(
        document,
        unknownCatalog,
        DocumentSvgExecutionBridge.WASM_SCENE_RECORDING,
      )
    assertTrue(unknownReadiness.blockers.any { it.code == "UNKNOWN_SVG_STATUS" })
    assertTrue(unknownReadiness.blockers.any { it.code == "UNKNOWN_SVG_FALLBACK" })

    val rasterCatalog =
      catalog.withSvg("m3/surface") {
        it.copy(
          status = "raster-fallback-required",
          fallback = "embedded-raster",
          blocksExport = false,
        )
      }
    val rasterReadiness =
      inspectDocumentSvgExport(
        document,
        rasterCatalog,
        DocumentSvgExecutionBridge.WASM_SCENE_RECORDING,
      )
    assertTrue(
      rasterReadiness.blockers.any {
        it.code == "FULL_SCREEN_RASTER_FALLBACK" && it.nodeId == document.roots.single()
      }
    )
  }

  @Test
  fun `invalid viewport density and theme block both export paths`() {
    listOf(
        JsonObject(document.environment + ("widthDp" to JsonPrimitive(0))),
        JsonObject(document.environment + ("heightDp" to JsonPrimitive(-1))),
        JsonObject(document.environment + ("density" to JsonPrimitive(0))),
        JsonObject(document.environment + ("theme" to JsonPrimitive("  "))),
        JsonObject(emptyMap()),
      )
      .forEach { environment ->
        val bad = document.copy(environment = environment)
        assertBlocked(bad, "INVALID_EXPORT_ENVIRONMENT")
        val svg =
          inspectDocumentSvgExport(bad, catalog, DocumentSvgExecutionBridge.WASM_SCENE_RECORDING)
        assertTrue(svg.blockers.any { it.code == "INVALID_EXPORT_ENVIRONMENT" })
      }
  }

  private fun assertBlocked(bad: UiBuilderDocument, code: String) {
    val result = ComposeCodeExporter.export(bad, catalog)
    assertFalse(result.successful)
    assertNull(result.source)
    assertTrue(result.diagnostics.any { it.code == code }, result.diagnostics.joinToString())
  }

  private fun ee.schimke.composeai.uibuilder.capability.CapabilityCatalog.withSvg(
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

  private fun assertBalancedKotlinDelimiters(source: String) {
    val stack = mutableListOf<Char>()
    var quoted = false
    var escaped = false
    source.forEach { character ->
      if (quoted) {
        when {
          escaped -> escaped = false
          character == '\\' -> escaped = true
          character == '"' -> quoted = false
        }
      } else {
        when (character) {
          '"' -> quoted = true
          '(',
          '{',
          '[' -> stack += character
          ')' -> assertEquals('(', stack.removeLastOrNull(), "unbalanced ')' in generated source")
          '}' -> assertEquals('{', stack.removeLastOrNull(), "unbalanced '}' in generated source")
          ']' -> assertEquals('[', stack.removeLastOrNull(), "unbalanced ']' in generated source")
        }
      }
    }
    assertFalse(quoted, "unterminated generated string")
    assertTrue(stack.isEmpty(), "unclosed generated delimiter(s): $stack")
  }

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()

  private fun resourceJson(path: String): JsonObject =
    Json.parseToJsonElement(resource(path)).jsonObject
}
