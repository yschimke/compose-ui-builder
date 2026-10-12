package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What kind of value a catalog property holds, decided by its name: `jsonType` only gives the JSON
 * scalar, not the meaning.
 *
 * `color`/`…Color` hold a colour; `assetKey` a key into the catalog's asset registry; a property
 * with `allowedValues` is an enumeration. Both reducers (the editor's `CapabilityValidator` and the
 * server's `CurrentM3UiBuilderCatalogExecutor`, which mirrors this) ask on every write and refuse
 * at commit what the canvas cannot draw. The line is "the canvas cannot draw it", not "the
 * generator cannot write it": a non-exporting mockup is legitimate.
 */
object PropertyValueKinds {
  /** Whether [property] holds a colour — `color`, `containerColor`, `startColor`, and so on. */
  fun isColour(property: String): Boolean = property == "color" || property.endsWith("Color")

  /** Whether [property] names an asset the canvas has to resolve. */
  fun isAssetKey(property: String): Boolean =
    property == "assetKey" || property == REMOTE_CONTAINER_IMAGE_PROPERTY

  /**
   * The theme roles the canvas draws, matching the renderer's `colorTokenOrNull` table and the
   * inspector's suggestions. Roles outside it are refused at commit, since the canvas could not
   * draw them.
   */
  val CANVAS_COLOR_TOKENS: Set<String> =
    setOf(
      "background",
      "surface",
      "surfaceContainer",
      "surfaceContainerLow",
      "surfaceContainerHigh",
      "surfaceContainerHighest",
      "primary",
      "onPrimary",
      "secondary",
      "onSecondary",
      "tertiary",
      "onTertiary",
      "onSurface",
      "onSurfaceVariant",
      "outlineVariant",
      "transparent",
    )

  /**
   * Whether the canvas draws [value] as a colour: a `#RRGGBB` or `#AARRGGBB` literal, one of
   * [CANVAS_COLOR_TOKENS], or nothing at all — an empty value is "the component's own default",
   * which `m3/list-item.startAccentColor` documents as "draws none".
   */
  fun isDrawableColour(value: String): Boolean =
    value.isEmpty() || COLOR_LITERAL.matches(value) || value in CANVAS_COLOR_TOKENS

  /**
   * Every wrapper type a property value may carry (`{"type": <wrapper>, …}`), a closed set split
   * across two enforcers:
   *
   * - `CollaborationReducer`'s `propertyWrapperIssue` owns seventeen and their shapes, on
   *   `SetProperty` only.
   * - `list` and `binding` (repetition rows and row fields) arrive on inserts and are checked by
   *   `inspectUiBuilderArgumentBindings`.
   *
   * Without this union, an invented wrapper could pass every local lane and fail only at server
   * commit. `WrapperVocabularyTest` checks it against the fixture corpus in both directions.
   */
  val WRAPPER_TYPES: Set<String> =
    setOf(
      // Literals: `{"type": …, "value": …}`.
      "assetKey",
      "bool",
      "color",
      "colorToken",
      "enum",
      "float",
      "insets",
      "int",
      "shapeToken",
      "string",
      "typographyToken",
      // Carry a shape of their own, checked by `propertyWrapperIssue`.
      "object",
      "state",
      "stateEquals",
      "padding",
      "adaptiveGrid",
      // A catalog string resource, `{"type": "resource", "resourceKey": …}`; exported through the
      // catalog's declared accessor (`CatalogScreenStructure.stringResources`).
      "resource",
      // A typed adapter's sealed-type value, `{"type": "union", "case": …, "fields"?: {…}}`, and a
      // nested null inside a list, object or union (on a node itself, null is the property unset).
      "union",
      "null",
      // The repetition pair, checked by `inspectUiBuilderArgumentBindings`.
      "list",
      "binding",
      // Computed values the player evaluates, shaped by `UiExpressions.shapeIssue`.
      UiExpressions.EXPR,
      UiExpressions.SYSTEM,
    )

  /**
   * The `statusSemantics` entry listing the asset keys a catalog's canvas can draw, as `{"keys":
   * […]}` (`CatalogCapabilityV1` cannot grow a field from here). No registry means the asset-key
   * rule does not fire.
   */
  const val ASSET_REGISTRY_KEY: String = "assetRegistry"

  /** The `statusSemantics` entry that lists [CANVAS_COLOR_TOKENS] for a reader of the catalog. */
  const val COLOR_TOKENS_KEY: String = "colorTokens"

  /** The asset keys [statusSemantics] declares, or null when it declares no registry. */
  fun declaredAssetKeys(statusSemantics: JsonObject): Set<String>? =
    declaredStrings(statusSemantics, ASSET_REGISTRY_KEY, "keys")

  /** The theme roles [statusSemantics] lists, or null when it lists none. */
  fun declaredColorTokens(statusSemantics: JsonObject): Set<String>? =
    declaredStrings(statusSemantics, COLOR_TOKENS_KEY, "roles")

  private fun declaredStrings(
    statusSemantics: JsonObject,
    entry: String,
    field: String,
  ): Set<String>? {
    val declared = (statusSemantics[entry] as? JsonObject)?.get(field) as? JsonArray ?: return null
    return declared.mapNotNullTo(linkedSetOf()) {
      (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }
  }

  private val COLOR_LITERAL = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
}
