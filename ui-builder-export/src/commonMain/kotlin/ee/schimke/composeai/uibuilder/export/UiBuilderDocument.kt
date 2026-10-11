package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The location that remains authoritative when this document is copied elsewhere. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface UiBuilderDocumentHome {
  /** A persisted design served by a UI-builder host. */
  @Serializable
  @SerialName("server")
  data class Server(val url: String, val designId: String) : UiBuilderDocumentHome {
    init {
      require(url.isNotBlank()) { "a server home needs a URL" }
      require(designId.isNotBlank()) { "a server home needs a design id" }
    }
  }

  /** A versioned design file in the checkout that owns it. */
  @Serializable
  @SerialName("repo")
  data class Repo(val path: String) : UiBuilderDocumentHome {
    init {
      require(path.isNotBlank()) { "a repository home needs a path" }
    }
  }
}

/**
 * A document home reader that degrades a future (unknown) kind to unhomed: the generated sealed
 * serializer rejects unknown discriminators before `ignoreUnknownKeys` applies, and homes are
 * provenance, so older builders must still open the document.
 */
internal object NullableUiBuilderDocumentHomeSerializer : KSerializer<UiBuilderDocumentHome?> {
  override val descriptor: SerialDescriptor = UiBuilderDocumentHome.serializer().nullable.descriptor

  override fun serialize(encoder: Encoder, value: UiBuilderDocumentHome?) {
    val jsonEncoder = encoder as? JsonEncoder ?: error("document homes require JSON")
    val element =
      when (value) {
        null -> JsonNull
        else -> jsonEncoder.json.encodeToJsonElement(UiBuilderDocumentHome.serializer(), value)
      }
    jsonEncoder.encodeJsonElement(element)
  }

  override fun deserialize(decoder: Decoder): UiBuilderDocumentHome? {
    val jsonDecoder = decoder as? JsonDecoder ?: error("document homes require JSON")
    val element = jsonDecoder.decodeJsonElement()
    if (element is JsonNull) return null
    val home = element.jsonObject
    val payload = JsonObject(home - "kind")
    return when (home["kind"]?.jsonPrimitive?.contentOrNull) {
      "server" ->
        jsonDecoder.json.decodeFromJsonElement(UiBuilderDocumentHome.Server.serializer(), payload)
      "repo" ->
        jsonDecoder.json.decodeFromJsonElement(UiBuilderDocumentHome.Repo.serializer(), payload)
      else -> null
    }
  }
}

