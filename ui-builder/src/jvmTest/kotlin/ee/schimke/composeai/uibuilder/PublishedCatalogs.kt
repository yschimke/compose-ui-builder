package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.callableAliases
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

/** The same captured record and builder foundation that production export receives. */
internal fun publishedTestExportRecord(systemId: String): ComponentRecordFile {
  val root = Path.of(checkNotNull(System.getProperty("compose.uiBuilder.catalogDir")))
  fun readRecord(path: Path): ComponentRecordFile =
    Json.decodeFromString(
      GZIPInputStream(Files.newInputStream(path)).use { it.readBytes().decodeToString() }
    )
  val record = readRecord(root.resolve("$systemId/components.json.gz"))
  val published =
    GZIPInputStream(Files.newInputStream(root.resolve("$systemId/ui-builder.json.gz"))).use {
      it.readBytes().decodeToString()
    }
  val exports = ExportCapabilitiesV1.Builder().apply { composeCode = true }.build()
  val composed = PublishedUiBuilderCatalog.compose(published, record, exports)
  check(composed is PublishedUiBuilderCatalog.Result.Composed)
  val foundation = readRecord(root.resolve("compose-foundation/components.json.gz"))
  val taken = record.components.mapTo(mutableSetOf()) { it.canonicalId }
  val claimed = record.components.flatMapTo(mutableSetOf()) { it.componentIds }
  val extra =
    foundation.components.filterNot { candidate ->
      candidate.canonicalId in taken || candidate.componentIds.any { it in claimed }
    }
  val builderOwned =
    checkNotNull(embeddedComponentRecord()).components.filter { component ->
      component.componentIds.any { id ->
        listOf("layout/", "shape/", "asset/", "remote-compose/").any(id::startsWith)
      } && component.canonicalId !in taken && component.componentIds.none { it in claimed }
    }
  val joined =
    record
      .newBuilder()
      .also { it.components = record.components + extra + builderOwned }
      .build()
      .callableAliases()
  val aliases = mutableMapOf<String, MutableList<String>>()
  for ((builderId, component) in composed.records) {
    aliases.getOrPut(component.canonicalId) { mutableListOf() }.add(builderId)
  }
  return joined
    .newBuilder()
    .also { builder ->
      builder.components =
        joined.components.map { component ->
          val added = aliases[component.canonicalId]?.filterNot { it in component.componentIds }
          if (added.isNullOrEmpty()) component
          else
            component.newBuilder().also { it.componentIds = component.componentIds + added }.build()
        }
    }
    .build()
}
