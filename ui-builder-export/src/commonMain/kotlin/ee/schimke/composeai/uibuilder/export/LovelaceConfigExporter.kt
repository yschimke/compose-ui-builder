package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.*

/**
 * Lossless Lovelace dashboard projection. The host supplies config and owns authentication and
 * saving. Unknown fields (including nested custom card config) remain in rawConfig. Fields exposed
 * for editing are moved out of rawConfig, so removing a property really deletes it.
 */
object LovelaceConfigExporter {
  const val CATALOG_SYSTEM_ID = "lovelace"
  const val RAW_CONFIG = "rawConfig"
  const val DASHBOARD = "lovelace/dashboard"
  const val VIEW = "lovelace/view"
  const val OPAQUE_VIEW = "lovelace/opaque-view"
  const val SECTION = "lovelace/section"
  const val CARD = "lovelace/card/"

  private val fields =
    mapOf(
      DASHBOARD to setOf("title"),
      VIEW to setOf("title", "path", "icon", "max_columns"),
      SECTION to setOf("title", "column_span", "row_span"),
      CARD to
        setOf(
          "title",
          "name",
          "entity",
          "icon",
          "content",
          "min",
          "max",
          "unit",
          "show_name",
          "show_state",
        ),
    )

  sealed interface Result {
    data class Emitted(val config: JsonObject, val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /** Refuse wire-only semantics rather than silently discarding them during projection. */
  fun export(document: DesignDocumentV1): Result {
    val unsupported = buildList {
      if (document.tokenBindings.isNotEmpty())
        add("tokenBindings: Lovelace has no design token bindings")
      document.nodes.forEach { (id, node) ->
        if (node.predicate != null) add("nodes.$id.predicate: use Lovelace visibility config")
        if (node.accessibility != null) add("nodes.$id.accessibility: use Lovelace card config")
        if (node.assetBindings.isNotEmpty())
          add("nodes.$id.assetBindings: use Lovelace card config")
        if (node.tokenBindings.isNotEmpty())
          add("nodes.$id.tokenBindings: Lovelace has no design token bindings")
      }
    }
    return if (unsupported.isNotEmpty()) Result.Refused(unsupported)
    else export(document.toUiBuilderDocument())
  }

  /** Import only sections views structurally; other views and strategies remain opaque. */
  fun import(
    config: JsonObject,
    id: String,
    catalogPin: JsonObject,
    cardComponentIds: Set<String> = emptySet(),
  ): UiBuilderDocument {
    val nodes = linkedMapOf<String, UiBuilderNode>()
    fun read(value: JsonObject, component: String, nodeId: String): String {
      val raw = value.toMutableMap()
      val properties = linkedMapOf<String, JsonElement>()
      val editable = fields[if (component.startsWith(CARD)) CARD else component].orEmpty()
      editable.forEach { key ->
        val value = raw[key] as? JsonPrimitive
        val editableValue =
          when (key) {
            "min",
            "max" -> losslessNumber(value)
            "max_columns",
            "column_span",
            "row_span" -> value?.let { !it.isString && it.intOrNull != null } == true
            "show_name",
            "show_state" -> value?.let { !it.isString && it.booleanOrNull != null } == true
            else -> value?.isString == true
          }
        if (editableValue) raw.remove(key)?.let { properties[key] = wrap(it) }
      }
      if (component.startsWith(CARD)) {
        val grid = (raw["grid_options"] as? JsonObject)?.toMutableMap()
        if (grid != null) {
          for (key in listOf("columns", "rows")) {
            grid[key]
              ?.takeIf { validGridSize(key, it) && !(key == "rows" && it == JsonPrimitive("auto")) }
              ?.let { rawValue ->
                grid.remove(key)
                properties["grid_$key"] = wrap(rawValue)
              }
          }
          raw["grid_options"] = JsonObject(grid)
        }
      }
      val slot =
        when (component) {
          DASHBOARD -> "views"
          VIEW -> "sections"
          SECTION -> "cards"
          else -> null
        }
      val slots = linkedMapOf<String, List<String>>()
      if (slot != null && raw.containsKey(slot)) {
        val children = raw.remove(slot) as? JsonArray ?: error("$nodeId.$slot must be an array")
        slots[slot] = children.mapIndexed { index, child ->
          val obj = child as? JsonObject ?: error("$nodeId.$slot[$index] must be an object")
          val kind =
            when (slot) {
              "views" ->
                if (obj["type"] == JsonPrimitive("sections") && !obj.containsKey("strategy")) VIEW
                else OPAQUE_VIEW
              "sections" -> SECTION
              else -> {
                val type =
                  (obj["type"] as? JsonPrimitive)?.content ?: error("card type is required")
                (CARD + type).takeIf { it in cardComponentIds } ?: (CARD + "custom")
              }
            }
          read(obj, kind, "$nodeId-$index")
        }
      }
      // An opaque JSON string is intentional. UiValue.object recursively decodes typed values,
      // which would reinterpret custom config and round unknown numbers during .uid persistence.
      properties[RAW_CONFIG] = wrap(JsonPrimitive(jsonText(JsonObject(raw))))
      nodes[nodeId] = UiBuilderNode(nodeId, component, JsonObject(properties), slots = slots)
      return nodeId
    }
    val root = read(config, DASHBOARD, "dashboard")
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = (config["title"] as? JsonPrimitive)?.content ?: "Home Assistant dashboard",
      revision = 0,
      catalogPin = catalogPin,
      environment =
        buildJsonObject {
          put("widthDp", 1200)
          put("heightDp", 900)
          put("density", 1)
          put("theme", "light")
          put("locale", "en-US")
          put("fontScale", 1)
          put("layoutDirection", "ltr")
        },
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(root),
      nodes = nodes,
    )
  }

