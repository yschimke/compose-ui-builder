package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1

/**
 * Which Kotlin emitter a catalog's designs export through.
 *
 * Today a host decides that from the catalog's id and platform: `RecordFreeExport` routes a Wear
 * catalog to the Wear screen emitter, a Remote Compose catalog to the widget emitter and an A2UI
 * catalog to the A2UI program emitter, and `CATALOG_SYSTEM_IDS` names the catalogs whose designs
 * export without a record. Under [CatalogOwnership] the catalog states it instead, in the same
 * `composeSourceExport` declaration `compose-material3` already uses, so a catalog published under
 * another id reaches the same emitter and a catalog that declares nothing is offered no export
 * rather than a guessed one.
 *
 * The emitters stay in this repository: a catalog names an interpreter this build ships, never code
 * (see [CatalogComposeSourceExportAdapters]). The record-free ids are kept out of that registry on
 * purpose — adding a `Strategy` member is a source break for every host with an exhaustive `when`
 * over it, and a host that has not shipped one of these ids refuses a catalog that declares it — so
 * a catalog must not declare one until the server it is served by reads it.
 */
public object CatalogExportRouting {

  /** A Wear screen: `WearScreenCodeExporter` over a `frame/round-screen` scaffold. */
  public const val WEAR_SCREEN: String = "wear-compose-screen"

  /** A Remote Compose widget: `WearWidgetCodeExporter` and the inline Remote content emitter. */
  public const val REMOTE_COMPOSE: String = "remote-compose"

  /** An A2UI surface: the A2UI messages and the Kotlin program that sends them. */
  public const val A2UI: String = "a2ui-program"

  /** The first version of each record-free lane. */
  public const val V1: Int = 1

  private val recordFree: Map<Pair<String, Int>, UiBuilderCatalogPlatform> =
    mapOf(
      (WEAR_SCREEN to V1) to UiBuilderCatalogPlatform.WEAR,
      (REMOTE_COMPOSE to V1) to UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      (A2UI to V1) to UiBuilderCatalogPlatform.A2UI,
    )

  /** Where a catalog's designs export. */
  public sealed interface Route {
    /** The generic record projection, via a [CatalogComposeSourceExportAdapters] adapter. */
    public data class ComponentRecords(public val adapter: CatalogComposeSourceExportAdapter) :
      Route

    /**
     * A dedicated emitter, reached through `RecordFreeExport` as if the catalog were [platform].
     */
    public data class RecordFree(
      public val adapter: String,
      public val platform: UiBuilderCatalogPlatform,
    ) : Route

    /** The id- and platform-based routing every catalog not under the flag keeps. */
    public data class BuiltIn(public val platform: UiBuilderCatalogPlatform) : Route

    /** An owned catalog that states no export: none is offered. */
    public data object NotDeclared : Route

    /** An owned catalog that names an emitter this build does not ship. */
    public data class Unsupported(public val adapter: String, public val version: Int) : Route
  }

  /** [catalog]'s route under [ownership]. Off the flag, always [Route.BuiltIn]. */
  public fun route(catalog: CatalogCapabilityV1, ownership: CatalogOwnership): Route {
    val platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics)
    if (!ownership.owns(catalog.benchmark.catalogSystemId)) return Route.BuiltIn(platform)
    val declaration = catalog.composeSourceExport ?: return Route.NotDeclared
    recordFree[declaration.adapter to declaration.version]?.let {
      return Route.RecordFree(declaration.adapter, it)
    }
    return when (val resolved = CatalogComposeSourceExportAdapters.resolve(declaration)) {
      is CatalogComposeSourceExportAdapters.Resolution.Supported ->
        Route.ComponentRecords(resolved.adapter)
      is CatalogComposeSourceExportAdapters.Resolution.Unsupported ->
        Route.Unsupported(resolved.adapter, resolved.version)
      CatalogComposeSourceExportAdapters.Resolution.NotDeclared -> Route.NotDeclared
    }
  }

  /**
   * Whether [route] offers Compose export at all — the question a host's `composeExportFor`
   * answers. [Route.BuiltIn] defers to the host's existing answer, [builtIn].
   */
  public fun exportsCompose(route: Route, builtIn: () -> Boolean): Boolean =
    when (route) {
      is Route.ComponentRecords,
      is Route.RecordFree -> true
      is Route.BuiltIn -> builtIn()
      Route.NotDeclared,
      is Route.Unsupported -> false
    }

  /**
   * The platform `RecordFreeExport.generate` is asked as. An owned catalog is routed by its
   * declaration, whatever platform word it states; a built-in one by the platform it states.
   */
  public fun recordFreePlatform(route: Route): UiBuilderCatalogPlatform? =
    when (route) {
      is Route.RecordFree -> route.platform
      is Route.BuiltIn -> route.platform
      is Route.ComponentRecords -> UiBuilderCatalogPlatform.MOBILE
      Route.NotDeclared,
      is Route.Unsupported -> null
    }
}
