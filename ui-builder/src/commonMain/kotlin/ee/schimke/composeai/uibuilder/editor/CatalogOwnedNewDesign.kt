package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.CatalogListing
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The new-design chooser's card for a catalog that answers for itself, built only from the served
 * catalog.
 *
 * Label: `newDesign.label`, then `platformLabel`, then the catalog's name. Templates: those
 * `templates` names, carded from matching `newDesign.templates` entries and sorted by their
 * `order`. Without a `newDesign` block, templates are named after their files rather than from a
 * table keyed by catalog id.
 */
fun catalogOwnedNewDesignCatalog(catalog: CatalogCapabilityV1): UiBuilderNewDesignCatalog {
  val systemId = catalog.benchmark.catalogSystemId
  val semantics = catalog.statusSemantics
  val declared =
    (semantics[TEMPLATES] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.map { it.substringAfterLast('/').removeSuffix(".json") }
      ?.filter(String::isNotBlank)
      .orEmpty()
  val ids = declared.ifEmpty { listOf(UiBuilderNewDesignSeed.BLANK_TEMPLATE) }
  val chooser = semantics[NEW_DESIGN] as? JsonObject
  val cards =
    (chooser?.get(TEMPLATES) as? JsonArray)
      .orEmpty()
      .mapNotNull { it as? JsonObject }
      .associateBy { it.string("id") }
  val label =
    chooser?.string("label")
      ?: semantics[PLATFORM_LABEL]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
  return UiBuilderNewDesignCatalog(
    catalogOwned = true,
    systemId = systemId,
    label = label ?: titleOf(systemId),
    platform = UiBuilderCatalogPlatform.from(semantics),
    templates =
      ids
        .withIndex()
        // Ordered entries first (by order), then unordered, each keeping the catalog's listing
        // order.
        .sortedWith(
          compareBy(
            { cards[it.value]?.int("order") == null },
            { cards[it.value]?.int("order") ?: 0 },
            { it.index },
          )
        )
        .map { (_, id) ->
          val card = cards[id]
          UiBuilderNewDesignTemplate(
            id = id,
            label = card?.string("label") ?: titleOf(id),
            supportingText =
              card?.string("supportingText")
                ?: if (declared.isEmpty()) "An empty starting point for this catalog."
                else "A starting point published by $systemId.",
            group = card?.string("group"),
          )
        },
  )
}

/**
 * Where a catalog-owned card asks to sit, from its `newDesign.order`; null when it says nothing.
 */
internal fun CatalogCapabilityV1.newDesignOrder(): Int? =
  (statusSemantics[NEW_DESIGN] as? JsonObject)?.int("order")

private fun JsonObject.string(key: String): String? =
  (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

/**
 * The chooser's cards in order: catalogs [ownership] names use [catalogOwnedNewDesignCatalog],
 * others their built-in card from [builtIn].
 *
 * Once all are owned, served order is used. Until then [builtInOrder] decides, with unnamed
 * catalogs after in served order, so unmoved cards keep their place. With the flag off this is the
 * original chooser.
 *
 * Unlisted catalogs ([CatalogListing]) get no card, except one named in [revealed] — the catalog
 * the page's URL asked for, so `/ui-builder/<unlisted>/` still offers its own templates.
 */
fun newDesignCatalogs(
  catalogs: List<CatalogCapabilityV1>,
  ownership: CatalogOwnership,
  builtInOrder: List<String>,
  builtIn: (CatalogCapabilityV1) -> UiBuilderNewDesignCatalog?,
  revealed: Set<String> = emptySet(),
): List<UiBuilderNewDesignCatalog> {
  @Suppress("NAME_SHADOWING")
  val catalogs = catalogs.filter {
    !CatalogListing.isUnlisted(it) || it.benchmark.catalogSystemId in revealed
  }
  val served = catalogs.map { it.benchmark.catalogSystemId }
  val allOwned = served.all(ownership::owns)
  val cards = catalogs.mapNotNull { catalog ->
    if (ownership.owns(catalog.benchmark.catalogSystemId)) catalogOwnedNewDesignCatalog(catalog)
    else builtIn(catalog)
  }
  // Every card owned: the catalogs' own `newDesign.order`, then the order the host served them in.
  if (allOwned) {
    val order = catalogs.associate { it.benchmark.catalogSystemId to it.newDesignOrder() }
    return cards
      .withIndex()
      .sortedWith(
        compareBy(
          { order[it.value.systemId] == null },
          { order[it.value.systemId] ?: 0 },
          { it.index },
        )
      )
      .map { it.value }
  }
  if (ownership.isNone) return cards.sortedBy { builtInOrder.indexOf(it.systemId) }
  return cards.sortedBy { card ->
    builtInOrder.indexOf(card.systemId).takeIf { it >= 0 }
      ?: (builtInOrder.size + served.indexOf(card.systemId))
  }
}

/**
 * Whether the chooser lists this card among the primary types rather than under "other types":
 * built-in cards by their id list, catalog-owned cards by whether their declared platform is a
 * screen-authoring one.
 */
val UiBuilderNewDesignCatalog.isPrimary: Boolean
  get() =
    if (catalogOwned) platform != UiBuilderCatalogPlatform.A2UI
    else systemId in BUILT_IN_PRIMARY_CATALOGS

private val BUILT_IN_PRIMARY_CATALOGS = listOf("m3-catalog", "wear-m3", "remote-m3")

private const val TEMPLATES = "templates"
private const val PLATFORM_LABEL = "platformLabel"
private const val NEW_DESIGN = "newDesign"

private fun titleOf(id: String): String =
  id.split('-', '_', '.').filter(String::isNotEmpty).joinToString(" ") { word ->
    word.replaceFirstChar(Char::uppercaseChar)
  }
