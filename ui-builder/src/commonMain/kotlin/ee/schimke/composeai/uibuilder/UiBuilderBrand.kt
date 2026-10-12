package ee.schimke.composeai.uibuilder

/**
 * A white-labelled host's identity for the editor, read from the shell's `<meta>`s the way
 * [normalizeUiBuilderBasePath] reads `ui-builder-base-path`. compose-preview-server writes them on
 * a host it serves as another product (its `--ui-builder-profile-host`, e.g. the Home Assistant
 * card designer on `ha.coo.ee`):
 * - `ui-builder-brand-name`: what the product is called, shown instead of "UI Builder";
 * - `ui-builder-brand-accent`: a `#rrggbb` colour for the editor's primary role;
 * - `ui-builder-brand-home`: where the brand name leads, a same-origin path or an HTTPS URL;
 * - `ui-builder-catalogs`: the catalogs this host offers, comma-separated.
 *
 * Every field is optional and checked on its own, so one bad value costs only itself. A page with
 * none of them has no brand ([parse] answers null) and the editor is exactly what it was.
 *
 * The server enforces the catalog list; the editor only stops offering what would be refused.
 */
data class UiBuilderBrand(
  /** The product's name, or null to keep the editor's own. */
  val name: String? = null,
  /** `0xAARRGGBB`, opaque, or null for the default palette. */
  val accentArgb: Long? = null,
  /** Where the brand name leads: a same-origin path or an `https://` URL; null for nowhere. */
  val homeHref: String? = null,
  /** The catalogs this host offers, in its order; empty when it does not narrow them. */
  val catalogs: List<String> = emptyList(),
) {
  /** Whether [catalogSystemId] may be offered here. Every catalog, when the host names none. */
  fun allows(catalogSystemId: String): Boolean = catalogs.isEmpty() || catalogSystemId in catalogs

  /** One catalog only: a choice between catalogs has nothing to choose from. */
  val singleCatalog: Boolean
    get() = catalogs.size == 1

  /** [name], or [default] where the host gave none. */
  fun nameOr(default: String): String = name ?: default

  companion object {
    const val NAME_META: String = "ui-builder-brand-name"
    const val ACCENT_META: String = "ui-builder-brand-accent"
    const val HOME_META: String = "ui-builder-brand-home"
    const val CATALOGS_META: String = "ui-builder-catalogs"

    /** As long as a name in the app bar can sensibly be; anything longer is not a name. */
    const val NAME_MAX: Int = 80

    private val ACCENT = Regex("#[0-9a-fA-F]{6}")
    private val CATALOG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** The brand the four metas' contents describe, or null when none says anything usable. */
    fun parse(
      name: String?,
      accent: String?,
      home: String?,
      catalogs: String?,
    ): UiBuilderBrand? {
      val brand =
        UiBuilderBrand(
          name = parseName(name),
          accentArgb = parseAccent(accent),
          homeHref = parseHome(home),
          catalogs = parseCatalogs(catalogs),
        )
      return brand.takeUnless { it == UiBuilderBrand() }
    }

    fun parseName(raw: String?): String? =
      raw?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= NAME_MAX && it.none { c -> c.isISOControl() }
      }

    fun parseAccent(raw: String?): Long? =
      raw?.trim()?.takeIf(ACCENT::matches)?.substring(1)?.toLong(16)?.let { 0xFF000000L or it }

    /**
     * A same-origin path (as [normalizeUiBuilderBasePath] accepts one, with a query allowed) or an
     * `https://` URL with a host and no credentials. Anything else is no home at all, so the brand
     * name never leads somewhere a scheme like `javascript:` could.
     */
    fun parseHome(raw: String?): String? {
      val value = raw?.trim().orEmpty()
      if (value.isEmpty() || value.any { it == '\\' || it.code < 0x20 || it.code == 0x7f })
        return null
      if (value.startsWith("/")) return value.takeUnless { it.startsWith("//") }
      if (!value.startsWith("https://")) return null
      val authority =
        value
          .removePrefix("https://")
          .substringBefore('/')
          .substringBefore('?')
          .substringBefore('#')
      if (authority.isEmpty() || '@' in authority) return null
      return value
    }

    fun parseCatalogs(raw: String?): List<String> =
      raw.orEmpty().split(',').map(String::trim).filter(CATALOG::matches).distinct()
  }
}
