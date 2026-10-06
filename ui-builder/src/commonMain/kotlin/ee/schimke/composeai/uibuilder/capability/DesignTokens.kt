package ee.schimke.composeai.uibuilder.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What a token holds, which decides how it is written and whether a slider can drive it. */
enum class DesignTokenKind(val wire: String) {
  /** A number, written as a `float` (or an `int` where the property is one). Tunable. */
  Number("number"),

  /** A colour: a `#RRGGBB` literal written as `color`, or a theme role written as `colorToken`. */
  Color("color");

  companion object {
    fun from(wire: String?): DesignTokenKind? = entries.firstOrNull { it.wire == wire }
  }
}

/**
 * One property a token writes: [property] on every node of [componentId] — or, when [theme], only
 * on the design's theme host, the top-level node of that component.
 */
data class DesignTokenBinding(
  val componentId: String,
  val property: String,
  val theme: Boolean = false,
)

/**
 * A design-system value with a name: a theme colour, the spacing of a list, the size of an icon.
 *
 * Declared by the catalog, not by the design, because it is a fact about the design system — which
 * values it lets a design re-skin, and where each one lands. [default] is **nullable**, and null is
 * the usual answer: it means the design system's own value, which is what an unset property draws.
 * A token is *applied* by writing its value into every property it binds, and reset by unsetting
 * them again; the design holds the result as ordinary properties, so what a token is set to is read
 * back from the document rather than stored a second time.
 *
 * See [`UI_BUILDER_DESIGN_TOKENS.md`](../../../../../../docs/design/UI_BUILDER_DESIGN_TOKENS.md).
 */
data class DesignToken(
  /** Stable and dotted, `color.primary` or `space.list`; the group is everything before the dot. */
  val id: String,
  val label: String,
  val kind: DesignTokenKind,
  /** The catalog's own value, or null for "the design system decides" — an unset property. */
  val default: JsonPrimitive? = null,
  /** A number token's range: what its slider spans and what an applied value must lie in. */
  val minimum: Double? = null,
  val maximum: Double? = null,
  /** Whether a number token moves in whole steps. */
  val integer: Boolean = false,
  val bindings: List<DesignTokenBinding>,
  val notes: String? = null,
) {
  /** Whether any binding is on the theme host, which is how the panel groups it. */
  val themed: Boolean
    get() = bindings.any { it.theme }
}

/**
 * The catalog's tokens, read from `statusSemantics.designTokens` for the reason every other
 * catalog-wide fact is: `CatalogCapabilityV1` is published from compose-preview-contracts and
 * cannot grow a field from here, and `statusSemantics` is the catalog's own open vocabulary.
 *
 * ```json
 * "designTokens": {
 *   "tokens": [
 *     { "id": "color.primary", "label": "Primary", "kind": "color", "default": null,
 *       "theme": [{ "component": "wear-m3/screen-scaffold", "property": "themePrimaryColor" }] },
 *     { "id": "space.list", "label": "List spacing", "kind": "number", "default": null,
 *       "minimum": 0, "maximum": 24,
 *       "components": [{ "component": "layout/column", "property": "verticalSpacingDp" }] }
 *   ]
 * }
 * ```
 *
 * Lenient the way the rest of `statusSemantics` is read: an entry that is not a token — no id, an
 * unknown kind, nothing bound, a range the wrong way round — is skipped rather than failing the
 * catalog, and a catalog that says nothing has no tokens.
 */
object DesignTokens {
  const val KEY: String = "designTokens"

  fun from(statusSemantics: JsonObject): List<DesignToken> {
    val tokens =
      (statusSemantics[KEY] as? JsonObject)?.get("tokens") as? JsonArray ?: return emptyList()
    val seen = mutableSetOf<String>()
    return tokens.mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val id =
        entry.string("id")?.takeIf { it.isNotBlank() && seen.add(it) } ?: return@mapNotNull null
      val kind = DesignTokenKind.from(entry.string("kind")) ?: return@mapNotNull null
      val bindings =
        entry.bindings("theme", theme = true) + entry.bindings("components", theme = false)
      if (bindings.isEmpty()) return@mapNotNull null
      val minimum = entry.number("minimum")
      val maximum = entry.number("maximum")
      if (
        kind == DesignTokenKind.Number && (minimum == null || maximum == null || minimum >= maximum)
      )
        return@mapNotNull null
      DesignToken(
        id = id,
        label = entry.string("label") ?: id,
        kind = kind,
        default = (entry["default"] as? JsonPrimitive)?.takeUnless { it is JsonNull },
        minimum = minimum,
        maximum = maximum,
        integer = (entry["integer"] as? JsonPrimitive)?.booleanOrNull == true,
        bindings = bindings,
        notes = entry.string("notes"),
      )
    }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)
      ?.takeUnless { it.isString }
      ?.doubleOrNull
      ?.takeIf { it.isFinite() }

  private fun JsonObject.bindings(key: String, theme: Boolean): List<DesignTokenBinding> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
      val binding = element as? JsonObject ?: return@mapNotNull null
      DesignTokenBinding(
        componentId = binding.string("component") ?: return@mapNotNull null,
        property = binding.string("property") ?: return@mapNotNull null,
        theme = theme,
      )
    }
}
