package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.RemoteComposeSource
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorProblem
import ee.schimke.composeai.uibuilder.editor.EditorVariantAxis
import ee.schimke.composeai.uibuilder.editor.ProblemsInspector
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeFrame
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeInput
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeLive
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeNodeBounds
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeStream
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignScreen
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.problemHeading
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.withTemplatePreviews
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import ee.schimke.composeai.uibuilder.inspector.EventActionsInspector
import ee.schimke.composeai.uibuilder.inspector.LocalUiBuilderPageDestinations
import ee.schimke.composeai.uibuilder.inspector.UiBuilderPageDestination
import ee.schimke.composeai.uibuilder.parseRemoteComposeSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The editor's own chrome (toolbar, layers, canvas and inspector) rendered as a preview, so changes
 * to it are diffed like any other surface.
 *
 * Fully deterministic: the frozen Jetcaster fixture at a known revision, its own catalog pin, the
 * default local actor and session label, no collaborators, and a pinned selection.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderEditorChromePreview() {
  UiBuilderEditor(
    visibilityLabel = "Private · invited collaborators only",
    onManageVisibility = {},
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    // Both docks open, which is not how the editor starts: this preview exists to diff the panels,
    // and [UiBuilderCanvasForwardPreview] is the one that diffs the default.
    initialComponentsOpen = true,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The editor as it opens: both docks closed, the canvas with the whole window. Covers the default
 * empty state, which every other chrome preview opens away from.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCanvasForwardPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    // The toolbar as a served catalog shows it: every production catalog renders PNG, so the
    // Export button is part of the default chrome rather than an exception to it.
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The same chrome with the Remote Compose palette populated (the `remote-m3` case). Sources are
 * literals since previews can't read the network; the resolver is present only so the panel shows.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderRemoteComposePalettePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    // A container rather than a text leaf, so the rows' Add is enabled.
    initialSelectedNodeId = "discover-grid",
    initialComponentsOpen = true,
    // Narrow the large production sheet so rows below the authoring catalog are visible; search
    // still matches their ids.
    initialCatalogQuery = "compact",
    remoteComposeSources = REMOTE_COMPOSE_PALETTE_PREVIEW_SOURCES,
    resolveRemoteComposeDocument = { error("a preview never adds") },
    // Stands in for the serving catalog's PNG route, since previews can't read the network.
    resolveRemoteComposeThumbnail = ::remoteComposePaletteThumbnail,
  )
}

/**
 * The component menu narrowed to a variant name, with everything it matched unfolded, so the
 * **variant** row shape is diffed. "filled" matches no display name, so the components shown are
 * there because of their variants.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderComponentVariantsPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    // A container, for the same reason [UiBuilderRemoteComposePalettePreview] picks one: a leaf
    // selection greys every Add in the panel and the affordance never shows.
    initialSelectedNodeId = "discover-grid",
    initialComponentsOpen = true,
    initialCatalogQuery = "filled",
  )
}

/**
 * The palette narrowed to the components the Compose export cannot write, to diff the "no Kotlin"
 * marker on each row shape (including a variant row). "picker" matches only the two pickers, which
 * are unlikely to become exportable.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderUnexportablePalettePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialComponentsOpen = true,
    initialCatalogQuery = "picker",
  )
}

/**
 * Two families and three states, so the palette's group headings are visible rather than implied.
 */
private val REMOTE_COMPOSE_PALETTE_PREVIEW_SOURCES =
  parseRemoteComposeSources(
    """
    {"previews": [
      {"id":"appcard__ideal__default__compact","label":"appcard__ideal__default__compact","remoteCompose":true},
      {"id":"appcard__ideal__default__large","label":"appcard__ideal__default__large","remoteCompose":true},
      {"id":"appcard__ideal__icon__compact","label":"appcard__ideal__icon__compact","remoteCompose":true},
      {"id":"appcard__ideal__icon__large","label":"appcard__ideal__icon__large","remoteCompose":true},
      {"id":"button-filled__ideal__default__compact","label":"button-filled__ideal__default__compact","remoteCompose":true},
      {"id":"button-filled__ideal__default__large","label":"button-filled__ideal__default__large","remoteCompose":true},
      {"id":"button-filled__ideal__disabled__compact","label":"button-filled__ideal__disabled__compact","remoteCompose":true},
      {"id":"button-filled__ideal__disabled__large","label":"button-filled__ideal__disabled__large","remoteCompose":true}
    ]}
    """
      .trimIndent()
  )

