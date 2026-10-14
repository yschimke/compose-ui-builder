package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `compose-foundation`: the builder's own vocabulary (`layout/`, `shape/`, `asset/` from
 * `androidx.compose.foundation`/`ui`), curated per platform. m3-catalog declines to own these.
 *
 * The `remote-compose/` seams are Remote Compose's and arrive through `seams`; curations only say
 * which seam a platform takes and where it is shelved. A seam the source lacks is left off.
 *
 * Derived from the packaged Material 3 catalog for now; intended to become a published catalog,
 * after which the synthesised per-platform generators can go. Per platform because an entry that
 * cannot be exported (e.g. `layout/lazy-grid` on Wear) is worse than a missing one.
 */
internal fun composeFoundationCatalog(
  base: CatalogCapabilityV1,
  platform: String,
  seams: Map<String, ComponentCapabilityV1>,
): CatalogCapabilityV1 {
  val curation = FOUNDATION_CURATIONS[platform] ?: FOUNDATION_CURATIONS.getValue(MOBILE_PLATFORM)
  val declared = base.components.associateBy { it.componentId }
  val registryKey = CurrentM3UiBuilderCatalogExecutor.ASSET_REGISTRY_KEY
  return base
    .newBuilder()
    .also {
      it.components =
        curation.componentIds(base).mapNotNull { id ->
          val component =
            if (id.startsWith(REMOTE_COMPOSE_NAMESPACE)) seams[id] ?: return@mapNotNull null
            else
              declared[id]
                ?: remoteOnlyLayout(id, declared)
                ?: remoteDrawComponent(id, declared)
                ?: declared.getValue(id)
          curation.curate(component)
        }
      it.statusSemantics =
        JsonObject(
          buildMap<String, JsonElement> {
            put(CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY, JsonPrimitive(platform))
            base.statusSemantics[registryKey]?.let { put(registryKey, it) }
            put("componentMenu", curation.menu(base))
          }
        )
    }
    .build()
}

private const val MOBILE_PLATFORM = CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM

/**
 * One platform's share of the foundation: which components, in what shape, on which shelves.
 * `ComposeFoundationFaithfulnessTest` checks each against the synthesised catalog's donation.
 */
private class FoundationCuration(
  /**
   * Null means "every builder-namespace component the packaged catalog declares, in its order",
   * including seam ids. If the packaged catalog stops declaring seams, mobile needs an explicit
   * list like wear and remote-compose, or it silently loses them.
   */
  private val ids: List<String>?,
  val curate: (ComponentCapabilityV1) -> ComponentCapabilityV1 = { it },
  val menu: (CatalogCapabilityV1) -> JsonObject,
) {
  fun componentIds(base: CatalogCapabilityV1): List<String> =
    ids
      ?: base.components
        .map { it.componentId }
        .filter { id -> BUILDER_NAMESPACES.any { namespace -> id.startsWith(namespace) } }
}

private val FOUNDATION_CURATIONS =
  mapOf(
    // Mobile takes the packaged declarations unchanged, on the packaged shelves.
    MOBILE_PLATFORM to
      FoundationCuration(ids = null, menu = { base -> base.statusSemantics.componentMenuObject() }),
    // Wear borrows only the four shared foundation components (`layout/box`, `layout/column`,
    // `layout/row`, `asset/image`), all exportable. The Remote Compose seams are withdrawn: the
    // screen generator cannot write them.
    "wear" to
      FoundationCuration(
        ids = listOf("layout/box", "layout/column", "layout/row", "asset/image"),
        // Every remaining borrow is foundation, so every one takes [WEAR_FOUNDATION_NOTE]. The
        // `REMOTE_COMPOSE_BORROWED_AS_THEMSELVES` branch that stood here went with the seams.
        curate = { component ->
          component
            .newBuilder()
            .also {
              it.wasm = component.wasm.newBuilder().also { it.notes = WEAR_FOUNDATION_NOTE }.build()
            }
            .build()
        },
        menu = { wearComponentMenu() },
      ),
    // A2UI is a closed vocabulary and neither A2UI exporter handles `layout/`, `shape/` or
    // `asset/`, so offer nothing rather than components that cannot be exported.
    "a2ui" to FoundationCuration(ids = emptyList(), menu = { JsonObject(emptyMap()) }),
    // A Remote Compose document body. No `shape/radial-gradient`: the emitter only writes linear
    // gradient chains.
    "remote-compose" to
      FoundationCuration(
        ids =
          listOf(
            "layout/box",
            "layout/column",
            "layout/row",
            "layout/for-each",
            "layout/flow-row",
            *REMOTE_ONLY_LAYOUT_IDS.toTypedArray(),
            "remote-compose/document",
            REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
            "shape/linear-gradient",
            "asset/image",
            *REMOTE_DRAW_IDS.toTypedArray(),
          ),
        // Narrow to the Remote emitter's supported vocabulary.
        curate = { component -> component.narrowedForRemoteAuthoring().withWidgetProfileNote() },
        menu = { base -> remoteM3ComponentMenu(base.statusSemantics) },
      ),
  )

private fun JsonObject.componentMenuObject(): JsonObject =
  (this["componentMenu"] as? JsonObject) ?: JsonObject(emptyMap())
