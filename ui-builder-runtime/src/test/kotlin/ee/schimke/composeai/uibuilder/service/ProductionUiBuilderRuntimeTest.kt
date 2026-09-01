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

  @Test
  fun `runtime owns one deterministic packaged renderer bundle`() {
    val first = PackagedUiBuilderRenderBundle.copyTo(stateDirectory.resolve("bundle"))
    val repeated = PackagedUiBuilderRenderBundle.copyTo(stateDirectory.resolve("bundle"))

    assertEquals(first, repeated)
    assertTrue(Files.size(first) > 0)
    assertTrue(first.startsWith(stateDirectory.resolve("bundle")))
  }

  @Test
  fun `compose export is revision pinned deterministic and changes with saved content`() {
    val catalog = CurrentM3UiBuilderCatalogExecutor().listCatalogs().single()
    val exporter = RevisionPinnedComposeExportExecutor()
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
    assertTrue(first.content.contains("Text("))
    assertTrue(first.content.contains("Hello from the saved design"))
    assertTrue(
      first.content.contains("Document SHA-256: ${pinned(document(), catalog).documentHash}")
    )
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
            RevisionPinnedComposeExportExecutor().export(it)
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
    ProductionUiBuilderExportExecutor(renderer).use { exporter ->
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

  private fun document(): DesignDocumentV1 =
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
}

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

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
    "%02x".format(it)
  }
