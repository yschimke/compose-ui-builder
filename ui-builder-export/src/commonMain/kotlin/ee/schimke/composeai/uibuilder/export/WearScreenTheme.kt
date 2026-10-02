package ee.schimke.composeai.uibuilder.export

/**
 * The colour roles a `wear-m3/screen-scaffold` can re-skin, and the property each is authored as.
 *
 * A Wear screen could only be recoloured one component at a time, and the components with no colour
 * of their own — the progress indicator, the edge button, an icon — stayed stock lavender beside a
 * re-branded list (yschimke/wear-m3-catalog#682). A theme belongs on the screen, where everything
 * inside reads it: the canvas draws the scaffold's subtree under a copy of Wear's scheme with these
 * roles replaced, and [WearScreenCodeExporter] wraps the screen in the same
 * `MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(…))`.
 *
 * Role overrides rather than a seed. A seed needs a dynamic-scheme generator on both sides — the
 * catalog's own named themes use `materialkolor` — and a generated screen that pulled a library in
 * to recolour itself is a dependency an author did not ask for. Every role here exists under the
 * same name on Wear's `ColorScheme` and on Material 3's, which the canvas also has to recolour: its
 * token resolution for a few foundation nodes goes through the mobile scheme.
 *
 * One list, read by the catalog that declares the properties, the canvas that draws them and the
 * generator that writes them, so the three cannot disagree about which roles exist.
 */
object WearScreenTheme {
  /**
   * Each role and the scaffold property that overrides it, spelled out rather than derived so a
   * search for a property's name finds where it is read.
   */
  private val PROPERTIES: Map<String, String> =
    linkedMapOf(
      "primary" to "themePrimaryColor",
      "onPrimary" to "themeOnPrimaryColor",
      "primaryContainer" to "themePrimaryContainerColor",
      "onPrimaryContainer" to "themeOnPrimaryContainerColor",
      "secondary" to "themeSecondaryColor",
      "onSecondary" to "themeOnSecondaryColor",
      "secondaryContainer" to "themeSecondaryContainerColor",
      "onSecondaryContainer" to "themeOnSecondaryContainerColor",
      "tertiary" to "themeTertiaryColor",
      "onTertiary" to "themeOnTertiaryColor",
      "tertiaryContainer" to "themeTertiaryContainerColor",
      "onTertiaryContainer" to "themeOnTertiaryContainerColor",
      "surfaceContainerLow" to "themeSurfaceContainerLowColor",
      "surfaceContainer" to "themeSurfaceContainerColor",
      "surfaceContainerHigh" to "themeSurfaceContainerHighColor",
      "onSurface" to "themeOnSurfaceColor",
      "onSurfaceVariant" to "themeOnSurfaceVariantColor",
      "outline" to "themeOutlineColor",
      "outlineVariant" to "themeOutlineVariantColor",
      "background" to "themeBackgroundColor",
      "onBackground" to "themeOnBackgroundColor",
      "error" to "themeErrorColor",
      "onError" to "themeOnErrorColor",
    )

  val ROLES: List<String> = PROPERTIES.keys.toList()

  /** The scaffold property that overrides [role]: `primary` is `themePrimaryColor`. */
  fun property(role: String): String = PROPERTIES.getValue(role)
}
