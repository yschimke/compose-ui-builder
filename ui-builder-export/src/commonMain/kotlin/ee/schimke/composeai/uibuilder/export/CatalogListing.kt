package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether a served catalog is offered to people.
 *
 * An **unlisted** catalog is served like any other — it resolves by id, so a design pinned to it
 * opens, `/ui-builder/<catalog>/…` reaches it, and an MCP client can create, edit and export
 * against it — but the catalog chooser and the New design list leave it out. It exists for catalogs
 * a deployment runs beside the real one without advertising, such as a typed-export pilot.
 *
 * The mark travels on the catalog itself, as `statusSemantics.unlisted: true`, so every reader of
 * `list-catalogs` (the editor, an MCP client) sees the same answer without a protocol change. The
 * host sets it through the runtime's `unlistedCatalogIds`; a published catalog file may also
 * declare it. Unmarked catalogs are listed, which is the default.
 */
public object CatalogListing {

  /** The `statusSemantics` key carrying the mark: `true` means unlisted. */
  public const val UNLISTED_KEY: String = "unlisted"

  private val CATALOG_ID = Regex("[a-z0-9][a-z0-9._-]*")

  /** True when [catalog] is marked unlisted. Anything but a JSON `true` is listed. */
  public fun isUnlisted(catalog: CatalogCapabilityV1): Boolean =
    (catalog.statusSemantics[UNLISTED_KEY] as? JsonPrimitive)
      ?.takeUnless { it.isString }
      ?.booleanOrNull == true

  /** [catalogs] without the unlisted ones, in the same order. */
  public fun listed(catalogs: List<CatalogCapabilityV1>): List<CatalogCapabilityV1> =
    catalogs.filterNot(::isUnlisted)

  /** [catalog] with the unlisted mark set; itself when already marked. */
  public fun markUnlisted(catalog: CatalogCapabilityV1): CatalogCapabilityV1 =
    if (isUnlisted(catalog)) catalog
    else
      catalog
        .newBuilder()
        .also {
          it.statusSemantics =
            JsonObject(catalog.statusSemantics + (UNLISTED_KEY to JsonPrimitive(true)))
        }
        .build()

  /**
   * A host flag's value: a comma-separated list of catalog ids. Null or blank is the empty set, so
   * an unset setting lists everything.
   */
  public fun parse(value: String?): Set<String> {
    val ids = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    require(ids.all(CATALOG_ID::matches)) {
      "unlisted catalogs name an invalid catalog id: ${ids.filterNot(CATALOG_ID::matches)}"
    }
    return ids.toSet()
  }
}
