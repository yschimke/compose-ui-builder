package ee.schimke.composeai.uibuilder

/**
 * One frame the Screen inspector can drop onto the canvas — a phone, a foldable, a tablet, a watch.
 *
 * **The editor never authors this list.** `:ui-builder` is a Kotlin Multiplatform module whose
 * `wasmJs` target is what the browser editor ships, and the render lane's device catalog
 * (`ee.schimke.composeai:daemon-devices`, `DeviceDimensions`) applies `kotlin.jvm`, so the editor
 * cannot resolve it. The host supplies the presets instead — the wasm host reads them from the
 * server, which resolves `DeviceDimensions` on the JVM — and a preset therefore always describes a
 * frame the renderer will actually produce.
 *
 * That indirection is the whole point. The catalog already exists twice (the daemon's copy and
 * `:gradle-plugin`'s discovery copy, guarded by `DeviceDimensionsCatalogDriftTest` because the two
 * did drift once); a third copy inside the editor would be the one that silently disagrees with the
 * pixels. So [widthDp], [heightDp] and [density] are carried, never restated, and [label] / [group]
 * are the only fields the producer invents — presentation derived from the device id, holding no
 * geometry of its own.
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
 * [this] with the preset's frame applied — width, height and density together.
 *
 * Font scale, locale, theme and layout direction survive on purpose: a device is a frame, not a
 * whole environment, and someone checking an RTL screen across three devices should not have to
 * re-pick RTL three times.
 */
fun ScreenEnvironmentSettings.withDevicePreset(
  preset: UiBuilderDevicePreset
): ScreenEnvironmentSettings =
  copy(widthDp = preset.widthDp, heightDp = preset.heightDp, density = preset.density)

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
