package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1

/**
 * A source-export interpreter the builder ships and a catalog may select by a versioned id.
 *
 * A catalog declares a name, never Kotlin. The host resolves that name here, then supplies the
 * catalog's published component records to the selected strategy. This keeps source export a
 * catalog contract without turning a published JSON artifact into executable code.
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
 * The shipped source-export adapters, keyed only by catalog-declared adapter id and version.
 *
 * This is intentionally not an extension registry. Hosts must not load executable adapters from a
 * catalog artifact: an unknown declaration is an explicit unsupported result, not code to run.
 */
public object CatalogComposeSourceExportAdapters {
  /** Adapter for AndroidX Compose Material 3 catalogs. */
  public const val COMPOSE_MATERIAL3: String = "compose-material3"

  /** First stable Compose Material 3 source projection. */
  public const val COMPOSE_MATERIAL3_V1: Int = 1

  private val adapters =
    listOf(
      CatalogComposeSourceExportAdapter(
        id = COMPOSE_MATERIAL3,
        version = COMPOSE_MATERIAL3_V1,
        strategy = CatalogComposeSourceExportAdapter.Strategy.COMPONENT_RECORDS,
      )
    )

  /**
   * Resolves [catalog]'s published declaration to an interpreter this build understands.
   *
   * The catalog system id deliberately plays no part in this lookup: two catalogs can select the
   * same adapter, and a renamed delivery system continues to export through its pinned declaration.
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
