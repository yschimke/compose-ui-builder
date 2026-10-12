package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Small catalogs built in the test, for tests of what the builder does rather than of what a real
 * catalog holds. A real catalog publishes from its own repository and is read from there; a copy
 * here would go stale the first time it republished (docs/design/UI_BUILDER_CATALOG_CUTOVER.md).
 *
 * Each one carries only the components the builder's own code names, in that catalog's shapes.
 */
object SyntheticCatalogs {

  /**
   * An A2UI-shaped catalog: no `layout/…` floor, framed by its own `a2ui/Column`, with the
   * components [StarterContent] and the editor rules name.
   */
  val A2UI_JSON: String by lazy {
    catalogJson(
      systemId = "a2ui-catalog",
      statusSemantics = buildJsonObject { put("platform", "a2ui") },
      components =
        listOf(
          a2ui("Column", role = CONTAINER, slots = listOf(children())) {
            weight()
            enum("align", "center", "end", "start", "stretch")
            enum("justify", "start", "center", "end", "spaceBetween", "stretch")
          },
          a2ui("Row", role = CONTAINER, slots = listOf(children())) {
            weight()
            enum("align", "start", "center", "end", "stretch")
            enum("justify", "start", "center", "end", "spaceBetween", "stretch")
          },
          a2ui("Card", role = CONTAINER, slots = listOf(child())) { weight() },
          a2ui("Text") {
            enum("variant", "h1", "h2", "caption", "body")
            weight()
            dynamic("text", "string", required = true)
          },
          a2ui("Divider") {
            enum("axis", "horizontal", "vertical")
            weight()
          },
          a2ui("Button", role = CONTAINER, slots = listOf(child())) {
            enum("variant", "default", "primary", "borderless")
            weight()
            property("action", JsonPrimitive("object"), required = true)
          },
          a2ui("TextField") {
            weight()
            dynamic("label", "string", required = true)
            dynamic("value", "string")
          },
          a2ui("CheckBox") {
            weight()
            dynamic("label", "string", required = true)
            dynamic("value", "boolean", required = true)
          },
          a2ui("ChoicePicker") {
            enum("variant", "mutuallyExclusive", "multipleSelection")
            dynamic("options", "array", required = true)
            weight()
            dynamic("value", "array", required = true)
          },
          a2ui("Slider") {
            property("min", JsonPrimitive("number"))
            property("max", JsonPrimitive("number"), required = true)
            weight()
            dynamic("value", "number", required = true)
          },
        ),
    )
  }

  val a2ui: CapabilityCatalog by lazy { CapabilityCatalogParser.parse(A2UI_JSON) }

  private const val CONTAINER = "Container"
  private const val LEAF = "Leaf"

  private fun catalogJson(
    systemId: String,
    statusSemantics: JsonObject,
    components: List<JsonObject>,
  ): String = buildJsonObject {
    put("schema", "compose-ui-builder-capabilities/v1-candidate")
    putJsonObject("benchmark") {
      put("id", "synthetic-$systemId")
      put("sourceRevision", "synthetic")
      put("catalogSystemId", systemId)
      put("catalogRevision", "synthetic")
      put("nativeRuntimeId", "candidate")
    }
    put("statusSemantics", statusSemantics)
    put("components", JsonArray(components))
  }
    .toString()

  private fun children() = slot("children", max = null)

  private fun child() = slot("child", min = 1, max = 1)

  private fun slot(name: String, min: Int = 0, max: Int?) = buildJsonObject {
    put("name", name)
    putJsonObject("cardinality") {
      put("min", min)
      put("max", max)
    }
    put("ordered", true)
    putJsonArray("acceptedRoles") {
      add(CONTAINER)
      add(LEAF)
    }
    putJsonArray("acceptedTraits") { add(A2UI_TRAIT) }
  }

  private const val A2UI_TRAIT = "A2uiComponent"

  private class Properties {
    val list = mutableListOf<JsonObject>()

    fun property(
      name: String,
      jsonType: JsonElement,
      required: Boolean = false,
      allowed: List<String> = emptyList(),
    ) {
      list += buildJsonObject {
        put("name", name)
        put("jsonType", jsonType)
        put("required", required)
        putJsonArray("allowedValues") { allowed.forEach { add(it) } }
      }
    }

    fun enum(name: String, vararg values: String) =
      property(name, JsonPrimitive("string"), allowed = values.toList())

    fun weight() = property("weight", JsonPrimitive("number"))

    /** A literal or a state variable, as A2UI's `Dynamic*` types are. */
    fun dynamic(name: String, literal: String, required: Boolean = false) =
      property(
        name,
        buildJsonArray {
          add(literal)
          add("object")
        },
        required,
      )
  }

  private fun a2ui(
    name: String,
    role: String = LEAF,
    slots: List<JsonObject> = emptyList(),
    properties: Properties.() -> Unit,
  ) = buildJsonObject {
    put("componentId", "a2ui/$name")
    put("displayName", name)
    put("role", role)
    putJsonArray("traits") { add(A2UI_TRAIT) }
    put("slots", JsonArray(slots))
    put("properties", JsonArray(Properties().apply(properties).list))
    putJsonArray("modifierCapabilities") {}
    putJsonObject("wasm") {
      put("platformSupported", true)
      put("adapterStatus", "supported")
    }
    putJsonObject("svg") {
      put("status", "unsupported")
      put("fallback", "none")
      put("blocksExport", true)
    }
  }
}
