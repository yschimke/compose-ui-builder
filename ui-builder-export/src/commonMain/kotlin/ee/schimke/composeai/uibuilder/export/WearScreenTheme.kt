package ee.schimke.composeai.uibuilder.export

/**
 * The colour roles a `wear-m3/screen-scaffold` can re-skin, and the property each is authored as.
 * The canvas draws the subtree under Wear's scheme with these replaced, and
 * [WearScreenCodeExporter] writes `MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(…))`.
 *
 * Role overrides rather than a seed, which would pull a dynamic-scheme library into generated code.
 * Every role exists by name on both Wear's and Material 3's `ColorScheme`.
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
