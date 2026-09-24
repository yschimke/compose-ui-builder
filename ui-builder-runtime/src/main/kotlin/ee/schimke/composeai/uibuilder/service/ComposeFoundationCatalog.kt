package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `compose-foundation`: the builder's own vocabulary, curated per platform.
 *
 * A box, a gradient and an image are not Material 3's and not Wear Material 3's — they are
 * `androidx.compose.foundation` and `androidx.compose.ui`, which publish one of each rather than
 * one per design system. m3-catalog says the same from the other side: it declares no builtins at
 * all, on the stated grounds that doing so "would be this catalog claiming to own the builder's own
 * vocabulary". Somebody still has to own them, and for `layout/`, `shape/` and `asset/` this is
 * who.
 *
 * **The `remote-compose/` seams are not among them.** Remote Compose is a different library with a
 * catalog of its own to describe it — so `document`, `inline` and `custom` arrive here through the
 * `seams` parameter rather than being declared here. The curations below still say WHICH seam a
 * platform takes and WHERE it sits on the shelf, because that is a statement about the palette
 * rather than about the component; the component is Remote Compose's. A seam the source does not
 * have is left off the palette rather than invented.
 *
 * **Local for now, external eventually.** The per-platform sets below are derived here from the
 * packaged Material 3 catalog because that is where the components are currently declared. The
 * intent of [#819](https://github.com/yschimke/compose-preview-server/issues/819) is that this
 * becomes a catalog published like any other, at which point this file loads a file instead of
 * building a list. The two synthesised per-platform generators it replaced as the donor are gone.
 *
 * ## Why the set is per platform rather than one list
 *
 * Because a palette entry that cannot be exported is worse than a missing one — it is only
 * discovered at the end. `WearScreenCodeExporter` refuses `layout/lazy-grid`, `layout/scaffold` and
 * the shapes with "no Wear Compose Material 3 counterpart this generator can write", so handing a
 * watch palette the mobile seventeen would guarantee a failed export for any design that used one.
 * The curation each platform already had is the curation kept here, component for component.
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
            else declared.getValue(id)
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
 *
 * `ComposeFoundationFaithfulnessTest` asserts each of these against what the retired synthesised
 * catalog of that platform donated, frozen in its committed fixture.
 */
private class FoundationCuration(
  /**
   * Null means "every builder-namespace component the packaged catalog declares, in its order".
   *
   * So a mobile palette's SEAM ids also come from the packaged catalog, even though the seam
   * components come from the seam source. That is right while the packaged catalog still declares
   * all three; when it stops (#819), mobile needs an explicit list here the way wear and
   * remote-compose already have one, or it silently loses them.
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
    // Mobile takes the packaged declarations unchanged, on the packaged shelves. This is the set
    // m3-catalog is served today, and the reason a published catalog that correctly declines to
    // claim `layout/box` still ships a shelf with a box on it.
    MOBILE_PLATFORM to
      FoundationCuration(ids = null, menu = { base -> base.statusSemantics.componentMenuObject() }),
    // Wear borrows four foundation components, and nothing else.
    //
    // The three Remote Compose seams were here too, and they were offered rather than usable: the
    // whole-screen Wear generator has no case for any of them, so a design that placed one was
    // refused at export. That is the failure a curated borrow set exists to prevent — a palette
    // entry that cannot be exported is worse than a missing one, because it is only discovered at
    // the end, after the design is drawn.
    //
    // Withdrawn rather than fixed, for now, because making them work is a question about what a
    // Remote Compose seam even means inside a Wear SCREEN — these are widget vocabulary, and the
    // screen generator writes plain Compose. Tracked to come back once that has an answer.
    //
    // The four that remain are the ones that are genuinely shared: `layout/box`, `layout/column`,
    // `layout/row` and `asset/image` are `androidx.compose.foundation` and `androidx.compose.ui`,
    // which both platforms have, and all four export.
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
    // A Remote Compose document body. `shape/radial-gradient` is deliberately absent —
    // `RemoteContentEmitter` writes linear gradient chains and has an authored refusal for
    // `asset/image`, while a radial one would fall through to a generic "is not a widget background
    // brush", which is a worse answer than not offering it.
    "remote-compose" to
      FoundationCuration(
        ids =
          listOf(
            "layout/box",
            "layout/column",
            "layout/row",
            "layout/for-each",
            "remote-compose/document",
            REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
            "shape/linear-gradient",
            "asset/image",
          ),
        // The narrowing the retired synthesised `remote-m3` applied, from the one place it is
        // written.
        curate = { component -> component.narrowedForRemoteAuthoring() },
        menu = { base -> remoteM3ComponentMenu(base.statusSemantics) },
      ),
  )

private fun JsonObject.componentMenuObject(): JsonObject =
  (this["componentMenu"] as? JsonObject) ?: JsonObject(emptyMap())
