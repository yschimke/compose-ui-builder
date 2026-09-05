package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * The new-design dialog, where a screen's state is declared.
 *
 * `CreateDesign` carries a whole document and no released mutation reaches `stateVariables`
 * afterwards, so this is the only moment a design can be given the variables the inspector then
 * binds properties to. `blankUiBuilderDocument` has taken them since #238 and nothing called it
 * with any.
 */
@Preview(widthDp = 900, heightDp = 900)
@Composable
fun UiBuilderNewDesignPreview() {
  UiBuilderNewDesignScreen(
    catalogs =
      listOf(
        UiBuilderNewDesignCatalog(
          systemId = "m3-catalog",
          label = "Material 3",
          templates =
            listOf(
              UiBuilderNewDesignTemplate("blank", "Blank", "A scaffold and an empty container."),
              UiBuilderNewDesignTemplate(
                "jetcaster",
                "Jetcaster",
                "The frozen discover screen, as a starting point.",
              ),
            ),
        )
      ),
    initialCatalogSystemId = "m3-catalog",
    onCreate = { _, _, _, _ -> },
  )
}

/**
 * A state-bound property, in the inspector that can now see it.
 *
 * `chip-crime.selected` is bound to `stateEquals(selectedCategory, "Crime")` in the fixture. Until
 * this change the panel drew a switch for it — a control the reducer refuses, because the value is
 * the binding — and said nothing about the binding at all. It now says what it is bound to and
 * offers the one edit that works.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderStateBindingPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "chip-crime",
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

/**
 * The Screen inspector's device menu, with the canvas on a phone frame.
 *
 * Pairs with [UiBuilderDevicePresetTabletPreview]: same document, same catalog, same inspector tab,
 * one preset apart. Read side by side they are the evidence that picking a frame moves the canvas —
 * width, height and density together — which is the thing five raw text fields never showed.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderDevicePresetPhonePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onDevice(PREVIEW_PHONE),
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Screen,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/** The same editor one preset over — see [UiBuilderDevicePresetPhonePreview]. */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderDevicePresetTabletPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onDevice(PREVIEW_TABLET),
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Screen,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * Three frames for the two previews above, and nothing else reads them.
 *
 * A preview cannot reach the server, and the server is where the real list comes from — it resolves
 * `DeviceDimensions`, the JVM-only catalog the render lane uses, precisely because `:ui-builder`
 * cannot. So these are fixture values, in the same category as the ones the tests state, and they
 * are deliberately three rather than a curated catalog: a fourth entry here would start to look
 * like the hard-coded device list this feature exists to not have.
 */
private val PREVIEW_PHONE =
  UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
private val PREVIEW_TABLET =
  UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)
private val PREVIEW_DEVICE_PRESETS =
  listOf(
    PREVIEW_PHONE,
    UiBuilderDevicePreset("id:pixel_fold", "Pixel Fold", "Foldables", 841, 701, 2.625),
    PREVIEW_TABLET,
  )

private fun UiBuilderDocument.onDevice(preset: UiBuilderDevicePreset): UiBuilderDocument =
  copy(
    environment =
      JsonObject(
        environment +
          mapOf(
            "widthDp" to JsonPrimitive(preset.widthDp),
            "heightDp" to JsonPrimitive(preset.heightDp),
            "density" to JsonPrimitive(preset.density),
          )
      )
  )

/**
 * The code pane open on a design that exports.
 *
 * The builder's proposition is that a design *is* code, and until this pane the only way to read
 * the code a design produced was to run an export and open the artifact. What it draws is the real
 * `ScreenGenerator` output for the document beside it — same generator, same component record and
 * same allow-list the server's export runs — so this render is also the evidence that the two
 * builders now share one emitter.
 *
 * A small authored document rather than the Jetcaster fixture, because the fixture does not export
 * and this preview is the case where there is Kotlin to show. [UiBuilderCodePaneRefusedPreview] is
 * the other half.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCodePanePreview() {
  UiBuilderEditor(
    document = editorCodePanePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "code-pane-heading",
    initialCodePaneVisible = true,
  )
}

/**
 * The code pane on a design the export refuses.
 *
 * Pairs with [UiBuilderCodePanePreview]. The reasons appear where the source would, rather than the
 * pane going blank and leaving them behind the Issues tab — a designer looking at the code pane on
 * a design that cannot generate is looking at exactly the question the refusals answer.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCodePaneRefusedPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialCodePaneVisible = true,
  )
}

/**
 * A scaffold, a column, a heading and a rule — every id backed by the component record.
 *
 * Authored here rather than replayed, because the point is a document the generator can express:
 * the fixture reaches for an adaptive grid, an enum value with no Kotlin member and a state read,
 * and refuses on all three.
 */
private val editorCodePanePreviewDocument: UiBuilderDocument by lazy {
  val blank =
    blankUiBuilderDocument(
      designId = "code-pane-preview",
      catalogPin = editorChromePreviewDocument.catalogPin,
      environment = editorChromePreviewDocument.environment,
    )
  blank.copy(
    title = "Discover header",
    nodes =
      blank.nodes +
        mapOf(
          "screen-content" to
            blank.nodes
              .getValue("screen-content")
              .copy(slots = mapOf("children" to listOf("code-pane-column"))),
          "code-pane-column" to
            UiBuilderNode(
              id = "code-pane-column",
              componentId = "layout/column",
              slots =
                mapOf(
                  "children" to listOf("code-pane-heading", "code-pane-rule", "code-pane-card")
                ),
            ),
          "code-pane-card" to
            UiBuilderNode(
              id = "code-pane-card",
              componentId = "m3/card",
              slots = mapOf("content" to listOf("code-pane-card-text")),
            ),
          "code-pane-card-text" to
            UiBuilderNode(
              id = "code-pane-card-text",
              componentId = "m3/text",
              properties =
                JsonObject(
                  mapOf(
                    "text" to previewStringValue("Latest episodes"),
                    "style" to previewTypedValue("typographyToken", "titleMedium"),
                  )
                ),
            ),
          "code-pane-heading" to
            UiBuilderNode(
              id = "code-pane-heading",
              componentId = "m3/text",
              properties =
                JsonObject(
                  mapOf(
                    "text" to previewStringValue("Discover"),
                    "style" to previewTypedValue("typographyToken", "headlineSmall"),
                  )
                ),
            ),
          "code-pane-rule" to
            UiBuilderNode(id = "code-pane-rule", componentId = "m3/horizontal-divider"),
        ),
  )
}

private fun previewStringValue(value: String): JsonObject = previewTypedValue("string", value)

private fun previewTypedValue(type: String, value: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

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
