package ee.schimke.composeai.uibuilder

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
   * The design's asset registry, keyed by the `assetKey` an `asset/image` names: the protocol's
   * `AssetBindingV1` shape, carried untyped the way `catalogPin` is. Each value has a `mediaType`,
   * a `contentDigest`, and a `source` whose `type` is `embedded` (with the bytes in `base64`),
   * `uploaded` (with the `storageKey` a host resolves) or `catalog`. Defaulted so every fixture and
   * every document written before the registry existed still parses; a renderer that finds a key
   * here draws it, and one that does not draws a placeholder.
   */
  val assets: JsonObject = JsonObject(emptyMap()),
  /**
   * The components this design defines, keyed the way an instance names them: the protocol's
   * `DesignComponentV1` shape, carried untyped as `catalogPin` and `assets` are. Each value has a
   * `name` — what the generated function is called — the `root` node its body starts at, and an
   * optional `description`.
   *
   * There is deliberately **no parameter list**. What an instance passes is a dictionary and what
   * the body reads is a key of it, so a generator derives the signature from those keys rather than
   * from a second statement of the same fact, free to disagree with the bodies and instances it
   * describes.
   *
   * Never encoded when empty, even by a writer that asks for defaults: the cross-language document
   * hash is taken over this shape, and a design that defines no component must canonicalize to
   * exactly what it did before the field existed.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val components: JsonObject = JsonObject(emptyMap()),
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
   * The component this node places, if it places one: `componentKey`, and the `arguments`
   * dictionary the body reads through `{"type":"binding","value":"<key>"}`.
   *
   * Its own field rather than a reserved property key, so a reader that has never heard of
   * components can still see what the node is and draw a placeholder, instead of reading a
   * component with properties nobody declared.
   *
   * Never encoded when absent, for the reason [UiBuilderDocument.components] is not.
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
 * The candidate wire accessors, public because the document shape now has two readers.
 *
 * They were `internal` while the document, its reducer and everything that read them lived in one
 * module. The templates and the reducer moved here so the server can seed a design the same way the
 * browser does, and the browser's own exporters still parse the same candidate shapes on the other
 * side of the module boundary — one definition, read from both, beats two that can drift.
 */
fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be non-empty text" }
    .also { require(it.isNotEmpty()) { "$name must be non-empty text" } }

fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject ?: JsonObject(emptyMap())

fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())
