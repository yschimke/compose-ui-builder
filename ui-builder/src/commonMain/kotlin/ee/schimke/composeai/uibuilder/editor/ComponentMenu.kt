package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How the insert panel arranges a catalog: each component's shelf, shelf order, and which property
 * enumerates a component's variants.
 *
 * Declared by the catalog in [UiBuilderPreviewSurfaces]'s open `statusSemantics` under [KEY], since
 * `CatalogCapabilityV1` (compose-preview-contracts) is decoded strictly and cannot take new keys.
 * Shelf names follow m3-catalog's `@file:CatalogGroup(section = …)`, plus the builder's own
 * (`Scaffolds`, `Layout`, `Content`, `Styles`, `Embedded`).
 *
 * Everything falls back: no declaration is [EMPTY] (grouping by [EditorComponentKind]); a missed
 * component uses its kind label; an unresolvable [variantProperty] means no variants. A menu is
 * presentation and must never block an insert. [variantProperty] is declared rather than inferred
 * because every heuristic is wrong somewhere (`m3/icon.iconKey`, `m3/text.style`).
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
     * Read the declaration from a catalog's `statusSemantics`, falling back per entry (never
     * throwing) to a plainer menu.
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
 * This component's variant values in declared order; empty when the declaration names none or is
 * stale.
 */
internal fun ComponentCapability.menuVariantValues(menu: ComponentMenu): List<String> {
  val property =
    menu.variantPropertyOf(componentId)?.let(propertiesByName::get) ?: return emptyList()
  return property.allowedValues.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
