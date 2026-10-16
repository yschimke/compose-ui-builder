package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Which Kotlin emitter a catalog's designs export through.
 *
 * Built-in catalogs are routed by id and platform (`RecordFreeExport`). Under [CatalogOwnership]
 * the catalog states it in its `composeSourceExport` declaration, so any id reaches the right
 * emitter and a catalog declaring nothing gets no export. Emitters stay in this repository;
 * catalogs name an interpreter, never code (see [CatalogComposeSourceExportAdapters]). These ids
 * are kept out of that registry's `Strategy` to avoid a source break for hosts.
 */
public object CatalogExportRouting {

  /** A Wear screen: `WearScreenCodeExporter` over a `frame/round-screen` scaffold. */
  public const val WEAR_SCREEN: String = "wear-compose-screen"

  /** A Remote Compose widget: `WearWidgetCodeExporter` and the inline Remote content emitter. */
  public const val REMOTE_COMPOSE: String = "remote-compose"

  /** An A2UI surface: the A2UI messages and the Kotlin program that sends them. */
  public const val A2UI: String = "a2ui-program"

  /** A host-owned Lovelace config, exported through JSON only. */
  public const val LOVELACE: String = "lovelace-config"

  /**
   * A phone launcher widget: `LauncherWidgetCodeExporter`'s `RemoteComposeWidget`, rooted on the
   * catalog's own screen root and previewed at its `frame.geometry.sizesDp`, whatever the root is
   * called.
   */
  public const val LAUNCHER_WIDGET: String = "remote-compose-launcher-widget"

  /**
   * The trait marking the component a catalog's launcher widgets are rooted on; under
   * [LAUNCHER_WIDGET] a design rooted on anything else is refused.
   */
  public const val LAUNCHER_HOST_TRAIT: String = "LauncherWidgetHost"

  /** The first version of each record-free lane. */
  public const val V1: Int = 1

  private val recordFree: Map<Pair<String, Int>, UiBuilderCatalogPlatform> =
    mapOf(
      (WEAR_SCREEN to V1) to UiBuilderCatalogPlatform.WEAR,
      (REMOTE_COMPOSE to V1) to UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      (A2UI to V1) to UiBuilderCatalogPlatform.A2UI,
      (LOVELACE to V1) to UiBuilderCatalogPlatform.LOVELACE,
      (LAUNCHER_WIDGET to V1) to UiBuilderCatalogPlatform.REMOTE_COMPOSE,
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
      is Route.ComponentRecords -> true
      is Route.RecordFree -> route.platform != UiBuilderCatalogPlatform.LOVELACE
      is Route.BuiltIn -> route.platform != UiBuilderCatalogPlatform.LOVELACE && builtIn()
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

  /**
   * [catalog]'s `statusSemantics.frame.geometry.sizesDp`, in order, or empty for a device-sized
   * catalog. Unreadable rows are skipped, as `UiBuilderFrameGeometry.sizes` does.
   */
  public fun frameSizes(catalog: CatalogCapabilityV1): List<CatalogFrameSize> =
    frameSizes(catalog.statusSemantics)

  /** [frameSizes] read from a `statusSemantics` block, or a policy file of the same shape. */
  public fun frameSizes(statusSemantics: JsonObject): List<CatalogFrameSize> {
    val geometry =
      ((statusSemantics["frame"] as? JsonObject)?.get("geometry") as? JsonObject)
        ?: return emptyList()
    val rows = geometry["sizesDp"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row ->
      val size = row as? JsonObject ?: return@mapNotNull null
      val width = (size["widthDp"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
      val height = (size["heightDp"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
      if (width <= 0 || height <= 0) return@mapNotNull null
      CatalogFrameSize(
        widthDp = width,
        heightDp = height,
        label =
          (size["label"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: "${width}x${height}dp",
      )
    }
  }

  /** The components of [catalog] that carry [LAUNCHER_HOST_TRAIT]. */
  public fun launcherRoots(catalog: CatalogCapabilityV1): Set<String> =
    catalog.components
      .filter { LAUNCHER_HOST_TRAIT in it.traits }
      .mapTo(linkedSetOf()) { it.componentId }

  /**
   * [launcherRoots] read from a policy's `builtins`, for a catalog not yet composed into a
   * capability — the shape its repository writes.
   */
  public fun launcherRoots(policy: JsonObject): Set<String> =
    (policy["builtins"] as? JsonObject)
      .orEmpty()
      .filterValues { builtin ->
        ((builtin as? JsonObject)?.get("traits") as? JsonArray).orEmpty().any {
          (it as? JsonPrimitive)?.contentOrNull == LAUNCHER_HOST_TRAIT
        }
      }
      .keys
}

/** One size a catalog declares its designs are authored at: `{widthDp, heightDp, label}`. */
public data class CatalogFrameSize(
  public val widthDp: Int,
  public val heightDp: Int,
  public val label: String,
)
