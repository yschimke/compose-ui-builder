package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `compose-foundation`: the builder's own vocabulary, curated per platform.
 *
 * A box, a gradient, an image and the Remote Compose seams are not Material 3's and not Wear
 * Material 3's — they are `androidx.compose.foundation` and `androidx.compose.ui`, which publish
 * one of each rather than one per design system. m3-catalog says the same from the other side: it
 * declares no builtins at all, on the stated grounds that doing so "would be this catalog claiming
 * to own the builder's own vocabulary". Somebody still has to own them, and this is who.
 *
 * **Local for now, external eventually.** The per-platform sets below are derived here from the
 * packaged Material 3 catalog because that is where the components are currently declared. The
 * intent of [#819](https://github.com/yschimke/compose-preview-server/issues/819) is that this
 * becomes a catalog published like any other, at which point this file loads a file instead of
 * building a list — and the two synthesised per-platform generators, whose only remaining job is to
 * be this donor, can go.
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
): CatalogCapabilityV1 {
  val curation = FOUNDATION_CURATIONS[platform] ?: FOUNDATION_CURATIONS.getValue(MOBILE_PLATFORM)
  val declared = base.components.associateBy { it.componentId }
  val registryKey = CurrentM3UiBuilderCatalogExecutor.ASSET_REGISTRY_KEY
  return base.copy(
    components = curation.componentIds(base).map { curation.curate(declared.getValue(it)) },
    // Only what a donor is actually read for: `withBuilderVocabulary` takes the asset registry that
    // travels with `asset/image`, and the shelves and shelf ORDER an injected component is filed
    // under. Trimmed to those rather than carrying the whole Material 3 block, so this catalog
    // states what it is for and a future reader does not have to work out which keys matter.
    statusSemantics =
      JsonObject(
        buildMap<String, JsonElement> {
          put(CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY, JsonPrimitive(platform))
          base.statusSemantics[registryKey]?.let { put(registryKey, it) }
          put("componentMenu", curation.menu(base))
        }
      ),
  )
}

private const val MOBILE_PLATFORM = CurrentM3UiBuilderCatalogExecutor.DEFAULT_PLATFORM

/**
 * One platform's share of the foundation: which components, in what shape, on which shelves.
 *
 * `ComposeFoundationFaithfulnessTest` asserts each of these against what the synthesised catalog of
 * that platform donates today, which is what makes the generators deletable rather than merely
 * redundant.
 */
private class FoundationCuration(
  /** Null means "every builder-namespace component the packaged catalog declares, in its order". */
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
    // Wear borrows four foundation components and the three Remote Compose seams, and nothing else
    // — the same seven `wearM3Catalog` borrows, in its order, because the order decides where they
    // land in the insert panel.
    "wear" to
      FoundationCuration(
        ids =
          listOf(
            "layout/box",
            "layout/column",
            "layout/row",
            "asset/image",
            "remote-compose/document",
            REMOTE_COMPOSE_INLINE_COMPONENT_ID,
            REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
          ),
        // The Remote Compose seams are already themselves and keep their own note; everything
        // else takes [WEAR_FOUNDATION_NOTE], which the generator this replaces still shares, so
        // the two cannot drift apart while both exist.
        curate = { component ->
          if (component.componentId in REMOTE_COMPOSE_BORROWED_AS_THEMSELVES) component
          else component.copy(wasm = component.wasm.copy(notes = WEAR_FOUNDATION_NOTE))
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
        // Narrowed to the modifiers `RemoteContentEmitter` can write, so the refusal lands at the
        // moment the modifier is added rather than at export — the only moment an author can act on
        // it (yschimke/compose-preview-server#508).
        curate = { component ->
          component.copy(
            modifierCapabilities =
              component.modifierCapabilities.filter { it in REMOTE_M3_MODIFIERS }
          )
        },
        menu = { base -> base.statusSemantics.componentMenuObject() },
      ),
  )

private fun JsonObject.componentMenuObject(): JsonObject =
  (this["componentMenu"] as? JsonObject) ?: JsonObject(emptyMap())
