package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The editor's own chrome — toolbar, layers, canvas and inspector — rendered as a preview.
 *
 * [ProductionUiBuilderPreview] draws the *designed document* with `editorOverlay = false`, so the
 * editor around it was in no preview at all. Every change to it therefore shipped with the words
 * "not something a static render shows", which was true and was a hole: the repository asks that a
 * new visual surface be wired into the preview workflow so the next change to it is diffed without
 * anyone remembering to. This is that wiring. Layer names, toolbar controls, the property inspector
 * and the selection overlay are now diffed like every other surface.
 *
 * Everything it draws is fixed: the frozen Jetcaster fixture replayed to a known revision, its own
 * catalog pin, the default local actor and session label, and no collaborators. Nothing here reads
 * a clock, the network or a random source, and the selection is pinned to a node rather than left
 * to whichever root the editor would pick.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderEditorChromePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
  )
}

/**
 * A node deep enough in the tree to exercise the layers panel's indentation and its naming, rather
 * than a root whose row says the same thing either way. It is also on screen in both panels at
 * once: its layer row is above the fold and the text it carries is in the rendered canvas, so a
 * change to either the naming rule or the property inspector shows up here.
 */
private const val EDITOR_CHROME_PREVIEW_SELECTION = "search-placeholder"

private val editorChromePreviewDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/jetcaster-discover-operations-v1.json")).jsonObject
    )
    .document
}

private val editorChromePreviewCatalog: CapabilityCatalog by lazy {
  CapabilityCatalogParser.parse(previewResource("/jetcaster-discover-capabilities-v1.json"))
}

private fun previewResource(path: String): String =
  checkNotNull(UiBuilderDocument::class.java.getResource(path)) { "missing preview resource $path" }
    .readText()
