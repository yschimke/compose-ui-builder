package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `ui/message` params: a user message for the conversation the app sits in. [target] and [send] are
 * the OpenAI `_meta["openai/message"]` options (`openai/mcp-extensions` `docs/spec.md`); this
 * editor always posts to the **active** thread and sends at once (the defaults), stated explicitly.
 */
data class McpAppMessage(
  val content: JsonArray,
  val target: String = "active",
  val send: Boolean = true,
) {
  fun toParams(): JsonObject = buildJsonObject {
    put("role", "user")
    put("content", content)
    putJsonObject("_meta") {
      putJsonObject("openai/message") {
        put("target", target)
        put("send", send)
      }
    }
  }
}

/** A picture to attach to model context: an MCP `image` content block's data. */
data class McpAppImage(val base64: String, val mimeType: String)

/**
 * What a **Comment** on a node turns into: one `ui/message` and one `ui/update-model-context`.
 *
 * The **message**: the person's words, then a titled block naming the node (component, id, path,
 * properties), shown as one labelled inline item.
 *
 * The **model context**: the node drawn alone (a titled `image` block) where possible, and its
 * detail as JSON with how to act on it, marked `annotations.audience: ["assistant"]`.
 * `structuredContent` carries the same JSON.
 */
object McpAppNodeComment {
  fun message(
    file: McpAppFile,
    document: UiBuilderDocument,
    nodeId: String,
    text: String,
  ): McpAppMessage? {
    val node = document.nodes[nodeId] ?: return null
    val comment = text.trim().takeIf { it.isNotEmpty() } ?: return null
    return McpAppMessage(
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", comment)
          }
        )
        add(
          McpAppSelectionContext.titledText(
            "Comment on " + McpAppSelectionContext.summary(file, document, node),
            McpAppSelectionContext.title(node),
          )
        )
      }
    )
  }

  fun context(
    file: McpAppFile,
    document: UiBuilderDocument,
    nodeId: String,
    text: String,
    image: McpAppImage?,
  ): McpAppModelContext? {
    val node = document.nodes[nodeId] ?: return null
    val comment = text.trim().takeIf { it.isNotEmpty() } ?: return null
    val detail = McpAppSelectionContext.detail(file, document, node)
    val hidden =
      "The person commented on node `${node.id}` of the UI Builder design in ${file.name}: " +
        "\"$comment\". If the comment asks for a change, edit that node in the design file (a " +
        "DesignDocumentV1 `.uid`), addressing it by node id, which is stable, rather than by " +
        "path; then show the result by rendering device previews in the chat " +
        "(render_preview / render_matrix) rather than asking the person to look at the editor " +
        "panel. Node detail: " +
        McpAppSelectionContext.encode(detail)
    return McpAppModelContext(
      content =
        buildJsonArray {
          if (image != null) {
            add(
              buildJsonObject {
                put("type", "image")
                put("data", image.base64)
                put("mimeType", image.mimeType)
                putJsonObject("_meta") { put("openai/title", McpAppSelectionContext.title(node)) }
              }
            )
          }
          add(McpAppSelectionContext.assistantText(hidden))
        },
      structuredContent =
        buildJsonObject {
          putJsonObject("comment") {
            put("nodeId", node.id)
            put("text", comment)
          }
          put("selection", detail)
        },
    )
  }
}
