package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One starting point a catalog publishes: a design document on its delivery branch, named by its
 * policy's `templates` list.
 *
 * [id] is the document's file name without `.json`, which is what the chooser and a `POST` name.
 * [title] is the document's own title. The policy's `templates` entries are bare paths today, so a
 * catalog cannot yet state the label, supporting text or default the Kotlin chooser carries; the
 * title is the one honest label the document already holds (`UI_BUILDER_SEED_TEMPLATES.md`, the
 * object-shaped entries step).
 */
public data class CatalogSeedTemplate(
  public val id: String,
  public val title: String,
  public val document: UiBuilderDocument,
)

/**
 * The seed templates [catalogSystemId] publishes, in its policy's order, for a catalog that
 * [CatalogOwnership] says owns its seeds.
 *
 * Read by [UiBuilderNewDesignSeed] in place of its Kotlin builders. Composing them is a host's job
 * (a server fetches the documents beside `ui-builder.json`; a test reads captured copies), which is
 * why this takes a reader function rather than a location.
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
     * A declared template could not be used. The whole set is refused rather than served without
     * it: a catalog whose chooser silently lost a card is harder to notice than one whose host says
     * which document it could not read.
     */
    public data class Unusable(public val reason: String) : Result
  }

  public companion object {
    private val json = Json {
      classDiscriminator = "type"
      ignoreUnknownKeys = true
    }

    /**
     * The templates [paths] name (a policy's `statusSemantics.templates`), read with [read].
     *
     * A document must be pinned to [catalogSystemId]: a template authored against another catalog
     * is a different catalog's design, and seeding it would hand a new design components this
     * catalog cannot validate.
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
 * This template's document as design [designId], pinned exactly as a Kotlin-built template would
 * be: [catalogPin] is the served catalog's pin, and a template frozen against `candidate` must not
 * carry that forward into a design the service then refuses.
 */
internal fun CatalogSeedTemplate.seed(designId: String, catalogPin: JsonObject): UiBuilderDocument =
  document.copy(id = designId, revision = 0, catalogPin = catalogPin)
