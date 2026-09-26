package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lowers a design built from an A2UI catalog's palette (`a2ui/<Component>` builtins, as
 * yschimke/a2ui-catalog's `ui-builder.policy.json` publishes them) to the A2UI v0.9 messages an
 * agent would send to draw it: `createSurface`, then `updateDataModel` when the design declares
 * state, then one `updateComponents` carrying every component.
 *
 * An A2UI design has no Kotlin to generate — the client already holds the catalog; what varies is
 * the payload — so this is the export, the way [RemoteDocumentJsonExporter] is for Remote Compose.
 *
 * The lowering is deliberately literal, because the protocol already is the builder's shape:
 * * a node's `componentId` minus the `a2ui/` prefix is the A2UI `component`;
 * * its slots are A2UI child references — a single-child slot (`child`, `trigger`, `content`)
 *   becomes an id, any other slot (`children`) an id list;
 * * a property wrapper (`{"type":"string","value":…}` and the other literal kinds) becomes its bare
 *   value, and a `{"type":"state","variable":"x"}` reference becomes the data binding
 *   `{"path":"/x"}`, reading the `stateVariables` initial values this export writes into the
 *   surface's data model;
 * * the design's single root is renamed `root`, the id A2UI draws a surface from, and every
 *   reference to it follows.
 *
 * What has no A2UI counterpart is REFUSED with a reason rather than dropped: a node outside the
 * `a2ui/` palette, a Compose modifier (A2UI lays out through its components, and `weight` is a
 * property), an event binding (a Button's `action` is a property), and more than one root.
 */
object A2uiDocumentExporter {
  const val PROTOCOL_VERSION: String = "v0.9"
  const val COMPONENT_PREFIX: String = "a2ui/"

  /** The AndroidX / a2ui.org basic catalog, which `material3-a2ui` 1.0.0-alpha01 implements. */
  const val BASIC_CATALOG_ID: String =
    "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

  private const val ROOT_ID = "root"
  private val SINGLE_CHILD_SLOTS = setOf("child", "trigger", "content")
  private val LITERAL_WRAPPERS = setOf("string", "int", "float", "bool", "enum")

  sealed interface Result {
    /**
     * [messages] in send order, and [source] as JSON Lines — one message per line, the framing A2UI
     * streams over a transport.
     */
    data class Emitted(val messages: List<JsonObject>, val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  fun export(
    document: UiBuilderDocument,
    surfaceId: String = document.id,
    catalogId: String = BASIC_CATALOG_ID,
  ): Result {
    val reasons = mutableListOf<String>()
    if (document.roots.size != 1) {
      reasons +=
        "roots: an A2UI surface draws exactly one root, this design has ${document.roots.size}"
    }
    val rootId = document.roots.firstOrNull()
    val rename: (String) -> String = { id -> if (id == rootId) ROOT_ID else id }
    if (rootId != ROOT_ID && document.nodes.containsKey(ROOT_ID)) {
      reasons += "nodes.root: `root` is reserved for the design's root node, which is `$rootId`"
    }

    val components = buildJsonArray {
      for (node in reachable(document, rootId, reasons)) {
        val where = "nodes.${node.id}"
        val type = node.componentId.removePrefix(COMPONENT_PREFIX)
        if (!node.componentId.startsWith(COMPONENT_PREFIX) || type.isEmpty()) {
          reasons +=
            "$where: `${node.componentId}` is not an A2UI component (expected `$COMPONENT_PREFIX<Component>`)"
          continue
        }
        if (node.modifiers.isNotEmpty()) {
          reasons += "$where.modifiers: A2UI has no modifiers; lay out with Row/Column and `weight`"
        }
        if (node.eventBindings.isNotEmpty()) {
          reasons +=
            "$where.eventBindings: an A2UI action is the `action` property, not an event binding"
        }
        add(
          buildJsonObject {
            put("id", rename(node.id))
            put("component", type)
            for ((name, value) in node.properties) {
              val lowered = lowerValue(value, "$where.properties.$name", document, reasons)
              if (lowered != null) put(name, lowered)
            }
            for ((slot, children) in node.slots) {
              val ids = children.map(rename)
              if (slot in SINGLE_CHILD_SLOTS) {
                if (ids.size > 1) reasons += "$where.slots.$slot: A2UI `$slot` takes one component"
                ids.firstOrNull()?.let { put(slot, it) }
              } else {
                put(slot, JsonArray(ids.map(::JsonPrimitive)))
              }
            }
          }
        )
      }
    }
    if (reasons.isNotEmpty()) return Result.Refused(reasons)

    val messages = buildList {
      add(
        envelope(
          "createSurface",
          buildJsonObject {
            put("surfaceId", surfaceId)
            put("catalogId", catalogId)
          },
        )
      )
      val data = initialData(document)
      if (data.isNotEmpty()) {
        add(
          envelope(
            "updateDataModel",
            buildJsonObject {
              put("surfaceId", surfaceId)
              put("path", "/")
              put("value", data)
            },
          )
        )
      }
      add(
        envelope(
          "updateComponents",
          buildJsonObject {
            put("surfaceId", surfaceId)
            put("components", components)
          },
        )
      )
    }
    return Result.Emitted(messages, messages.joinToString("\n") { canonicalJson(it) } + "\n")
  }

  /**
   * The root first, then every node reachable through slots, each once, in slot order. A reference
   * to a node the document does not contain is refused: emitting it would hand the client a
   * component graph with a dangling id, which it cannot draw.
   */
  private fun reachable(
    document: UiBuilderDocument,
    rootId: String?,
    reasons: MutableList<String>,
  ): List<UiBuilderNode> {
    val seen = linkedMapOf<String, UiBuilderNode>()
    fun visit(id: String, from: String) {
      if (id in seen) return
      val node = document.nodes[id]
      if (node == null) {
        reasons += "$from: references `$id`, which is not a node of this design"
        return
      }
      seen[id] = node
      for ((slot, children) in node.slots) children.forEach { visit(it, "nodes.$id.slots.$slot") }
    }
    rootId?.let { visit(it, "roots") }
    return seen.values.toList()
  }

  private fun lowerValue(
    value: JsonElement,
    where: String,
    document: UiBuilderDocument,
    reasons: MutableList<String>,
  ): JsonElement? {
    val wrapper = value as? JsonObject ?: return value
    val type = (wrapper["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
    return when (type) {
      in LITERAL_WRAPPERS -> wrapper["value"] ?: JsonNull
      // Structured values, as the builder's reducer writes them: an object's members under
      // `fields`, a list's elements under `values`, each of which may itself be a wrapper.
      "object" -> {
        val fields = wrapper["fields"] as? JsonObject
        if (fields == null) {
          reasons += "$where: an `object` value carries its members under `fields`"
          null
        } else {
          buildJsonObject {
            for ((key, member) in fields) {
              lowerValue(member, "$where.fields.$key", document, reasons)?.let { put(key, it) }
            }
          }
        }
      }
      "list" -> {
        val values = wrapper["values"] as? JsonArray
        if (values == null) {
          reasons += "$where: a `list` value carries its elements under `values`"
          null
        } else {
          JsonArray(
            values.mapIndexedNotNull { i, element ->
              lowerValue(element, "$where.values[$i]", document, reasons)
            }
          )
        }
      }
      "state" -> {
        val variable = (wrapper["variable"] as? JsonPrimitive)?.content
        if (variable == null || !document.stateVariables.containsKey(variable)) {
          reasons += "$where: state `$variable` is not declared in stateVariables"
          null
        } else {
          buildJsonObject { put("path", "/$variable") }
        }
      }
      else -> {
        reasons += "$where: a `$type` value has no A2UI counterpart"
        null
      }
    }
  }

  /** Each declared state variable's `initialValue`, the surface's data model at creation. */
  private fun initialData(document: UiBuilderDocument): JsonObject = buildJsonObject {
    for ((name, declaration) in document.stateVariables) {
      val initial = (declaration as? JsonObject)?.get("initialValue") ?: continue
      put(name, initial)
    }
  }

  private fun envelope(kind: String, body: JsonObject) = buildJsonObject {
    put("version", PROTOCOL_VERSION)
    put(kind, body)
  }
}
