package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Small catalogs built in the test, for tests of what the builder does rather than of what a real
 * catalog holds. A real catalog publishes from its own repository and is read from there; a copy
 * here would go stale the first time it republished (docs/design/UI_BUILDER_CATALOG_CUTOVER.md).
 *
 * Each one carries only the components and catalog-level declarations the builder's own code and
 * these tests name, in that catalog's shapes.
 */
object SyntheticCatalogs {

  /**
   * An A2UI-shaped catalog: no `layout/…` floor, framed by its own `a2ui/Column`, with the
   * components [StarterContent] and the editor rules name.
   */
  val A2UI_JSON: String by lazy {
    syntheticCatalogJson(
      systemId = "a2ui-catalog",
      statusSemantics = buildJsonObject { put("platform", "a2ui") },
      components =
        listOf(
          a2ui("Column", CONTAINER, children()) {
            weight()
            p("align", STRING, allowed = "center end start stretch")
            p("justify", STRING, allowed = "start center end spaceBetween stretch")
          },
          a2ui("Row", CONTAINER, children()) {
            weight()
            p("align", STRING, allowed = "start center end stretch")
            p("justify", STRING, allowed = "start center end spaceBetween stretch")
          },
          a2ui("Card", CONTAINER, child()) { weight() },
          a2ui("Text") {
            p("variant", STRING, allowed = "h1 h2 caption body")
            weight()
            p("text", STRING, OBJECT, required = true)
          },
          a2ui("Divider") {
            p("axis", STRING, allowed = "horizontal vertical")
            weight()
          },
          a2ui("Button", CONTAINER, child()) {
            p("variant", STRING, allowed = "default primary borderless")
            weight()
            p("action", OBJECT, required = true)
          },
          a2ui("TextField") {
            weight()
            p("label", STRING, OBJECT, required = true)
            p("value", STRING, OBJECT)
          },
          a2ui("CheckBox") {
            weight()
            p("label", STRING, OBJECT, required = true)
            p("value", "boolean", OBJECT, required = true)
          },
          a2ui("ChoicePicker") {
            p("variant", STRING, allowed = "mutuallyExclusive multipleSelection")
            p("options", "array", OBJECT, required = true)
            weight()
            p("value", "array", OBJECT, required = true)
          },
          a2ui("Slider") {
            p("min", NUMBER)
            p("max", NUMBER, required = true)
            weight()
            p("value", NUMBER, OBJECT, required = true)
          },
        ),
    )
  }

  val a2ui: CapabilityCatalog by lazy { CapabilityCatalogParser.parse(A2UI_JSON) }

  /** A Wear-shaped catalog; see [SYNTHETIC_WEAR_JSON]. */
  val WEAR_JSON: String
    get() = SYNTHETIC_WEAR_JSON

  val wear: CapabilityCatalog by lazy { CapabilityCatalogParser.parse(WEAR_JSON) }

  /** A Remote-Compose-shaped catalog; see [SYNTHETIC_REMOTE_JSON]. */
  val REMOTE_JSON: String
    get() = SYNTHETIC_REMOTE_JSON

  val remote: CapabilityCatalog by lazy { CapabilityCatalogParser.parse(REMOTE_JSON) }

  private const val CONTAINER = "Container"
  private const val LEAF = "Leaf"
  private const val STRING = "string"
  private const val NUMBER = "number"
  private const val OBJECT = "object"
  private const val A2UI_TRAIT = "A2uiComponent"

  private fun children() =
    syntheticSlot("children", max = null, roles = "$CONTAINER $LEAF", traits = A2UI_TRAIT)

  private fun child() =
    syntheticSlot("child", min = 1, max = 1, roles = "$CONTAINER $LEAF", traits = A2UI_TRAIT)

  private fun SyntheticProperties.weight() = p("weight", NUMBER)

  private fun a2ui(
    name: String,
    role: String = LEAF,
    slot: JsonObject? = null,
    properties: SyntheticProperties.() -> Unit,
  ) =
    syntheticComponent(
      componentId = "a2ui/$name",
      displayName = name,
      role = role,
      traits = A2UI_TRAIT,
      slots = listOfNotNull(slot),
      properties = properties,
    )
}
