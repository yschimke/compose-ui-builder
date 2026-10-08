package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import ee.schimke.composeai.uibuilder.capability.DesignToken
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform

/**
 * Colour schemes for the Theme panel: generated from a seed colour, or read from a Material Theme
 * Builder export, and then written onto whatever the design's theme host and design tokens can
 * hold.
 *
 * A scheme here is Material 3's role names (`primary`, `onSurface`, `surfaceContainerHigh`, …) to
 * `#RRGGBB` literals — the spelling Material Theme Builder exports, the `color.<role>` token ids a
 * Material catalog declares, and the `theme<Role>Color` properties a Wear theme host carries. Pure,
 * so every mapping is pinned by a JVM test rather than by looking at the panel.
 */
object ThemeSchemes {
  /** The scheme styles the generator offers, TonalSpot (Material You's own) first. */
  val STYLES: List<PaletteStyle> =
    listOf(PaletteStyle.TonalSpot) + PaletteStyle.entries.filter { it != PaletteStyle.TonalSpot }

  /** The contrast levels the generator offers, standard first. */
  val CONTRASTS: List<Contrast> =
    listOf(Contrast.Default, Contrast.Medium, Contrast.High, Contrast.Reduced)

  /**
   * The light or dark scheme a design starts on: a watch face is dark, a phone light. The same
   * choice the Material Theme Builder import makes.
   */
  fun preferredScheme(platform: UiBuilderCatalogPlatform): String =
    if (platform == UiBuilderCatalogPlatform.WEAR) "dark" else "light"

  /**
   * The Material 3 scheme materialkolor generates from [seed] (`#RRGGBB` or `#AARRGGBB`; alpha is
   * ignored), by role; null when [seed] is not a colour.
   */
  fun generate(
    seed: String,
    dark: Boolean,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    contrast: Double = Contrast.Default.value,
  ): Map<String, String>? {
    val colour = parseHex(seed) ?: return null
    return roles(
      dynamicColorScheme(
        seedColor = colour.copy(alpha = 1f),
        isDark = dark,
        style = style,
        contrastLevel = contrast,
      )
    )
  }

  /** [scheme]'s roles as `#RRGGBB`, in Material 3's own order. */
  fun roles(scheme: ColorScheme): Map<String, String> =
    linkedMapOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "inversePrimary" to scheme.inversePrimary,
        "secondary" to scheme.secondary,
        "onSecondary" to scheme.onSecondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "onTertiary" to scheme.onTertiary,
        "tertiaryContainer" to scheme.tertiaryContainer,
        "onTertiaryContainer" to scheme.onTertiaryContainer,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "onSurface" to scheme.onSurface,
        "surfaceVariant" to scheme.surfaceVariant,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "surfaceTint" to scheme.surfaceTint,
        "inverseSurface" to scheme.inverseSurface,
        "inverseOnSurface" to scheme.inverseOnSurface,
        "error" to scheme.error,
        "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer,
        "onErrorContainer" to scheme.onErrorContainer,
        "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant,
        "scrim" to scheme.scrim,
        "surfaceBright" to scheme.surfaceBright,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
      )
      .mapValues { (_, colour) -> hex(colour) }

  /** The roles the panel previews as swatches, in pairs a reader checks together. */
  val PREVIEW_ROLES: List<String> =
    listOf(
      "primary",
      "onPrimary",
      "primaryContainer",
      "secondary",
      "tertiary",
      "background",
      "surface",
      "surfaceContainer",
      "onSurface",
      "outline",
      "error",
    )

  /**
   * Which of a theme host's colour properties each Material role sets, given the properties its
   * component declares.
   *
   * An `m3/surface` carries four colours, and its content colour is the text drawn on its surface:
   * `onSurface`. A Wear screen scaffold carries one property per role, spelled `theme<Role>Color`.
   * A role the host has no property for is absent: the host cannot hold it.
   */
  fun hostColorProperties(declared: Set<String>): Map<String, String> = buildMap {
    declared.forEach { property ->
      val role =
        MATERIAL_SURFACE_ROLES[property]
          ?: THEME_COLOR.matchEntire(property)?.groupValues?.get(1)?.replaceFirstChar {
            it.lowercase()
          }
      if (role != null) put(role, property)
    }
  }

  /** [scheme] as the theme host's property writes, property to value; see [hostColorProperties]. */
  fun hostWrites(scheme: Map<String, String>, hostProperties: Map<String, String>) =
    hostProperties.entries
      .mapNotNull { (role, property) -> scheme[role]?.let { property to it } }
      .toMap()

  /**
   * [scheme] as values for this catalog's colour tokens: `color.<role>` for each role the catalog
   * declares a token for.
   */
  fun tokenValues(scheme: Map<String, String>, tokens: List<DesignToken>): Map<String, String> =
    tokens
      .filter { it.kind == DesignTokenKind.Color && it.id.startsWith("color.") }
      .mapNotNull { token -> scheme[token.id.removePrefix("color.")]?.let { token.id to it } }
      .toMap()

  /** `#RRGGBB` or `#AARRGGBB` as a colour, or null. */
  fun parseHex(value: String): Color? {
    val trimmed = value.trim()
    if (!trimmed.isArgbColor()) return null
    val hex = trimmed.removePrefix("#")
    val argb = if (hex.length == 6) "FF$hex" else hex
    return argb.toLongOrNull(16)?.let { Color(it.toInt()) }
  }

  /** [colour] as `#RRGGBB`, upper case, as Material Theme Builder writes it. */
  fun hex(colour: Color): String =
    "#" + (colour.toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

  private val THEME_COLOR = Regex("theme([A-Z][A-Za-z]*)Color")

  /** `m3/surface`'s four colours, by property; its other theme properties are not colours. */
  private val MATERIAL_SURFACE_ROLES =
    mapOf(
      THEME_PRIMARY to "primary",
      THEME_BACKGROUND to "background",
      THEME_SURFACE to "surface",
      THEME_CONTENT to "onSurface",
    )
}
