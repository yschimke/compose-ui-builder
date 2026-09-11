package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class ProductionUiBuilderRuntimeTest {
  @TempDir lateinit var stateDirectory: Path

  @Test
  fun `packaged current m3 catalog resolves only its exact pin and validates strictly`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()

    assertEquals("m3-catalog", catalog.benchmark.catalogSystemId)
    assertTrue(catalog.exportCapabilities.composeCode)
    assertEquals(false, catalog.exportCapabilities.svg)
    assertEquals(false, catalog.exportCapabilities.png)
    assertEquals(catalog, catalogs.resolve(document().catalogPin))
    assertNull(
      catalogs.resolve(document().catalogPin.copy(catalogRevision = "moving-main")),
      "catalog resolution must never float to the current revision",
    )
    assertNull(catalogs.validate(document(), catalog))
    val invalid =
      document()
        .copy(
          nodes =
            document().nodes +
              ("text" to document().nodes.getValue("text").copy(componentId = "m3/not-real"))
        )
    assertEquals("UNKNOWN_COMPONENT", catalogs.validate(invalid, catalog)?.code)
  }

  /**
   * A design may place its own components against the real packaged catalog.
   *
   * `design/component-instance` is a document construct — no catalog declares it — so looking it up
   * as a catalog component refused every design that placed one, which made `declareComponent`
   * unusable: nothing could instantiate what it declared. The editor's `CapabilityValidator` has
   * always drawn this distinction; this is the writing side holding the same line.
   */
  @Test
  fun `a placement of the design's own component validates against the body, not the catalog`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    val placed =
      document().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("surface" to
                base.nodes
                  .getValue("surface")
                  .copy(slots = mapOf("content" to listOf("text", "placed")))) +
              ("placed" to
                DesignNodeV1(
                  id = "placed",
                  componentId = DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
                  component = DesignComponentInstanceV1("greeting"),
                )),
          components = mapOf("greeting" to DesignComponentV1(name = "Greeting", root = "text")),
        )
      }

    assertNull(catalogs.validate(placed, catalog))

    // The one thing that can be wrong about a placement here: naming a component the design does
    // not declare. A placement of nothing draws nothing while reporting success.
    val dangling = placed.copy(components = emptyMap())
    val issue = catalogs.validate(dangling, catalog)
    assertEquals("UNKNOWN_COMPONENT", issue?.code)
    assertEquals("placed", issue?.nodeId)
  }

  /**
   * A placement carries no capability of its own, so it is held to the body's.
   *
   * Skipping the check let a direct `ApplyOperation` persist a modifier
   * `CapabilityComposeCodeExporter.modifierExpression` throws on — a design the server accepted and
   * the export then refused.
   */
  @Test
  fun `a placement's modifiers are judged by the body it draws`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    val placed = document().withPlacement()

    assertNull(catalogs.validate(placed, catalog))

    val modified =
      placed.copy(
        nodes =
          placed.nodes +
            ("placed" to
              placed.nodes
                .getValue("placed")
                .copy(
                  // `matchParentSize` is a real modifier and is not among the eighteen `m3/text`
                  // declares, so this is the exact shape the export would refuse.
                  modifiers = listOf(MatchParentSizeModifierV1)
                ))
      )
    val issue = catalogs.validate(modified, catalog)
    assertEquals("UNKNOWN_MODIFIER", issue?.code)
    assertEquals("placed", issue?.nodeId)
  }

  /**
   * Children hung on a placement are drawn by nobody.
   *
   * The renderer's component-instance path and the exporter's `emitPlacement` both draw the
   * declared body and never traverse the placement's own slots, so accepting these commits nodes
   * that vanish from preview and generated output while the operation reports success.
   */
  @Test
  fun `a placement may not hold children of its own`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    val placed = document().withPlacement()

    val withChild =
      placed.copy(
        nodes =
          placed.nodes +
            ("orphan" to
              DesignNodeV1(
                id = "orphan",
                componentId = "m3/text",
                properties = mapOf("text" to StringValueV1("drawn by nobody")),
              )) +
            ("placed" to
              placed.nodes.getValue("placed").copy(slots = mapOf("content" to listOf("orphan")))),
        components = placed.components,
      )

    val issue = catalogs.validate(withChild, catalog)
    assertEquals("INVALID_PLACEMENT", issue?.code)
    assertEquals("placed", issue?.nodeId)
    assertEquals("content", issue?.field)
  }

  /**
   * A body root that is itself a placement is an ordinary composition.
   *
   * Resolving only one hop asked the catalog for `design/component-instance`, got null, and
   * rejected every nested component placed in a slot — a shape the renderer and exporter both
   * support, cycle diagnostics included.
   */
  @Test
  fun `a component whose body places another component still resolves in a slot`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    val nested =
      document().withPlacement().let { base ->
        base.copy(
          nodes =
            base.nodes +
              ("surface" to
                base.nodes
                  .getValue("surface")
                  .copy(slots = mapOf("content" to listOf("text", "outer")))) +
              ("outer" to
                DesignNodeV1(
                  id = "outer",
                  componentId = DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
                  component = DesignComponentInstanceV1("wrapper"),
                )),
          components =
            base.components + ("wrapper" to DesignComponentV1(name = "Wrapper", root = "placed")),
        )
      }

    assertNull(catalogs.validate(nested, catalog))

    // A declaration whose body root places itself is a cycle; the walk has to stop rather than
    // recurse until the stack gives out. The export gate reports it as `GRAPH_CYCLE`; from here it
    // is simply a body that cannot be resolved.
    val cyclic =
      nested.copy(
        components = nested.components + ("wrapper" to DesignComponentV1("Wrapper", "outer"))
      )
    assertEquals("UNKNOWN_COMPONENT", catalogs.validate(cyclic, catalog)?.code)
  }

  @Test
  fun `a write is refused for the value kind its name states, and only on the write`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    val text = document().nodes.getValue("text")
    fun coloured(value: UiValueV1) = text.copy(properties = text.properties + ("color" to value))

    // #476: a colour written as `string` committed, rendered, and was refused at export with a
    // sentence about `Text`. Refused here instead, naming the node, the field and the wrapper.
    val asString =
      assertNotNull(catalogs.validateWrite(catalog, coloured(StringValueV1("#5F6368")), "color"))
    assertEquals("INVALID_PROPERTY", asString.code)
    assertEquals("text", asString.nodeId)
    assertEquals("color", asString.field)
    assertTrue(asString.message.contains("`color` or `colorToken`"), asString.message)
    assertTrue(asString.message.contains("`string`"), asString.message)
    assertNull(catalogs.validateWrite(catalog, coloured(ColorValueV1("#5F6368")), "color"))
    assertNull(catalogs.validateWrite(catalog, coloured(ColorTokenValueV1("primary")), "color"))
    // A role the export can write but the canvas does not draw used to throw inside the renderer.
    val unknownRole =
      assertNotNull(
        catalogs.validateWrite(catalog, coloured(ColorTokenValueV1("primaryContainer")), "color")
      )
    assertEquals("color", unknownRole.field)
    assertTrue(unknownRole.message.contains("`primaryContainer`"), unknownRole.message)
    // An empty colour is the component's own default, which `startAccentColor` documents.
    assertNull(catalogs.validateWrite(catalog, coloured(ColorValueV1("")), "color"))
    // Bound to state, or not a colour property at all: nothing to say here — `validate` answers.
    assertNull(catalogs.validateWrite(catalog, coloured(StateValueV1("accent")), "color"))
    assertNull(catalogs.validateWrite(catalog, text, "text"))

    // The rule bites on the write and nowhere else: a document that already holds the old
    // spelling still validates as a whole, so an unrelated edit to it is not refused.
    val legacy =
      document().copy(nodes = document().nodes + ("text" to coloured(StringValueV1("#5F6368"))))
    assertNull(catalogs.validate(legacy, catalog))
  }

  @Test
  fun `an asset key is refused unless the catalog's registry lists it`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()
    fun image(key: UiValueV1) =
      DesignNodeV1(id = "photo", componentId = "asset/image", properties = mapOf("assetKey" to key))

    // #484: the one property whose value the renderer must resolve was the one nothing checked.
    val unresolved =
      assertNotNull(
        catalogs.validateWrite(catalog, image(StringValueV1("avatar-lain")), "assetKey")
      )
    assertEquals("INVALID_PROPERTY", unresolved.code)
    assertEquals("photo", unresolved.nodeId)
    assertEquals("assetKey", unresolved.field)
    assertTrue(unresolved.message.contains("`avatar-lain`"), unresolved.message)
    assertTrue(
      unresolved.message.contains("jetcaster.cover.android-developers-backstage"),
      "the refusal says what a valid key looks like: ${unresolved.message}",
    )
    assertNull(
      catalogs.validateWrite(
        catalog,
        image(AssetKeyValueV1("jetcaster.cover.google-developers-podcast")),
        "assetKey",
      )
    )
    // The editor's own insert placeholder is a key the canvas draws, so an insert from the palette
    // is not refused by the rule that refuses an agent's guess.
    assertNull(
      catalogs.validateWrite(catalog, image(StringValueV1("editor.placeholder")), "assetKey")
    )

    // The registry is the catalog's, and every catalog derived from the base one inherits it.
    val everyCatalog =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog", "remote-m3", "wear-m3")
        )
        .listCatalogs()
    everyCatalog.forEach { each ->
      assertEquals(
        setOf(
          "jetcaster.cover.android-developers-backstage",
          "jetcaster.cover.google-developers-podcast",
          "ui-builder.gate0.cover",
          "editor.placeholder",
        ),
        CurrentM3UiBuilderCatalogExecutor.declaredAssetKeys(each),
        each.benchmark.catalogSystemId,
      )
      // The roles the catalog tells a reader about are the roles the executor refuses against.
      assertEquals(
        CurrentM3UiBuilderCatalogExecutor.CANVAS_COLOR_TOKENS,
        CurrentM3UiBuilderCatalogExecutor.declaredColorTokens(each),
        each.benchmark.catalogSystemId,
      )
    }
    // A catalog that declares no registry says nothing about keys.
    val silent = catalog.copy(statusSemantics = kotlinx.serialization.json.JsonObject(emptyMap()))
    assertNull(catalogs.validateWrite(silent, image(StringValueV1("avatar-lain")), "assetKey"))
  }

  @Test
  fun `only explicitly enabled catalogs get independent exact pins`() {
    val catalogs =
      CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = linkedSetOf("m3-catalog", "remote-m3"))

    assertEquals(
      listOf("m3-catalog", "remote-m3"),
      catalogs.listCatalogs().map { it.benchmark.catalogSystemId },
    )
    val remoteDocument =
      document()
        .copy(
          catalogPin =
            document()
              .catalogPin
              .copy(
                systemId = "remote-m3",
                catalogRevision = "wear-widget-scaffolds-v1",
              ),
          roots = listOf("widget"),
          nodes =
            mapOf(
              "widget" to
                DesignNodeV1(
                  id = "widget",
                  componentId = "remote-m3/widget-container-small",
                  slots = mapOf("content" to emptyList()),
                )
            ),
        )
    val remoteCatalog = assertNotNull(catalogs.resolve(remoteDocument.catalogPin))
    assertNull(catalogs.validate(remoteDocument, remoteCatalog))
    assertEquals(
      listOf(
        "remote-m3/widget-container-small",
        "remote-m3/widget-container-large",
        "remote-m3/lottie",
        "layout/box",
        "layout/column",
        "layout/row",
        "m3/surface",
        "m3/text",
        "remote-compose/document",
        "remote-compose/custom",
        "shape/linear-gradient",
        "asset/image",
      ),
      remoteCatalog.components.map { it.componentId },
    )
    assertEquals(
      "UNKNOWN_COMPONENT",
      catalogs
        .validate(
          document()
            .copy(
              catalogPin = remoteDocument.catalogPin,
              nodes =
                document().nodes +
                  ("text" to document().nodes.getValue("text").copy(componentId = "m3/button")),
            ),
          remoteCatalog,
        )
        ?.code,
    )
    assertNull(catalogs.resolve(document().catalogPin.copy(systemId = "wear-m3-catalog")))
    assertFailsWith<IllegalArgumentException> {
      CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("remote/m3"))
    }
    assertFailsWith<IllegalArgumentException> {
      CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("wear-m3-catalog"))
    }
  }

  @Test
  fun `runtime owns one deterministic packaged renderer bundle`() {
    val first = PackagedUiBuilderRenderBundle.copyTo(stateDirectory.resolve("bundle"))
    val repeated = PackagedUiBuilderRenderBundle.copyTo(stateDirectory.resolve("bundle"))

    assertEquals(first, repeated)
    assertTrue(Files.size(first) > 0)
    assertTrue(first.startsWith(stateDirectory.resolve("bundle")))
  }

  @Test
  fun `an export request is revision pinned and changes with saved content`() {
    // This used to assert the *source* a projection in this module emitted — `Text(`, the design's
    // text, a `// Document SHA-256:` comment. That projection is gone, and the assertions went with
    // it rather than being ported: which Kotlin a document becomes is `ScreenGenerator`'s answer,
    // reached from `:server`, and `checkUiBuilderRuntimeBoundary` keeps it off this module's
    // classpath on purpose.
    //
    // What is this module's is the *request*: that one saved revision always produces the same
    // pinned export, and that editing the document changes it. Asserted against an executor that
    // echoes what it was handed, so a passing test means the pinning held rather than that some
    // emitter happened to be deterministic.
    val catalog = CurrentM3UiBuilderCatalogExecutor().listCatalogs().single()
    val exporter = EchoingComposeExportExecutor()
    val first = exporter.export(pinned(document(), catalog))
    val repeated = exporter.export(pinned(document(), catalog))
    val editedDocument =
      document()
        .copy(
          nodes =
            document().nodes +
              ("text" to
                document()
                  .nodes
                  .getValue("text")
                  .copy(properties = mapOf("text" to StringValueV1("Changed on revision one")))),
          revision = 1,
        )
    val edited = exporter.export(pinned(editedDocument, catalog))

    assertEquals(first, repeated)
    assertEquals(sha256(first.content), first.contentDigest)
    assertTrue(first.content.contains(pinned(document(), catalog).documentHash))
    assertNotEquals(first.contentDigest, edited.contentDigest)
    assertTrue(edited.content.contains("Changed on revision one"))
    assertFailsWith<IllegalArgumentException> {
      exporter.export(pinned(document(), catalog).copy(format = ExportFormatV1.SVG))
    }
  }

  @Test
  fun `unsupported svg and png fail before the exporter and survive service restart`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    var exports = 0
    fun service() =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = catalogs,
        exporter =
          UiBuilderExportExecutor {
            exports++
            EchoingComposeExportExecutor().export(it)
          },
      )

    val owner = AuthenticatedUiBuilderActor("owner")
    assertIs<UiBuilderServiceResponse.Snapshot>(
      executeProduction(service(), owner, UiBuilderServiceRequest.CreateDesign(document()))
    )
    val restarted = service()
    assertIs<UiBuilderServiceResponse.Snapshot>(
      executeProduction(restarted, owner, UiBuilderServiceRequest.OpenDesign("production-design"))
    )
    listOf(ExportFormatV1.SVG, ExportFormatV1.PNG).forEach { format ->
      val error =
        assertIs<UiBuilderServiceResponse.Error>(
            executeProduction(
              restarted,
              owner,
              UiBuilderServiceRequest.ExportDesign("production-design", revision = 0, format),
            )
          )
          .error
      assertEquals(ServiceErrorCodeV1.BAD_REQUEST, error.code)
    }
    assertEquals(0, exports, "unsupported formats must never reach the artifact executor")
    val compose =
      assertIs<UiBuilderServiceResponse.Export>(
        executeProduction(
          restarted,
          owner,
          UiBuilderServiceRequest.ExportDesign(
            "production-design",
            revision = 0,
            ExportFormatV1.COMPOSE,
          ),
        )
      )
    assertEquals(1, exports)
    assertTrue(compose.artifact.content.contains("Hello from the saved design"))
  }

  @Test
  fun `protocol projection preserves 99 node renderer fields and rejects revision overflow`() {
    val base = document()
    val nodes =
      (1..99).associate { index ->
        "text-$index" to
          DesignNodeV1(
            id = "text-$index",
            componentId = "m3/text",
            properties = mapOf("text" to StringValueV1("Node $index")),
          )
      }
    val large = base.copy(revision = 99, roots = nodes.keys.toList(), nodes = nodes)

    val projected =
      kotlinx.serialization.json.Json.parseToJsonElement(projectRendererDocument(large)).jsonObject

    assertEquals(99, projected.getValue("revision").jsonPrimitive.content.toInt())
    assertEquals(99, projected.getValue("nodes").jsonObject.size)
    assertEquals(
      nodes.keys.toList(),
      projected.getValue("roots").jsonArray.map { it.jsonPrimitive.content },
    )
    assertTrue("assets" !in projected)
    assertFailsWith<IllegalArgumentException> {
      projectRendererDocument(base.copy(revision = Int.MAX_VALUE.toLong() + 1))
    }
  }

  @Test
  fun `render port receives exact saved document and returns deterministic png and svg`() {
    val requests = mutableListOf<UiBuilderRenderRequest>()
    val renderer =
      object : UiBuilderRenderPort {
        override val supportsSvg = true

        override fun renderPng(request: UiBuilderRenderRequest): ByteArray {
          requests += request
          return "png:${request.encodedDocument}".toByteArray()
        }

        override fun renderSvg(request: UiBuilderRenderRequest): ByteArray {
          requests += request
          return "<svg>${request.encodedDocument}</svg>".toByteArray()
        }

        override fun close() = Unit
      }
    ProductionUiBuilderExportExecutor(renderer, EchoingComposeExportExecutor()).use { exporter ->
      val catalog =
        CurrentM3UiBuilderCatalogExecutor(exportCapabilities = exporter.capabilities)
          .listCatalogs()
          .single()
      val initial = pinned(document(), catalog)
      val editedDocument =
        document()
          .copy(
            revision = 1,
            nodes =
              document().nodes +
                ("text" to
                  document()
                    .nodes
                    .getValue("text")
                    .copy(properties = mapOf("text" to StringValueV1("Exact edited document")))),
          )
      val edited = pinned(editedDocument, catalog)

      val png = exporter.export(initial.copy(format = ExportFormatV1.PNG))
      val pngAgain = exporter.export(initial.copy(format = ExportFormatV1.PNG))
      val editedPng = exporter.export(edited.copy(format = ExportFormatV1.PNG))
      val svg = exporter.export(initial.copy(format = ExportFormatV1.SVG))
      val svgAgain = exporter.export(initial.copy(format = ExportFormatV1.SVG))
      val editedSvg = exporter.export(edited.copy(format = ExportFormatV1.SVG))

      assertEquals(png, pngAgain)
      assertEquals(svg, svgAgain)
      assertNotEquals(png.contentDigest, editedPng.contentDigest)
      assertNotEquals(svg.contentDigest, editedSvg.contentDigest)
      assertTrue(
        Base64.getDecoder()
          .decode(editedPng.content)
          .decodeToString()
          .contains("Exact edited document")
      )
      assertTrue(editedSvg.content.contains("Exact edited document"))
      assertEquals(6, requests.size)
      assertEquals(400, requests.last().widthPx)
      assertEquals(800, requests.last().heightPx)
      assertEquals(1f, requests.last().density)
      assertTrue(requests.last().encodedDocument.contains("Exact edited document"))
    }
  }

  private fun pinned(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): RevisionPinnedUiBuilderExport =
    RevisionPinnedUiBuilderExport(
      actor = AuthenticatedUiBuilderActor("owner"),
      designId = document.id,
      revision = document.revision,
      documentHash = sha256(PersistentUiBuilderServiceJsonForTest.encode(document)),
      document = document,
      catalog = catalog,
      format = ExportFormatV1.COMPOSE,
    )

  private fun document(): DesignDocumentV1 = Companion.document()

  /** The fixture with a `greeting` component over the `text` node, and one placement of it. */
  private fun DesignDocumentV1.withPlacement(): DesignDocumentV1 =
    copy(
      nodes =
        nodes +
          ("surface" to
            nodes.getValue("surface").copy(slots = mapOf("content" to listOf("text", "placed")))) +
          ("placed" to
            DesignNodeV1(
              id = "placed",
              componentId = DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
              component = DesignComponentInstanceV1("greeting"),
            )),
      components = mapOf("greeting" to DesignComponentV1(name = "Greeting", root = "text")),
    )

  internal companion object {
    /** The two-node m3 design every test here starts from; shared with the pack tests. */
    fun document(): DesignDocumentV1 = productionDocument()
  }
}

