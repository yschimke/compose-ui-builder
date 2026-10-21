package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.callableAliases
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
      }
      .build()
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == systemId }
  return CapabilityCatalogParser.parse(Json.encodeToString(served))
}

/** The same captured record and builder foundation that production export receives. */
internal fun publishedTestExportRecord(
  systemId: String,
  foundationPolicyOverride: String? = null,
): ComponentRecordFile {
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
  val foundationPublished =
    foundationPolicyOverride
      ?: GZIPInputStream(
          Files.newInputStream(root.resolve("compose-foundation/ui-builder.json.gz"))
        )
        .use { it.readBytes().decodeToString() }
  val foundationComposed =
    PublishedUiBuilderCatalog.compose(foundationPublished, foundation, exports)
  check(foundationComposed is PublishedUiBuilderCatalog.Result.Composed)
  val taken = record.components.mapTo(mutableSetOf()) { it.canonicalId }
  val claimed = record.components.flatMapTo(mutableSetOf()) { it.componentIds }
  val extra =
    foundation.components.filterNot { candidate ->
      candidate.canonicalId in taken || candidate.componentIds.any { it in claimed }
    }
  // Builtins without a record still use the builder's embedded implementation. A published
  // foundation implementation takes precedence as soon as the delivery pair supplies one.
  val foundationIds = foundationComposed.records.keys
  val extraCanonicalIds = extra.mapTo(mutableSetOf()) { it.canonicalId }
  val builderOwned =
    checkNotNull(embeddedComponentRecord()).components.filter { component ->
      component.componentIds.any { id ->
        listOf("layout/", "shape/", "asset/", "remote-compose/").any(id::startsWith)
      } &&
        component.canonicalId !in taken &&
        component.canonicalId !in extraCanonicalIds &&
        component.componentIds.none { it in claimed || it in foundationIds }
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
  for ((builderId, component) in foundationComposed.records) {
    if (component.canonicalId in extraCanonicalIds)
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

/** [systemId]'s published seed templates, read from its captured delivery files in policy order. */
internal fun publishedTestTemplates(systemId: String): CatalogSeedTemplates {
  val directory =
    Path.of(checkNotNull(System.getProperty("compose.uiBuilder.catalogDir"))).resolve(systemId)
  val policy =
    GZIPInputStream(Files.newInputStream(directory.resolve("ui-builder.json.gz"))).use {
      Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
  val paths =
    (policy["statusSemantics"]?.jsonObject?.get("templates") as? JsonArray)
      ?.map { it.jsonPrimitive.content }
      .orEmpty()
  return when (
    val read =
      CatalogSeedTemplates.read(systemId, paths) { path ->
        directory.resolve("designs/${path.substringAfterLast('/')}").takeIf(Files::exists)?.let {
          Files.readString(it)
        }
      }
  ) {
    is CatalogSeedTemplates.Result.Read -> read.templates
    is CatalogSeedTemplates.Result.Unusable -> error("$systemId: ${read.reason}")
  }
}

/**
 * Design [designId] from [systemId]'s published [templateId], seeded exactly as Create seeds it and
 * pinned to [catalogRevision] and [nativeRuntimeId] (the served catalog's, by default).
 */
internal fun publishedTestSeed(
  systemId: String,
  templateId: String,
  designId: String = "$systemId-$templateId",
  catalogRevision: String? = null,
  nativeRuntimeId: String? = null,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  val served by lazy { publishedTestCatalog(systemId).benchmark }
  return UiBuilderNewDesignSeed.document(
    designId = designId,
    templateId = templateId,
    catalogPin =
      CatalogReferenceV1(
        systemId,
        catalogRevision ?: served.catalogRevision,
        "candidate",
        nativeRuntimeId ?: served.nativeRuntimeId,
      ),
    published = publishedTestTemplates(systemId),
    state = state,
  )
}
