package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.COMPOSE_EMITTED_DP_PROPERTIES
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
  /**
   * For a property the catalog declares only as `"object"`, which of the closed value shapes it
   * holds — `padding`, `adaptiveGrid`. The catalog says `"object"` and stops, so without this the
   * inspector cannot tell a four-edge padding from an arbitrary map and refuses to edit either.
   */
  val objectKind: String? = null,
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
      // A numeric property carries its unit in its name, and that is enough to give it a range.
      //
      // Without one it has no bounds, and a number with no bounds is `Unsupported` in the
      // inspector — which is how every spacing, size, elevation and thickness in this catalog came
      // to be uneditable while `m3/text` had six working number fields. Fourteen of the catalog's
      // twenty-two numeric properties were in that state, including `verticalSpacingDp` on Column
      // and `horizontalSpacingDp` on Row: the two controls a person reaches for first.
      //
      // A rule rather than fourteen more entries in [EDITOR_OVERRIDES], because the next component
      // to declare a `…Dp` should arrive editable rather than waiting for someone to notice. An
      // explicit override still wins: it is consulted above this.
      "number",
      "integer" ->
        // …and only where a projection reads it. A dimension no emitter takes is a control whose
        // every value is discarded — worse than no control, because the design then looks
        // authored and renders and exports as if it were not. `COMPOSE_EMITTED_DP_PROPERTIES` is
        // derived from the exporter's own field table, so adding a dimension to an emitter is
        // what makes it editable, rather than someone remembering to.
        if (
          property.name.endsWith("Dp") &&
            "$componentId.${property.name}" in COMPOSE_EMITTED_DP_PROPERTIES
        )
          // Arrangement spacing may be negative — that is how children are made to overlap, and
          // `Arrangement.spacedBy` and the exporter both take the signed value. A padding or a
          // size may not, so the floor is per property rather than one blanket zero.
          numberEditor(
            if (property.name.endsWith("SpacingDp")) -MAXIMUM_AUTHORED_DP else 0.0,
            MAXIMUM_AUTHORED_DP,
            1.0,
          )
        else null
      else -> null
    }
  }

  /**
   * Wide enough for any dimension this renderer can lay out and narrow enough to stay a dimension.
   *
   * The bound is not a design opinion about how much padding is reasonable — it is the range the
   * renderer and the Compose exporter both round trip, which is what a number editor's bounds mean
   * everywhere else in this file.
   */
  private const val MAXIMUM_AUTHORED_DP = 4096.0

  private val MATERIAL_COLOR_TOKENS =
    listOf(
      "background",
      "surface",
      "surfaceContainer",
      "surfaceContainerLow",
      "surfaceContainerHigh",
      "surfaceContainerHighest",
      "primary",
      // Added for the Wear widget samples: a widget on a `primary` background needs the matching
      // on-colour for its text, and the renderer, the exporter and this list are the three places
      // that have to agree on a token before a design may hold it.
      "onPrimary",
      "tertiary",
      "onTertiary",
      "onSurface",
      "onSurfaceVariant",
      "outlineVariant",
      "transparent",
    )

  /**
   * The Wear widget host frames, which declare `WearWidgetContainer`'s parameters.
   *
   * Listed rather than matched by prefix because an override table keyed by exact id is what makes
   * an unrecognised component fall through to the type rules instead of silently inheriting a
   * neighbour's editor. Declared above [EDITOR_OVERRIDES] because that map reads it while this
   * object initialises.
   */
  private val WEAR_WIDGET_CONTAINER_IDS =
    listOf("remote-m3/widget-container-small", "remote-m3/widget-container-large")

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
      // Declared `"object"` and nothing else, so the shape has to be named here. Both are closed
      // protocol value types the renderer already reads and the exporter already emits; only the
      // inspector could not reach them.
      ("layout/lazy-column" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-grid" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-row" to "contentPadding") to objectEditor("padding"),
      ("m3/horizontal-floating-toolbar" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-grid" to "columns") to objectEditor("adaptiveGrid"),
      // A clock, not a dimension. The `…Dp` rule below is what gives a number its editor, so
      // without these two the hour and minute of a time picker would be `Unsupported` in the
      // inspector — a component whose whole content is two numbers, neither of them editable.
      // A tab index is a count, not a dimension, so the `…Dp` rule below cannot reach it and the
      // control would be `Unsupported` — on the one property a tab row has.
      ("m3/primary-tab-row" to "selectedIndex") to numberEditor(0.0, 32.0, 1.0),
      ("m3/time-picker" to "hour") to numberEditor(0.0, 23.0, 1.0),
      ("m3/time-picker" to "minute") to numberEditor(0.0, 59.0, 1.0),
    ) + WEAR_WIDGET_CONTAINER_IDS.flatMap(::widgetContainerEditors)

  /**
   * Editors for the four container parameters, none of which the type rules can supply.
   *
   * `background` is declared `"string"`, so it fell through to a plain text field — you could type
   * `#FF2196F3` into it and nothing would tell you that was the shape it wanted, or that a token
   * like `primary` was also legal. The three dimensions are worse: the `…Dp` rule only offers a
   * number editor for a property the **Compose exporter** emits, and Remote Compose is deliberately
   * outside that exporter, so padding and corner radius arrived uneditable. A property the catalog
   * declares and the renderer reads but the inspector will not show is the same as not having it.
   *
   * `cornerRadiusDp` deliberately reaches 999: that is the value `RoundWidgetPreviewParams` uses
   * for a fully round container, and a bound that stopped short of it would make the round shape
   * unauthorable.
   */
  private fun widgetContainerEditors(componentId: String) =
    listOf(
      (componentId to "background") to colorEditor(),
      (componentId to "horizontalPaddingDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
      (componentId to "verticalPaddingDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
      (componentId to "cornerRadiusDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
    )

  private fun objectEditor(kind: String) = PropertyEditorCapability(objectKind = kind)

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
