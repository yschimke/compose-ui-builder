package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.CatalogListing
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The new-design chooser's card for a catalog that answers for itself, built only from the served
 * catalog.
 *
 * Label: `newDesign.label`, then `platformLabel`, then the catalog's name; `newDesign.description`
 * is the type card's second line and `newDesign.primary` its place in the picker
 * ([newDesignTypeOptions]). Templates: those `templates` names, carded from matching
 * `newDesign.templates` entries and sorted by their `order`. Without a `newDesign` block, templates
 * are named after their files rather than from a table keyed by catalog id.
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
    systemId = systemId,
    label = label ?: titleOf(systemId),
    platform = UiBuilderCatalogPlatform.from(semantics),
    description = chooser?.string("description"),
    primary = (chooser?.get("primary") as? JsonPrimitive)?.booleanOrNull,
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

/** Cards from published metadata, ordered by the catalog's order then the host's listing. */
fun newDesignCatalogs(
  catalogs: List<CatalogCapabilityV1>,
  revealed: Set<String> = emptySet(),
): List<UiBuilderNewDesignCatalog> =
  catalogs
    .filter { !CatalogListing.isUnlisted(it) || it.benchmark.catalogSystemId in revealed }
    .withIndex()
    .sortedWith(
      compareBy(
        { it.value.newDesignOrder() == null },
        { it.value.newDesignOrder() ?: 0 },
        { it.index },
      )
    )
    .map { catalogOwnedNewDesignCatalog(it.value) }

/**
 * The catalogs [newDesignCatalogs] shows even when unlisted: the one the page's URL named, if any.
 * A default the URL did not name reveals nothing, or an unlisted default would leak into the plain
 * chooser.
 */
fun revealedNewDesignCatalogs(urlCatalogSystemId: String?): Set<String> =
  setOfNotNull(urlCatalogSystemId?.takeIf(String::isNotBlank))

/**
 * Whether the chooser lists this card among the primary types rather than under "other types": what
 * the catalog's `newDesign.primary` says, else false. A brand that narrows the catalogs puts every
 * one in the row regardless ([newDesignTypeOptions]).
 */
val UiBuilderNewDesignCatalog.isPrimary: Boolean
  get() = primary ?: false

private const val TEMPLATES = "templates"
private const val PLATFORM_LABEL = "platformLabel"
private const val NEW_DESIGN = "newDesign"

private fun titleOf(id: String): String =
  id.split('-', '_', '.').filter(String::isNotEmpty).joinToString(" ") { word ->
    word.replaceFirstChar(Char::uppercaseChar)
  }
