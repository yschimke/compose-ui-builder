package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.editor.EditorLayoutDirection
import ee.schimke.composeai.uibuilder.editor.EditorScreenTheme
import ee.schimke.composeai.uibuilder.editor.ScreenEnvironmentSettings

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

/**
 * [this] with the Screen dock's seven typed fields applied, and everything else carried forward.
 *
 * A `copy`, for the same reason [withDevicePreset] is one. The dock has three writers for this
 * object — the frame menu, the export-device picker and the Apply button — and the two that build a
 * *fresh* `ScreenEnvironmentSettings` from what they own silently reset what they do not. Apply did
 * exactly that to `exportDevices`: the field defaults to the empty list, `updateEnvironment`
 * compares it against the document's and cannot tell "the author did not touch the devices" from
 * "the author cleared the devices", so every Apply wrote the clear (#903).
 *
 * Going through here instead means a field added to this class later is carried by default, and
 * forgetting it is a compile error at the call site rather than quiet data loss at the dock.
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
 * The menu sections worth offering a design on this platform, in order.
 *
 * The host serves one device catalog for every catalog it hosts — 40-odd presets across eight
 * families — because the geometry comes from the render lane and the render lane does not care what
 * is being authored. The picker does care: a phone screen has no use for a watch frame, and a watch
 * screen has no use for anything else. Offering all of them made the common choice a scroll through
 * the uncommon ones.
 *
 * **Keyed on [UiBuilderCatalogPlatform], never on a catalog id**, which is the difference between
 * this and the catalog knowledge `.github/scripts/ui-builder-catalog-literals.sh` exists to push
 * out of these modules. `platform` is a declared contract field: a catalog says what it authors
 * for, and this answers what that kind of screen runs on. A catalog the server learns tomorrow gets
 * a sensible menu with no edit here, which a mapping from catalog ids could not offer.
 *
 * That said, and stated rather than reworded away, because a comment that stops naming a thing
 * while still knowing it is the failure mode that gate documents: **this is still an opinion about
 * devices held in this repository.** The policy schema already has the better home for it —
 * `frame.seedDevice` is the same kind of claim, authored by the catalog — so when a catalog's own
 * `ui-builder.policy.json` is the thing being read, the families it wants belong beside its seed
 * device and this function becomes the fallback for a catalog that declares none.
 *
 * Sections rather than ids, so a device the render catalog learns lands in the right place for free
 * — the same rule `UiBuilderDevicePresets.groupFor` follows on the server.
 *
 * It is a default, not a restriction. The picker's **Show all devices** row reveals the rest, and a
 * design that already names a device outside these sections keeps it: an author who deliberately
 * put a phone screen on a TV frame gets to keep that answer.
 */
fun UiBuilderCatalogPlatform.relevantDeviceGroups(): List<String> =
  when (this) {
    UiBuilderCatalogPlatform.MOBILE -> listOf("Phones", "Foldables", "Tablets")
    // A widget body is drawn on a watch, so the widget catalog wants the watch frames too.
    UiBuilderCatalogPlatform.WEAR,
    UiBuilderCatalogPlatform.REMOTE_COMPOSE -> listOf("Wear OS")
  }

/**
 * [presets] narrowed to what [platform] is for, plus anything [keep] already names.
 *
 * `keep` is the design's own `exportDevices`: a selection the picker hides is a selection whose
 * tick an author cannot find to clear, which is worse than a long menu.
 */
fun List<UiBuilderDevicePreset>.forPlatform(
  platform: UiBuilderCatalogPlatform,
  keep: Collection<String> = emptyList(),
): List<UiBuilderDevicePreset> {
  val groups = platform.relevantDeviceGroups()
  return filter { it.group in groups || it.id in keep }
}
