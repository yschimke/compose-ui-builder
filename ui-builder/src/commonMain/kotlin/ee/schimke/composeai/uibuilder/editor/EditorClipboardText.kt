package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The editor's clipboard as text, so a copy in one tab, window or IDE can be pasted in another: the
 * copied subtrees plus definitions of every component they place ([UiBuilderEditorReducer]
 * `pasteComponents`). Marked with [FORMAT] and decoded strictly, so other clipboard text is
 * ignored.
 */
@Serializable
internal data class EditorClipboardTextV1(
  val format: String = FORMAT,
  val roots: List<String>,
  val nodes: Map<String, UiBuilderNode>,
  val components: Map<String, JsonObject> = emptyMap(),
) {
  companion object {
    const val FORMAT = "compose-ui-builder-clipboard/v1"
  }
}

private val clipboardJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/** [clipboard] as text for the system clipboard. Where a cut came from stays in this editor. */
internal fun encodeEditorClipboard(clipboard: EditorClipboard): String =
  clipboardJson.encodeToString(
    EditorClipboardTextV1.serializer(),
    EditorClipboardTextV1(
      roots = clipboard.rootNodeIds,
      nodes = clipboard.nodes,
      components = clipboard.components,
    ),
  )

/** The clipboard [text] carries, or null when it is not one this editor wrote. */
internal fun decodeEditorClipboard(text: String): EditorClipboard? {
  if (EditorClipboardTextV1.FORMAT !in text) return null
  val payload =
    runCatching { clipboardJson.decodeFromString(EditorClipboardTextV1.serializer(), text.trim()) }
      .getOrNull()
      ?.takeIf { it.format == EditorClipboardTextV1.FORMAT && it.roots.isNotEmpty() } ?: return null
  if (payload.roots.any { it !in payload.nodes }) return null
  return EditorClipboard(
    rootNodeIds = payload.roots,
    nodes = payload.nodes,
    components = payload.components,
  )
}

/**
 * Whether two clipboards hold the same fragment, whatever each knows about where a cut came from.
 */
internal fun EditorClipboard?.sameFragmentAs(other: EditorClipboard?): Boolean =
  this?.rootNodeIds == other?.rootNodeIds &&
    this?.nodes == other?.nodes &&
    this?.components == other?.components

/**
 * The system clipboard's text, or null when absent or refused (headless JVM, browser permission);
 * the paste then uses the editor's own clipboard.
 */
internal expect suspend fun readSystemClipboardText(): String?

/**
 * Whether every paste consults the system clipboard: true on desktop and IDEs where reading is
 * free, false in browsers (which prompt), where it is read only when the editor has nothing of its
 * own.
 */
internal expect val readsSystemClipboardOnEveryPaste: Boolean
