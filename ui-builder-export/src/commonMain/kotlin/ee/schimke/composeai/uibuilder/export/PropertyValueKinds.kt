package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What kind of value a catalog property holds, decided by its name.
 *
 * The catalog declares `m3/text.color` as `jsonType: "string"` and stops: `jsonType` is the JSON
 * scalar the wrapper carries, not what the value *means*, and the published wire type has no field
 * for the second. So the reducer committed a colour written as `{"type":"string"}`, the canvas drew
 * it, and the export refused it a call later with a sentence about `Text` — one authored mistake
 * caught by the most expensive actor, on a different call, in a message that named nothing the
 * author could act on (yschimke/compose-preview-server#476, #487).
 *
 * The decision, made once here: **a property's name states its value kind.** `color` and `…Color`
 * hold a colour; `assetKey` holds a key into the catalog's asset registry; a property with
 * `allowedValues` is an enumeration (the rule #339 settled). Both reducers — the editor's
 * `CapabilityValidator` and the server's `CurrentM3UiBuilderCatalogExecutor`, which cannot reach
 * this module and mirrors it — ask this on every write, and refuse at commit what the canvas could
 * not draw. Named rather than declared per property because the catalog wire shape is published
 * from another repository and the whole vocabulary already agrees: every colour property in every
 * catalog here is spelled this way, and the inspector's `…Dp` rule is the same kind of decision.
 *
 * The line is "the canvas cannot draw it", not "the generator cannot write it". A mockup that does
 * not export is a legitimate document (#477, #488); a document that cannot render is not.
 */
object PropertyValueKinds {
  /** Whether [property] holds a colour — `color`, `containerColor`, `startColor`, and so on. */
  fun isColour(property: String): Boolean = property == "color" || property.endsWith("Color")

  /** Whether [property] names an asset the canvas has to resolve. */
  fun isAssetKey(property: String): Boolean = property == "assetKey"

  /**
   * The theme roles the canvas draws, in one place.
   *
   * The same fourteen names as the renderer's `colorTokenOrNull` table and the inspector's colour
   * suggestions: a role the export can write (`primaryContainer` is in `MaterialTheme.colorScheme`)
   * but this list does not carry is refused at commit, because the canvas would not draw it — the
   * renderer used to throw on exactly that, and one node's token then failed the whole frame.
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
   * Every wrapper type a property value may carry, in one place.
   *
   * A property value is `{"type": <wrapper>, …}` and the set of wrappers is closed. It is assembled
   * from two enforcers rather than one, which is the fact that made it worth writing down:
   *
   * - `CollaborationReducer`'s `propertyWrapperIssue` owns sixteen of them, and owns their *shapes*
   *   — how many keys, which are numeric. It runs on `SetProperty` and nowhere else.
   * - `list` and `binding` are the repetition pair — a `layout/for-each`'s rows, and a row field
   *   read inside the loop. They arrive on **inserts**, which `propertyWrapperIssue` never sees,
   *   and their shapes are checked by `inspectUiBuilderArgumentBindings` instead.
   *
   * So neither enforcer knows the whole vocabulary, and until this existed nothing did. A value
   * with an invented type could be hand-authored into an operations fixture and pass every local
   * lane: `{"type":"fixedGrid","columns":7}` was kept by `UiBuilderReducer.replay`, reported clean
   * by [CapabilityValidator][ee.schimke.composeai.uibuilder.capability.CapabilityValidator],
   * exported as Compose, drawn by the renderer and asserted green by `DesignFixturesTest` — then
   * refused by the server at commit with a kotlinx-serialization registration message naming a
   * sealed base class, which is a sentence about the wire format rather than about the design
   * (#901). The catalog could not have caught it either: `layout/lazy-grid.columns` is declared
   * `object` and stops there, which is precisely the shape an invented wrapper claims to be.
   *
   * `WrapperVocabularyTest` holds this against the corpus in both directions — every wrapper in
   * every committed fixture is named here, and every name here is one some enforcer accepts — so a
   * seventeenth wrapper added to a writer and not to this set fails the build rather than a commit.
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
      // The repetition pair, checked by `inspectUiBuilderArgumentBindings`.
      "list",
      "binding",
    )

  /**
   * The `statusSemantics` entry under which a catalog lists the asset keys its canvas can draw, as
   * `{"keys": […]}`. `statusSemantics` rather than a property field for the reason every other
   * catalog fact that is not on the wire type sits there: `CatalogCapabilityV1` is published from
   * compose-preview-contracts and cannot grow a field from here. A catalog that declares no
   * registry says nothing about keys, and the asset-key rule does not fire.
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
