package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.codegen.COMPOSE_EMITTED_DP_PROPERTIES
import ee.schimke.composeai.uibuilder.editor.ComponentMenu
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.CatalogBuilderRoles
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPacks
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.UiRemoteTheme
import ee.schimke.composeai.uibuilder.export.UiTimeText
import ee.schimke.composeai.uibuilder.export.VariableFontText
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class CapabilityCatalog(
  val schema: String,
  val benchmark: CapabilityBenchmark,
  val statusSemantics: JsonObject = JsonObject(emptyMap()),
  val components: List<ComponentCapability>,
  val browserPreview: BrowserPreviewCapabilityV1? = null,
) {
  val componentsById: Map<String, ComponentCapability> by lazy {
    components.associateBy(ComponentCapability::componentId)
  }

  /**
   * Which renderer may claim to be showing you this catalog's designs. Read from [statusSemantics]
   * because the wire type is published from another repository (see [UiBuilderPreviewSurfaces]);
   * the default is the canvas.
   */
  val previewSurfaces: UiBuilderPreviewSurfaces by lazy {
    UiBuilderPreviewSurfaces.from(statusSemantics)
  }

  /**
   * How the builder's insert panel shelves this catalog's components. Read from [statusSemantics]
   * like [previewSurfaces]; see [ComponentMenu]. Defaults to [ComponentMenu.EMPTY] (group by
   * component kind).
   */
  val componentMenu: ComponentMenu by lazy { ComponentMenu.from(statusSemantics) }

  /**
   * The values this design system lets a design re-skin, and where each lands. Read out of
   * [statusSemantics] for the same reason [previewSurfaces] is; see [DesignTokens]. A catalog that
   * says nothing has none, and the Theme panel shows no token section.
   */
  val designTokens: List<DesignToken> by lazy { DesignTokens.from(statusSemantics) }

  /**
   * Components this build keeps out of the insert panel although the catalog declares them.
   * `RemoteContentEmitter` refuses a repetition unless Remote Compose authoring is enabled;
   * existing designs holding one must still validate, open and edit, so it stays a catalog
   * component and only export refuses it.
   */
  val paletteHiddenComponentIds: Set<String> by lazy {
    if (
      !UiBuilderBuildFeatures.remoteCompose && platform == UiBuilderCatalogPlatform.REMOTE_COMPOSE
    ) {
      setOf("layout/for-each")
    } else emptySet()
  }
  /**
   * The components the insert panel lists: every one this catalog declares, less
   * [paletteHiddenComponentIds].
   */
  val paletteComponents: List<ComponentCapability> by lazy {
    components.filterNot { it.componentId in paletteHiddenComponentIds }
  }

  /**
   * Which kind of screen this catalog authors: a phone, a watch, a Remote Compose widget. Read from
   * [statusSemantics] like [previewSurfaces]; defaults to mobile.
   */
  val platform: UiBuilderCatalogPlatform by lazy { UiBuilderCatalogPlatform.from(statusSemantics) }

  /**
   * The component packs the runtime merged into this catalog (another catalog's components, shelved
   * under their own name and switched on in settings). Read from [statusSemantics] like
   * [previewSurfaces]; see [UiBuilderComponentPacks].
   */
  val componentPacks: UiBuilderComponentPacks by lazy {
    UiBuilderComponentPacks.from(statusSemantics)
  }

  /**
   * The components this catalog asks the insert panel to keep at the top. Read from
   * [statusSemantics] like [previewSurfaces]; only the catalog knows its typical app. Ids the
   * catalog does not offer are dropped.
   */
  val pinnedComponents: Set<String> by lazy {
    (statusSemantics["pinnedComponents"] as? JsonArray)
      .orEmpty()
      .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      .filterTo(mutableSetOf()) { it in componentsById }
  }

  /**
   * Which slot each component considers its main one — the place an empty container invites a drop
   * into, and the slot a drop prefers when two regions tie.
   *
   * Declared by the catalog under `recommendedSlots`, because it is a fact about how that catalog's
   * components are meant to be assembled (`m3/scaffold`'s content is the screen; its topBar is
   * optional trim) and not something this repository can know for a catalog it has not seen. A
   * catalog that declares nothing gets [derivedRecommendedSlot]: the slot that accepts the most of
   * the catalog, which lands on `content` and `children` for the obvious reasons.
   *
   * Only slots the component actually declares are considered, from either source — a stale
   * declaration must not invent a destination.
   */
  val recommendedSlots: Map<String, String> by lazy {
    val declared =
      (statusSemantics["recommendedSlots"] as? JsonObject)
        .orEmpty()
        .mapNotNull { (componentId, value) ->
          val slotNames =
            (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
          val declaredSlots = componentsById[componentId]?.slotsByName.orEmpty()
          slotNames.firstOrNull { it in declaredSlots }?.let { componentId to it }
        }
        .toMap()
    components
      .mapNotNull { component ->
        val slot =
          declared[component.componentId]
            ?: when (component.slots.size) {
              0 -> null
              1 -> component.slots.single().name
              // The slot accepting the most of this catalog wins; ties go to the first declared
              // slot.
              else -> component.slots.maxByOrNull { slot -> components.count(slot::accepts) }?.name
            }
        slot?.let { component.componentId to it }
      }
      .toMap()
  }

  /** The slot [componentId] considers its main one, or null where it declares no slots. */
  fun recommendedSlot(componentId: String): String? = recommendedSlots[componentId]
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

/**
 * Whether this slot accepts [component] as a child. The one slot rule, used by the validator, the
 * editor's drop targets and required-slot fill alike, and mirrored by the server's `slotAccepts` in
 * `:ui-builder-runtime`.
 *
 * A child must pass **both** axes: an empty `acceptedRoles` or `acceptedTraits` constrains nothing
 * on that axis, and `AnyContent` in the traits is the same as declaring none. (Matching either axis
 * let coarse roles admit e.g. a lazy grid into `layout/scaffold.topBar`.)
 */
fun SlotCapability.accepts(component: ComponentCapability): Boolean =
  slotAccepts(acceptedRoles, acceptedTraits, component.role, component.traits)

/** [SlotCapability.accepts] over the raw lists, for a caller holding another slot type. */
fun slotAccepts(
  acceptedRoles: List<String>,
  acceptedTraits: List<String>,
  role: String,
  traits: List<String>,
): Boolean {
  val roleAccepted = acceptedRoles.isEmpty() || role in acceptedRoles
  val traitAccepted =
    acceptedTraits.isEmpty() ||
      "AnyContent" in acceptedTraits ||
      traits.any(acceptedTraits::contains)
  return roleAccepted && traitAccepted
}

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
 * Builder-only presentation metadata for a catalog property, carried by the catalog resource rather
 * than the wire protocol: it describes how to author an existing JSON shape, not a new value kind.
 */
@Serializable
data class PropertyEditorCapability(
  val control: PropertyEditorControl? = null,
  /**
   * For a property the catalog declares only as `"object"`, which closed value shape it holds
   * (`padding`, `adaptiveGrid`), so the inspector can edit it.
   */
  val objectKind: String? = null,
  val minimum: Double? = null,
  val maximum: Double? = null,
  val step: Double? = null,
  val suggestedValues: List<String> = emptyList(),
  /**
   * A count, whatever the catalog's JSON type says (Wear declares `steps` and `segments` as
   * `number`), so fractional values are refused.
   */
  val integer: Boolean = false,
  /**
   * For a [PropertyEditorControl.LIST] or [PropertyEditorControl.OBJECT] property (a typed
   * adapter's list or object codec), the recursive shape its catalog publishes in the capability's
   * `editor`; read by [PropertyValueShapes.compositeEditor], never decoded field by field.
   */
  @Transient val valueShape: PropertyValueShape? = null,
)

@Serializable
enum class PropertyEditorControl {
  @SerialName("text") TEXT,
  @SerialName("boolean") BOOLEAN,
  @SerialName("number") NUMBER,
  @SerialName("enum") ENUM,
  @SerialName("color") COLOR,
  /**
   * A typed adapter's list: rows added, removed and reordered, each edited by its element shape.
   */
  @SerialName("list") LIST,
  /** A typed adapter's object: one row per field, each edited by its own shape. */
  @SerialName("object") OBJECT,
  /**
   * A typed adapter's sealed type: a case picked from its cases, then that case's fields, each
   * edited by its own shape.
   */
  @SerialName("union") UNION,
}

@Serializable
data class WasmCapability(
  val platformSupported: JsonElement,
  val adapterStatus: WasmAdapterStatus,
  val notes: String? = null,
  /**
   * Which canvas adapter draws this component, named by the catalog
   * (`UI_BUILDER_CATALOG_CONTRACT.md` item 17), so a catalog can reuse an adapter this build has.
   *
   * Null means key on the component id. An id this build has no case for draws the named
   * placeholder, not an error.
   */
  val canvas: String? = null,
  val canvasMapping: CanvasAdapterMappingV1? = null,
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
      .withFontSettings()
      .withEditorMetadata(compositeEditors(element))
      .let { catalog ->
        if (UiBuilderBuildFeatures.remoteCompose) catalog
        else
          catalog.copy(
            components =
              catalog.components.map {
                it.copy(
                  properties =
                    it.properties.filterNot { property -> property.name == SHOW_BY_STATE }
                )
              }
          )
      }
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
   * Every text component's [FontSettings] properties, for a catalog that doesn't declare them.
   * Mirrors the server's `withFontSettingsVocabulary` for hosts reading catalog files directly. A
   * catalog's own declaration wins.
   */
  private fun CapabilityCatalog.withFontSettings(): CapabilityCatalog =
    copy(
      components =
        components.map { component ->
          if (!CatalogBuilderRoles.isText(component.componentId, component.traits)) {
            return@map component
          }
          val declared = component.properties.mapTo(mutableSetOf()) { it.name }
          component.copy(
            properties =
              component.properties +
                listOf(
                    FontSettings.VARIATION_PROPERTY to FontSettings.VARIATION_NOTES,
                    FontSettings.FEATURE_PROPERTY to FontSettings.FEATURE_NOTES,
                  )
                  .filter { (name, _) -> name !in declared }
                  .map { (name, notes) ->
                    PropertyCapability(name, JsonPrimitive("string"), notes = notes)
                  }
          )
        }
    )

  /**
   * The list and object editors a catalog's own property capabilities publish (a typed adapter's
   * `editor` hint, beside `jsonType`), keyed by component and property. Read from the raw element
   * because [PropertyCapability.editor] is builder-side metadata the wire type does not carry.
   */
  private fun compositeEditors(
    element: JsonElement
  ): Map<Pair<String, String>, PropertyEditorCapability> =
    ((element as? JsonObject)?.get("components") as? JsonArray)
      .orEmpty()
      .flatMap { component ->
        val declared = component as? JsonObject ?: return@flatMap emptyList()
        val componentId =
          (declared["componentId"] as? JsonPrimitive)?.contentOrNull ?: return@flatMap emptyList()
        (declared["properties"] as? JsonArray).orEmpty().mapNotNull { property ->
          val capability = property as? JsonObject ?: return@mapNotNull null
          val name = (capability["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
          val editor = capability["editor"] as? JsonObject ?: return@mapNotNull null
          val nullable = (capability["nullable"] as? JsonPrimitive)?.booleanOrNull ?: false
          PropertyValueShapes.declaredCompositeEditor(editor, nullable)?.let {
            (componentId to name) to it
          }
        }
      }
      .toMap()

  /**
   * Adds builder-only presentation metadata without changing the released catalog wire shape. Enum
   * choices stay authoritative; the bounds are the range the renderer and Compose exporter both
   * round trip. A list or object editor the catalog publishes ([compositeEditors]) comes first: it
   * is the only description of that value's shape.
   */
  private fun CapabilityCatalog.withEditorMetadata(
    composite: Map<Pair<String, String>, PropertyEditorCapability>
  ): CapabilityCatalog =
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
                property.copy(
                  editor =
                    composite[component.componentId to property.name]
                      ?: CatalogPublishedPolicy.editorFor(
                        statusSemantics,
                        component.componentId,
                        property.name,
                      )
                      ?: editorMetadata(component.componentId, property)
                )
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
    // A draw operation states what each number measures, so its range comes from there rather than
    // from a name rule: an angle, a fraction and a dp offset are three different controls.
    (UiDrawing.BY_ID[componentId]?.properties
        ?: UiDrawing.CANVAS_PROPERTIES.takeIf { componentId == UiDrawing.CANVAS }
        ?: UiTimeText.PROPERTIES.takeIf { componentId == UiTimeText.ID }
        ?: UiRemoteTheme.PROPERTIES.takeIf { componentId == UiRemoteTheme.ID })
      ?.firstOrNull { it.name == property.name }
      ?.let { declared ->
        when (declared) {
          is UiDrawing.Property.Number ->
            return numberEditor(declared.unit.minimum, declared.unit.maximum, declared.unit.step)
          is UiDrawing.Property.Color -> return colorEditor()
          is UiDrawing.Property.Text ->
            return PropertyEditorCapability(control = PropertyEditorControl.TEXT)
          is UiDrawing.Property.Flag ->
            return PropertyEditorCapability(control = PropertyEditorControl.BOOLEAN)
          is UiDrawing.Property.Choice -> Unit
        }
      }
    // A Wear component edits like its Material 3 counterpart where they share a property (the
    // overrides above are keyed on Material 3 ids).
    COUNTERPARTS[componentId]?.let { counterpart -> EDITOR_OVERRIDES[counterpart to property.name] }
      ?.let {
        return it
      }
    // `weight` is a node's share of its Row or Column for any component, and never a `…Dp`; same
    // range as `m3/text`'s weight.
    if (property.name == "weight") {
      return numberEditor(0.1, 100.0, 0.1)
    }
    // A Remote Compose length (`RemoteDp`) keeps its parameter's own name rather than a `…Dp`
    // suffix, so its range comes from the names the records give one: a stroke, a gap, a border, a
    // radius, an inset.
    if (property.name in REMOTE_DP_PROPERTY_NAMES && property.isNumber()) {
      return numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0)
    }
    // A colour property gets a colour control, by the same name rule `PropertyValueKinds` uses to
    // decide what a value means, so the editor never writes a spelling the reducers refuse.
    if (PropertyValueKinds.isColour(property.name)) return colorEditor()
    return when ((property.jsonType as? JsonPrimitive)?.contentOrNull) {
      "string" -> PropertyEditorCapability(control = PropertyEditorControl.TEXT)
      "boolean" -> PropertyEditorCapability(control = PropertyEditorControl.BOOLEAN)
      // A numeric property's unit is in its name (`…Dp`), which is enough to give it a range, so
      // new components arrive editable. An explicit override (checked above) still wins.
      "number",
      "integer" ->
        // …and only where a projection reads it: a control whose values every emitter discards
        // would be misleading. `COMPOSE_EMITTED_DP_PROPERTIES` is derived from the exporter's own
        // table.
        if (
          property.name.endsWith("Dp") &&
            "$componentId.${property.name}" in COMPOSE_EMITTED_DP_PROPERTIES
        )
          // Arrangement spacing may be negative (overlap); padding and sizes may not, so the floor
          // is per property.
          numberEditor(
            if (property.name.endsWith("SpacingDp")) -MAXIMUM_AUTHORED_DP else 0.0,
            MAXIMUM_AUTHORED_DP,
            1.0,
          )
        else null
      else -> null
    }
  }

  /** The range the renderer and the Compose exporter both round trip for a dimension. */
  private const val MAXIMUM_AUTHORED_DP = 4096.0

  /** The `RemoteDp` parameters `remote-material3` declares, by the name the catalog keeps. */
  private val REMOTE_DP_PROPERTY_NAMES =
    setOf("strokeWidth", "gapSize", "border", "indicatorRadius", "padding", "spacing")

  private fun PropertyCapability.isNumber(): Boolean =
    ((jsonType as? kotlinx.serialization.json.JsonArray)?.map {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
      } ?: listOf((jsonType as? kotlinx.serialization.json.JsonPrimitive)?.content))
      .any { it == "number" || it == "integer" }

  /**
   * The bound on a slider's own numbers (design units, not dp): wide enough for real use, finite so
   * the `Float` round trip holds.
   */
  private const val MAXIMUM_AUTHORED_VALUE = 1_000_000.0

  /**
   * A variable font text's axes, each over the widest range any of its fonts gives it (the font
   * clamps a value to its own), and its size on `m3/text`'s range. An axis is declared
   * `["number","object"]` so it can be bound to state, which no type rule reads as a number.
   */
  private val VARIABLE_FONT_TEXT_EDITORS =
    listOf(VariableFontText.M3_ID, VariableFontText.WEAR_ID).flatMap { id ->
      VariableFontText.AXIS_PROPERTIES.map { property ->
        val axes = VariableFontText.Font.entries.mapNotNull { it.axis(property) }
        (id to property) to
          numberEditor(
            axes.minOf { it.min }.toDouble(),
            axes.maxOf { it.max }.toDouble(),
            if (property == "slnt") 0.5 else 1.0,
          )
      } + listOf((id to "fontSizeSp") to numberEditor(1.0, 512.0, 1.0))
    }

  /**
   * The Remote Material 3 numbers, declared `["number", "object"]` (literal or state read) and not
   * `…Dp`, so no type rule can bound them. Ranges follow the Material ones: a fraction 0..1, an
   * angle a turn either way, a slider's value in design units.
   */
  private val REMOTE_MATERIAL_3_NUMBER_EDITORS =
    listOf(
        "remote-m3/remote-circular-progress-indicator",
        "remote-m3/remote-linear-progress-indicator",
        "remote-m3/remote-curved-progress-indicator",
      )
      .map { (it to "progress") to numberEditor(0.0, 1.0, 0.05) } +
      listOf("remote-m3/remote-slider", "remote-m3/remote-stepper").flatMap {
        listOf(
          (it to "value") to numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 0.1),
          (it to "steps") to countEditor(0.0, 100.0),
        )
      } +
      listOf(
          "remote-m3/remote-horizontal-page-indicator",
          "remote-m3/remote-vertical-page-indicator",
        )
        .flatMap {
          listOf(
            (it to "pageCount") to countEditor(1.0, 20.0),
            (it to "selectedPage") to countEditor(0.0, 19.0),
          )
        } +
      listOf(
        ("remote-m3/remote-circular-progress-indicator" to "startAngle") to angleEditor(),
        ("remote-m3/remote-circular-progress-indicator" to "endAngle") to angleEditor(),
        ("remote-m3/remote-curved-progress-indicator" to "startAngle") to angleEditor(),
        ("remote-m3/remote-curved-progress-indicator" to "sweepAngle") to angleEditor(),
        ("remote-m3/remote-curved-progress-indicator" to "gapAngleDegrees") to
          numberEditor(0.0, 360.0, 1.0),
        ("remote-m3/remote-curved-progress-indicator" to "dotFadeOutFraction") to
          numberEditor(0.0, 1.0, 0.05),
        // `RemoteText`, on the same ranges `m3/text` gives the same two parameters.
        ("remote-m3/remote-text" to "fontSize") to numberEditor(1.0, 512.0, 1.0),
        ("remote-m3/remote-text" to "maxLines") to numberEditor(1.0, 100.0, 1.0),
      )

  private fun angleEditor() = numberEditor(-360.0, 360.0, 1.0)

  private val MATERIAL_COLOR_TOKENS =
    listOf(
      "background",
      "surface",
      "surfaceContainer",
      "surfaceContainerLow",
      "surfaceContainerHigh",
      "surfaceContainerHighest",
      "primary",
      // A token must be known to the renderer, the exporter and this list before a design may hold
      // it.
      "onPrimary",
      "secondary",
      "onSecondary",
      "tertiary",
      "onTertiary",
      "onSurface",
      "onSurfaceVariant",
      "outlineVariant",
      "transparent",
    )

  /**
   * The Wear widget host frames, which declare `WearWidgetContainer`'s parameters. Exact ids (not a
   * prefix) so unknown components fall through to the type rules. Declared above
   * [EDITOR_OVERRIDES], which reads it during initialisation.
   */
  private val WEAR_WIDGET_CONTAINER_IDS =
    listOf(
      "remote-m3/widget-container-small",
      "remote-m3/widget-container-large",
      AdaptiveWearWidget.COMPONENT_ID,
    )

  /**
   * Components that edit like another where they share a property name (see [editorMetadata]); a
   * Stepper takes the slider's ranges.
   */
  private val COUNTERPARTS: Map<String, String> =
    mapOf(
      "wear-m3/text" to "m3/text",
      "wear-m3/list-header" to "m3/text",
      "wear-m3/list-sub-header" to "m3/text",
      "wear-m3/slider" to "m3/slider",
      "wear-m3/stepper" to "m3/slider",
      "wear-m3/progress-indicator" to "m3/progress-indicator",
    )

  private val EDITOR_OVERRIDES =
    mapOf(
      ("layout/list-detail-pane-scaffold" to "activePaneIndex") to numberEditor(0.0, 2.0, 1.0),
      ("layout/supporting-pane-scaffold" to "fixedPaneWidthDp") to numberEditor(0.0, 4096.0, 1.0),
      ("layout/supporting-pane-scaffold" to "splitFraction") to numberEditor(0.1, 0.9, 0.05),
      ("layout/list-detail-pane-scaffold" to "fixedPaneWidthDp") to numberEditor(0.0, 4096.0, 1.0),
      ("layout/list-detail-pane-scaffold" to "splitFraction") to numberEditor(0.1, 0.9, 0.05),
      ("layout/list-detail-pane-scaffold" to "listPanePreferredWidthDp") to
        numberEditor(0.0, 4096.0, 1.0),
      ("layout/list-detail-pane-scaffold" to "detailPanePreferredWidthDp") to
        numberEditor(0.0, 4096.0, 1.0),
      ("layout/list-detail-pane-scaffold" to "extraPanePreferredWidthDp") to
        numberEditor(0.0, 4096.0, 1.0),
      ("layout/list-detail-pane-scaffold" to "paneSpacingDp") to numberEditor(0.0, 512.0, 1.0),
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
      // Declared only as `"object"`, so the closed value shape is named here.
      ("layout/lazy-column" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-grid" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-row" to "contentPadding") to objectEditor("padding"),
      ("m3/horizontal-floating-toolbar" to "contentPadding") to objectEditor("padding"),
      ("layout/lazy-grid" to "columns") to objectEditor("adaptiveGrid"),
      // A fraction, bounded 0..1 as both Material indicators take it.
      ("m3/progress-indicator" to "progress") to numberEditor(0.0, 1.0, 0.05),
      // A count; zero is Compose's own "no limit", which is how the canvas reads it.
      ("layout/flow-row" to "maxItemsInEachRow") to countEditor(0.0, 100.0),
      // Dimensions the `…Dp` rule can't reach because the Compose exporter doesn't write them:
      // Remote Compose loop/collapsible spacing, Wear list and icon sizes, a custom slot's frame,
      // and the search bar corner.
      ("layout/for-each" to "verticalSpacingDp") to spacingEditor(),
      ("layout/collapsible-column" to "verticalSpacingDp") to spacingEditor(),
      ("layout/collapsible-row" to "horizontalSpacingDp") to spacingEditor(),
      ("wear-m3/transforming-lazy-column" to "verticalSpacingDp") to spacingEditor(),
      ("wear-m3/icon" to "sizeDp") to numberEditor(1.0, 512.0, 1.0),
      ("remote-compose/custom" to "widthDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
      ("remote-compose/custom" to "heightDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
      ("m3/search-bar" to "shapeDp") to numberEditor(0.0, MAXIMUM_AUTHORED_DP, 1.0),
      // Fractions.
      ("shape/radial-gradient" to "innerAlpha") to numberEditor(0.0, 1.0, 0.05),
      ("remote-m3/lottie" to "progress") to numberEditor(0.0, 1.0, 0.05),
      // Wear's segmented ring: how many arcs it is cut into. A count, so the `…Dp` rule misses it.
      ("wear-m3/progress-indicator" to "segments") to countEditor(1.0, 64.0),
      // A slider's range and value are numbers in the design's own units, not dimensions.
      ("m3/slider" to "value") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 0.1),
      ("m3/slider" to "valueFrom") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 1.0),
      ("m3/slider" to "valueTo") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 1.0),
      // Material counts the stops *between* the ends, so zero is a continuous slider.
      ("m3/slider" to "steps") to countEditor(0.0, 100.0),
      // A tab index is a count, not a dimension, so the `…Dp` rule below cannot reach it and the
      // control would be `Unsupported` — on the one property a tab row has.
      ("m3/primary-tab-row" to "selectedIndex") to numberEditor(0.0, 32.0, 1.0),
      ("m3/primary-scrollable-tab-row" to "selectedIndex") to numberEditor(0.0, 32.0, 1.0),
      ("m3/time-picker" to "hour") to numberEditor(0.0, 23.0, 1.0),
      ("m3/time-picker" to "minute") to numberEditor(0.0, 59.0, 1.0),
      // A2UI's Slider: the range and the value are in the payload's own units, like `m3/slider`.
      ("a2ui/Slider" to "min") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 1.0),
      ("a2ui/Slider" to "max") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 1.0),
      ("a2ui/Slider" to "value") to
        numberEditor(-MAXIMUM_AUTHORED_VALUE, MAXIMUM_AUTHORED_VALUE, 0.1),
    ) +
      WEAR_WIDGET_CONTAINER_IDS.flatMap(::widgetContainerEditors) +
      REMOTE_MATERIAL_3_NUMBER_EDITORS +
      VARIABLE_FONT_TEXT_EDITORS

  /**
   * The editor for the container's `background`, declared `"string"`: a colour editor that also
   * accepts tokens like `primary`. Padding and corner radius are the host's (`WearWidgetParams`,
   * per host shape), not the widget's.
   */
  private fun widgetContainerEditors(componentId: String) =
    listOf((componentId to "background") to colorEditor())

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

  /** Arrangement spacing, which may be negative: that is how children are made to overlap. */
  private fun spacingEditor() = numberEditor(-MAXIMUM_AUTHORED_DP, MAXIMUM_AUTHORED_DP, 1.0)

  private fun countEditor(minimum: Double, maximum: Double) =
    numberEditor(minimum, maximum, 1.0).copy(integer = true)

  private fun colorEditor() =
    PropertyEditorCapability(
      control = PropertyEditorControl.COLOR,
      suggestedValues = MATERIAL_COLOR_TOKENS,
    )
}
