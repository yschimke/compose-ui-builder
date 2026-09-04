package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * The inspector with a layout container selected.
 *
 * A second preview rather than a second selection on the first, because they answer different
 * questions: that one is the editor's shape, this one is what the property panel will actually let
 * a person change about a screen's layout. `discover-grid` is a `layout/lazy-grid`, which declares
 * spacing, an adaptive column rule and a content padding — the three shapes the inspector used to
 * refuse.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderLayoutInspectorPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
  )
}

private val editorChromePreviewDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/jetcaster-discover-operations-v1.json")).jsonObject
    )
    .document
}

/**
 * The issues panel with something to say.
 *
 * The checked-in fixture is clean, which is the right baseline and a useless render: an empty panel
 * proves nothing about how a problem reads. Two are seeded here instead, one of each kind the gate
 * finds — a node missing a property its component requires, and a node no root can reach.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderIssuesInspectorPreview() {
  UiBuilderEditor(
    document = editorIssuesPreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorMode = EditorInspectorMode.Issues,
  )
}

/**
 * The canvas handed to the screen.
 *
 * The selection overlay is gone, so a tap reaches the component under it and the renderer's state
 * writes actually run. The toolbar says which side it is on. Nothing else moves — the panels stay
 * live on purpose, because they are explicit actions, unlike a keystroke aimed at the canvas.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderPreviewModePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    // The same node `UiBuilderLayoutInspectorPreview` selects, and a big one, so the selection
    // overlay's absence is the visible half of a change that is otherwise all behaviour.
    initialSelectedNodeId = "discover-grid",
    initialPreviewMode = true,
  )
}

/**
 * The layers panel filtered, which is the state the panel spends most of its useful life in.
 *
 * `m3/filter-chip` narrows a hundred and eight rows to the fixture's four chips plus the chain that
 * holds them, and the panel offers to select all four at once — which is what makes the multi-node
 * inspector reachable on a screen this size.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderLayerFilterPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialLayerQuery = "m3/filter-chip",
  )
}

private val editorIssuesPreviewDocument: UiBuilderDocument by lazy {
  val document = editorChromePreviewDocument
  val placeholder = document.nodes.getValue(EDITOR_CHROME_PREVIEW_SELECTION)
  val searchInput = document.nodes.getValue("search-input")
  document.copy(
    nodes =
      document.nodes +
        mapOf(
          // `m3/text` requires `text`, and a node that lost it is not a rejected write — nothing
          // wrote to it. It is a document that will not export.
          placeholder.id to
            placeholder.copy(properties = JsonObject(placeholder.properties - "text")),
          // Dropping the child leaves the icon in `nodes` with no parent, which is the shape a
          // botched delete leaves behind. The slot it moves to names a node that was never there,
          // which is the other half: the canvas and the layers panel both walk that reference, and
          // both used to take the editor down on it rather than leave it to the panel to report.
          searchInput.id to
            searchInput.copy(
              slots =
                searchInput.slots - "trailingIcon" +
                  mapOf("leadingIcon" to listOf("search-leading-icon", "icon-that-was-deleted"))
            ),
        )
  )
}

private val editorChromePreviewCatalog: CapabilityCatalog by lazy {
  CapabilityCatalogParser.parse(previewResource("/jetcaster-discover-capabilities-v1.json"))
}

private fun previewResource(path: String): String =
  checkNotNull(UiBuilderDocument::class.java.getResource(path)) { "missing preview resource $path" }
    .readText()