/** Deterministic stand-ins for the four catalog PNGs used by the chrome preview above. */
private fun remoteComposePaletteThumbnail(source: RemoteComposeSource): ImageBitmap {
  val bitmap = ImageBitmap(160, 120)
  val canvas = Canvas(bitmap)
  canvas.drawRect(
    Rect(0f, 0f, 160f, 120f),
    Paint().apply { color = Color(0xffe6e1e9) },
  )
  val component = Paint().apply { color = Color(0xff6750a4) }
  if (source.group == "appcard") {
    canvas.drawRect(Rect(18f, 20f, 142f, 100f), component)
    val detail = Paint().apply { color = Color(0xffeaddff) }
    canvas.drawRect(Rect(34f, 42f, 110f, 50f), detail)
    canvas.drawRect(Rect(34f, 62f, 126f, 70f), detail)
  } else {
    canvas.drawRect(Rect(24f, 42f, 136f, 82f), component)
  }
  return bitmap
}

/**
 * A node deep enough in the tree to exercise the layers panel's indentation and naming, visible in
 * both the layers panel and the rendered canvas.
 */
internal const val EDITOR_CHROME_PREVIEW_SELECTION = "search-placeholder"

/**
 * The inspector with a layout container selected: `discover-grid` is a `layout/lazy-grid` declaring
 * spacing, an adaptive column rule and content padding.
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

internal val editorChromePreviewDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/jetcaster-discover-operations-v1.json")).jsonObject
    )
    .document
}

/**
 * The issues panel with something to say. Problems are seeded (the fixture is clean): a missing
 * required property, an unreachable node, and a stale catalog property with Drop / Map recovery.
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

/** A degraded catalog property and the two explicit recovery paths it offers. */
@Preview(widthDp = 520, heightDp = 320)
@Composable
fun UiBuilderDegradedDesignPreview() {
  MaterialTheme {
    Surface {
      Column(Modifier.padding(24.dp)) {
        Text("Issues", style = MaterialTheme.typography.titleMedium)
        ProblemsInspector(
          problems =
            listOf(
              EditorProblem(
                code = "PROPERTY_NOT_DECLARED",
                message = "property oldFontStyle is not declared by m3/text in the pinned catalog",
                nodeId = "headline",
                componentId = "m3/text",
                blocking = false,
                propertyName = "oldFontStyle",
                replacementProperties = listOf("fontStyle", "fontWeight"),
              )
            ),
          dispatch = {},
        )
      }
    }
  }
}

/** Thirty raw findings reduced to five structural causes and five downstream groups. */
@Preview(widthDp = 520, heightDp = 760)
@Composable
fun UiBuilderLargeIssuesTriagePreview() {
  val problems =
    (1..5).flatMap { index ->
      listOf(
        EditorProblem(
          code = "SLOT_CARDINALITY",
          message = "content accepts one child but contains two on card-$index",
          nodeId = "card-$index",
          componentId = "m3/card",
        )
      ) +
        (1..5).map { occurrence ->
          EditorProblem(
            code = "COMPOSE_EXPORT_REFUSED",
            message = "Emitter failure $occurrence caused by card-$index's invalid content slot",
            nodeId = "card-$index",
            componentId = "m3/card",
          )
        }
    }
  MaterialTheme {
    Surface {
      Column(Modifier.padding(24.dp)) {
        Text(problemHeading(problems), style = MaterialTheme.typography.titleMedium)
        ProblemsInspector(problems, dispatch = {})
      }
    }
  }
}

/**
 * The new-design dialog, where a screen's state is declared. `CreateDesign` is the only point a
 * design can get `stateVariables`, which the inspector then binds properties to.
 */
