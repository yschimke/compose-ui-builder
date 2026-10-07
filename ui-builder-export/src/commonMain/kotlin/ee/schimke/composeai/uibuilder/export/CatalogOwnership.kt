package ee.schimke.composeai.uibuilder.export

/**
 * Which catalogs answer for themselves: the one flag behind the catalog-owned cutover.
 *
 * `--ui-builder-published-catalogs` already decides where a catalog's *definition* comes from, and
 * production has run every catalog published since 3.27.0. What it never covered is everything else
 * this repository still knows about a catalog by id: the seed templates it offers, the order and
 * copy of the new-design chooser, the Kotlin emitter its designs export through and the synthesised
 * catalog it falls back to when its published file is unusable. A catalog this names gets all of
 * those from what it publishes — its `ui-builder.json`, its `templates` documents and its
 * `composeSourceExport` declaration — and nothing from a `when` on its id. A catalog it does not
 * name behaves exactly as it did before the flag existed.
 *
 * The flag defaults to [NONE] everywhere, so adding a reader under it changes no deployment.
 * `CatalogCutoverReadinessTest` turns it on for every catalog against the files their repositories
 * publish today, which is the evidence the switch is safe before anybody flips it, and its gap
 * ledger is the list of what still is not. See `docs/design/UI_BUILDER_CATALOG_CUTOVER.md`.
 *
 * Spelled like the server's lever (`all`, `none`, or a comma-separated list) so an operator reads
 * the two the same way.
 */
public class CatalogOwnership private constructor(private val owned: Set<String>?) {

  /** Whether [catalogSystemId]'s seeds, chooser card, export route and definition are its own. */
  public fun owns(catalogSystemId: String): Boolean = owned?.contains(catalogSystemId) ?: true

  /** True for [NONE]: nothing reads a catalog-owned path, the shipped behaviour. */
  public val isNone: Boolean
    get() = owned?.isEmpty() == true

  /** The flag's value as it would be written on a command line. */
  public val wireValue: String
    get() =
      when {
        owned == null -> ALL_VALUE
        owned.isEmpty() -> NONE_VALUE
        else -> owned.sorted().joinToString(",")
      }

  override fun equals(other: Any?): Boolean = other is CatalogOwnership && other.owned == owned

  override fun hashCode(): Int = owned.hashCode()

  override fun toString(): String = "CatalogOwnership($wireValue)"

  public companion object {
    private const val ALL_VALUE = "all"
    private const val NONE_VALUE = "none"
    private val CATALOG_ID = Regex("[a-z0-9][a-z0-9._-]*")

    /** The default: every catalog keeps the builder's built-in seeds, chooser and routing. */
    public val NONE: CatalogOwnership = CatalogOwnership(emptySet())

    /** Every catalog answers for itself, including one this build has never heard of. */
    public val ALL: CatalogOwnership = CatalogOwnership(null)

    /** Exactly [catalogSystemIds]. */
    public fun of(catalogSystemIds: Set<String>): CatalogOwnership {
      require(catalogSystemIds.all(CATALOG_ID::matches)) {
        "catalog ownership names an invalid catalog id: ${catalogSystemIds.filterNot(CATALOG_ID::matches)}"
      }
      return CatalogOwnership(catalogSystemIds.toSet())
    }

    /**
     * `all`, `none`, or `<id>[,<id>]`. Null or blank is [NONE]: an unset environment variable must
     * mean "not switched", never "switched for everything".
     */
    public fun parse(value: String?): CatalogOwnership {
      val raw = value?.trim().orEmpty()
      return when (raw.lowercase()) {
        "",
        NONE_VALUE -> NONE
        ALL_VALUE -> ALL
        else -> {
          val ids = raw.split(',').map(String::trim).filter(String::isNotEmpty)
          require(ids.distinct().size == ids.size) {
            "catalog ownership names a catalog twice: $raw"
          }
          of(ids.toSet())
        }
      }
    }
  }
}
