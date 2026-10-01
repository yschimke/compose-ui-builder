package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The editor's clipboard as text, so a copy in one tab, window or IDE can be pasted in another.
 *
 * What is copied is a fragment of a design: the copied subtrees, and the definitions of every
 * component they place, with those components' bodies. A design opened elsewhere reads it back and
 * pastes it the way it pastes its own clipboard — a component it does not define arrives with the
 * placement ([UiBuilderEditorReducer] `pasteComponents`).
 *
 * Marked with [FORMAT] and decoded strictly on that marker, so any other text on the system
 * clipboard — a sentence, a Kotlin file, a design's whole JSON — is simply not a clipboard here.
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
 * The system clipboard's text, or null when there is none or it cannot be read.
 *
 * A host may refuse — a headless JVM has no clipboard, a browser asks the person first — and every
 * refusal is null: the paste then uses the editor's own clipboard, which is what it did before
 * there was a system clipboard to consult.
 */
internal expect suspend fun readSystemClipboardText(): String?

/**
 * Whether a paste consults the system clipboard even while this editor holds a clipboard of its
 * own. True on the desktop and in an IDE, where reading it is free; false in a browser, where it
 * asks the person each time, so a tab consults it only when it has nothing of its own to paste.
 */
internal expect val readsSystemClipboardOnEveryPaste: Boolean