@Preview(widthDp = 900, heightDp = 900)
@Composable
fun UiBuilderNewDesignPreview() {
  UiBuilderNewDesignScreen(
    // Fixed, not random: the app rolls a new name each time, and so would every render of this.
    initialDesignId = "sunny-otter",
    catalogs =
      listOf(
          UiBuilderNewDesignCatalog(
            systemId = "m3-catalog",
            label = "Mobile app",
            templates =
              listOf(
                UiBuilderNewDesignTemplate(
                  "blank",
                  "Blank screen",
                  "A scaffold and an empty container.",
                ),
                UiBuilderNewDesignTemplate(
                  "hello",
                  "Hello sample",
                  "A headline and a line of text to edit.",
                ),
              ),
          ),
          UiBuilderNewDesignCatalog(
            systemId = "wear-m3",
            label = "Wear app",
            templates =
              listOf(
                UiBuilderNewDesignTemplate(
                  "wear-screen",
                  "Blank screen",
                  "A ScreenScaffold with its clock and scroll indicator, over an empty list.",
                )
              ),
          ),
          UiBuilderNewDesignCatalog(
            systemId = "remote-m3",
            label = "Wear widget",
            templates =
              listOf(
                UiBuilderNewDesignTemplate(
                  "wear-widget-small",
                  "Blank small widget",
                  "216×76dp host with a single content slot.",
                )
              ),
          ),
        )
        .map { choice ->
          choice.withTemplatePreviews(
            CapabilityCatalogParser.parse(
              UiBuilderDocument::class
                .java
                .getResource("/${choice.systemId}-capabilities-v1.json")!!
                .readText()
            ),
            Json.parseToJsonElement(
                UiBuilderDocument::class
                  .java
                  .getResource("/jetcaster-discover-operations-v1.json")!!
                  .readText()
              )
              .jsonObject,
          )
        },
    initialCatalogSystemId = "m3-catalog",
    onCreatePublic = { _, _, _, _ -> },
    onCreate = { _, _, _, _ -> },
  )
}

/**
 * A state-bound property in the inspector: `chip-crime.selected` is bound to
 * `stateEquals(selectedCategory, "Crime")`, and the panel shows the binding and its one valid edit.
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

/** The action editor offering another design in the same project as a named destination. */
@Preview(widthDp = 520, heightDp = 900)
@Composable
fun UiBuilderNavigationActionPreview() {
  val document = editorChromePreviewDocument
  val node =
    document.nodes
      .getValue("toolbar-library")
      .copy(
        eventBindings =
          buildJsonObject {
            put(
              "click",
              JsonArray(
                listOf(
                  buildJsonObject {
                    put("type", "navigatePage")
                    put("pageKey", "confirmation")
                  }
                )
              ),
            )
          }
      )
  MaterialTheme {
    Surface {
      CompositionLocalProvider(
        LocalUiBuilderPageDestinations provides
          listOf(
            UiBuilderPageDestination("checkout", "Checkout"),
            UiBuilderPageDestination("confirmation", "Confirmation"),
          )
      ) {
        Column(Modifier.padding(24.dp)) {
          Text("Button · ${node.componentId}", style = MaterialTheme.typography.titleMedium)
          EventActionsInspector(document, node, {}, {})
        }
      }
    }
  }
}

/**
 * The workspace with the authoring canvas switched off: the preview pane on its own. Taps reach
 * components and state writes run; the panels stay live.
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
    initialPanes = setOf(EditorPane.Preview),
  )
}

/**
 * The preview pane: the design as drawn beside the design being edited, with its frame plus every
 * device and axis at one shared scale. Costs the host nothing.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderEditorAndPreviewPanesPreview() {
  UiBuilderEditor(
    // A tablet with the Dark axis, so the pane shows the design's frame, its device and an
    // override, each labelled.
    document =
      editorChromePreviewDocument.onDevice(PREVIEW_PHONE).claiming(PREVIEW_TABLET).inLightTheme(),
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
    initialVariantAxes = setOf(EditorVariantAxis.Dark),
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * The layers panel filtered: `m3/filter-chip` narrows the tree to four chips plus their ancestors,
 * and the panel offers to select all four (the multi-node inspector).
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
 * The Screen inspector's device menu, with the canvas on a phone frame. Pairs with
 * [UiBuilderDevicePresetTabletPreview] to show that picking a frame moves the canvas.
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

/** IntelliJ's main editor-area view: the editable canvas without an embedded Preview pane. */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderEditorAreaPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onDevice(PREVIEW_PHONE),
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialPanes = setOf(EditorPane.Editor),
    availablePanes = setOf(EditorPane.Editor),
    openDefaultPreview = false,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/** IntelliJ's separate Preview tool-window view, sharing the editor's persisted session. */
@Preview(widthDp = 720, heightDp = 900)
@Composable
fun UiBuilderSeparatePreviewViewPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onDevice(PREVIEW_PHONE),
    catalog = editorChromePreviewCatalog,
    initialPanes = setOf(EditorPane.Preview),
    availablePanes = setOf(EditorPane.Preview),
    openDefaultPreview = false,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * Three fixture frames for the two previews above. The real list comes from the server (JVM-only
 * `DeviceDimensions`); kept to three so it doesn't become a hard-coded device list.
 */
