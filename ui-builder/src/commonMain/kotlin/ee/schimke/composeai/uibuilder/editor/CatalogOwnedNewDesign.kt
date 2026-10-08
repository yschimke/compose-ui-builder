package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The new-design chooser's card for a catalog that answers for itself, built from nothing but what
 * the served catalog says: its `platformLabel` (or its own name), and one template per document its
 * policy's `templates` names, in the catalog's order.
 *
 * The built-in cards (`newDesignCatalog` in the Wasm entry point) carry a label and supporting text
 * per template that a catalog cannot publish yet — `templates` is a list of paths, not of objects
 * (`UI_BUILDER_SEED_TEMPLATES.md`). So an owned card names a template after its file, and says it
 * comes from the catalog. That loss is listed in the cutover's gap ledger rather than papered over
 * with a table here keyed by catalog id, which is the coupling this card exists to remove.
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
  val source = semantics[PLATFORM_LABEL]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
  return UiBuilderNewDesignCatalog(
    catalogOwned = true,
    systemId = systemId,
    label = source ?: titleOf(systemId),
    platform = UiBuilderCatalogPlatform.from(semantics),
    templates =
      ids.map { id ->
        UiBuilderNewDesignTemplate(
          id = id,
          label = titleOf(id),
          supportingText =
            if (declared.isEmpty()) "An empty starting point for this catalog."
            else "A starting point published by $systemId.",
        )
      },
  )
}

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
  if (allOwned) return cards
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

private fun titleOf(id: String): String =
  id.split('-', '_', '.').filter(String::isNotEmpty).joinToString(" ") { word ->
    word.replaceFirstChar(Char::uppercaseChar)
  }
