package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours the editor's own UI is drawn in: its panels, rails, menus and inspector — never the
 * design, which the catalog's renderer draws in the design's theme.
 *
 * The browser keeps [Default], the dark scheme this editor has always had. A host that embeds the
 * editor among its own panels passes one derived from its theme, so the editor reads as part of the
 * host rather than as a dark page inside it: the VS Code custom editor builds one from the
 * workbench's `--vscode-*` colours and rebuilds it when the person switches theme.
 */
data class UiBuilderEditorTheme(
  val colorScheme: ColorScheme,
  val palette: UiBuilderEditorPalette = UiBuilderEditorPalette.Default,
) {
  companion object {
    val Default: UiBuilderEditorTheme = UiBuilderEditorTheme(EditorColors)
  }
}

/**
 * The editor colours that are not a Material role: the backdrop the design sits on, the layer
 * tree's highlights and the session badge. They were literals, which is why a host theme could not
 * reach them.
 */
data class UiBuilderEditorPalette(
  /** Behind the design on the canvas, and behind the revision strip. */
  val workspace: Color,
  val layerSelected: Color,
  val layerDragged: Color,
  /** A layer row a dragged catalog item would land in. */
  val dropTarget: Color,
  val sessionBadge: Color,
  val onSessionBadge: Color,
) {
  companion object {
    val Default: UiBuilderEditorPalette =
      UiBuilderEditorPalette(
        workspace = Color(0xff0d0e11),
        layerSelected = Color(0xff30385a),
        layerDragged = Color(0xff3b4468),
        dropTarget = Color(0xff26304a),
        sessionBadge = Color(0xff214c37),
        onSessionBadge = Color(0xffa8f2c6),
      )
  }
}

internal val LocalUiBuilderEditorPalette = staticCompositionLocalOf {
  UiBuilderEditorPalette.Default
}

/** [MaterialTheme] with [theme]'s scheme, and [theme]'s palette for the non-Material colours. */
@Composable
internal fun EditorTheme(theme: UiBuilderEditorTheme, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = theme.colorScheme) {
    CompositionLocalProvider(LocalUiBuilderEditorPalette provides theme.palette, content = content)
  }
}