private val PREVIEW_PHONE =
  UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
private val PREVIEW_TABLET =
  UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)
private val PREVIEW_FOLD =
  UiBuilderDevicePreset("id:pixel_fold", "Pixel Fold", "Foldables", 841, 701, 2.625)
private val PREVIEW_DEVICE_PRESETS = listOf(PREVIEW_PHONE, PREVIEW_FOLD, PREVIEW_TABLET)

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
 * The code pane open on a design that exports, showing the real `ScreenGenerator` output (same
 * generator, component record and allow-list as the server). A small authored document since the
 * Jetcaster fixture doesn't export; [UiBuilderCodePaneRefusedPreview] is the other half.
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
 * The code pane on a design the export refuses: the reasons appear where the source would. Pairs
 * with [UiBuilderCodePanePreview].
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
 * A scaffold, a column, a heading and a rule, every id backed by the component record, so the
 * generator can express it.
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
 * The native render pane, beside the browser-drawn canvas, in its **refusal** state (what this
 * fixture produces). Reasons come from the same reducer as the problems panel and code pane. A
 * frame-bearing state isn't previewed because a preview can't compile Kotlin.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderNativeRenderPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeRenderPreviewRefusal,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Native),
    onRequestNativeRender = { _ -> nativeRenderPreviewRefusal },
  )
}

/**
 * The comparison case: all three panes at once. [UiBuilderNativeRenderPreview] is the native-only
 * state.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderRenderComparisonPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeRenderPreviewRefusal,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Preview, EditorPane.Native),
    onRequestNativeRender = { _ -> nativeRenderPreviewRefusal },
  )
}

/**
 * The overlay the native pane draws over a frame: the selected node outlined in the frame's pixels,
 * scaled to fit the pane.
 *
 * The frame under it is a **geometry fixture**, not a render: flat blocks at the boxes the host
 * would report. What is diffed is the transform: whether the outline lands on the reported box at
 * the right scale.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderNativeOverlayPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialNativeRender = nativeOverlayPreviewRender,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Native),
    onRequestNativeRender = { _ -> nativeOverlayPreviewRender },
  )
}

/**
 * The native pane streaming, on a catalog with an Android lane. The caption (live, taps reach it)
 * is the content; the frame is the same geometry fixture as [UiBuilderNativeOverlayPreview].
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderNativeLivePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    // The Android lane, so the caption names the native toolkit as a `remote-m3`/`wear-m3` design
    // would see it.
    catalog = androidNativePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Native),
    initialNativeRender = nativeLivePreviewRender,
    onRequestNativeRender = { _ -> nativeLivePreviewRender },
    onOpenNativeStream = { nativeLivePreviewStream },
  )
}

/**
 * The chrome fixture's catalog with `previewSurfaces.native.backend` set to Android, patched in
 * rather than duplicating the catalog. Same shape `UiBuilderPreviewSurfaces.from` reads.
 */
private val androidNativePreviewCatalog: CapabilityCatalog by lazy {
  val source = Json.parseToJsonElement(previewResource("/m3-catalog-capabilities-v1.json"))
  val declared =
    JsonObject(
      source.jsonObject +
        ("statusSemantics" to
          JsonObject(
            source.jsonObject["statusSemantics"]?.jsonObject.orEmpty() +
              (UiBuilderPreviewSurfaces.KEY to
                buildJsonObject {
                  putJsonObject("native") {
                    put("fidelity", JsonPrimitive("authoritative"))
                    put("backend", JsonPrimitive(UiBuilderPreviewSurfaces.BACKEND_ANDROID))
                  }
                })
          ))
    )
  CapabilityCatalogParser.parse(declared.toString())
}

/** A render that names a session and carries no still: what a live host answers with. */
private val nativeLivePreviewRender =
  UiBuilderNativeRender(live = UiBuilderNativeLive(sessionId = "session", previewId = "generated"))

/** A stream that has already painted. Inputs are dropped. */
private val nativeLivePreviewStream =
  object : UiBuilderNativeStream {
    override val frame =
      UiBuilderNativeFrame(
        image =
          overlayFixtureFrame(
            800,
            1600,
            listOf(
              UiBuilderNativeNodeBounds(x = 32, y = 96, width = 736, height = 112),
              UiBuilderNativeNodeBounds(x = 32, y = 240, width = 736, height = 1120),
            ),
          )
      )

    override val failure: String? = null

    override fun send(input: UiBuilderNativeInput) = Unit

    override fun close() = Unit
  }

