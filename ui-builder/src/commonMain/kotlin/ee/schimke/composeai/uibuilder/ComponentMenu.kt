package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How the insert panel arranges a catalog: which shelf each component sits on, in what order the
 * shelves read, and which of a component's properties enumerates its variants.
 *
 * ## Declared by the catalog, not by this repository
 *
 * This was a hardcoded table here, because the obvious home — a field on `ComponentCapability` — is
 * not available: the wire type is `CatalogCapabilityV1` in compose-preview-contracts, decoded by
 * `CurrentM3UiBuilderCatalogExecutor` with `ignoreUnknownKeys = false`, and a key this repository
 * invents is a catalog that repository cannot read.
 *
 * [UiBuilderPreviewSurfaces] answered the same question better: `statusSemantics` is an open
 * `JsonObject` whose whole job is a catalog explaining its own vocabulary, and it already carries
 * `adapterStatus` and `svgStatus`. So the menu goes there too, under [KEY]. The difference this
 * makes is not tidiness — a table in `:ui-builder` can only ever describe the catalogs this
 * repository knows the ids of, so `wear-m3` and `remote-m3` fell back to role headings no matter
 * how well-organised their components were. A catalog that declares its own shelves gets them.
 *
 * ## The vocabulary
 *
 * Borrowed, not invented: m3-catalog's declaration uses the section names it already publishes
 * through `@file:CatalogGroup(section = …)` — Actions, Selection, Containment, Navigation,
 * Communication, Text inputs — so the builder's menu and the catalog beside it name the same shelf
 * the same way. `Scaffolds`, `Layout`, `Content`, `Styles` and `Embedded` cover the builder's own
 * primitives, which a Material catalog has no shelf for.
 *
 * ## Everything falls back
 *
 * A catalog that declares nothing is [EMPTY], and the panel groups by [EditorComponentKind] exactly
 * as it did before any of this existed. A component the declaration misses falls back to its kind
 * label the same way. A [variantProperty] naming a property the component does not declare, or one
 * with no allowed values, means no variants. None of the three is an error, because a menu is
 * presentation: a wrong one must never be the reason a component cannot be inserted.
 *
 * That is also why [variantProperty] is declared rather than inferred. Every heuristic is wrong
 * somewhere in m3-catalog: `m3/icon.iconKey` is an enum of forty-seven icons and no more a variant
 * than `layout/row.horizontalArrangement` is, and `m3/text.style` is fifteen type scales, which is
 * a property of a text rather than a kind of Text — while `m3/card.variant` and `m3/button.style`
 * are the same idea under two names.
 */
data class ComponentMenu(
  /** The shelves, most useful to a person building a screen first. */
  val groupOrder: List<String> = emptyList(),
  private val components: Map<String, Entry> = emptyMap(),
) {

  /** What one component's row says about itself. */
  data class Entry(val group: String? = null, val variantProperty: String? = null)

  /** The shelf [componentId] sits on, or null where the catalog does not say. */
  fun groupOf(componentId: String): String? = components[componentId]?.group

  /** The property whose allowed values are [componentId]'s variants, or null where it has none. */
  fun variantPropertyOf(componentId: String): String? = components[componentId]?.variantProperty

  /**
   * Every component this declaration names. Exists for the test that checks it against a catalog.
   */
  val componentIds: Set<String>
    get() = components.keys

  companion object {
    const val KEY: String = "componentMenu"

    /** What a catalog that says nothing gets: the role headings the panel always had. */
    val EMPTY: ComponentMenu = ComponentMenu()

    /**
     * Read the declaration out of a catalog's `statusSemantics`, falling back to [EMPTY].
     *
     * Every miss falls back rather than throwing, per entry, for the reason the class comment
     * gives: an unreadable or partial declaration must degrade to a plainer menu, never to an
     * editor that will not open.
     */
    fun from(statusSemantics: JsonObject): ComponentMenu {
      val declared = (statusSemantics[KEY] as? JsonObject) ?: return EMPTY
      val order =
        (declared["groupOrder"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
          (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
      val components =
        (declared["components"] as? JsonObject)?.mapNotNull { (componentId, value) ->
          val entry = value as? JsonObject ?: return@mapNotNull null
          componentId to
            Entry(
              group = entry["group"]?.jsonPrimitive?.contentOrNull,
              variantProperty = entry["variantProperty"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()
      return ComponentMenu(groupOrder = order, components = components.toMap())
    }
  }
}

/**
 * This component's variant values, in the order the catalog declares them.
 *
 * Empty when [menu] names no variant property for it, when the name does not resolve, or when the
 * property it names turns out not to be an enum — the three ways a declaration can be stale, all
 * answered the same way rather than by a failure.
 */
internal fun ComponentCapability.menuVariantValues(menu: ComponentMenu): List<String> {
  val property =
    menu.variantPropertyOf(componentId)?.let(propertiesByName::get) ?: return emptyList()
  return property.allowedValues.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
