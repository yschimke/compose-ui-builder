package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject

/**
 * Which font API a generated screen sets a theme's [ThemeTypefaces] with — a fact about the
 * classpath the file will be compiled against, not about the design.
 *
 * ## Why there are two
 *
 * A typeface is a Google Fonts family name, and the API that fetches one by name —
 * `androidx.compose.ui.text.googlefonts.GoogleFont` — is
 * `androidx.compose.ui:ui-text-google-fonts`, an Android AAR. Compose Multiplatform has no
 * counterpart. So a file written with it compiles in an Android module and nowhere else, and the
 * native lane for `m3-catalog` compiles against that catalog's bundle, which is Compose
 * Multiplatform **Desktop** (`kotlin.jvm`): every themed design with a typeface failed there at
 * `Unresolved reference 'GoogleFont'`, and the dependency cannot be added to a desktop catalog.
 *
 * [DESKTOP] writes the desktop lookup by family name instead —
 * `androidx.compose.ui.text.platform.SystemFont("Lobster", FontWeight.Bold)`, Compose
 * Multiplatform's `ui-text` on Skiko. It compiles on every desktop classpath, and it resolves the
 * family through the machine's font manager: a family that is installed draws, one that is not
 * falls back to the platform's default face rather than failing. A Google family is normally not
 * installed on a render host, so a desktop render shows the fallback face. That is the trade the
 * desktop form makes, and [SystemFontLookups] is how it is said out loud rather than discovered in
 * a picture.
 *
 * ## Who picks
 *
 * The caller, because only it knows what the file will be compiled against. [ANDROID] is the
 * default and what every surface wrote before this — the code pane, the export artifact, and the
 * Wear and Remote emitters (which never read this at all: Wear compiles on Android, Remote names a
 * font as `RemoteFontFamily.Named`). A native lane that compiles against a desktop bundle asks for
 * [DESKTOP]; [forNativeBackend] and [forCatalog] read that from the bundle's declared backend or
 * the catalog's `previewSurfaces`.
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
 * The export warning for a typeface written in the [TypefaceTarget.DESKTOP] form.
 *
 * A warning and not a refusal: the file compiles and the screen renders, in the platform's default
 * face wherever the family is not installed — which, for a Google Fonts family on a render host, is
 * almost always. Reported beside the source the way `ASSET_PLACEHOLDER` is, so a person reading a
 * desktop render that shows a plain sans-serif knows it is the font and not the theme.
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
