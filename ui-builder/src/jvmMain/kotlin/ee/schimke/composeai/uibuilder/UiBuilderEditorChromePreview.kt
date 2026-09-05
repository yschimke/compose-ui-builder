package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
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
    // Both docks open, which is not how the editor starts: this preview exists to diff the panels,
    // and [UiBuilderCanvasForwardPreview] is the one that diffs the default.
    initialComponentsOpen = true,
    initialInspectorOpen = true,
  )
}

/**
 * The editor as it opens: both docks closed, the canvas with the whole window.
 *
 * The default this repository now ships, and therefore the one that has to be diffed. Every other
 * chrome preview asks for a panel, so without this one a change that broke the empty state would
 * pass every render.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCanvasForwardPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
  )
}

/**
 * The same chrome with the Remote Compose palette populated.
 *
 * A second preview rather than sources on the first, because the first is the answer for every
 * catalog that publishes no Remote Compose documents — which is most of them — and this one is the
 * answer for `remote-m3`. Both have to keep working, so both are diffed.
 *
 * The sources are literals rather than a catalog fetch: a preview may not read the network, and
 * what this surface has to show is the panel's grouping, its headings and its Add affordance, none
 * of which depends on the bytes behind a row. The resolver is present because the panel is hidden
 * without one, and never called because nothing here presses Add.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderRemoteComposePalettePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    // A container rather than [EDITOR_CHROME_PREVIEW_SELECTION]'s text leaf: a row's Add is enabled
    // off the same question the insert asks, so a leaf selection would diff a palette whose every
    // row is greyed and never show the affordance working.
    initialSelectedNodeId = "discover-grid",
    initialComponentsOpen = true,
    remoteComposeSources = REMOTE_COMPOSE_PALETTE_PREVIEW_SOURCES,
    resolveRemoteComposeDocument = { error("a preview never adds") },
  )
}

/**
 * Two families and three states, so the palette's group headings are visible rather than implied.
 */
private val REMOTE_COMPOSE_PALETTE_PREVIEW_SOURCES =
  listOf(
    RemoteComposeSource("appcard__ideal__default__compact", "App card", "appcard"),
    RemoteComposeSource("appcard__ideal__icon__compact", "App card with icon", "appcard"),
    RemoteComposeSource("button-filled__ideal__default__compact", "Filled button", "button-filled"),
    RemoteComposeSource(
      "button-filled__ideal__disabled__compact",
      "Filled button, disabled",
      "button-filled",
    ),
  )

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
    initialInspectorOpen = true,
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
    initialInspectorOpen = true,
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
    initialInspectorOpen = true,
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
    initialLayersOpen = true,
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
    initialInspectorOpen = true,
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
    initialInspectorOpen = true,
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