fun UiBuilderDocumentHome.description(): String =
  when (this) {
    is UiBuilderDocumentHome.Server -> "server $url design $designId"
    is UiBuilderDocumentHome.Repo -> "repository $path"
  }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiBuilderDocument(
  val schema: String,
  val id: String,
  val title: String,
  val revision: Int,
  val catalogPin: JsonObject,
  val environment: JsonObject,
  val stateVariables: JsonObject,
  val roots: List<String>,
  val nodes: Map<String, UiBuilderNode>,
  /**
   * The design's asset registry, keyed by an `asset/image`'s `assetKey`: the protocol's
   * `AssetBindingV1` shape, untyped like `catalogPin`. Each value has `mediaType`, `contentDigest`,
   * and a `source` of type `embedded` (`base64`), `uploaded` (`storageKey`) or `catalog`. Unknown
   * keys draw a placeholder.
   */
  val assets: JsonObject = JsonObject(emptyMap()),
  /**
   * The components this design defines (the protocol's `DesignComponentV1`, untyped): each has a
   * `name` (the generated function), a `root` node and an optional `description`.
   *
   * No parameter list: generators derive the signature from the keys instances pass and bodies
   * read. Never encoded when empty, so the cross-language document hash is unchanged for designs
   * without components.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val components: JsonObject = JsonObject(emptyMap()),
  /** Starter template identity retained by persisted designs; it does not affect interpretation. */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val template: String? = null,
  /**
   * Where this design is canonical, when it has one. Absent for older documents, which are not
   * guessed to belong anywhere.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = NullableUiBuilderDocumentHomeSerializer::class)
  val home: UiBuilderDocumentHome? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiBuilderNode(
  val id: String,
  val componentId: String,
  val properties: JsonObject = JsonObject(emptyMap()),
  val modifiers: JsonArray = JsonArray(emptyList()),
  val slots: Map<String, List<String>> = emptyMap(),
  val eventBindings: JsonObject = JsonObject(emptyMap()),
  /**
   * The component this node places, if any: `componentKey` and the `arguments` dictionary the body
   * reads via `{"type":"binding","value":"<key>"}`. A separate field so older readers draw a
   * placeholder; never encoded when absent.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val component: JsonObject? = null,
)

data class ReplayResult(val document: UiBuilderDocument, val operationRevisions: Map<String, Int>)

/** Candidate reducer. Its wire types move to compose-preview-contracts before the API is stable. */
object UiBuilderReducer {
  fun replay(fixture: JsonObject): ReplayResult {
    var document: UiBuilderDocument? = null
    val outcomes = linkedMapOf<String, Int>()

    fixture.array("operations").forEach { element ->
      val operation = element.jsonObject
      val operationId = operation.requiredString("operationId")
      if (operationId in outcomes) return@forEach

      when (operation.requiredString("type")) {
        "createDesign" -> {
          require(document == null) { "createDesign may only be accepted once" }
          document =
            UiBuilderDocument(
              schema = fixture.requiredString("documentSchema"),
              id = fixture.requiredString("designId"),
              title = operation.requiredString("title"),
              revision = 0,
              catalogPin = operation.obj("catalogPin"),
              environment = operation.obj("environment"),
              stateVariables = operation.obj("stateVariables"),
              roots = emptyList(),
              nodes = emptyMap(),
              assets = operation.obj("assets"),
              components = operation.obj("components"),
              home = operation.home(),
            )
          outcomes[operationId] = 0
        }
        "insertNode" -> {
          val current = requireNotNull(document) { "insertNode requires createDesign first" }
          val nodeObject = operation.obj("node")
          val node =
            UiBuilderNode(
              id = nodeObject.requiredString("id"),
              componentId = nodeObject.requiredString("componentId"),
              properties = nodeObject.obj("properties"),
              modifiers = nodeObject.array("modifiers"),
              slots =
                nodeObject.obj("slots").mapValues { (_, value) ->
                  value.jsonArray.map { it.jsonPrimitive.content }
                },
              eventBindings = nodeObject.obj("eventBindings"),
              component = nodeObject["component"] as? JsonObject,
            )
          require(node.id !in current.nodes) { "node already exists: ${node.id}" }

          val roots = current.roots.toMutableList()
          val nodes = current.nodes.toMutableMap()
          val parentElement = operation["parent"]
          if (parentElement == null || parentElement is JsonNull) {
            roots.insertAfter(node.id, operation.optionalString("afterNodeId"), "roots")
          } else {
            val parentRef = parentElement.jsonObject
            val parentId = parentRef.requiredString("nodeId")
            val slot = parentRef.requiredString("slot")
            val parent = requireNotNull(nodes[parentId]) { "unknown parent: $parentId" }
            val children = parent.slots[slot].orEmpty().toMutableList()
            children.insertAfter(node.id, operation.optionalString("afterNodeId"), slot)
            nodes[parentId] = parent.copy(slots = parent.slots + (slot to children))
          }
          nodes[node.id] = node
          document = current.copy(revision = current.revision + 1, roots = roots, nodes = nodes)
          outcomes[operationId] = document.revision
        }
        else -> error("unsupported candidate operation: ${operation.requiredString("type")}")
      }
    }

    return ReplayResult(
      requireNotNull(document) { "operation fixture did not create a design" },
      outcomes,
    )
  }
}

fun canonicalJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
    is JsonArray ->
      element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonPrimitive -> {
      val number = element.takeUnless { it.isString }?.doubleOrNull
      if (number != null && number % 1.0 == 0.0) number.toLong().toString() else element.toString()
    }
  }

private fun MutableList<String>.insertAfter(value: String, anchor: String?, label: String) {
  if (anchor == null) {
    add(0, value)
    return
  }
  val index = indexOf(anchor)
  require(index >= 0) { "unknown insertion anchor $anchor in $label" }
  add(index + 1, value)
}

/**
 * The candidate wire accessors, public so the browser's exporters and this module share one
 * definition.
 */
fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be non-empty text" }
    .also { require(it.isNotEmpty()) { "$name must be non-empty text" } }

fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject ?: JsonObject(emptyMap())

fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

private val documentJson = Json {
  classDiscriminator = "type"
  ignoreUnknownKeys = true
}

private fun JsonObject.home(): UiBuilderDocumentHome? =
  this["home"]
    ?.takeUnless { it is JsonNull }
    ?.let { home ->
      val kind = (home as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull
      if (kind !in setOf("server", "repo")) null
      else documentJson.decodeFromJsonElement(UiBuilderDocumentHome.serializer(), home)
    }
