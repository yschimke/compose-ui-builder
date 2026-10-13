package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json

/** Compose the captured delivery pair just as the server does. */
internal fun publishedTestCatalog(systemId: String): CapabilityCatalog {
  val root = Path.of(checkNotNull(System.getProperty("compose.uiBuilder.catalogDir")))
  val directory = root.resolve(systemId)
  fun read(name: String): String =
    GZIPInputStream(Files.newInputStream(directory.resolve("$name.json.gz"))).use {
      it.readBytes().decodeToString()
    }

  val record = Json.decodeFromString<ComponentRecordFile>(read("components"))
  val exports = ExportCapabilitiesV1.Builder().apply { composeCode = true }.build()
  val result = PublishedUiBuilderCatalog.compose(read("ui-builder"), record, exports)
  check(result is PublishedUiBuilderCatalog.Result.Composed) {
    "$systemId: ${(result as PublishedUiBuilderCatalog.Result.Unusable).reason}"
  }
  val served =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = setOf(systemId)
        it.published = mapOf(systemId to result.catalog)
        it.catalogOwnership = CatalogOwnership.of(setOf(systemId))
      }
      .build()
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == systemId }
  return CapabilityCatalogParser.parse(Json.encodeToString(served))
}
