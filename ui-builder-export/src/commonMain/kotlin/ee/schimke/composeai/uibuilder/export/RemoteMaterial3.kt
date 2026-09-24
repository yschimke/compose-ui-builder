package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.EMBEDDED_REMOTE_M3_RECORD_JSON
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Remote Material 3 components a widget body can hold:
 * `androidx.wear.compose.remote.material3`, under the ids `yschimke/wear-m3-catalog` publishes them
 * as.
 *
 * ## One table, three readers
 *
 * - **The built-in `remote-m3` catalog** offers each on the widget palette, its properties and
 *   slots read off the component's own signature in the embedded record.
 * - **The canvas** draws each with the Wear Material 3 adapter the published catalog names —
 *   `RemoteButton` is `wear-m3/button` — through [Component.canvasMapping], so a widget shows a
 *   Wear button rather than a placeholder.
 * - **The export** writes each from its record: [records] is what `RemoteContentEmitter`'s record
 *   fallback reads, so `remote-m3/remote-button` becomes `RemoteButton(onClick = …) { … }`.
 *
 * The ids, record ids, shelves, canvas adapters and mappings are wear-m3-catalog's
 * `remote-catalog/ui-builder.policy.json`, row for row, so a design authored against this catalog
 * opens unchanged against the published one and the other way round.
 *
 * ## What is left out, and why
 *
 * Each of these is published and would be refused by every export, because a required parameter has
 * no value this generator can write:
 * - `remote-m3/remote-icon` takes an `ImageVector`;
 * - `remote-m3/remote-horizontal-page-indicator` and `remote-m3/remote-vertical-page-indicator`
 *   take a pager's state.
 *
 * `remote-m3/remote-text` is left out because `m3/text` already offers text on this palette and is
 * written as the same `RemoteText`; a second Text beside it would be two answers to one question.
 */
public object RemoteMaterial3 {

  /**
   * One Remote Material 3 component.
   *
   * @property recordId the component's `canonicalId` in the embedded record.
   * @property group the palette shelf, as the published catalog files it.
   * @property canvas the Wear Material 3 canvas adapter that draws it.
   */
  public class Component
  internal constructor(
    public val componentId: String,
    public val recordId: String,
    public val displayName: String,
    public val group: String,
    public val canvas: String,
    public val canvasMapping: CanvasAdapterMappingV1,
  )

