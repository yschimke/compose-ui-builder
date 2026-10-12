package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The capability document shape [SyntheticCatalogs] are written in, so each catalog reads as its
 * components rather than as JSON. Lists of words (traits, modifiers, accepted roles) are written
 * space-separated.
 */
internal fun syntheticCatalogJson(
  systemId: String,
  statusSemantics: JsonObject,
  components: List<JsonObject>,
  catalogRevision: String = "synthetic",
  benchmarkId: String = "synthetic-$systemId",
): String = buildJsonObject {
  put("schema", "compose-ui-builder-capabilities/v1-candidate")
  putJsonObject("benchmark") {
    put("id", benchmarkId)
    put("sourceRevision", "synthetic")
    put("catalogSystemId", systemId)
    put("catalogRevision", catalogRevision)
    put("nativeRuntimeId", "candidate")
  }
  put("statusSemantics", statusSemantics)
  put("components", JsonArray(components))
}
  .toString()

internal fun syntheticSlot(
  name: String,
  min: Int = 0,
  max: Int?,
  roles: String = "",
  traits: String = "",
): JsonObject = buildJsonObject {
  put("name", name)
  putJsonObject("cardinality") {
    put("min", min)
    put("max", max)
  }
  put("ordered", true)
  put("acceptedRoles", words(roles))
  put("acceptedTraits", words(traits))
}

internal class SyntheticProperties {
  val list = mutableListOf<JsonObject>()

  /** One property; two or more [jsonType]s are a union, as a literal-or-state value is. */
  fun p(
    name: String,
    vararg jsonType: String,
    required: Boolean = false,
    allowed: String = "",
  ) {
    list += buildJsonObject {
      put("name", name)
      put(
        "jsonType",
        jsonType.singleOrNull()?.let(::JsonPrimitive)
          ?: buildJsonArray { jsonType.forEach { add(it) } },
      )
      put("required", required)
      put("allowedValues", words(allowed))
    }
  }
}

internal fun syntheticComponent(
  componentId: String,
  displayName: String,
  role: String,
  traits: String = "",
  modifiers: String = "",
  slots: List<JsonObject> = emptyList(),
  properties: SyntheticProperties.() -> Unit = {},
): JsonObject = buildJsonObject {
  put("componentId", componentId)
  put("displayName", displayName)
  put("role", role)
  put("traits", words(traits))
  put("slots", JsonArray(slots))
  put("properties", JsonArray(SyntheticProperties().apply(properties).list))
  put("modifierCapabilities", words(modifiers))
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

private fun words(text: String): JsonElement = buildJsonArray {
  text.split(' ').filter { it.isNotEmpty() }.forEach { add(it) }
}
