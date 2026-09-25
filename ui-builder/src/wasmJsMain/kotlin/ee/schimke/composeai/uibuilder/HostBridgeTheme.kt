@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorPalette
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A host bridge page's theme, so the editor's panels are drawn in the host's colours.
 *
 * The contract is in Material 3's vocabulary, not the host's: [roles] maps `ColorScheme` role names
 * (`background`, `primary`, `onSurfaceVariant`, …) to ARGB, and [palette] maps
 * [UiBuilderEditorPalette] field names the same way. Which host colour becomes which role is the
 * host's decision, made where the host's colours are known: the VS Code page maps its `--vscode-*`
 * variables. A role the host leaves out keeps the base scheme's value, and a name this editor does
 * not know is ignored, so either side can grow.
 *
 * Light or dark is not a field. It is read from [roles]' background, which is what decides whether
 * a light or a dark base is the right one to fill the gaps from.
 */
@Serializable
internal data class HostThemeMessage(
  val roles: Map<String, Long> = emptyMap(),
  val palette: Map<String, Long> = emptyMap(),
)

private val hostThemeJson = Json { ignoreUnknownKeys = true }

/** The theme in [json], or null for none or an unreadable one: the editor then keeps its own. */
internal fun decodeHostTheme(json: String?): UiBuilderEditorTheme? {
  if (json.isNullOrBlank()) return null
  val message =
    runCatching { hostThemeJson.decodeFromString(HostThemeMessage.serializer(), json) }.getOrNull()
      ?: return null
  if (message.roles.isEmpty()) return null
  val colors = message.roles.mapValues { (_, argb) -> Color(argb.toInt()) }
  val background = colors["background"] ?: colors["surface"]
  val dark = background == null || background.luminance() < 0.5f
  val base = if (dark) UiBuilderEditorTheme.Default.colorScheme else lightColorScheme()
  val scheme = colors.entries.fold(base) { scheme, (role, color) -> scheme.withRole(role, color) }
  val palette = message.palette.mapValues { (_, argb) -> Color(argb.toInt()) }
  val defaults = defaultPalette(scheme, dark)
  return UiBuilderEditorTheme(
    colorScheme = scheme,
    palette =
      UiBuilderEditorPalette(
        workspace = palette["workspace"] ?: defaults.workspace,
        layerSelected = palette["layerSelected"] ?: defaults.layerSelected,
        layerDragged = palette["layerDragged"] ?: defaults.layerDragged,
        dropTarget = palette["dropTarget"] ?: defaults.dropTarget,
        sessionBadge = palette["sessionBadge"] ?: defaults.sessionBadge,
        onSessionBadge = palette["onSessionBadge"] ?: defaults.onSessionBadge,
      ),
  )
}

/** A palette from the scheme alone, for a host that sends roles and no palette. */
private fun defaultPalette(scheme: ColorScheme, dark: Boolean): UiBuilderEditorPalette =
  if (dark && scheme == UiBuilderEditorTheme.Default.colorScheme) {
    UiBuilderEditorPalette.Default
  } else {
    UiBuilderEditorPalette(
      workspace = scheme.surfaceContainerLowest,
      layerSelected = scheme.primaryContainer,
      layerDragged = scheme.secondaryContainer,
      dropTarget = scheme.secondaryContainer,
      sessionBadge = scheme.secondaryContainer,
      onSessionBadge = scheme.onSecondaryContainer,
    )
  }

private fun ColorScheme.withRole(role: String, color: Color): ColorScheme =
  when (role) {
    "primary" -> copy(primary = color)
    "onPrimary" -> copy(onPrimary = color)
    "primaryContainer" -> copy(primaryContainer = color)
    "onPrimaryContainer" -> copy(onPrimaryContainer = color)
    "inversePrimary" -> copy(inversePrimary = color)
    "secondary" -> copy(secondary = color)
    "onSecondary" -> copy(onSecondary = color)
    "secondaryContainer" -> copy(secondaryContainer = color)
    "onSecondaryContainer" -> copy(onSecondaryContainer = color)
    "tertiary" -> copy(tertiary = color)
    "onTertiary" -> copy(onTertiary = color)
    "tertiaryContainer" -> copy(tertiaryContainer = color)
    "onTertiaryContainer" -> copy(onTertiaryContainer = color)
    "background" -> copy(background = color)
    "onBackground" -> copy(onBackground = color)
    "surface" -> copy(surface = color, surfaceBright = color, surfaceDim = color)
    "onSurface" -> copy(onSurface = color)
    "surfaceVariant" -> copy(surfaceVariant = color)
    "onSurfaceVariant" -> copy(onSurfaceVariant = color)
    "surfaceTint" -> copy(surfaceTint = color)
    "inverseSurface" -> copy(inverseSurface = color)
    "inverseOnSurface" -> copy(inverseOnSurface = color)
    "error" -> copy(error = color)
    "onError" -> copy(onError = color)
    "errorContainer" -> copy(errorContainer = color)
    "onErrorContainer" -> copy(onErrorContainer = color)
    "outline" -> copy(outline = color)
    "outlineVariant" -> copy(outlineVariant = color)
    "scrim" -> copy(scrim = color)
    "surfaceContainerLowest" -> copy(surfaceContainerLowest = color)
    "surfaceContainerLow" -> copy(surfaceContainerLow = color)
    "surfaceContainer" -> copy(surfaceContainer = color)
    "surfaceContainerHigh" -> copy(surfaceContainerHigh = color)
    "surfaceContainerHighest" -> copy(surfaceContainerHighest = color)
    else -> this
  }

/**
 * `composeUiBuilderHost.readTheme()`, the host's theme as JSON when the editor starts, or null
 * where the host has none.
 */
@JsFun(
  """() => {
  const host = globalThis.composeUiBuilderHost;
  if (!host || typeof host.readTheme !== 'function') return null;
  try {
    const theme = host.readTheme();
    return typeof theme === 'string' ? theme : JSON.stringify(theme);
  } catch (e) {
    return null;
  }
}"""
)
internal external fun readHostTheme(): String?

/** Hands each `{ type: "compose-ui-builder/theme", theme }` to [onTheme] as JSON. */
@JsFun(
  """(onTheme) => {
  globalThis.addEventListener('message', (event) => {
    const data = event.data;
    if (!data || data.type !== 'compose-ui-builder/theme' || !data.theme) return;
    onTheme(typeof data.theme === 'string' ? data.theme : JSON.stringify(data.theme));
  });
}"""
)
internal external fun listenForHostTheme(onTheme: (String) -> Unit)
