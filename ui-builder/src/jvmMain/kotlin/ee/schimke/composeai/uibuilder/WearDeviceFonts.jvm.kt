package ee.schimke.composeai.uibuilder

/**
 * Wear's face from the classpath, where the build copies `assets/rc-fonts`' manifest and Roboto
 * Flex under `fonts/`. Once per process; a missing resource leaves the fallback in place.
 */
internal actual fun ensureBundledWearDeviceFonts() {
  bundledWearDeviceFonts
}

private val bundledWearDeviceFonts: Unit by lazy {
  val loader = UiBuilderFontRegistry::class.java
  val manifest =
    loader.getResource("/fonts/fonts.json")?.readText()?.let(::parseVendoredFontManifest)
      ?: return@lazy
  registerWearDeviceFonts(manifest) { file ->
    loader.getResourceAsStream("/fonts/$file")?.use { it.readBytes() }
      ?: error("fonts/$file is not on the classpath")
  }
}
