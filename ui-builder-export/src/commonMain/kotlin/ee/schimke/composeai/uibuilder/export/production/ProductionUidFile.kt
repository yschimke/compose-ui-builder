package ee.schimke.composeai.uibuilder.export.production

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Experimental project-file contract, deliberately distinct from the editor's v1 document.
 *
 * An old editor must refuse this schema rather than discard an application API on save. These
 * declarations are not part of the released design-service protocol yet.
 */
@Serializable
data class ProductionUidFile(
  val schema: String,
  val imports: List<String> = emptyList(),
  /** SHA-256 of the build's ordered component-record contents; required for Compose generation. */
  val catalogDigest: String? = null,
  val models: List<ProductionModel> = emptyList(),
  val entryPoint: ProductionEntryPoint? = null,
  val design: UiBuilderDocument? = null,
)

@Serializable
data class ProductionModel(
  val id: String,
  val kotlinType: String,
  val ownership: ModelOwnership,
  /** Declaration order is constructor order, even when a field is unused by the design. */
  val fields: List<ProductionField>,
)

@Serializable
enum class ModelOwnership {
  @SerialName("generated") GENERATED,
  @SerialName("external") EXTERNAL,
}

@Serializable
data class ProductionField(
  val name: String,
  val type: ProductionType,
  /** Actual Kotlin property for an external model's design-facing field. */
  val property: String? = null,
)

/** Closed type vocabulary: no executable Kotlin expressions or arbitrary generic type strings. */
@Serializable
sealed interface ProductionType {
  val nullable: Boolean

  @Serializable
  @SerialName("scalar")
  data class Scalar(val scalar: ScalarType, override val nullable: Boolean = false) : ProductionType

  @Serializable
  @SerialName("model")
  data class Model(val modelId: String, override val nullable: Boolean = false) : ProductionType

  @Serializable
  @SerialName("list")
  data class ListType(val element: ProductionType, override val nullable: Boolean = false) :
    ProductionType
}

@Serializable
enum class ScalarType(val kotlinType: String) {
  @SerialName("string") STRING("kotlin.String"),
  @SerialName("boolean") BOOLEAN("kotlin.Boolean"),
  @SerialName("int") INT("kotlin.Int"),
  @SerialName("long") LONG("kotlin.Long"),
  @SerialName("float") FLOAT("kotlin.Float"),
  @SerialName("double") DOUBLE("kotlin.Double"),
}

@Serializable
enum class EntryPointKind {
  @SerialName("screen") SCREEN,
  @SerialName("component") COMPONENT,
}

@Serializable
enum class ProductionVisibility {
  @SerialName("public") PUBLIC,
  @SerialName("internal") INTERNAL,
}

@Serializable
data class ProductionEntryPoint(
  val id: String,
  val kotlinFunction: String,
  val kind: EntryPointKind,
  val visibility: ProductionVisibility,
  val inputModel: String,
  val root: String,
  val events: List<ProductionEvent> = emptyList(),
  val bindings: List<ProductionBinding> = emptyList(),
  val components: List<ProductionComponentUse> = emptyList(),
  val eventBindings: List<ProductionEventBinding> = emptyList(),
)

@Serializable data class ProductionEvent(val name: String, val payload: ProductionType? = null)

/** A zero-argument UI callback reports an event, optionally reading its payload from data. */
@Serializable
data class ProductionEventBinding(
  val nodeId: String,
  /** Catalog Kotlin parameter, for example onClick. */
  val property: String,
  val event: String,
  /** Null for a payload-free event; an empty path selects the complete input model. */
  val payloadPath: List<String>? = null,
)

/** A checked read from the entry point's input model, for one node property. */
@Serializable
data class ProductionBinding(
  val nodeId: String,
  val property: String,
  val path: List<String>,
  val expectedType: ProductionType,
)

@Serializable
data class ProductionComponentUse(
  val nodeId: String,
  val componentId: String,
  /** An empty path passes the entry point's entire input model. */
  val dataPath: List<String>,
  /** Child event name to parent event name; no implicit callback capture. */
  val events: Map<String, String> = emptyMap(),
)

object ProductionUidFiles {
  const val SCHEMA: String = "compose-ui-builder-production/v1-candidate"

  private val json = Json {
    classDiscriminator = "kind"
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  /** Strict even for unknown fields: a contract this version cannot understand is never guessed. */
  fun decode(text: String): ProductionUidFile {
    val encoded = json.parseToJsonElement(text)
    val schema = ((encoded as? JsonObject)?.get("schema") as? JsonPrimitive)?.content
    require(schema == SCHEMA) { "unsupported production schema '$schema'; expected '$SCHEMA'" }
    return json.decodeFromJsonElement(ProductionUidFile.serializer(), encoded)
  }

  fun encode(file: ProductionUidFile): String {
    require(file.schema == SCHEMA) { "unsupported production schema '${file.schema}'" }
    return json.encodeToString(ProductionUidFile.serializer(), file) + "\n"
  }
}
