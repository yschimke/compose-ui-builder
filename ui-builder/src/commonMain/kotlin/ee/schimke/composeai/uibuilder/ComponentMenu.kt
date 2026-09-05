package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How the insert panel arranges the catalog: which shelf a component sits on, and which of its
 * properties enumerates its variants.
 *
 * ## Why this is a table here and not a field on the catalog
 *
 * The capability catalog is a **wire document**. Its shape is `CatalogCapabilityV1` in
 * `compose-preview-contracts`, and `CurrentM3UiBuilderCatalogExecutor` decodes it with
 * `ignoreUnknownKeys = false` on purpose — a packaged catalog is parsed strictly and its invariants
 * checked before it is exposed. A key this repository invents is a catalog that repository cannot
 * read. Which shelf a component is displayed on is also not a capability: nothing the server
 * advertises, validates or exports depends on it, and a menu that regrouped itself would change no
 * document. So it lives beside [StarterContent], which is here for the same reason — presentation
 * that has to agree with the catalog, held where the catalog can be checked against it.
 *
 * ## What keeps it honest
 *
 * `CatalogMenuTest` asserts this table against the packaged catalog: every component has a shelf,
 * every shelf is in [GROUP_ORDER], and every declared variant property exists on the component it
 * names and is an enum. A renamed property or a component that left the catalog fails a test rather
 * than silently degrading in the product — and at runtime a stale entry degrades anyway, to "no
 * variants" and to the component's kind as its shelf, because a wrong menu must never be the reason
 * a component cannot be inserted.
 *
 * A catalog this table says nothing about — `wear-m3`, `remote-m3` — gets exactly the panel it had
 * before: grouped by [EditorComponentKind], with no variant rows.
 *
 * ## The vocabulary
 *
 * Borrowed, not invented. [GROUPS] uses the section names m3-catalog already publishes through
 * `@file:CatalogGroup(section = …)` — Actions, Selection, Containment, Navigation, Communication,
 * Text inputs — so the builder's menu and the catalog beside it name the same shelf the same way.
 * `Scaffolds`, `Layout`, `Content`, `Styles` and `Embedded` cover the builder's own primitives,
 * which a Material catalog has no shelf for.
 */
internal object ComponentMenu {

  /** The shelves, most useful to a person building a screen first. */
  val GROUP_ORDER: List<String> =
    listOf(
      "Scaffolds",
      "Layout",
      "Navigation",
      "Actions",
      "Selection",
      "Text inputs",
      "Containment",
      "Communication",
      "Content",
      "Styles",
      "Embedded",
    )

  /** The shelf [componentId] sits on, or null for a catalog this table says nothing about. */
  fun groupOf(componentId: String): String? = GROUPS[componentId]

  /**
   * The property whose allowed values are [componentId]'s variants, or null where it has none.
   *
   * **Declared rather than inferred**, because every heuristic is wrong somewhere in this catalog.
   * `m3/icon.iconKey` is an enum of forty-seven icons and no more a variant than
   * `layout/row.horizontalArrangement` is; `m3/text.style` is fifteen type scales, which is a
   * property of a text rather than a kind of Text. Meanwhile `m3/card.variant` and
   * `m3/button.style` are the same idea under two names, and no rule about naming would catch both
   * without also catching the first two.
   */
  fun variantPropertyOf(componentId: String): String? = VARIANT_PROPERTIES[componentId]

  /** Every component this table names. Exists for the test that checks it against a catalog. */
  val componentIds: Set<String>
    get() = GROUPS.keys + VARIANT_PROPERTIES.keys

  private val GROUPS: Map<String, String> =
    mapOf(
      "layout/scaffold" to "Scaffolds",
      "layout/supporting-pane-scaffold" to "Scaffolds",
      "layout/box" to "Layout",
      "layout/column" to "Layout",
      "layout/row" to "Layout",
      "layout/lazy-column" to "Layout",
      "layout/lazy-row" to "Layout",
      "layout/lazy-grid" to "Layout",
      "m3/surface" to "Layout",
      "m3/center-aligned-top-app-bar" to "Navigation",
      "m3/primary-tab-row" to "Navigation",
      "m3/tab" to "Navigation",
      "m3/search-bar" to "Navigation",
      "m3/search-input-field" to "Navigation",
      "m3/button" to "Actions",
      "m3/icon-button" to "Actions",
      "m3/horizontal-floating-toolbar" to "Actions",
      "m3/checkbox" to "Selection",
      "m3/radio-button" to "Selection",
      "m3/switch" to "Selection",
      "m3/slider" to "Selection",
      "m3/filter-chip" to "Selection",
      "m3/date-picker" to "Selection",
      "m3/time-picker" to "Selection",
      "m3/text-field" to "Text inputs",
      "m3/card" to "Containment",
      "m3/dialog" to "Containment",
      "m3/list-item" to "Containment",
      "m3/horizontal-divider" to "Containment",
      "layout/horizontal-carousel" to "Containment",
      "m3/progress-indicator" to "Communication",
      "m3/snackbar-host" to "Communication",
      "m3/text" to "Content",
      "m3/icon" to "Content",
      "asset/image" to "Content",
      "shape/colour-dot" to "Styles",
      "shape/linear-gradient" to "Styles",
      "shape/radial-gradient" to "Styles",
      "remote-compose/document" to "Embedded",
    )

  private val VARIANT_PROPERTIES: Map<String, String> =
    mapOf(
      "m3/button" to "style",
      "m3/card" to "variant",
      "m3/icon-button" to "variant",
      "m3/progress-indicator" to "variant",
      "m3/text-field" to "variant",
      "m3/date-picker" to "mode",
      "m3/time-picker" to "mode",
    )
}

/**
 * This component's variant values, in the order the catalog declares them.
 *
 * Empty when the table names no variant property for it, when the name does not resolve, or when
 * the property it names turns out not to be an enum — the three ways the table can be stale, all
 * answered the same way rather than by a failure.
 */
internal fun ComponentCapability.menuVariantValues(): List<String> {
  val property =
    ComponentMenu.variantPropertyOf(componentId)?.let(propertiesByName::get) ?: return emptyList()
  return property.allowedValues.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