  fun export(document: UiBuilderDocument): Result = exportRoot(document, DASHBOARD)

  /** Exact config for one card, used to match session-only host previews to the current edit. */
  fun exportCard(document: UiBuilderDocument, nodeId: String): Result =
    exportRoot(document.copy(roots = listOf(nodeId)), CARD)

  private fun exportRoot(document: UiBuilderDocument, expected: String): Result {
    val reasons = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    fun emit(id: String, expected: String): JsonObject {
      if (!seen.add(id)) {
        reasons += "$id: repeated node or cycle"
        return JsonObject(emptyMap())
      }
      val node = document.nodes[id]
      if (node == null) {
        reasons += "$id: missing node"
        return JsonObject(emptyMap())
      }
      val kind = node.componentId
      if (
        !(kind == expected ||
          expected == VIEW && kind == OPAQUE_VIEW ||
          expected == CARD && kind.startsWith(CARD))
      ) {
        reasons += "$id: expected $expected, got $kind"
      }
      if (node.modifiers.isNotEmpty() || node.eventBindings.isNotEmpty() || node.component != null)
        reasons += "$id: Lovelace cannot hold Compose modifiers, bindings or component instances"
      val raw = node.properties[RAW_CONFIG] as? JsonObject
      val original =
        if (raw == null) JsonObject(emptyMap())
        else
          runCatching {
            require(raw["type"] == JsonPrimitive("string"))
            val text = raw.getValue("value").jsonPrimitive
            require(text.isString)
            Json.parseToJsonElement(text.content).jsonObject
          }
            .getOrElse {
              reasons += "$id.rawConfig: expected JSON text containing an object"
              JsonObject(emptyMap())
            }
      val result = original.toMutableMap()
      val editable = fields[if (kind.startsWith(CARD)) CARD else kind].orEmpty()
      node.properties.forEach { (key, value) ->
        when {
          key == RAW_CONFIG -> Unit
          key in editable -> result[key] = unwrap(value, "$id.$key", reasons)
          kind.startsWith(CARD) && key in setOf("grid_columns", "grid_rows") -> Unit
          else -> reasons += "$id.$key: unsupported property"
        }
      }
      val slot =
        when (kind) {
          DASHBOARD -> "views"
          VIEW -> "sections"
          SECTION -> "cards"
          else -> null
        }
      if (node.slots.keys.any { it != slot }) reasons += "$id: unsupported child slot"
      if (slot != null && node.slots.containsKey(slot)) {
        val childKind =
          when (slot) {
            "views" -> VIEW
            "sections" -> SECTION
            else -> CARD
          }
        result[slot] = JsonArray(node.slots.getValue(slot).map { emit(it, childKind) })
      }
      if (kind == VIEW) result["type"] = JsonPrimitive("sections")
      if (kind == SECTION && raw == null && !result.containsKey("type"))
        result["type"] = JsonPrimitive("grid")
      if (kind.startsWith(CARD)) {
        if (!result.containsKey("type")) {
          if (kind == CARD + "custom")
            reasons += "$id.rawConfig: custom cards require their original type"
          else result["type"] = JsonPrimitive(kind.removePrefix(CARD))
        }
        val grid = (result["grid_options"] as? JsonObject).orEmpty().toMutableMap()
        for (key in listOf("columns", "rows")) {
          node.properties["grid_$key"]?.let {
            val value = unwrap(it, "$id.grid_$key", reasons)
            val valid = validGridSize(key, value)
            if (!valid) reasons += "$id.grid_$key: invalid grid size"
            grid[key] = value
          }
        }
        if (
          grid.isNotEmpty() ||
            result["grid_options"] is JsonObject ||
            node.properties.keys.any { it in setOf("grid_columns", "grid_rows") }
        )
          result["grid_options"] = JsonObject(grid)
      }
      return JsonObject(result)
    }
    if (document.roots.size != 1)
      return Result.Refused(listOf("dashboard requires exactly one root"))
    if (document.stateVariables.isNotEmpty())
      reasons += "Lovelace values come from the host, not design state"
    val config = emit(document.roots.single(), expected)
    return if (reasons.isEmpty()) Result.Emitted(config, jsonText(config) + "\n")
    else Result.Refused(reasons)
  }

