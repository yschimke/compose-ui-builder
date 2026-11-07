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
 * - Otherwise the [isPrimary] catalogs in the row, the rest behind "Other design types…". When no
 *   catalog asks for the row, the first [FALLBACK_PRIMARY_COUNT] (in published order) fill it, so
 *   the question is never asked over an empty row.
 *
 * Catalogs that publish the same label (typically several falling back to one `platformLabel`) are
 * told apart by their id ([newDesignTypeLabels]).
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
  val labeled = newDesignTypeLabels(catalogs)
  val (primary, other) =
    when {
      narrowed -> catalogs to emptyList()
      catalogs.none { it.isPrimary } ->
        catalogs.take(FALLBACK_PRIMARY_COUNT) to catalogs.drop(FALLBACK_PRIMARY_COUNT)
      else -> catalogs.partition { it.isPrimary }
    }
  return NewDesignTypeOptions(
    primary = primary.map { newDesignTypeChoice(it).copy(title = labeled.getValue(it.systemId)) },
    other = other.map { it.copy(label = labeled.getValue(it.systemId)) },
  )
}

/**
 * Each catalog's label in the type picker, keyed by system id: its published label, or — when two
 * or more catalogs publish the same one — that label followed by the catalog's id in words, so four
 * catalogs falling back to "Mobile" read as "Mobile · M3 Catalog", "Mobile · Compose Foundation"…
 */
fun newDesignTypeLabels(catalogs: List<UiBuilderNewDesignCatalog>): Map<String, String> {
  val counts = catalogs.groupingBy { it.label.trim().lowercase() }.eachCount()
  return catalogs.associate { catalog ->
    val shared = (counts[catalog.label.trim().lowercase()] ?: 0) > 1
    val named = newDesignTitleOf(catalog.systemId)
    catalog.systemId to
      when {
        !shared -> catalog.label
        named.equals(catalog.label, ignoreCase = true) -> named
        else -> "${catalog.label} · $named"
      }
  }
}

/** A type card uses the catalog's published label and description. */
fun newDesignTypeChoice(catalog: UiBuilderNewDesignCatalog): NewDesignTypeChoice =
  NewDesignTypeChoice(catalog.systemId, catalog.label, catalog.description)

/** How many catalogs fill the type row when none of them asks for it (one full row of cards). */
const val FALLBACK_PRIMARY_COUNT = 3

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

/**
 * What a design is for — the New design form's first question when the catalogs on offer span more
 * than one. [id] is the word a catalog writes in `newDesign.device`; [label] is what a person
 * reads.
 */
data class NewDesignDevice(val id: String, val label: String) {
  companion object {
    val PHONE = NewDesignDevice("phone", "Phone & tablet")
    val WATCH = NewDesignDevice("watch", "Watch")
    val WIDGET = NewDesignDevice("widget", "Widget")
    val GLASSES = NewDesignDevice("glasses", "Glasses")
    val SMART_HOME = NewDesignDevice("smart-home", "Smart home")
    val AGENT = NewDesignDevice("agent", "Agent UI")

    /** The devices the builder names, in the order the form offers them. */
    val KNOWN: List<NewDesignDevice> = listOf(PHONE, WATCH, WIDGET, GLASSES, SMART_HOME, AGENT)

    /** The device [word] names: a known one by its id, else one of the catalog's own. */
    fun of(word: String): NewDesignDevice {
      val id = word.trim().lowercase().replace(' ', '-')
      return KNOWN.firstOrNull { it.id == id } ?: NewDesignDevice(id, newDesignTitleOf(id))
    }
  }
}

/**
 * The device a catalog's designs are for: its `newDesign.device` when it says, else inferred.
 * - A label naming a device the platform cannot ("Glasses", "Home Assistant") is taken at its word.
 * - Otherwise the platform decides: Remote Compose documents are widgets (watch or launcher), A2UI
 *   surfaces are agent UI, Lovelace configs are smart home, Wear is a watch, the rest a phone.
 *
 * The inference is a stopgap for catalogs that publish no `device`; a catalog that does is never
 * second-guessed.
 */
val UiBuilderNewDesignCatalog.newDesignDevice: NewDesignDevice
  get() {
    device?.let {
      return NewDesignDevice.of(it)
    }
    val words = "$label $systemId".lowercase()
    return when {
      "glasses" in words -> NewDesignDevice.GLASSES
      "home assistant" in words || "home-assistant" in words -> NewDesignDevice.SMART_HOME
      else ->
        when (platform) {
          UiBuilderCatalogPlatform.WEAR -> NewDesignDevice.WATCH
          UiBuilderCatalogPlatform.REMOTE_COMPOSE -> NewDesignDevice.WIDGET
          UiBuilderCatalogPlatform.A2UI -> NewDesignDevice.AGENT
          UiBuilderCatalogPlatform.LOVELACE -> NewDesignDevice.SMART_HOME
          UiBuilderCatalogPlatform.MOBILE -> NewDesignDevice.PHONE
        }
    }
  }

/**
 * The catalogs grouped by [newDesignDevice]: the known devices in [NewDesignDevice.KNOWN]'s order,
 * then any of the catalogs' own in the order they first appear. Within a device, catalogs that ask
 * for the row (`primary`) come first, otherwise in published order.
 */
fun newDesignDeviceGroups(
  catalogs: List<UiBuilderNewDesignCatalog>,
): List<Pair<NewDesignDevice, List<UiBuilderNewDesignCatalog>>> {
  val grouped = catalogs.groupBy { it.newDesignDevice }
  val order = NewDesignDevice.KNOWN + grouped.keys.filter { it !in NewDesignDevice.KNOWN }
  return order.mapNotNull { device ->
    grouped[device]?.let { members -> device to members.sortedBy { !it.isPrimary } }
  }
}

/**
 * Whether the form asks for the device first: catalogs span two or more devices, the host has not
 * narrowed them to its own set, and the choice is not one catalog's template groups.
 */
fun newDesignAsksDeviceFirst(
  catalogs: List<UiBuilderNewDesignCatalog>,
  brand: UiBuilderBrand? = null,
): Boolean =
  brand?.catalogs.orEmpty().isEmpty() &&
    !newDesignTypeOptions(catalogs, brand).byGroup &&
    newDesignDeviceGroups(catalogs).size > 1