/**
 * The geometry fixture behind [UiBuilderNativeOverlayPreview]: boxes for a few fixture nodes and a
 * frame with a flat block at each. Deliberately not screen-like; the numbers are the content.
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
          // `m3/text` requires `text`, so this node won't export. `oldFontStyle` models a catalog
          // rename the Issues panel offers to recover.
          placeholder.id to
            placeholder.copy(
              properties =
                JsonObject(
                  placeholder.properties - "text" +
                    ("oldFontStyle" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "value" to JsonPrimitive("normal"),
                        )
                      ))
                )
            ),
          // Leaves the icon orphaned in `nodes` and the slot pointing at a missing node, as a
          // botched delete would; both must be reported, not crash.
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

/**
 * The History dock over a session with [seeded edits][historyPreviewEdits]: each entry named from
 * its operation, before/after values on the row, the undo target marked, and the undone entry
 * marked as redo's. Ends with an undo so both markers show.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderHistoryDockPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorOpen = true,
    initialInspectorMode = EditorInspectorMode.History,
    initialEdits = historyPreviewEdits,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The history strip under the canvas, over the same session: each revision has its own picture, the
 * newest is marked as on the canvas, and the undo's row sits beside the change it took back.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderHistoryBarPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorOpen = true,
    initialInspectorMode = EditorInspectorMode.History,
    initialHistoryBarVisible = true,
    initialEdits = historyPreviewEdits,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * Two revisions of one design, compared side by side with the list of changes between them; the
 * editing canvas is not composed. Pinned to revisions 1 and 3 so the subject is stable.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderRevisionComparePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialHistoryBarVisible = true,
    initialEdits = historyPreviewEdits,
    initialRevisionPeek = editorChromePreviewDocument.revision + 1,
    initialRevisionCompare = editorChromePreviewDocument.revision + 3,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * Three changes and their undo, each a different kind of entry: a modifier chain (was empty), a
 * property (had a value), and a screen field (no node). Applied through the real reducer.
 */
private val historyPreviewEdits: List<UiBuilderEditorEvent> =
  listOf(
    UiBuilderEditorEvent.ToggleModifier(EDITOR_CHROME_PREVIEW_SELECTION, "padding"),
    UiBuilderEditorEvent.CommitProperty(
      EDITOR_CHROME_PREVIEW_SELECTION,
      "text",
      "Search podcasts",
    ),
    UiBuilderEditorEvent.UpdateEnvironment(
      editorChromePreviewDocument.screenEnvironmentSettings().copy(density = 2.0)
    ),
    UiBuilderEditorEvent.Undo,
  )