  // Only extract a number the typed .uid scalar codec can represent without changing its
  // spelling/value. Other numeric forms remain opaque, just like future schema value forms.
  private fun losslessNumber(value: JsonPrimitive?): Boolean {
    if (value == null || value.isString) return false
    if (value.intOrNull != null) return true
    return value.doubleOrNull?.let {
      it.isFinite() && JsonPrimitive(it).content == value.content
    } == true
  }

  /** Sort object keys without coercing unknown JSON numbers through Double. */
  private fun jsonText(value: JsonElement): String =
    when (value) {
      is JsonObject ->
        value.entries
          .sortedBy { it.key }
          .joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + jsonText(it.value)
          }
      is JsonArray -> value.joinToString(",", "[", "]") { jsonText(it) }
      else -> value.toString()
    }

  private fun validGridSize(key: String, value: JsonElement): Boolean {
    val n = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    return if (key == "columns") n in 1..12 || value == JsonPrimitive("full")
    else n != null && n > 0 || value == JsonPrimitive("auto")
  }

  private fun wrap(value: JsonElement): JsonObject = buildJsonObject {
    when (value) {
      is JsonObject -> {
        put("type", "object")
        put("fields", value)
      }
      is JsonArray -> {
        put("type", "list")
        put("values", value)
      }
      else -> {
        val p = value as? JsonPrimitive
        put(
          "type",
          when {
            p == null || p.isString -> "string"
            p.booleanOrNull != null -> "bool"
            p.intOrNull != null -> "int"
            else -> "float"
          },
        )
        put("value", value)
      }
    }
  }

  private fun unwrap(value: JsonElement, where: String, reasons: MutableList<String>): JsonElement {
    val obj = value as? JsonObject ?: return value
    return when ((obj["type"] as? JsonPrimitive)?.content) {
      "string",
      "enum",
      "int",
      "float",
      "bool" -> obj["value"] ?: JsonNull
      "object" -> obj["fields"] ?: JsonObject(emptyMap())
      "list" -> obj["values"] ?: JsonArray(emptyList())
      else -> {
        reasons += "$where: only literal values can be exported"
        JsonNull
      }
    }
  }
}
