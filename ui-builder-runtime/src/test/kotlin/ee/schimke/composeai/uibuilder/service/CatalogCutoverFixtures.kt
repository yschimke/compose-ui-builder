package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.callableAliases
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.util.zip.GZIPInputStream
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What every catalog repository publishes for the builder, captured from one delivery commit per
 * catalog by `scripts/capture-catalog-cutover-fixtures.sh` into `catalog-cutover/<id>/`.
 *
 * Read the way a host reads a delivery branch: the policy and the record composed by
 * [PublishedUiBuilderCatalog], the seed documents the policy's `templates` names, and the renderer
 * runtime id the branch's `runtime.zip` declares. Nothing here is the builder's own description of
 * a catalog, which is the point: these are the inputs the catalog-owned cutover would serve from.
 */
internal object CatalogCutoverFixtures {

  private const val ROOT = "/catalog-cutover/"
  private val json = Json { ignoreUnknownKeys = true }

  /** The builder catalogs a deployment serves, in the order compose-preview-server lists them. */
  val catalogIds: List<String> =
    listOf("m3-catalog", "wear-m3", "remote-m3", "a2ui-catalog", "glimmer-catalog")

  /**
   * Catalogs whose repository has written a policy and seed templates but publishes no delivery
   * branch yet, captured from the source repository instead (`source.json` says `published:
   * false`). They cannot be served, so [catalogIds] does not hold them; the readiness test reports
   * each as a ledger line and holds its templates to their emitter directly.
   */
  val unpublishedIds: List<String> = listOf("remote-widgets")

  /** An unpublished catalog's policy file, as its repository wrote it. */
  fun sourcePolicy(id: String): JsonObject =
    json.parseToJsonElement(checkNotNull(text("$id/ui-builder.policy.json"))).jsonObject

  /** The builder's own vocabulary, published from m3-catalog but not a catalog anyone opens. */
  const val FOUNDATION: String = "compose-foundation"

  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  fun text(path: String): String? =
    CatalogCutoverFixtures::class.java.getResourceAsStream(ROOT + path)?.use { stream ->
      (if (path.endsWith(".gz")) GZIPInputStream(stream) else stream).readBytes().decodeToString()
    }

  fun policy(id: String): JsonObject =
    json.parseToJsonElement(checkNotNull(text("$id/ui-builder.json.gz"))).jsonObject

  fun record(id: String): ComponentRecordFile =
    json.decodeFromString(checkNotNull(text("$id/components.json.gz")))

  fun source(id: String): JsonObject =
    json.parseToJsonElement(checkNotNull(text("$id/source.json"))).jsonObject

  fun rendererRuntimeId(id: String): String? =
    (source(id)["rendererRuntimeId"] as? JsonPrimitive)?.contentOrNull

  /**
   * [id]'s record under every id a node may name it by, as compose-preview-server's
   * `ScreenGeneratorComposeExportExecutor.exportRecord` builds it: the variant table's callable
   * aliases, plus the builder ids the published file gave each record component.
   */
  fun exportRecord(id: String): ComponentRecordFile {
    val record = withFoundation(record(id)).callableAliases()
    val aliases = mutableMapOf<String, MutableList<String>>()
    for ((builderId, component) in composed(id).records) {
      aliases.getOrPut(component.canonicalId) { mutableListOf() }.add(builderId)
    }
    return record.copy(
      components =
        record.components.map { component ->
          val added = aliases[component.canonicalId]?.filterNot { it in component.componentIds }
          if (added.isNullOrEmpty()) component
          else component.copy(componentIds = component.componentIds + added)
        }
    )
  }

  /**
   * The builder's own vocabulary record (`layout/`, `shape/`, `asset/`): the packaged
   * `compose-foundation-components-v1.json` the server ships, which the cutover keeps — it is the
   * builder's floor, not knowledge of anybody's catalog (`UI_BUILDER_CATALOG_AGNOSTICISM.md` §7).
   */
  val packagedFoundationRecord: ComponentRecordFile by lazy {
    val fixtures = "docs/design/fixtures/ui-builder/compose-foundation-components-v1.json"
    json.decodeFromString(
      (java.io.File("../$fixtures").takeIf { it.isFile } ?: java.io.File(fixtures)).readText()
    )
  }

  /**
   * [record] with the builder's own components added, exactly as the server's
   * `ComponentRecordSource.withFoundation` adds them: the catalog's own entries win, by canonical
   * id or by claimed id.
   */
  private fun withFoundation(record: ComponentRecordFile): ComponentRecordFile {
    val taken = record.components.map { it.canonicalId }.toSet()
    val claimed = record.components.flatMapTo(mutableSetOf()) { it.componentIds }
    val extra =
      packagedFoundationRecord.components.filterNot { candidate ->
        candidate.canonicalId in taken || candidate.componentIds.any { it in claimed }
      }
    return record.copy(components = record.components + extra)
  }

  private val composed = mutableMapOf<String, PublishedUiBuilderCatalog.Result.Composed>()

  /** [id] composed from its captured policy and record, exactly as the server composes it. */
  fun composed(id: String): PublishedUiBuilderCatalog.Result.Composed =
    composed.getOrPut(id) {
      when (
        val result =
          PublishedUiBuilderCatalog.compose(
            checkNotNull(text("$id/ui-builder.json.gz")),
            record(id),
            exports,
          )
      ) {
        is PublishedUiBuilderCatalog.Result.Composed -> result
        is PublishedUiBuilderCatalog.Result.Unusable ->
          fail("$id's published file does not compose: ${result.reason}")
      }
    }

  fun catalog(id: String): CatalogCapabilityV1 = composed(id).catalog

  /** The paths [id]'s policy names under `statusSemantics.templates`. */
  fun templatePaths(id: String): List<String> =
    (if (id in unpublishedIds) sourcePolicy(id)["templates"] as? JsonArray
      else (policy(id)["statusSemantics"] as? JsonObject)?.get("templates") as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      .orEmpty()

  /** [id]'s seed templates, read from the captured documents the way a host reads the branch. */
  fun templates(id: String): CatalogSeedTemplates =
    when (
      val result =
        CatalogSeedTemplates.read(id, templatePaths(id)) { path ->
          text("$id/designs/${path.substringAfterLast('/')}")
        }
    ) {
      is CatalogSeedTemplates.Result.Read -> result.templates
      is CatalogSeedTemplates.Result.Unusable -> fail(result.reason)
    }

  /** An executor serving [ids] from their captured published files. */
  fun executor(
    ids: Collection<String>,
    ownership: CatalogOwnership,
  ): CurrentM3UiBuilderCatalogExecutor = executor(ids.associateWith(::catalog), ownership)

  /** An executor serving [published], as a deployment hands it composed catalogs. */
  fun executor(
    published: Map<String, CatalogCapabilityV1>,
    ownership: CatalogOwnership,
  ): CurrentM3UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = published.keys
        it.published = published
        it.nativeRuntimeIds =
          published.keys
            .mapNotNull { id -> rendererRuntimeId(id)?.let { runtime -> id to runtime } }
            .toMap()
        it.catalogOwnership = ownership
      }
      .build()
}
