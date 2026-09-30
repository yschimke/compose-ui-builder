package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `ui/update-model-context` params this editor sends: `content` blocks and `structuredContent`.
 *
 * Each call replaces the previous one, so [Empty] is how a cleared selection takes the attachment
 * away again.
 */
data class McpAppModelContext(val content: JsonArray, val structuredContent: JsonObject?) {
  fun toParams(): JsonObject = buildJsonObject {
    put("content", content)
    structuredContent?.let { put("structuredContent", it) }
  }

  companion object {
    val Empty: McpAppModelContext = McpAppModelContext(JsonArray(emptyList()), null)
  }
}

/**
 * What the model is told about the selected layer, so "make this bigger" names the right node.
 *
 * Two blocks, per the OpenAI `ui/update-model-context` extension:
 * - a **visible** text block, titled with `_meta["openai/title"]`, which the host shows as a
 *   removable composer attachment: the component, the node id, its path from the root and its
 *   properties — short enough for a person to read;
 * - a **hidden** block, `annotations.audience: ["assistant"]`, with the same facts as JSON plus how
 *   to act on them: the design is the opened `.uid` file, and a node is addressed by its id, which
 *   is stable across edits where a path is not.
 *
 * `structuredContent` carries the JSON too, for a host that reads it rather than the text.
 */
object McpAppSelectionContext {
  private val compact = Json { encodeDefaults = true }

  fun of(file: McpAppFile, document: UiBuilderDocument, nodeId: String?): McpAppModelContext {
    val node = nodeId?.let(document.nodes::get) ?: return McpAppModelContext.Empty
    val path = nodePath(document, node.id)
    val component = componentName(node.componentId)
    val detail = buildJsonObject {
      put("file", file.name)
      put("designId", document.id)
      put("catalogSystemId", (document.catalogPin["systemId"] as? JsonPrimitive)?.content)
      put("nodeId", node.id)
      put("componentId", node.componentId)
      putJsonArray("path") {
        path.forEach { step ->
          add(
            buildJsonObject {
              put("nodeId", step.nodeId)
              put("componentId", step.componentId)
              step.slot?.let { put("slot", it) }
              step.index?.let { put("index", it) }
            }
          )
        }
      }
      put("properties", node.properties)
      if (node.modifiers.isNotEmpty()) put("modifiers", node.modifiers)
      putJsonObject("slots") {
        node.slots.forEach { (slot, children) ->
          putJsonArray(slot) { children.forEach { add(it) } }
        }
      }
    }
    val visible = buildString {
      append("Selected in ${file.name}: $component `${node.id}` (${node.componentId})\n")
      append("Path: ").append(path.joinToString(" › ") { it.label() }).append('\n')
      if (node.properties.isEmpty()) append("Properties: none")
      else
        append("Properties: ")
          .append(compact.encodeToString(JsonObject.serializer(), node.properties))
    }
    val hidden =
      "The person is pointing at node `${node.id}` of the UI Builder design in ${file.name}. " +
        "To change it, edit that node in the design file (a DesignDocumentV1 `.uid`); address it " +
        "by node id, which is stable, rather than by path. Selection detail: " +
        compact.encodeToString(JsonObject.serializer(), detail)
    return McpAppModelContext(
      content =
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "text")
              put("text", visible)
              putJsonObject("_meta") { put("openai/title", "$component · ${node.id}") }
            }
          )
          add(
            buildJsonObject {
              put("type", "text")
              put("text", hidden)
              putJsonObject("annotations") { putJsonArray("audience") { add("assistant") } }
            }
          )
        },
      structuredContent = buildJsonObject { put("selection", detail) },
    )
  }

  /** One step from a root to the selected node: which slot of the parent, and where in it. */
  data class PathStep(
    val nodeId: String,
    val componentId: String,
    val slot: String?,
    val index: Int?,
  ) {
    fun label(): String =
      (if (slot != null) "$slot[${index ?: 0}] " else "") +
        "${componentName(componentId)} `$nodeId`"
  }

  /** Root first, the selected node last. A node no root reaches is a path of itself alone. */
  fun nodePath(document: UiBuilderDocument, nodeId: String): List<PathStep> {
    val parents = mutableMapOf<String, Triple<String, String, Int>>()
    document.nodes.values.forEach { parent ->
      parent.slots.forEach { (slot, children) ->
        children.forEachIndexed { index, child -> parents[child] = Triple(parent.id, slot, index) }
      }
    }
    val steps = ArrayDeque<PathStep>()
    var current: String? = nodeId
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
      val node = document.nodes[current] ?: break
      val parent = parents[current]
      steps.addFirst(PathStep(node.id, node.componentId, parent?.second, parent?.third))
      current = parent?.first
    }
    return steps
  }

  /** `m3/button` → `Button`, `layout/column` → `Column`: the name a person would say. */
  fun componentName(componentId: String): String =
    componentId.substringAfterLast('/').split('-', '_').joinToString("") { part ->
      part.replaceFirstChar { it.uppercaseChar() }
    }
}