private fun productionDocument(): DesignDocumentV1 =
  DesignDocumentV1(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "production-design",
    title = "Production design",
    revision = 0,
    catalogPin =
      CatalogReferenceV1(
        systemId = "m3-catalog",
        catalogRevision = "candidate",
        capabilityDigest = CurrentM3UiBuilderCatalogExecutor.CURRENT_CAPABILITY_DIGEST,
        nativeRuntimeId = "candidate",
      ),
    environment =
      DesignEnvironmentV1(
        widthDp = 400,
        heightDp = 800,
        density = 1.0,
        theme = ThemeV1.DARK,
        locale = "en-US",
        fontScale = 1.0,
        layoutDirection = LayoutDirectionV1.LTR,
        windowPosture = WindowPostureV1.FLAT,
        animations = AnimationStateV1.SETTLED,
        networkAccess = false,
      ),
    roots = listOf("surface"),
    nodes =
      linkedMapOf(
        "surface" to
          DesignNodeV1(
            id = "surface",
            componentId = "m3/surface",
            slots = mapOf("content" to listOf("text")),
          ),
        "text" to
          DesignNodeV1(
            id = "text",
            componentId = "m3/text",
            properties = mapOf("text" to StringValueV1("Hello from the saved design")),
          ),
      ),
  )

