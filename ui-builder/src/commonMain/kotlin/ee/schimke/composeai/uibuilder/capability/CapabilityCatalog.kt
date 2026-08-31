package ee.schimke.composeai.uibuilder.capability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class CapabilityCatalog(
  val schema: String,
  val benchmark: CapabilityBenchmark,
  val statusSemantics: JsonObject = JsonObject(emptyMap()),
  val components: List<ComponentCapability>,
) {
  val componentsById: Map<String, ComponentCapability> by lazy {
    components.associateBy(ComponentCapability::componentId)
  }
}

@Serializable
data class CapabilityBenchmark(
  val id: String,
  val sourceRevision: String,
  val catalogSystemId: String,
  val catalogRevision: String,
  val nativeRuntimeId: String,
)

@Serializable
data class ComponentCapability(
  val componentId: String,
  val displayName: String,
  val role: String,
  val traits: List<String> = emptyList(),
  val slots: List<SlotCapability> = emptyList(),
  val properties: List<PropertyCapability> = emptyList(),
  val modifierCapabilities: List<String> = emptyList(),
  val wasm: WasmCapability,
  val code: CodeCapability? = null,
  val svg: SvgCapability? = null,
) {
  val slotsByName: Map<String, SlotCapability> by lazy { slots.associateBy(SlotCapability::name) }
  val propertiesByName: Map<String, PropertyCapability> by lazy {
    properties.associateBy(PropertyCapability::name)
  }
}

@Serializable
data class SlotCapability(
  val name: String,
  val cardinality: SlotCardinality,
  val ordered: Boolean,
  val acceptedRoles: List<String> = emptyList(),
  val acceptedTraits: List<String> = emptyList(),
)

@Serializable data class SlotCardinality(val min: Int = 0, val max: Int? = null)

@Serializable
data class PropertyCapability(
  val name: String,
  val jsonType: JsonElement,
  val required: Boolean = false,
  val allowedValues: List<JsonElement> = emptyList(),
  val notes: String? = null,
)

@Serializable
data class WasmCapability(
  val platformSupported: JsonElement,
  val adapterStatus: WasmAdapterStatus,
  val notes: String? = null,
)

@Serializable
enum class WasmAdapterStatus {
  @SerialName("supported") SUPPORTED,
  @SerialName("planned") PLANNED,
  @SerialName("unsupported") UNSUPPORTED,
}

@Serializable data class CodeCapability(val symbol: String, val imports: List<String> = emptyList())

@Serializable
data class SvgCapability(
  val status: String,
  val fallback: String,
  val blocksExport: Boolean,
  val notes: String? = null,
)

object CapabilityCatalogParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(source: String): CapabilityCatalog = parse(json.parseToJsonElement(source))

  fun parse(element: JsonElement): CapabilityCatalog =
    json.decodeFromJsonElement<CapabilityCatalog>(element).also(::validateCatalogShape)

  private fun validateCatalogShape(catalog: CapabilityCatalog) {
    require(catalog.schema.isNotBlank()) { "capability schema must be non-empty" }
    require(catalog.components.isNotEmpty()) { "capability catalog must contain components" }
    require(catalog.components.map { it.componentId }.distinct().size == catalog.components.size) {
      "capability component ids must be unique"
    }
    catalog.components.forEach { component ->
      require(component.componentId.isNotBlank()) { "component id must be non-empty" }
      require(component.slots.map { it.name }.distinct().size == component.slots.size) {
        "slot names must be unique for ${component.componentId}"
      }
      require(component.properties.map { it.name }.distinct().size == component.properties.size) {
        "property names must be unique for ${component.componentId}"
      }
      component.slots.forEach { slot ->
        require(slot.cardinality.min >= 0) { "slot minimum must be non-negative" }
        require(slot.cardinality.max == null || slot.cardinality.max >= slot.cardinality.min) {
          "slot maximum must not be less than its minimum"
        }
      }
    }
  }
}
