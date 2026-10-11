package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lowers a design built from an A2UI catalog's palette (`a2ui/<Component>` builtins, as
 * yschimke/a2ui-catalog publishes them) to the A2UI v0.9 messages an agent would send:
 * `createSurface`, `updateDataModel` when the design declares state, then one `updateComponents`.
 * [A2uiComposeExporter] renders the same [lower] as Kotlin.
 *
 * The lowering is literal:
 * * `componentId` minus `a2ui/` is the A2UI `component`;
 * * single-child slots (`child`, `trigger`, `content`) become an id, others an id list, except
 *   `Tabs`' `tabs`, which becomes `{title, child}` entries;
 * * literal property wrappers become bare values, and `{"type":"state","variable":"x"}` becomes
 *   `{"path":"/x"}` against the data model built from `stateVariables`;
 * * the single root is renamed `root`, and references follow.
 *
 * Anything without an A2UI counterpart is refused with a reason: non-`a2ui/` nodes, Compose
 * modifiers, event bindings, and more than one root.
 */
object A2uiDocumentExporter {
  const val PROTOCOL_VERSION: String = "v0.9"
  const val COMPONENT_PREFIX: String = "a2ui/"

  /** The AndroidX / a2ui.org basic catalog, which `material3-a2ui` 1.0.0-alpha01 implements. */
  const val BASIC_CATALOG_ID: String =
    "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

  /** The builder catalog whose palette this lowers: the A2UI basic catalog, packaged here. */
  const val CATALOG_SYSTEM_ID: String = "a2ui-catalog"

  private const val ROOT_ID = "root"
  private val SINGLE_CHILD_SLOTS = setOf("child", "trigger", "content")
  private val LITERAL_WRAPPERS = setOf("string", "int", "float", "bool", "enum")

  /**
   * A2UI's `Tabs` nests bodies in `{title, child}` objects; the builder models a `tabs` slot plus a
   * builder-only `titles` list, zipped back here.
   */
  private const val TABS = "Tabs"
  private const val TABS_SLOT = "tabs"
  private const val TAB_TITLES = "titles"

  private fun tabEntries(ids: List<String>, titles: JsonArray?): JsonArray =
    JsonArray(
      ids.mapIndexed { index, id ->
        buildJsonObject {
          put("title", titles?.getOrNull(index) ?: JsonPrimitive("Tab ${index + 1}"))
          put("child", id)
        }
      }
    )

  sealed interface Result {
    /**
     * [messages] in send order, and [source] as JSON Lines — one message per line, the framing A2UI
     * streams over a transport.
     */
    data class Emitted(val messages: List<JsonObject>, val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * The design as A2UI's own shapes (components and data model), before framing as messages. Shared
   * by [export] (JSON Lines) and [A2uiComposeExporter] (Kotlin), so both payloads and refusals
   * agree.
   */
  sealed interface Lowered {
    /**
     * @param components the `updateComponents` entries in send order, the root (renamed `root`)
     *   first, each carrying its `id` and `component`.
     * @param data the surface's data model: every declared state variable's initial value.
     */
    data class Components(val components: List<JsonObject>, val data: JsonObject) : Lowered

    data class Refused(val reasons: List<String>) : Lowered
  }

  fun lower(document: UiBuilderDocument): Lowered {
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

    val components = buildList {
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
            var tabTitles: JsonArray? = null
            for ((name, value) in node.properties) {
              val lowered = lowerValue(value, "$where.properties.$name", document, reasons)
              if (type == TABS && name == TAB_TITLES) {
                tabTitles = lowered as? JsonArray
                if (lowered != null && tabTitles == null) {
                  reasons += "$where.properties.$name: tab titles are a list"
                }
              } else if (lowered != null) {
                put(name, lowered)
              }
            }
            for ((slot, children) in node.slots) {
              val ids = children.map(rename)
              if (type == TABS && slot == TABS_SLOT) {
                put(slot, tabEntries(ids, tabTitles))
              } else if (slot in SINGLE_CHILD_SLOTS) {
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
    return if (reasons.isNotEmpty()) Lowered.Refused(reasons)
    else Lowered.Components(components, initialData(document))
  }

  fun export(
    document: UiBuilderDocument,
    surfaceId: String = document.id,
    catalogId: String = BASIC_CATALOG_ID,
  ): Result {
    val lowered =
      when (val lowered = lower(document)) {
        is Lowered.Refused -> return Result.Refused(lowered.reasons)
        is Lowered.Components -> lowered
      }
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
      if (lowered.data.isNotEmpty()) {
        add(
          envelope(
            "updateDataModel",
            buildJsonObject {
              put("surfaceId", surfaceId)
              put("path", "/")
              put("value", lowered.data)
            },
          )
        )
      }
      add(
        envelope(
          "updateComponents",
          buildJsonObject {
            put("surfaceId", surfaceId)
            put("components", JsonArray(lowered.components))
          },
        )
      )
    }
    return Result.Emitted(messages, messages.joinToString("\n") { canonicalJson(it) } + "\n")
  }

  /**
   * The root first, then every node reachable through slots, each once, in slot order. A dangling
   * reference is refused.
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
