package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a catalog's `statusSemantics.code` block says about the structure around its projected
 * records (compose-ai-tools' `TYPED_STRUCTURAL_TEMPLATES.md`): the `screen-root` [frame] written
 * around the screen's root, the [imports] that frame's own text needs, the [locals] it declares for
 * the records to read, and the [scopes] a role opens (`list-item`: the item body's receiver).
 * [rootComponent] is the catalog's `screen-root` builtin (`wear-m3/screen-scaffold`): with a
 * [frame], a design rooted on it exports as the frame around its `content` child
 * ([ScreenRootFrame]).
 *
 * [stringResources] is the catalog's Compose Multiplatform resource accessor
 * (`code.stringResources`, for example `ee.schimke.m3catalog.generated.resources.Res`). With it a
 * `resource` value on a text parameter exports as `stringResource(Res.string.<key>)`, so a design's
 * copy can be the catalog's translated strings rather than literals. Without it a `resource` value
 * is refused, as before.
 *
 * Validated when the catalog is published; read here as it was published, with anything unreadable
 * left out, as [CatalogExportProjections] does.
 */
data class CatalogScreenStructure(
  val frame: String? = null,
  val imports: List<String> = emptyList(),
  val locals: Map<String, String> = emptyMap(),
  val scopes: Map<String, String> = emptyMap(),
  val rootComponent: String? = null,
  val stringResources: String? = null,
  /**
   * The `screen-root` frames for a root that fills other slots, by the set of slots filled:
   * `code.templates["screen-root+edgeButton"]` is the frame around a screen with an edge button. A
   * template has no conditionals, so each set the catalog supports is its own frame.
   */
  val slotFrames: Map<Set<String>, String> = emptyMap(),
) {
  /** The published six-field constructor, kept for binaries compiled against it. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    frame: String?,
    imports: List<String>,
    locals: Map<String, String>,
    scopes: Map<String, String>,
    rootComponent: String?,
    stringResources: String?,
  ) : this(frame, imports, locals, scopes, rootComponent, stringResources, emptyMap())

  /** The frame for a root filling [slots] besides `content`, or null when none is published. */
  fun frameFor(slots: Set<String>): String? = if (slots.isEmpty()) frame else slotFrames[slots]

  /** The published four-field constructor, kept for binaries compiled against it. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    frame: String? = null,
    imports: List<String> = emptyList(),
    locals: Map<String, String> = emptyMap(),
    scopes: Map<String, String> = emptyMap(),
  ) : this(frame, imports, locals, scopes, null, null, emptyMap())

  /** The scope a projection's item member composes its child under, if the catalog attests it. */
  val itemScope: String?
    get() = scopes[LIST_ITEM]

  companion object {
    private const val LIST_ITEM = "list-item"
    private const val SCREEN_ROOT = "screen-root"

    /** [CatalogScreenStructure] from a `statusSemantics` block, or a policy file of that shape. */
    fun from(statusSemantics: JsonObject): CatalogScreenStructure {
      val code = statusSemantics["code"] as? JsonObject ?: return CatalogScreenStructure()
      // One screen-root builtin or none: two would make "the screen's root" ambiguous.
      val rootComponent =
        (statusSemantics["builtins"] as? JsonObject)
          .orEmpty()
          .filter { (_, builtin) ->
            ((builtin as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull == SCREEN_ROOT
          }
          .keys
          .singleOrNull()
      fun strings(name: String): Map<String, String> =
        (code[name] as? JsonObject)
          .orEmpty()
          .mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it }
          }
          .toMap()
      val templates = strings("templates")
      return CatalogScreenStructure(
        frame = templates[SCREEN_ROOT],
        imports =
          (code["imports"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull
          },
        locals = strings("locals"),
        scopes = strings("scopes"),
        rootComponent = rootComponent,
        stringResources =
          (code["stringResources"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        slotFrames =
          templates
            .filterKeys { it.startsWith("$SCREEN_ROOT+") }
            .mapKeys { (key, _) -> key.split('+').drop(1).toSet() },
      )
    }
  }
}

/**
 * The structure [ScreenDocumentProjection] needs to wrap a projected DSL parameter's children: each
 * record's DSL receivers (`record canonical id` → `parameter` → receiver type, read off the
 * component record) and the [itemScope] the item member's body composes under.
 */
data class ProjectedStructure(
  val dslReceivers: Map<String, Map<String, String>> = emptyMap(),
  val itemScope: String? = null,
  /** [CatalogScreenStructure.stringResources], for lowering `resource` values. */
  val stringResources: String? = null,
  /**
   * The design nodes a screen frame writes into its holes besides `content`, by slot
   * ([ScreenRootFrame.Unwrapped.Document.frameSlots]); projected in the same pass as the root.
   */
  val frameSlots: Map<String, List<String>> = emptyMap(),
) {
  /** The published three-field constructor, kept for binaries compiled against it. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    dslReceivers: Map<String, Map<String, String>>,
    itemScope: String?,
    stringResources: String?,
  ) : this(dslReceivers, itemScope, stringResources, emptyMap())

  /** The published two-field constructor, kept for binaries compiled against it. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    dslReceivers: Map<String, Map<String, String>> = emptyMap(),
    itemScope: String? = null,
  ) : this(dslReceivers, itemScope, null)
}
