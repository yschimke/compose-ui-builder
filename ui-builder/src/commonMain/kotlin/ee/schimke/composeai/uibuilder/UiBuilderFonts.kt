package ee.schimke.composeai.uibuilder

import ee.schimke.wearcmp.port.WearFonts

/**
 * Hand the Wear port the face Wear's type scale names, from this host's vendored fonts.
 *
 * Wear Material 3 sets every role in `DeviceFontFamilyName("roboto-flex")` — the watch's system
 * face, which the port resolves through `WearFonts` and then the platform's font manager. Nothing
 * registered it, and no desktop or browser has a font by that name, so every Wear screen on the
 * canvas was set in the platform's fallback sans: wider than Roboto Flex, enough to cut the clock
 * to "10:1" and wrap labels that fit on a watch.
 *
 * Call it before anything composes a Wear component. The port resolves the name once, when its type
 * scale is first read, and keeps the answer.
 */
suspend fun UiBuilderFontRegistry.registerWearDeviceFonts() {
  val manifest = runCatching { parseVendoredFontManifest(readManifestText()) }.getOrNull() ?: return
  registerWearDeviceFonts(manifest) { file -> readFontBytes(file) }
}

/** [registerWearDeviceFonts] from a manifest and a way to read its files, for any host. */
internal inline fun registerWearDeviceFonts(
  manifest: VendoredFontManifest,
  read: (file: String) -> ByteArray,
) {
  if (WearFonts.isRegistered(WearFonts.RobotoFlex)) return
  val file =
    manifest.families.firstOrNull { it.name == WEAR_DEVICE_FONT_FAMILY }?.fonts?.firstOrNull()
      ?: return
  runCatching { WearFonts.register(WearFonts.RobotoFlex, read(file.file)) }
}

/** The vendored family that is Wear's `roboto-flex`: the same face, as a variable font. */
internal const val WEAR_DEVICE_FONT_FAMILY = "Roboto Flex"

/**
 * Register Wear's face from what this platform bundles, synchronously, if it bundles one.
 *
 * The JVM (previews, tests, the desktop app) reads it off the classpath the first time a Wear
 * surface composes. The browser has nothing to read synchronously, so its hosts call
 * [registerWearDeviceFonts] before they start composing, and this does nothing there.
 */
internal expect fun ensureBundledWearDeviceFonts()
