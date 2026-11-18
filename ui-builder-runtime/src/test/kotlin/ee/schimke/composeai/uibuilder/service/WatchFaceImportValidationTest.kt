package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * A face imported from WFF XML is a design the real catalog validator accepts, node by node and
 * property by property, as inserting it through the service would require.
 */
class WatchFaceImportValidationTest {
  private val repo: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/wff-watchface").isDirectory }

  private val composed =
    assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          File(repo, "docs/design/fixtures/ui-builder/wff-watchface-published-v1.json").readText(),
          null,
          ExportCapabilitiesV1.Builder().apply { composeCode = true }.build(),
        ),
      )
      .catalog

  private val executor =
    fixtureCatalogExecutor(
      catalogSystemIds = setOf("wff-watchface"),
      published = mapOf("wff-watchface" to composed),
    )

  /** The catalog as the service serves and validates against it. */
  private val catalog =
    executor.listCatalogs().single { it.benchmark.catalogSystemId == "wff-watchface" }

  private fun assertValid(name: String, document: UiBuilderDocument) {
    val wire =
      document.toDesignDocumentV1().let {
        it.copy(catalogPin = checkNotNull(executor.reference(catalog)))
      }
    assertNull(executor.validate(wire, catalog), "$name validates")
    for (node in wire.nodes.values) {
      for (property in node.properties.keys) {
        assertNull(
          executor.validateWrite(catalog, wire, node, property),
          "$name nodes.${node.id}.$property",
        )
      }
    }
  }

  @Test
  fun `every imported sample and both templates validate against the published catalog`() {
    val samples =
      File(repo, "ui-builder-export/src/jvmTest/resources/wear-os-samples")
        .listFiles { f -> f.isDirectory }
        .orEmpty()
        .sortedBy { it.name }
    assertEquals(7, samples.size)
    for (dir in samples) {
      val result =
        assertIs<WatchFaceXmlImporter.Result.Imported>(
          WatchFaceXmlImporter.import(
            File(dir, "raw/watchface.xml").readText(),
            // The samples use elements up to version 4.
            WatchFaceXmlImporter.Options(wffVersion = 5),
          ),
          dir.name,
        )
      assertValid(dir.name, result.document)
    }
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val template =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          File(repo, "docs/design/fixtures/ui-builder/wff-watchface/designs/$name.json").readText(),
        )
      val xml =
        assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(template)).xml
      assertValid(
        name,
        assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml)).document,
      )
    }
  }

  @Test
  fun `both shipped templates validate, property by property, against the published catalog`() {
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      assertValid(
        name,
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          File(repo, "docs/design/fixtures/ui-builder/wff-watchface/designs/$name.json").readText(),
        ),
      )
    }
  }
}
