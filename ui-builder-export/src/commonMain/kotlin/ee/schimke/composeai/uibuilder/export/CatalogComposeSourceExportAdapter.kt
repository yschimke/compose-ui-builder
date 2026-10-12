package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1

/**
 * A source-export interpreter the builder ships and a catalog may select by a versioned id. A
 * catalog declares a name, never Kotlin; the host supplies the catalog's component records to the
 * selected strategy.
 */
public data class CatalogComposeSourceExportAdapter(
  public val id: String,
  public val version: Int,
  public val strategy: Strategy,
) {
  /** The source model an adapter interprets. More strategies may be added without changing JSON. */
  public enum class Strategy {
    /** Generate calls from the catalog's discovered component records. */
    COMPONENT_RECORDS
  }
}

/**
 * The shipped source-export adapters, keyed by adapter id and version. Not an extension registry:
 * an unknown declaration is an unsupported result, never code to load.
 */
public object CatalogComposeSourceExportAdapters {
  /** Adapter for AndroidX Compose Material 3 catalogs. */
  public const val COMPOSE_MATERIAL3: String = "compose-material3"

  /** First stable Compose Material 3 source projection. */
  public const val COMPOSE_MATERIAL3_V1: Int = 1

  /**
   * The same component-record export for any catalog, named for what it does rather than for one
   * library: a catalog whose components publish typed export projections (and, for a structured
   * screen, a `code` block's frame, locals and scopes) selects this, whatever platform it is for.
   */
  public const val COMPONENT_RECORDS: String = "compose-component-records"

  /** First version of [COMPONENT_RECORDS]. */
  public const val COMPONENT_RECORDS_V1: Int = 1

  private val adapters =
    listOf(
      CatalogComposeSourceExportAdapter(
        id = COMPOSE_MATERIAL3,
        version = COMPOSE_MATERIAL3_V1,
        strategy = CatalogComposeSourceExportAdapter.Strategy.COMPONENT_RECORDS,
      ),
      CatalogComposeSourceExportAdapter(
        id = COMPONENT_RECORDS,
        version = COMPONENT_RECORDS_V1,
        strategy = CatalogComposeSourceExportAdapter.Strategy.COMPONENT_RECORDS,
      ),
    )

  /**
   * Resolves [catalog]'s published declaration to an interpreter this build understands. The system
   * id plays no part, so catalogs can share an adapter and renames keep exporting.
   */
  public fun resolve(catalog: CatalogCapabilityV1): Resolution =
    resolve(catalog.composeSourceExport)

  /** Resolves a standalone declaration; useful while a host is assembling its catalog pin. */
  public fun resolve(declaration: ComposeSourceExportCapabilityV1?): Resolution =
    when {
      declaration == null -> Resolution.NotDeclared
      else ->
        adapters
          .firstOrNull { it.id == declaration.adapter && it.version == declaration.version }
          ?.let(Resolution::Supported)
          ?: Resolution.Unsupported(declaration.adapter, declaration.version)
    }

  /** The complete outcome so callers never turn a missing declaration into a guessed fallback. */
  public sealed interface Resolution {
    /** The catalog does not claim to export Compose source. */
    public data object NotDeclared : Resolution

    /** The catalog selected an adapter this build ships. */
    public data class Supported(public val adapter: CatalogComposeSourceExportAdapter) : Resolution

    /** The catalog selected an adapter/version this build does not ship. */
    public data class Unsupported(public val adapter: String, public val version: Int) : Resolution
  }
}
