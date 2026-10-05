package ee.schimke.composeai.uibuilder.export.production

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.production.PRODUCTION_UID_SCHEMA_V1
import ee.schimke.composeai.uibuilder.protocol.production.ProductionUidFileV1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Build-tool compatibility adapter for the canonical production protocol in
 * compose-preview-contracts.
 *
 * Existing build callers retain these Kotlin types and signatures. ProductionUidFiles checks every
 * declaration against ProductionUidFileV1 before adapting it to the generator's document model. The
 * production wrapper remains distinct from the ordinary design-service document.
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
  val fallback: JsonPrimitive? = null,
) {
  @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
  constructor(
    nodeId: String,
    property: String,
    path: List<String>,
    expectedType: ProductionType,
  ) : this(nodeId, property, path, expectedType, null)

  @kotlin.jvm.JvmName("copy")
  @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
  fun legacyCopy(
    nodeId: String = this.nodeId,
    property: String = this.property,
    path: List<String> = this.path,
    expectedType: ProductionType = this.expectedType,
  ): ProductionBinding =
    copy(nodeId = nodeId, property = property, path = path, expectedType = expectedType)
}

@Serializable
data class ProductionComponentUse(
  val nodeId: String,
  val componentId: String,
  /** An empty path passes the entry point's entire input model. */
  val dataPath: List<String>,
  /** Child event name to parent event name; no implicit callback capture. */
  val events: Map<String, String> = emptyMap(),
  val onNull: ProductionNullPolicy? = null,
  val keyPath: List<String>? = null,
) {
  @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
  constructor(
    nodeId: String,
    componentId: String,
    dataPath: List<String>,
    events: Map<String, String> = emptyMap(),
  ) : this(nodeId, componentId, dataPath, events, null, null)

  @kotlin.jvm.JvmName("copy")
  @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
  fun legacyCopy(
    nodeId: String = this.nodeId,
    componentId: String = this.componentId,
    dataPath: List<String> = this.dataPath,
    events: Map<String, String> = this.events,
  ): ProductionComponentUse =
    copy(nodeId = nodeId, componentId = componentId, dataPath = dataPath, events = events)
}

@Serializable
enum class ProductionNullPolicy {
  @SerialName("skip") SKIP
}

object ProductionUidFiles {
  const val SCHEMA: String = "compose-ui-builder-production/v1-candidate"

  const val VERSIONED_SCHEMA: String = PRODUCTION_UID_SCHEMA_V1
  val SCHEMAS: Set<String> = setOf(SCHEMA, VERSIONED_SCHEMA)

  private val wireJson = Json { classDiscriminator = "type" }

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
    require(schema in SCHEMAS) {
      "unsupported production schema '$schema'; expected one of $SCHEMAS"
    }
    // The candidate admitted builder-only incomplete design fixtures. Keep that legacy behaviour;
    // the versioned file also checks the embedded design against the shared document protocol.
    val wire = if (schema == SCHEMA) JsonObject(encoded - "design") else encoded
    wireJson.decodeFromJsonElement(ProductionUidFileV1.serializer(), wire)
    return json.decodeFromJsonElement(ProductionUidFile.serializer(), encoded)
  }

  fun encode(file: ProductionUidFile): String {
    require(file.schema in SCHEMAS) { "unsupported production schema '${file.schema}'" }
    val text = json.encodeToString(ProductionUidFile.serializer(), file) + "\n"
    decode(text)
    return text
  }
}
