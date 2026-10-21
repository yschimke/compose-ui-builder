package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogTemplateDocumentsV1
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.encodeToString

/**
 * Network-first template documents. Validation precedes storage, so a refresh racing the catalog
 * list cannot replace a good cached set with templates for another revision. A new cache instance
 * can create from the same documents with the server down. One entry per catalog bounds storage
 * across upgrades; its envelope must still match the complete requested pin. Quota failures leave
 * online use intact.
 */
class LocalCatalogTemplates(private val storage: LocalDesignStorage) {
  suspend fun load(
    catalog: CatalogCapabilityV1,
    fetch: suspend () -> String,
  ): CatalogSeedTemplates {
    val benchmark = catalog.benchmark
    // The current v1 capability has no digest field; the serving executor accepts `candidate`.
    val pin =
      CatalogReferenceV1(
        benchmark.catalogSystemId,
        benchmark.catalogRevision,
        "candidate",
        benchmark.nativeRuntimeId,
      )
    val key = "ui-builder.local.templates." + localDesignJson.encodeToString(pin.systemId)
    fun decode(text: String): CatalogSeedTemplates = catalogTemplateDocuments(text, pin)
    return try {
      val text = fetch()
      val templates = decode(text)
      try {
        storage.write(key, text)
      } catch (_: LocalDesignStorageException) {
        // Online creation still works; an offline visit will explain the missing stored set.
      }
      templates
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      try {
        storage.read(key)?.let(::decode)
          ?: throw LocalDesignStorageException(
            "this browser has not stored ${pin.systemId} templates",
            failure,
          )
      } catch (invalid: IllegalArgumentException) {
        throw LocalDesignStorageException(
          "this browser has not stored matching ${pin.systemId} templates",
          invalid,
        )
      }
    }
  }
}

/**
 * [text], a `CatalogTemplateDocumentsV1` publication, as the seed templates of [pin]'s catalog. It
 * must be for exactly [pin], so a set published for another revision never seeds this one; an
 * unreadable or mismatched publication is an [IllegalArgumentException].
 */
internal fun catalogTemplateDocuments(text: String, pin: CatalogReferenceV1): CatalogSeedTemplates {
  val payload = localDesignJson.decodeFromString<CatalogTemplateDocumentsV1>(text)
  require(payload.schemaVersion == 1) {
    "unsupported template publication schema ${payload.schemaVersion}"
  }
  require(payload.catalogPin == pin) {
    "published templates do not match the stored ${pin.systemId} catalog"
  }
  val documents =
    payload.templates
      .mapKeys { (id, _) ->
        require(id.isNotBlank() && '/' !in id && '\\' !in id) { "invalid template id" }
        "$id.json"
      }
      .mapValues { (_, document) -> localDesignJson.encodeToString(document.toUiBuilderDocument()) }
  return when (
    val read = CatalogSeedTemplates.read(pin.systemId, documents.keys.toList()) { documents[it] }
  ) {
    is CatalogSeedTemplates.Result.Read -> read.templates
    is CatalogSeedTemplates.Result.Unusable -> throw IllegalArgumentException(read.reason)
  }
}
