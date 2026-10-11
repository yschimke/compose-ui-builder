package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject

/**
 * Which font API a generated screen sets a theme's [ThemeTypefaces] with — a fact about the
 * classpath the file will be compiled against, not about the design.
 *
 * [ANDROID] (the default) uses `GoogleFont`, an Android-only AAR. [DESKTOP] writes
 * `androidx.compose.ui.text.platform.SystemFont("Lobster", FontWeight.Bold)` for Compose
 * Multiplatform Desktop bundles (e.g. `m3-catalog`'s native lane); uninstalled families fall back
 * to the default face, reported via [SystemFontLookups].
 *
 * The caller picks; [forNativeBackend] and [forCatalog] derive it from a bundle's backend or a
 * catalog's `previewSurfaces`. Wear and Remote emitters never read it.
 */
enum class TypefaceTarget {
  /** `Font(GoogleFont("Lobster"), provider, weight)`: downloadable fonts on Android. */
  ANDROID,

  /** `SystemFont("Lobster", weight)`: a font-manager lookup on Compose Multiplatform Desktop. */
  DESKTOP;

  companion object {
    /** What a surface that says nothing writes, and what every surface wrote before this. */
    val DEFAULT: TypefaceTarget = ANDROID

    /**
     * The target for a native lane that compiles on [backend] — a bundle's `manifest.backend` or a
     * catalog's [UiBuilderPreviewSurfaces.SurfaceClaim.backend]. Only `desktop` changes anything;
     * an unknown word keeps [DEFAULT].
     */
    fun forNativeBackend(backend: String?): TypefaceTarget =
      if (backend?.trim().equals(UiBuilderPreviewSurfaces.BACKEND_DESKTOP, ignoreCase = true))
        DESKTOP
      else DEFAULT

    /**
     * The target for the native lane of the catalog whose `statusSemantics` this is: [DESKTOP] for
     * a [UiBuilderCatalogPlatform.MOBILE] catalog whose native surface is drawn by the desktop
     * daemon (`m3-catalog`), [DEFAULT] otherwise. Wear and Remote catalogs keep [DEFAULT] whatever
     * they declare: their emitters never read it.
     */
    fun forCatalog(statusSemantics: JsonObject): TypefaceTarget =
      if (UiBuilderCatalogPlatform.from(statusSemantics) != UiBuilderCatalogPlatform.MOBILE) DEFAULT
      else forNativeBackend(previewSurfacesOf(statusSemantics).native.backend)
  }
}

/**
 * The export warning for a typeface written in the [TypefaceTarget.DESKTOP] form: the file
 * compiles, but an uninstalled family (usual for Google Fonts on a render host) draws in the
 * default face. Reported beside the source like `ASSET_PLACEHOLDER`.
 */
object SystemFontLookups {
  const val CODE: String = "TYPEFACE_SYSTEM_FONT_LOOKUP"

  /** The sentence for one family, as written into the warning and the source's header. */
  fun note(family: String): String =
    "Typeface '$family' is set as a desktop system-font lookup, SystemFont(\"$family\"), because " +
      "Google Fonts' downloadable GoogleFont API is Android-only; it draws in '$family' only " +
      "where that family is installed, and in the platform's default face otherwise. Bundle the " +
      "font file and pass Font(...) for it to ship the face."
}
