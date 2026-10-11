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
 * The editor in the setups its hosts actually run it in, one preview per setup (the chrome previews
 * all use one 1600dp dark browser setup):
 *
 * - the compact layout (window under 840dp);
 * - the tightest window that keeps the wide layout, with both docks open;
 * - a host theme, as the VS Code custom editor sends its workbench colours;
 * - a host that draws the toolbar and rails itself (the VS Code webview);
 * - collaborators, whose selections the canvas and layers panel mark;
 * - a link pinned to a committed revision (read-only canvas);
 * - a link naming a layer the design does not have.
 *
 * Same fixture and catalog as the chrome previews; nothing reads a clock, the network or a random
 * source.
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
 * The compact layout with its Properties dock open over the canvas (`initialInspectorOpen`, as a
 * `?node=` link opens it on a phone).
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
 * A laptop window just wide enough to keep the wide layout, with every dock open, where panels take
 * over half the width.
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
 * The editor in a light host theme, docks open, to catch colours written as literals instead of
 * read from the theme.
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
 * A host that draws the toolbar and rails itself, as the VS Code webview does: with a
 * [UiBuilderHostChrome] the editor keeps the wide layout and draws only the canvas, selection bar,
 * zoom controls and host-opened panels.
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
 * A live session with two other people, each with a layer selected; one shares the local selection,
 * so marks must stack.
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
 * The editor opened from a `?revision=` link: read-only canvas, with a banner offering the way back
 * to the living design.
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
 * A light workbench theme as a host would send it: Material's light scheme, palette taken from its
 * roles as the host bridge fills one in.
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
