package ee.schimke.composeai.uibuilder.capability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
  @Transient val dynamicSlots: DynamicSlotCapability? = null,
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

/** Rules for document-authored slot names, such as Remote Compose custom component configs. */
@Serializable
data class DynamicSlotCapability(
  val cardinality: SlotCardinality = SlotCardinality(),
  val ordered: Boolean = true,
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
  @Transient val editor: PropertyEditorCapability? = null,
)

/**
 * Builder-only presentation metadata for a catalog property. This is deliberately carried by the
 * catalog resource rather than the collaboration wire protocol: it describes how an editor may
 * safely author an existing JSON property shape, not a new persisted value kind.
 */
@Serializable
data class PropertyEditorCapability(
  val control: PropertyEditorControl? = null,
  val minimum: Double? = null,
  val maximum: Double? = null,
  val step: Double? = null,
  val suggestedValues: List<String> = emptyList(),
)

@Serializable
enum class PropertyEditorControl {
  @SerialName("text") TEXT,
  @SerialName("boolean") BOOLEAN,
  @SerialName("number") NUMBER,
  @SerialName("enum") ENUM,
  @SerialName("color") COLOR,
}

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
    json
      .decodeFromJsonElement<CapabilityCatalog>(element)
      .withEditorMetadata()
      .also(::validateCatalogShape)

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
      component.dynamicSlots?.cardinality?.let { cardinality ->
        require(cardinality.min >= 0) { "dynamic slot minimum must be non-negative" }
        require(cardinality.max == null || cardinality.max >= cardinality.min) {
          "dynamic slot maximum must not be less than its minimum"
        }
      }
    }
  }

  /**
   * Adds builder-only presentation metadata without adding fields to the released catalog wire
   * shape. Enum choices remain authoritative catalog data; the bounds below describe the range that
   * both the renderer and Compose exporter can safely round trip.
   */
  private fun CapabilityCatalog.withEditorMetadata(): CapabilityCatalog =
    copy(
      components =
        components.map { component ->
          component.copy(
            dynamicSlots =
              component.dynamicSlots
                ?: if ("DynamicSlots" in component.traits)
                  DynamicSlotCapability(acceptedTraits = listOf("AnyContent"))
                else null,
            properties =
              component.properties.map { property ->
                property.copy(editor = editorMetadata(component.componentId, property))
              },
          )
        }
    )

  private fun editorMetadata(
    componentId: String,
    property: PropertyCapability,
  ): PropertyEditorCapability? {
    if (property.allowedValues.isNotEmpty()) {
      return PropertyEditorCapability(control = PropertyEditorControl.ENUM)
    }
    EDITOR_OVERRIDES[componentId to property.name]?.let {
      return it
    }
    return when ((property.jsonType as? JsonPrimitive)?.contentOrNull) {
      "string" -> PropertyEditorCapability(control = PropertyEditorControl.TEXT)
      "boolean" -> PropertyEditorCapability(control = PropertyEditorControl.BOOLEAN)
      else -> null
    }
  }

  private val MATERIAL_COLOR_TOKENS =
    listOf(
      "background",
      "surface",
      "surfaceContainer",
      "surfaceContainerLow",
      "surfaceContainerHigh",
      "surfaceContainerHighest",
      "primary",
      "tertiary",
      "onTertiary",
      "onSurface",
      "onSurfaceVariant",
      "outlineVariant",
      "transparent",
    )

  private val EDITOR_OVERRIDES =
    mapOf(
      ("layout/supporting-pane-scaffold" to "mainPanePreferredWidthDp") to
        numberEditor(0.0, 4096.0, 1.0),
      ("layout/supporting-pane-scaffold" to "supportingPanePreferredWidthDp") to
        numberEditor(0.0, 4096.0, 1.0),
      ("layout/supporting-pane-scaffold" to "paneSpacingDp") to numberEditor(0.0, 512.0, 1.0),
      ("m3/button" to "containerColor") to colorEditor(),
      ("m3/text" to "color") to colorEditor(),
      ("m3/text" to "fontSizeSp") to numberEditor(1.0, 512.0, 1.0),
      ("m3/text" to "lineHeightSp") to numberEditor(1.0, 1024.0, 1.0),
      ("m3/text" to "letterSpacingSp") to numberEditor(-32.0, 128.0, 0.1),
      ("m3/text" to "minLines") to numberEditor(1.0, 100.0, 1.0),
      ("m3/text" to "maxLines") to numberEditor(1.0, 100.0, 1.0),
      ("m3/text" to "weight") to numberEditor(0.1, 100.0, 0.1),
    )

  private fun numberEditor(
    minimum: Double,
    maximum: Double,
    step: Double,
  ) =
    PropertyEditorCapability(
      control = PropertyEditorControl.NUMBER,
      minimum = minimum,
      maximum = maximum,
      step = step,
    )

  private fun colorEditor() =
    PropertyEditorCapability(
      control = PropertyEditorControl.COLOR,
      suggestedValues = MATERIAL_COLOR_TOKENS,
    )
}
