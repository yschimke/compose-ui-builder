package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.UiBuilderBrand
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform

/**
 * One card in the New design form's "What are you designing?" row: a catalog, or — when the form
 * offers a single catalog whose templates are grouped — one of that catalog's template groups.
 */
data class NewDesignTypeChoice(
  val catalogSystemId: String,
  val title: String,
  /** The card's second line; null draws none. */
  val subtitle: String?,
  /** The template group this card stands for, or null when it stands for a whole catalog. */
  val group: String? = null,
)

/**
 * The type picker's contents: the cards in its row, and the catalogs behind "Other design types…".
 */
data class NewDesignTypeOptions(
  val primary: List<NewDesignTypeChoice>,
  val other: List<UiBuilderNewDesignCatalog>,
) {
  /** Whether the choices are one catalog's template groups rather than catalogs. */
  val byGroup: Boolean
    get() = primary.any { it.group != null }
}

/**
 * What the form's type picker offers for [catalogs] on a host wearing [brand].
 * - One catalog whose templates name two or more `group`s: those groups, in the catalog's order, so
 *   a single-catalog host still asks what is being made.
 * - A brand that narrows the catalogs (`ui-builder-catalogs`): every catalog in the row, nothing
 *   folded away — the host already chose what to offer.
 * - Otherwise the [isPrimary] catalogs in the row, the rest behind "Other design types…".
 */
fun newDesignTypeOptions(
  catalogs: List<UiBuilderNewDesignCatalog>,
  brand: UiBuilderBrand? = null,
): NewDesignTypeOptions {
  val only = catalogs.singleOrNull()
  if (only != null) {
    val groups = newDesignTargetGroups(only)
    if (groups.isNotEmpty())
      return NewDesignTypeOptions(
        primary =
          groups.map { group ->
            NewDesignTypeChoice(
              catalogSystemId = only.systemId,
              title = group,
              subtitle = startingPoints(templatesForTarget(only, group).size),
              group = group,
            )
          },
        other = emptyList(),
      )
  }
  val narrowed = brand?.catalogs.orEmpty().isNotEmpty()
  val (primary, other) = catalogs.partition { narrowed || it.isPrimary }
  return NewDesignTypeOptions(primary.map(::newDesignTypeChoice), other)
}

/** A type card uses the catalog's published label and description. */
fun newDesignTypeChoice(catalog: UiBuilderNewDesignCatalog): NewDesignTypeChoice =
  NewDesignTypeChoice(catalog.systemId, catalog.label, catalog.description)

/**
 * The template groups a catalog offers as separate targets, in the order its templates first name
 * them; empty when fewer than two (one group is no choice).
 */
fun newDesignTargetGroups(catalog: UiBuilderNewDesignCatalog): List<String> =
  catalog.templates.mapNotNull { it.group }.distinct().takeIf { it.size > 1 }.orEmpty()

/**
 * The starting points under one target: the [group]'s templates, plus any that name no group (a
 * generic blank belongs to every target). A null [group] is the whole catalog.
 */
fun templatesForTarget(
  catalog: UiBuilderNewDesignCatalog,
  group: String?,
): List<UiBuilderNewDesignTemplate> =
  if (group == null) catalog.templates
  else catalog.templates.filter { it.group == null || it.group == group }

/** The published label for a design's catalog, or its id when no catalog was offered. */
fun homeDesignKindLabel(systemId: String, catalogs: List<UiBuilderNewDesignCatalog>): String =
  catalogs.firstOrNull { it.systemId == systemId }?.label ?: systemId

/** Widget thumbnail geometry is determined by the offered catalog's platform. */
fun isWidgetDesign(systemId: String, catalogs: List<UiBuilderNewDesignCatalog>): Boolean =
  catalogs.firstOrNull { it.systemId == systemId }?.platform ==
    UiBuilderCatalogPlatform.REMOTE_COMPOSE

private fun startingPoints(count: Int): String =
  if (count == 1) "1 starting point" else "$count starting points"