/**
 * A design of several top-level items, before it has any: the seed the pair below starts from. Add
 * beside is already on so the pair differs only by two Adds.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderBoardBeforePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onCanvasFrame(),
    catalog = editorChromePreviewCatalog,
    initialAddBeside = true,
    initialComponentsOpen = true,
    initialInspectorOpen = true,
    initialInspectorMode = EditorInspectorMode.Screen,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * The same design after two Adds beside: a board of three items, one tree, one root. The canvas
 * draws an ordinary `layout/column`, the layers panel lists it as a node, the insert panel says
 * where the next Add lands, and the Frame inspector claims no device.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderBoardPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument.onCanvasFrame(),
    catalog = editorChromePreviewCatalog,
    initialEdits = boardPreviewEdits,
    initialAddBeside = true,
    initialComponentsOpen = true,
    initialInspectorOpen = true,
    initialInspectorMode = EditorInspectorMode.Screen,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * The variant strip in the preview pane: one document drawn beside itself. The design claims a
 * tablet (`exportDevices`) with the Dark axis on; the authoring canvas still holds **one** frame.
 * The before is [UiBuilderCanvasForwardPreview].
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderVariantStripPreview() {
  UiBuilderEditor(
    // Light, because the fixture is already dark and a Dark override over it would prove nothing.
    document =
      editorChromePreviewDocument.onDevice(PREVIEW_PHONE).claiming(PREVIEW_TABLET).inLightTheme(),
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
    initialVariantAxes = setOf(EditorVariantAxis.Dark),
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * The real `SupportingPaneScaffold`, deciding. The Jetcaster root is
 * `layout/supporting-pane-scaffold` with `expandedTwoPane`, authored at the **Pixel Fold** and
 * claiming the Pixel 7; the library computes the size class per frame, so the fold keeps its
 * supporting pane and the phone does not.
 *
 * The fold (841dp) is chosen because it is where the library's expanded threshold differs from a
 * naive sum of widths. The editor pane is off to give both frames room; the canvas behaviour is
 * pinned in `AdaptiveSupportingPaneTest` instead.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderAdaptivePaneVariantsPreview() {
  UiBuilderEditor(
    document =
      editorChromePreviewDocument.onDevice(PREVIEW_FOLD).claiming(PREVIEW_PHONE).inLightTheme(),
    catalog = editorChromePreviewCatalog,
    initialPanes = setOf(EditorPane.Preview),
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/**
 * The wear catalog on a board, filtered to the one component it must refuse.
 * `wear-m3/screen-scaffold` is in [RecordFreeExport.ROOT_ONLY_COMPONENT_IDS] (its emitter routes on
 * the root), so its Add is disabled; read the scaffold row's second line for the reason.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderBesideRefusalPreview() {
  UiBuilderEditor(
    document = wearBoardDocument,
    catalog = editorChromePreviewWearCatalog,
    initialCatalogQuery = refusalPreviewCatalogQuery,
    initialAddBeside = true,
    initialComponentsOpen = true,
    devicePresets = PREVIEW_DEVICE_PRESETS,
  )
}

/** The catalog filter both refusal previews use, so the pair differs only by the panel's code. */
private const val refusalPreviewCatalogQuery: String = "scaffold"

/** The wear catalog, for the one preview that needs a component a board cannot hold. */
internal val editorChromePreviewWearCatalog: CapabilityCatalog by lazy {
  CapabilityCatalogParser.parse(previewResource("/wear-m3-capabilities-v1.json"))
}

/**
 * A wear design that is already a board: a root `layout/column` holding two items, so the only
 * refusal left is the component's (`UiBuilderDocument.boardRootId`).
 */
private val wearBoardDocument: UiBuilderDocument by lazy {
  val items = mapOf("board-item-1" to "Now playing", "board-item-2" to "Up next")
  UiBuilderDocument(
    schema = "ui-builder/v1",
    id = "wear-board",
    title = "Wear board",
    revision = 1,
    catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("wear-m3"))),
    environment =
      JsonObject(
        mapOf(
          "widthDp" to JsonPrimitive(900),
          "heightDp" to JsonPrimitive(1400),
          "density" to JsonPrimitive(2.0),
          "theme" to JsonPrimitive("dark"),
        )
      ),
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("board"),
    nodes =
      buildMap {
        put(
          "board",
          UiBuilderBoard.node("board")
            .copy(slots = mapOf(UiBuilderBoard.SLOT to items.keys.toList())),
        )
        items.forEach { (id, label) ->
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = "wear-m3/text",
              properties =
                JsonObject(
                  mapOf(
                    "text" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "value" to JsonPrimitive(label),
                        )
                      )
                  )
                ),
            ),
          )
        }
      },
  )
}

/** The two Adds [UiBuilderBoardPreview] is a picture of, applied through the ordinary reducer. */
private val boardPreviewEdits: List<UiBuilderEditorEvent> =
  listOf(
    UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
    UiBuilderEditorEvent.InsertComponentBeside("m3/text"),
  )

/** A frame wide and long enough to hold several items; deliberately not a device preset. */
private fun UiBuilderDocument.onCanvasFrame(): UiBuilderDocument =
  copy(
    environment =
      JsonObject(
        environment +
          mapOf(
            "widthDp" to JsonPrimitive(900),
            "heightDp" to JsonPrimitive(1400),
            "density" to JsonPrimitive(2.0),
          )
      )
  )

/** This design under the light scheme, so a Dark variant beside it is visibly a variant. */
private fun UiBuilderDocument.inLightTheme(): UiBuilderDocument =
  copy(environment = JsonObject(environment + mapOf("theme" to JsonPrimitive("light"))))

/** This design claiming it also works on [preset] — the stored set the variant strip draws. */
private fun UiBuilderDocument.claiming(preset: UiBuilderDevicePreset): UiBuilderDocument =
  copy(
    environment =
      JsonObject(
        environment + mapOf("exportDevices" to JsonArray(listOf(JsonPrimitive(preset.id))))
      )
  )
