package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of the editor's own UI (panels, rails, menus, inspector), never the design's. The
 * browser uses [Default]; an embedding host passes one derived from its theme (VS Code builds it
 * from `--vscode-*` colours and rebuilds on theme switch).
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
 * Editor colours that are not Material roles: the design backdrop, layer highlights and session
 * badge.
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