/**
 * The native render pane, beside the canvas that the browser drew.
 *
 * Two renderers at once is the point: the Wasm canvas is immediate and cannot say what a screen
 * looks like on Android, and one pane replacing the other would hide the difference that matters.
 *
 * What it shows here is the **refusal** state, and that is not a placeholder — it is what this
 * fixture actually produces. The reasons are read from the same reducer the problems panel and the
 * code pane read, so this render cannot drift from them. A frame-bearing state is deliberately not
 * previewed: a preview cannot compile Kotlin, and standing a real render in for one the host would
 * have produced would be a picture that claims something untrue.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderNativeRenderPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeRenderPreviewRefusal,
    initialPreviewSurface = EditorPreviewSurface.Native,
    onRequestNativeRender = { nativeRenderPreviewRefusal },
  )
}

/**
 * The comparison case: both renderers at once.
 *
 * Rendered as its own preview rather than folded into the one above, because they are the two
 * shapes this control produces and a diff of either alone would not show the other moving. This is
 * the state a Wasm project reaches for occasionally; [UiBuilderNativeRenderPreview] is the state a
 * project with no browser renderer lives in.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderRenderComparisonPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeRenderPreviewRefusal,
    initialPreviewSurface = EditorPreviewSurface.Both,
    onRequestNativeRender = { nativeRenderPreviewRefusal },
  )
}

/**
 * The overlay the native pane draws over a frame: the selected node outlined, in the frame's own
 * pixels, scaled by the one factor that fits the frame into the pane.
 *
 * ## What the frame under it is, and is not
 *
 * It is a **geometry fixture**, not a render: flat blocks at the boxes the host would have reported
 * for this design, drawn here so the overlay has something to sit on.
 * [UiBuilderNativeRenderPreview] explains why no preview stands a real frame in for one the host
 * would have produced, and that still holds — nothing here claims to be what Compose drew. What
 * this preview is for is the half that is this repository's own code: whether the outline lands on
 * the box the host reported, at the right scale, for the node the editor says is selected. A
 * regression in the transform moves the outline off its block, which is exactly what a diff of this
 * render shows.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderNativeOverlayPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeOverlayPreviewRender,
    initialPreviewSurface = EditorPreviewSurface.Native,
    onRequestNativeRender = { nativeOverlayPreviewRender },
  )
}

/**
 * The geometry fixture behind [UiBuilderNativeOverlayPreview]: boxes for a few of the fixture's own
 * nodes, and a frame with a flat block drawn at each of them.
 *
 * Deliberately blocks rather than anything that resembles a screen. A frame that looked like a
 * render would read as one, and this is a coordinate-space fixture — the numbers are the content.
 */
private val nativeOverlayPreviewRender: UiBuilderNativeRender by lazy {
  val bounds =
    mapOf(
      "discover-grid" to UiBuilderNativeNodeBounds(x = 32, y = 240, width = 736, height = 1120),
      "search-bar" to UiBuilderNativeNodeBounds(x = 32, y = 96, width = 736, height = 112),
      EDITOR_CHROME_PREVIEW_SELECTION to
        UiBuilderNativeNodeBounds(x = 64, y = 128, width = 400, height = 48),
    )
  UiBuilderNativeRender(image = overlayFixtureFrame(800, 1600, bounds.values), nodeBounds = bounds)
}

/** A [width]x[height] ground with one flat block per box — see [nativeOverlayPreviewRender]. */
private fun overlayFixtureFrame(
  width: Int,
  height: Int,
  boxes: Collection<UiBuilderNativeNodeBounds>,
): ImageBitmap {
  val bitmap = ImageBitmap(width, height)
  val canvas = Canvas(bitmap)
  canvas.drawRect(
    Rect(0f, 0f, width.toFloat(), height.toFloat()),
    Paint().apply { color = Color(0xff16181d) },
  )
  val block = Paint().apply { color = Color(0xff2b3040) }
  boxes.forEach {
    canvas.drawRect(
      Rect(
        it.x.toFloat(),
        it.y.toFloat(),
        (it.x + it.width).toFloat(),
        (it.y + it.height).toFloat(),
      ),
      block,
    )
  }
  return bitmap
}

/** The fixture's own refusals, taken from the reducer rather than transcribed. */
private val nativeRenderPreviewRefusal: UiBuilderNativeRender by lazy {
  val code =
    UiBuilderEditorReducer(editorChromePreviewCatalog).generatedCode(editorChromePreviewDocument)
  UiBuilderNativeRender(refusals = (code as? EditorGeneratedCode.Refused)?.reasons ?: emptyList())
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

/** Shared with the reference previews next door, so both judge a design by the same catalog. */
internal val editorChromePreviewCatalog: CapabilityCatalog by lazy {
  CapabilityCatalogParser.parse(previewResource("/m3-catalog-capabilities-v1.json"))
}

/** Shared with the reference previews next door, which read the same frozen fixtures. */
internal fun previewResource(path: String): String =
  checkNotNull(UiBuilderDocument::class.java.getResource(path)) { "missing preview resource $path" }
    .readText()
