package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class PersistentArtifactDeterminismTest {
  private val json = Json { ignoreUnknownKeys = false }

  @Test
  fun `restart preserves the exact saved document Compose and SVG artifacts`() {
    val storeRoot = java.nio.file.Files.createTempDirectory("ui-builder-restart-artifacts")
    val initial =
      UiBuilderReducer.replay(resourceJson("/jetcaster-discover-operations-v1.json")).document
    val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
    val validators = DesignValidationProvider {
      DesignValidators(property = CapabilityPropertyWriteValidator(CapabilityValidator(catalog)))
    }
    val first = PersistentDesignService(FileDesignStore(storeRoot), validators)
    assertIs<CreateDesignResult.Created>(first.create(initial))

    val mutation =
      DesignCommand(
        designId = initial.id,
        operationId = "restart-artifact-title",
        actorId = "browser-user",
        clientId = "browser-client",
        baseRevision = initial.revision,
        operations =
          listOf(
            DesignOperation.SetProperty(
              nodeId = "search-placeholder",
              property = "text",
              value =
                buildJsonObject {
                  put("type", "string")
                  put("value", "Search the durable catalog")
                },
            )
          ),
      )
    val committed = assertIs<CommandOutcome.Accepted>(first.apply(mutation).application.outcome)
    assertEquals(initial.revision + 1, committed.committedRevision)
    val before = assertNotNull(first.open(initial.id))
    val beforeArtifacts = artifacts(before.document, catalog)

    val restarted = PersistentDesignService(FileDesignStore(storeRoot), validators)
    val after = assertNotNull(restarted.open(initial.id))
    val afterArtifacts = artifacts(after.document, catalog)

    assertEquals(before.lastSequence, after.lastSequence)
    assertEquals(before.revision, after.revision)
    assertEquals(before.documentHash, after.documentHash)
    assertEquals(canonicalDocument(before.document), canonicalDocument(after.document))
    assertExactText(beforeArtifacts.composeSource, afterArtifacts.composeSource, "Compose source")
    assertEquals(beforeArtifacts.composeProvenance, afterArtifacts.composeProvenance)
    assertEquals(beforeArtifacts.composeDiagnostics, afterArtifacts.composeDiagnostics)
    assertEquals(beforeArtifacts.svgProducer, afterArtifacts.svgProducer)
    assertEquals(beforeArtifacts.svgProvenance, afterArtifacts.svgProvenance)
    assertEquals(
      sha256Hex(beforeArtifacts.svg),
      sha256Hex(afterArtifacts.svg),
      "structured SVG bytes changed across restart",
    )
    assertTrue(afterArtifacts.composeSource.contains("Search the durable catalog"))
    assertTrue(afterArtifacts.svg.contains("Search the durable catalog"))
  }

  private fun artifacts(
    document: UiBuilderDocument,
    catalog: ee.schimke.composeai.uibuilder.capability.CapabilityCatalog,
  ): PersistedArtifacts {
    val compose = ComposeCodeExporter.export(document, catalog)
    assertTrue(compose.successful, compose.diagnostics.joinToString { it.message })
    val svg =
      assertIs<SavedDocumentSvgExportResult.Ok>(
        executeSavedDocumentSvgExport(
          SavedDocumentSvgExportJob(
            pin = SavedDocumentRevisionPin.from(document),
            documentSnapshot = document,
            recorderKind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS,
          ),
          catalog,
          JvmSkiaStructuredSvgRecorder,
        )
      )
    return PersistedArtifacts(
      composeSource = assertNotNull(compose.source),
      composeProvenance = compose.provenance,
      composeDiagnostics = compose.diagnostics,
      svg = svg.svg,
      svgProvenance = svg.provenance,
      svgProducer = svg.producer,
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun resourceJson(path: String): JsonObject =
    json.parseToJsonElement(resource(path)).jsonObject

  private fun assertExactText(expected: String, actual: String, label: String) {
    if (expected == actual) return
    val index =
      expected.indices.firstOrNull { it >= actual.length || expected[it] != actual[it] }
        ?: minOf(expected.length, actual.length)
    val start = (index - 80).coerceAtLeast(0)
    val expectedEnd = (index + 160).coerceAtMost(expected.length)
    val actualEnd = (index + 160).coerceAtMost(actual.length)
    error(
      "$label differs at character $index; " +
        "expected=${expected.substring(start, expectedEnd)}; " +
        "actual=${actual.substring(start, actualEnd)}"
    )
  }

  private data class PersistedArtifacts(
    val composeSource: String,
    val composeProvenance: DocumentExportProvenance,
    val composeDiagnostics: List<ComposeExportDiagnostic>,
    val svg: String,
    val svgProvenance: DocumentExportProvenance,
    val svgProducer: String,
  )
}
