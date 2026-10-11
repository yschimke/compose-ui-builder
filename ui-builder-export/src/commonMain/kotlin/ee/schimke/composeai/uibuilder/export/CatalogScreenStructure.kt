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
 * Validated when the catalog is published; read here as it was published, with anything unreadable
 * left out, as [CatalogExportProjections] does.
 */
data class CatalogScreenStructure(
  val frame: String? = null,
  val imports: List<String> = emptyList(),
  val locals: Map<String, String> = emptyMap(),
  val scopes: Map<String, String> = emptyMap(),
  val rootComponent: String? = null,
) {
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
      return CatalogScreenStructure(
        frame = strings("templates")[SCREEN_ROOT],
        imports =
          (code["imports"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull
          },
        locals = strings("locals"),
        scopes = strings("scopes"),
        rootComponent = rootComponent,
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
)
