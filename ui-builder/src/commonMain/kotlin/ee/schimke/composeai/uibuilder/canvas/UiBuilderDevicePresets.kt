package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.editor.EditorLayoutDirection
import ee.schimke.composeai.uibuilder.editor.EditorScreenTheme
import ee.schimke.composeai.uibuilder.editor.ScreenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform

/**
 * One frame the Screen inspector can drop onto the canvas: a phone, a foldable, a tablet, a watch.
 *
 * **The editor never authors this list.** The render lane's device catalog (`DeviceDimensions`) is
 * JVM-only, so the host supplies presets resolved from it, and each always matches a frame the
 * renderer produces. [widthDp], [heightDp] and [density] are carried, never restated; [label] and
 * [group] are presentation derived from the device id.
 */
data class UiBuilderDevicePreset(
  /** The `@Preview(device = …)` token the render lane resolves, e.g. `id:pixel_7`. */
  val id: String,
  /** Display name, e.g. "Pixel 7". Derived from [id]; carries no geometry. */
  val label: String,
  /** Menu section, e.g. "Phones". Derived from [id]; carries no geometry. */
  val group: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
) {
  /** The secondary line the menu shows, e.g. `411 × 914 dp · 2.625×`. */
  val summary: String
    get() = "$widthDp × $heightDp dp · ${density.densityLabel()}×"
}

/**
 * [this] with the preset's frame applied: width, height and density together. Font scale, locale,
 * theme and layout direction survive, since a device is a frame, not an environment.
 */
fun ScreenEnvironmentSettings.withDevicePreset(
  preset: UiBuilderDevicePreset
): ScreenEnvironmentSettings =
  copy(widthDp = preset.widthDp, heightDp = preset.heightDp, density = preset.density)

/**
 * [this] with the Screen dock's seven typed fields applied, and everything else carried forward. A
 * `copy` so fields the caller doesn't own (e.g. `exportDevices`) aren't silently reset (see #903).
 */
fun ScreenEnvironmentSettings.withScreenFields(
  widthDp: Int,
  heightDp: Int,
  density: Double,
  fontScale: Double,
  locale: String,
  theme: EditorScreenTheme,
  layoutDirection: EditorLayoutDirection,
): ScreenEnvironmentSettings =
  copy(
    widthDp = widthDp,
    heightDp = heightDp,
    density = density,
    fontScale = fontScale,
    locale = locale,
    theme = theme,
    layoutDirection = layoutDirection,
  )

/** The preset [this] currently sits on, or null when the frame has been hand-edited. */
fun ScreenEnvironmentSettings.matchingDevicePreset(
  presets: List<UiBuilderDevicePreset>
): UiBuilderDevicePreset? = presets.firstOrNull {
  it.widthDp == widthDp && it.heightDp == heightDp && it.density == density
}

/** `2.625` → `2.625`, `2.0` → `2`. Trailing `.0` on a density reads as noise in a menu. */
private fun Double.densityLabel(): String {
  val text = toString()
  return if (text.endsWith(".0")) text.dropLast(2) else text
}

/**
 * The menu sections worth offering a design on this platform, in order; the host serves one device
 * catalog for every catalog.
 *
 * **Keyed on [UiBuilderCatalogPlatform], never on a catalog id** (`platform` is a declared contract
 * field), so new catalogs get a sensible menu. Still a device opinion held in this repository; a
 * catalog's `ui-builder.policy.json` (beside `frame.seedDevice`) is the better future home, with
 * this as fallback.
 *
 * Sections rather than ids, so new devices land correctly (as `UiBuilderDevicePresets.groupFor`
 * does on the server). A default, not a restriction: **Show all devices** reveals the rest, and
 * devices a design already names are kept.
 */
fun UiBuilderCatalogPlatform.relevantDeviceGroups(): List<String> =
  when (this) {
    // An A2UI surface is drawn by whichever client receives it; the Android one the native lane
    // renders is a phone app, so the phone sections are where its frame is picked from.
    UiBuilderCatalogPlatform.MOBILE,
    UiBuilderCatalogPlatform.A2UI -> listOf("Phones", "Foldables", "Tablets")
    // A widget body is drawn on a watch, so the widget catalog wants the watch frames too.
    UiBuilderCatalogPlatform.WEAR,
    UiBuilderCatalogPlatform.REMOTE_COMPOSE -> listOf("Wear OS")
  }

/**
 * [presets] narrowed to what [platform] is for, plus anything [keep] (the design's `exportDevices`)
 * names, so selected ticks stay clearable.
 */
fun List<UiBuilderDevicePreset>.forPlatform(
  platform: UiBuilderCatalogPlatform,
  keep: Collection<String> = emptyList(),
): List<UiBuilderDevicePreset> {
  // A catalog declaring its own sizes (e.g. launcher grid cells) gets no device-family default.
  val groups =
    if (any { it.group == UiBuilderFrameGeometry.CATALOG_SIZES_GROUP })
      listOf(UiBuilderFrameGeometry.CATALOG_SIZES_GROUP)
    else platform.relevantDeviceGroups()
  return filter { it.group in groups || it.id in keep }
}