  public val components: List<Component> =
    listOf(
      component(
        id = "remote-m3/remote-app-card",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteAppCardKt.RemoteAppCard",
        displayName = "App card",
        group = "Containment",
        canvas = "wear-m3/card",
        canvasProperties = emptyMap(),
        canvasSlots = mapOf("content" to "title"),
        canvasDefaults = mapOf("variant" to wrapped("enum", "app")),
      ),
      component(
        id = "remote-m3/remote-button-group",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteButtonGroupKt.RemoteButtonGroup",
        displayName = "Button group",
        group = "Buttons",
        canvas = "wear-m3/button-group",
        canvasProperties = emptyMap(),
        canvasSlots = mapOf("children" to "content"),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-circular-progress-indicator",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteCircularProgressIndicatorKt.RemoteCircularProgressIndicator",
        displayName = "Circular progress indicator",
        group = "Communication",
        canvas = "wear-m3/progress-indicator",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "circular")),
      ),
      component(
        id = "remote-m3/remote-linear-progress-indicator",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteLinearProgressIndicatorKt.RemoteLinearProgressIndicator",
        displayName = "Linear progress indicator",
        group = "Communication",
        canvas = "wear-m3/progress-indicator",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "linear")),
      ),
      component(
        id = "remote-m3/remote-curved-progress-indicator",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteCurvedProgressIndicatorKt.RemoteCurvedProgressIndicator",
        displayName = "Curved progress indicator",
        group = "Communication",
        canvas = "wear-m3/progress-indicator",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "arc")),
      ),
      component(
        id = "remote-m3/remote-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteButtonKt.RemoteButton",
        displayName = "Button",
        group = "Buttons",
        canvas = "wear-m3/button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "filled")),
      ),
      component(
        id = "remote-m3/remote-compact-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteButtonKt.RemoteCompactButton",
        displayName = "Compact button",
        group = "Buttons",
        canvas = "wear-m3/button",
        canvasProperties = emptyMap(),
        canvasSlots = mapOf("content" to "label"),
        canvasDefaults = mapOf("variant" to wrapped("enum", "filled")),
      ),
      component(
        id = "remote-m3/remote-card",
        record = "remote-catalog/androidx.wear.compose.remote.material3.RemoteCardKt.RemoteCard",
        displayName = "Card",
        group = "Containment",
        canvas = "wear-m3/card",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "filled")),
      ),
      component(
        id = "remote-m3/remote-outlined-card",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteCardKt.RemoteOutlinedCard",
        displayName = "Outlined card",
        group = "Containment",
        canvas = "wear-m3/card",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "outlined")),
      ),
      component(
        id = "remote-m3/remote-checkbox-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteCheckboxButtonKt.RemoteCheckboxButton",
        displayName = "Checkbox button",
        group = "Selection buttons",
        canvas = "wear-m3/checkbox-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-switch-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteSwitchButtonKt.RemoteSwitchButton",
        displayName = "Switch button",
        group = "Selection buttons",
        canvas = "wear-m3/switch-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-radio-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteRadioButtonKt.RemoteRadioButton",
        displayName = "Radio button",
        group = "Selection buttons",
        canvas = "wear-m3/radio-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-split-checkbox-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteSplitCheckboxButtonKt.RemoteSplitCheckboxButton",
        displayName = "Split checkbox button",
        group = "Selection buttons",
        canvas = "wear-m3/checkbox-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-split-switch-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteSplitSwitchButtonKt.RemoteSplitSwitchButton",
        displayName = "Split switch button",
        group = "Selection buttons",
        canvas = "wear-m3/switch-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-split-radio-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteSplitRadioButtonKt.RemoteSplitRadioButton",
        displayName = "Split radio button",
        group = "Selection buttons",
        canvas = "wear-m3/radio-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-slider",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteSliderKt.RemoteSlider",
        displayName = "Slider",
        group = "Sliders",
        canvas = "wear-m3/slider",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults =
          mapOf("valueFrom" to wrapped("float", 0.0f), "valueTo" to wrapped("float", 1.0f)),
      ),
      component(
        id = "remote-m3/remote-stepper",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteStepperKt.RemoteStepper",
        displayName = "Stepper",
        group = "Steppers",
        canvas = "wear-m3/stepper",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults =
          mapOf("valueFrom" to wrapped("float", 0.0f), "valueTo" to wrapped("float", 1.0f)),
      ),
      component(
        id = "remote-m3/remote-edge-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteEdgeButtonKt.RemoteEdgeButton",
        displayName = "Edge button",
        group = "Edge-hugging buttons",
        canvas = "wear-m3/edge-button",
        canvasProperties = mapOf("size" to "buttonSize"),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
      ),
      component(
        id = "remote-m3/remote-icon-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteIconButtonKt.RemoteIconButton",
        displayName = "Icon button",
        group = "Buttons",
        canvas = "wear-m3/icon-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "filled")),
      ),
      component(
        id = "remote-m3/remote-text-button",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteTextButtonKt.RemoteTextButton",
        displayName = "Text button",
        group = "Buttons",
        canvas = "wear-m3/text-button",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "standard")),
      ),
      component(
        id = "remote-m3/remote-title-card",
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteTitleCardKt.RemoteTitleCard",
        displayName = "Title card",
        group = "Containment",
        canvas = "wear-m3/card",
        canvasProperties = emptyMap(),
        canvasSlots = mapOf("content" to "title"),
        canvasDefaults = mapOf("variant" to wrapped("enum", "title")),
      ),
    )

  /**
   * Each component's record, keyed by its component id and carrying that id — the shape
   * `RemoteContentEmitter` takes, which is the published catalog's aliased record.
   *
   * Empty only if the embedded record failed to parse, which a test in this module rules out.
   */
  public val records: Map<String, ComponentRecord> by
    lazy(LazyThreadSafetyMode.PUBLICATION) {
      val byCanonicalId = embeddedRecord?.components.orEmpty().associateBy { it.canonicalId }
      components
        .mapNotNull { component ->
          byCanonicalId[component.recordId]?.let {
            component.componentId to it.copy(componentIds = listOf(component.componentId))
          }
        }
        .toMap()
    }

  /** The component with this id, or null for one this table does not offer. */
  public fun component(componentId: String): Component? = byId[componentId]

  private val byId = components.associateBy(Component::componentId)

  private fun component(
    id: String,
    record: String,
    displayName: String,
    group: String,
    canvas: String,
    canvasProperties: Map<String, String>,
    canvasSlots: Map<String, String>,
    canvasDefaults: Map<String, JsonObject>,
  ) =
    Component(
      componentId = id,
      recordId = record,
      displayName = displayName,
      group = group,
      canvas = canvas,
      canvasMapping =
        CanvasAdapterMappingV1.Builder()
          .also {
            it.properties = canvasProperties
            it.slots = canvasSlots
            it.defaults = JsonObject(canvasDefaults)
          }
          .build(),
    )

  private fun wrapped(type: String, value: Any): JsonObject =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(type),
        "value" to
          when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("unsupported canvas default $value")
          },
      )
    )
}

private val recordJson = Json { ignoreUnknownKeys = true }

private val embeddedRecord: ComponentRecordFile? by
  lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
      recordJson.decodeFromString(ComponentRecordFile.serializer(), EMBEDDED_REMOTE_M3_RECORD_JSON)
    }
      .getOrNull()
  }
