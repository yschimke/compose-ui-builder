package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One starting point a catalog publishes: a design document on its delivery branch, named by its
 * policy's `templates` list. [id] is the file name without `.json`; [title] is the document's own
 * title, the only label available until policy entries can state one
 * (`UI_BUILDER_SEED_TEMPLATES.md`).
 */
public data class CatalogSeedTemplate(
  public val id: String,
  public val title: String,
  public val document: UiBuilderDocument,
)

/**
 * The seed templates [catalogSystemId] publishes, in policy order, for a catalog that
 * [CatalogOwnership] says owns its seeds; read by [UiBuilderNewDesignSeed]. Takes a reader function
 * because fetching is the host's job.
 */
public class CatalogSeedTemplates(
  public val catalogSystemId: String,
  public val templates: List<CatalogSeedTemplate>,
) {
  init {
    require(templates.map(CatalogSeedTemplate::id).distinct().size == templates.size) {
      "$catalogSystemId publishes two templates under one id: ${templates.map { it.id }}"
    }
  }

  /** The ids a caller is validated against, in the catalog's order. */
  public val ids: List<String>
    get() = templates.map(CatalogSeedTemplate::id)

  public operator fun get(templateId: String): CatalogSeedTemplate? = templates.firstOrNull {
    it.id == templateId
  }

  /** Reading a catalog's declared templates; never an exception. */
  public sealed interface Result {
    public data class Read(public val templates: CatalogSeedTemplates) : Result

    /**
     * A declared template could not be used, so the whole set is refused rather than silently
     * served short.
     */
    public data class Unusable(public val reason: String) : Result
  }

  public companion object {
    private val json = Json {
      classDiscriminator = "type"
      ignoreUnknownKeys = true
    }

    /**
     * The templates [paths] name (a policy's `statusSemantics.templates`), read with [read]. Each
     * must be pinned to [catalogSystemId], or it would seed components this catalog cannot
     * validate.
     */
    public fun read(
      catalogSystemId: String,
      paths: List<String>,
      read: (path: String) -> String?,
    ): Result {
      val templates = paths.map { path ->
        val id = path.substringAfterLast('/').removeSuffix(".json")
        if (id.isBlank() || !path.endsWith(".json")) {
          return Result.Unusable("$catalogSystemId template `$path` is not a .json document")
        }
        val text =
          read(path) ?: return Result.Unusable("$catalogSystemId template `$path` is missing")
        val document = runCatching {
          json.decodeFromString(UiBuilderDocument.serializer(), text)
        }
          .getOrElse {
            return Result.Unusable(
              "$catalogSystemId template `$path` is not a design document: ${it.message}"
            )
          }
        val pinned = document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull
        if (pinned != catalogSystemId) {
          return Result.Unusable(
            "$catalogSystemId template `$path` is pinned to `$pinned`, not `$catalogSystemId`"
          )
        }
        if (document.roots.isEmpty() || document.roots.any { it !in document.nodes }) {
          return Result.Unusable("$catalogSystemId template `$path` has a root it does not hold")
        }
        CatalogSeedTemplate(id = id, title = document.title, document = document)
      }
      return runCatching { Result.Read(CatalogSeedTemplates(catalogSystemId, templates)) }
        .getOrElse { Result.Unusable(it.message ?: "unusable templates") }
    }
  }
}

/**
 * This template as design [designId], pinned to the served catalog's [catalogPin] (a template
 * frozen against `candidate` must not carry that forward).
 */
internal fun CatalogSeedTemplate.seed(designId: String, catalogPin: JsonObject): UiBuilderDocument =
  document.copy(id = designId, revision = 0, catalogPin = catalogPin)
