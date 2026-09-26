package ee.schimke.composeai.uibuilder.preview

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.DesignRevisionPin
import ee.schimke.composeai.uibuilder.editor.UiBuilderCollaborator
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorPalette
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorTheme
import ee.schimke.composeai.uibuilder.editor.UiBuilderHostChrome

/**
 * The editor in the setups its hosts actually run it in, one preview per setup.
 *
 * [UiBuilderEditorChromePreview] and its neighbours diff the editor's *panels*, and every one of
 * them draws the same setup: a 1600 dp browser window, the default dark theme, the editor's own
 * toolbar and rails, one person, the living design. Each branch below is a real host or a real
 * state that setup never reaches, so a change that broke one of them passed every render:
 *
 * - the compact layout, which a window under 840 dp gets — a phone, or a narrow browser tab;
 * - the tightest window that still keeps the wide layout, with both docks open;
 * - a host theme, which the VS Code custom editor sends as its workbench colours;
 * - a host that draws the toolbar and rails in its own chrome (the VS Code webview);
 * - collaborators, whose selections the canvas and the layers panel both mark;
 * - a link pinned to a committed revision, which makes the canvas read-only;
 * - a link naming a layer the design does not have.
 *
 * Same fixture and catalog as the chrome previews, so a diff here is about the setup and never
 * about the design. Nothing reads a clock, the network or a random source.
 */
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun UiBuilderCompactEditorPreview() {
  // Under the 840 dp breakpoint the editor draws its mobile toolbar and bottom docks instead of
  // rails, and no other preview is narrow enough to take that branch with the editor dock showing.
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The compact layout with its Properties dock open over the canvas.
 *
 * `initialInspectorOpen` is the one initial panel the compact layout honours (a `?node=` link opens
 * it on a phone), and the dock is where every inspector mode lives on a narrow screen.
 */
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun UiBuilderCompactPropertiesPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * A laptop window just wide enough to keep the wide layout, with every dock open.
 *
 * The chrome previews open their docks at 1600 dp, where the canvas keeps most of the width. At
 * 1024 dp the components, layers and inspector panels take over half of it, which is where a panel
 * that grew a few dp starts to crush the canvas or clip its own rows.
 */
@Preview(widthDp = 1024, heightDp = 768)
@Composable
fun UiBuilderLaptopWindowPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialComponentsOpen = true,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The editor in a light host theme, docks open.
 *
 * Every other preview draws [UiBuilderEditorTheme.Default], the dark scheme. A colour written as a
 * literal instead of read from the theme is invisible there and wrong here — a dark panel on a
 * light workbench, or text in the default scheme's pale primary on white.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderLightThemePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialComponentsOpen = true,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
    theme = PREVIEW_LIGHT_THEME,
  )
}

/**
 * A host that draws the toolbar and rails itself, as the VS Code webview does.
 *
 * With a [UiBuilderHostChrome] the editor draws no top toolbar and no rails and keeps the wide
 * layout at any width; what remains is the canvas, its selection bar and zoom controls, and the
 * panels the host opens. The actions it publishes go nowhere, because nothing here is pressed.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderHostChromePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
    hostChrome = UiBuilderHostChrome(onActionsChanged = {}),
  )
}

/**
 * A live session with two other people in it, each with a layer selected.
 *
 * Their selections are marked on the canvas and in the layers panel in their own colours, beside
 * the local selection. One of them shares the local selection, which is the case where the marks
 * have to stack rather than paint over each other.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCollaboratorsPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialLayersOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
    sessionLabel = "Live session",
    collaborators =
      listOf(
        UiBuilderCollaborator(
          actorId = "preview-ada",
          displayName = "Ada",
          colorArgbHex = "#FFE8710A",
          selectedNodeIds = listOf("chip-crime"),
        ),
        UiBuilderCollaborator(
          actorId = "preview-grace",
          displayName = "Grace",
          colorArgbHex = "#FF12B5CB",
          selectedNodeIds = listOf(EDITOR_CHROME_PREVIEW_SELECTION),
        ),
      ),
  )
}

/**
 * The editor opened from a `?revision=` link, pinned to a committed revision.
 *
 * A pinned revision is history, so the canvas is read-only and the banner says so and offers the
 * way back to the living design. Nothing else draws that banner or the disabled editing controls.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderPinnedRevisionPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
    initialInspectorOpen = true,
    exportHost = PREVIEW_EXPORT_HOST,
    revisionPin = DesignRevisionPin(requested = 3, pinned = true),
    onGoToLatest = {},
  )
}

/**
 * The editor opened from a link naming a layer this design does not have: a notice, not an error.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderStaleLinkNoticePreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    exportHost = PREVIEW_EXPORT_HOST,
    openingNotice = "This link names a layer, removed-card, that this design does not have.",
  )
}

/**
 * A light workbench theme as a host would send it: Material's light scheme, with the palette taken
 * from its roles the way the host bridge fills one in for a host that sends no palette.
 */
private val PREVIEW_LIGHT_THEME: UiBuilderEditorTheme =
  lightColorScheme(
      primary = Color(0xff005fb8),
      background = Color(0xffffffff),
      surface = Color(0xfff8f8f8),
    )
    .let { scheme ->
      UiBuilderEditorTheme(
        colorScheme = scheme,
        palette =
          UiBuilderEditorPalette(
            workspace = scheme.surfaceContainerLowest,
            layerSelected = scheme.primaryContainer,
            layerDragged = scheme.secondaryContainer,
            dropTarget = scheme.secondaryContainer,
            sessionBadge = scheme.secondaryContainer,
            onSessionBadge = scheme.onSecondaryContainer,
          ),
      )
    }
