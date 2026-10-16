package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A developer option: which engine a catalog runtime draws with, for runtimes that offer more than
 * one (remote-m3 records a fresh document per edit, or keeps a live remote composition), so both
 * can be compared in the same editor.
 *
 * Hidden unless the page asks for it ([enabled], from `?previewEngine=` or a choice made before).
 * Offered only once a runtime announced [CAPABILITY] ([offered]), and sent only to such a runtime,
 * as [ENVIRONMENT_KEY] in the environment of the document it draws. The choice is the viewer's, not
 * the design's: it is never saved into the document.
 */
internal object CatalogRuntimePreviewEngine {
  /** Announced by a runtime in `initialized`: it honours [ENVIRONMENT_KEY]. */
  const val CAPABILITY = "previewEngines"

  /** The document environment key a runtime reads the engine from. */
  const val ENVIRONMENT_KEY = "previewEngine"

  enum class Engine(val id: String, val label: String, val detail: String) {
    Capture("capture", "Capture", "Record a fresh document on every edit"),
    Live("live", "Live", "Keep the remote composition alive across edits"),
  }

  /** Whether the toolbar offers the choice at all. */
  var enabled by mutableStateOf(false)

  /** Whether any runtime on the page announced [CAPABILITY]. */
  var offered by mutableStateOf(false)

  /** The engine asked for, or null for the runtime's own default. */
  var selected by mutableStateOf<Engine?>(null)
    private set

  /** Where the page keeps the choice (the browser's storage); set by the platform at start. */
  var persist: (Engine?) -> Unit = {}

  fun select(engine: Engine?) {
    selected = engine
    persist(engine)
  }

  fun parse(id: String): Engine? = Engine.entries.firstOrNull { it.id == id }

  /**
   * This document as sent to a runtime with [capabilities]: the selected engine in its environment
   * when the runtime takes one, else unchanged.
   */
  fun applyTo(document: UiBuilderDocument, capabilities: Set<String>): UiBuilderDocument {
    val engine = selected ?: return document
    if (CAPABILITY !in capabilities) return document
    return document.copy(
      environment = JsonObject(document.environment + (ENVIRONMENT_KEY to JsonPrimitive(engine.id)))
    )
  }
}
