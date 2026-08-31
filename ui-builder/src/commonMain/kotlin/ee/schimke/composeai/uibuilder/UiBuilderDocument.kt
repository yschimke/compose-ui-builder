package ee.schimke.composeai.uibuilder

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
)

@Serializable
data class UiBuilderNode(
  val id: String,
  val componentId: String,
  val properties: JsonObject = JsonObject(emptyMap()),
  val modifiers: JsonArray = JsonArray(emptyList()),
  val slots: Map<String, List<String>> = emptyMap(),
  val eventBindings: JsonObject = JsonObject(emptyMap()),
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

internal fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be non-empty text" }
    .also { require(it.isNotEmpty()) { "$name must be non-empty text" } }

internal fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

internal fun JsonObject.obj(name: String): JsonObject =
  this[name]?.jsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.array(name: String): JsonArray =
  this[name]?.jsonArray ?: JsonArray(emptyList())
