package ee.schimke.composeai.uibuilder

import ee.schimke.wearcmp.port.WearFonts

/**
 * Hand the Wear port the face Wear's type scale names (`DeviceFontFamilyName("roboto-flex")`,
 * resolved via `WearFonts` and the platform font manager), from this host's vendored fonts;
 * otherwise Wear text falls back to a wider sans that clips the clock and wraps labels.
 *
 * Call it before anything composes a Wear component: the port resolves the name once and keeps the
 * answer.
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
 * Register Wear's face synchronously from what this platform bundles, if anything. The JVM reads it
 * off the classpath; the browser can't, so its hosts call [registerWearDeviceFonts] first and this
 * is a no-op there.
 */
internal expect fun ensureBundledWearDeviceFonts()
