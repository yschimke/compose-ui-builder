package ee.schimke.composeai.uibuilder.editor

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
 * The new-design chooser's card for a catalog that answers for itself, built from nothing but what
 * the served catalog says.
 *
 * The card's label is the catalog's `newDesign.label`, then its `platformLabel`, then its own name.
 * Its templates are the ones `templates` names, in the catalog's order, each carded from the
 * matching `newDesign.templates` entry (label, supporting text, heading) and ordered by its `order`
 * where the catalog gives one. A catalog that publishes no `newDesign` block — every catalog built
 * before the policy could say what its cards read — has its templates named after their files and
 * said to come from the catalog, rather than from a table here keyed by catalog id, which is the
 * coupling this card exists to remove.
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
        // Stable: an entry the catalog gives no order keeps its place after those it does.
        .sortedBy { (index, id) -> cards[id]?.int("order") ?: (Int.MAX_VALUE - ids.size + index) }
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
 * The chooser's cards in order. A catalog [ownership] names takes its card from
 * [catalogOwnedNewDesignCatalog]; every other catalog keeps its built-in card from [builtIn].
 *
 * Order: once every served catalog is owned, the order the host served them in, so no list of names
 * is consulted at all. While only some are, [builtInOrder] still decides, with any catalog it does
 * not name after the ones it does, in served order — a partly-moved chooser must not reshuffle the
 * cards nobody moved. With the flag off this is exactly the chooser the editor shipped with.
 */
fun newDesignCatalogs(
  catalogs: List<CatalogCapabilityV1>,
  ownership: CatalogOwnership,
  builtInOrder: List<String>,
  builtIn: (CatalogCapabilityV1) -> UiBuilderNewDesignCatalog?,
): List<UiBuilderNewDesignCatalog> {
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
      .sortedWith(compareBy({ order[it.value.systemId] ?: Int.MAX_VALUE }, { it.index }))
      .map { it.value }
  }
  if (ownership.isNone) return cards.sortedBy { builtInOrder.indexOf(it.systemId) }
  return cards.sortedBy { card ->
    builtInOrder.indexOf(card.systemId).takeIf { it >= 0 }
      ?: (builtInOrder.size + served.indexOf(card.systemId))
  }
}

/**
 * Whether the chooser lists this card among the primary types rather than folded under "other
 * types". A built-in card keeps its three-id answer; a catalog-owned card is primary when the
 * platform it declares is one a person authors screens for, which is a fact the catalog states
 * rather than a list of names this build keeps.
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