private object PersistentUiBuilderServiceJsonForTest {
  private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

  fun encode(document: DesignDocumentV1): String =
    json.encodeToString(DesignDocumentV1.serializer(), document)
}

private fun executeProduction(
  service: PersistentUiBuilderService,
  actor: AuthenticatedUiBuilderActor,
  request: UiBuilderServiceRequest,
): UiBuilderServiceResponse {
  var completion: Result<UiBuilderServiceResponse>? = null
  suspend { service.execute(UiBuilderServiceCall(actor, request)) }
    .startCoroutine(
      object : Continuation<UiBuilderServiceResponse> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<UiBuilderServiceResponse>) {
          completion = result
        }
      }
    )
  return completion?.getOrThrow() ?: error("synchronous service call suspended")
}

/**
 * A Compose executor that echoes its request, for tests about *pinning* rather than about source.
 *
 * Deliberately not a generator. This module may not depend on `preview-discovery`
 * (`checkUiBuilderRuntimeBoundary`), so anything here claiming to emit Compose would be a fourth
 * emitter of exactly the kind the production default was removed for. Echoing the pinned document
 * hash and the node text keeps every property these tests actually assert — determinism, digest
 * agreement, sensitivity to an edit — without asserting a single line of Kotlin.
 */
private class EchoingComposeExportExecutor : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    require(request.format == ExportFormatV1.COMPOSE) {
      "${request.format} export is unsupported by this executor"
    }
    require(request.revision == request.document.revision) { "export revision/document mismatch" }
    require(request.document.id == request.designId) { "export design/document mismatch" }
    val content =
      "// pinned ${request.designId}@${request.revision} ${request.documentHash}\n" +
        PersistentUiBuilderServiceJsonForTest.encode(request.document)
    return ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = content,
      contentDigest = sha256(content),
    )
  }
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
    "%02x".format(it)
  }
