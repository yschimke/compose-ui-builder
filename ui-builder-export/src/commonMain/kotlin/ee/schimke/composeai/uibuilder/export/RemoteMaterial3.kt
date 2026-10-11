package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.EMBEDDED_REMOTE_M3_RECORD_JSON
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Remote Material 3 components a widget body can hold
 * (`androidx.wear.compose.remote.material3`), under the ids `yschimke/wear-m3-catalog` publishes.
 * One table read by the built-in `remote-m3` catalog (palette), the canvas (via
 * [Component.canvasMapping]) and the export ([records], read by
 * `RemoteContentEmitter`'s record fallback).
 *
 * Rows match wear-m3-catalog's `remote-catalog/ui-builder.policy.json`, so designs open against
 * either catalog. Hand-written cases: the page indicators (`RemotePageIndicatorState` built from
 * `pageCount`/`selectedPage`) and `remote-m3/remote-icon` (a Material icon key written as
 * `Icons.<Style>.<Name>.toRemoteImageVector()`). `m3/text` stays beside `remote-m3/remote-text` for
 * designs stored against it.
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
        id = REMOTE_HORIZONTAL_PAGE_INDICATOR_ID,
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemotePageIndicatorKt.RemoteHorizontalPageIndicator",
        displayName = "Horizontal page indicator",
        group = "Position indicators",
        canvas = "wear-m3/page-indicator",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "horizontal")),
      ),
      component(
        id = REMOTE_VERTICAL_PAGE_INDICATOR_ID,
        record =
          "remote-catalog/androidx.wear.compose.remote.material3.RemotePageIndicatorKt.RemoteVerticalPageIndicator",
        displayName = "Vertical page indicator",
        group = "Position indicators",
        canvas = "wear-m3/page-indicator",
        canvasProperties = emptyMap(),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("variant" to wrapped("enum", "vertical")),
      ),
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
        id = REMOTE_LABEL_BUTTON_ID,
        record = LABEL_BUTTON_RECORD_ID,
        displayName = "Label button",
        group = "Buttons",
        canvas = "wear-m3/button",
        canvasProperties = emptyMap(),
        canvasSlots = mapOf("content" to "label"),
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
        id = REMOTE_ICON_COMPONENT_ID,
        record = "remote-catalog/androidx.wear.compose.remote.material3.RemoteIconKt.RemoteIcon",
        displayName = "Icon",
        group = "Iconography",
        canvas = "wear-m3/icon",
        canvasProperties = mapOf("iconKey" to "imageVector", "color" to "tint"),
        canvasSlots = emptyMap(),
        canvasDefaults = mapOf("iconKey" to wrapped("enum", REMOTE_ICON_DEFAULT_KEY)),
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
        id = REMOTE_TEXT_COMPONENT_ID,
        record = "remote-catalog/androidx.wear.compose.remote.material3.RemoteTextKt.RemoteText",
        displayName = "Text",
        group = "Text",
        canvas = "wear-m3/text",
        canvasProperties = mapOf("fontSizeSp" to "fontSize"),
        canvasSlots = emptyMap(),
        canvasDefaults = emptyMap(),
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
   * Each component's record, keyed by its component id and carrying that id (the published
   * catalog's aliased shape). Non-empty, as a test in this module ensures.
   */
  public val records: Map<String, ComponentRecord> by
    lazy(LazyThreadSafetyMode.PUBLICATION) {
      val embedded = embeddedRecord?.components.orEmpty()
      val byCanonicalId =
        (embedded + listOfNotNull(labelButtonRecord(embedded))).associateBy { it.canonicalId }
      components
        .mapNotNull { component ->
          byCanonicalId[component.recordId]?.let {
            component.componentId to
              it.newBuilder().apply { componentIds = listOf(component.componentId) }.build()
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

/**
 * `RemoteButton`'s label overload (`label`, `secondaryLabel`, `icon` slots), assembled from the
 * record's `content` overload with slots typed as `RemoteCheckboxButton` declares them. `label` is
 * placed before the defaulted parameters so the emitter names it, selecting this overload rather
 * than a trailing lambda that matches both.
 */
public const val REMOTE_LABEL_BUTTON_ID: String = "remote-m3/remote-label-button"

/**
 * Rows the published catalog's policy does not have yet (assembled overloads); designs using them
 * open only against this built-in catalog, as with [UiTimeText] or [UiRemoteTheme].
 */
internal val UNPUBLISHED_REMOTE_M3_IDS: Set<String> = setOf(REMOTE_LABEL_BUTTON_ID)

private const val LABEL_BUTTON_RECORD_ID: String =
  "remote-catalog/androidx.wear.compose.remote.material3.RemoteButtonKt.RemoteButton#label"

private fun labelButtonRecord(embedded: List<ComponentRecord>): ComponentRecord? {
  val button =
    embedded.firstOrNull {
      it.canonicalId ==
        "remote-catalog/androidx.wear.compose.remote.material3.RemoteButtonKt.RemoteButton"
    } ?: return null
  val slots =
    embedded
      .firstOrNull {
        it.canonicalId ==
          "remote-catalog/androidx.wear.compose.remote.material3.RemoteCheckboxButtonKt.RemoteCheckboxButton"
      }
      ?.parameters
      ?.associateBy { it.name } ?: return null
  val own = button.parameters.associateBy { it.name }
  val order =
    listOf(
      own["onClick"],
      own["modifier"],
      slots["label"],
      slots["secondaryLabel"],
      slots["icon"],
      own["enabled"],
      own["colors"],
      own["border"],
      own["borderColor"],
      own["shape"],
      own["contentPadding"],
    )
  if (order.any { it == null }) return null
  val parameters = order.filterNotNull()
  return button
    .newBuilder()
    .also {
      it.canonicalId = LABEL_BUTTON_RECORD_ID
      it.parameters = parameters
    }
    .build()
}

/**
 * The asset whose picture fills a button's or card's container (`containerPainter` overloads,
 * written `painterRemoteImageBitmap(…)`). A stated property, since the record holds the overload
 * without one.
 */
public const val REMOTE_CONTAINER_IMAGE_PROPERTY: String = "containerImageKey"

/** The components [REMOTE_CONTAINER_IMAGE_PROPERTY] is offered on. */
public val REMOTE_CONTAINER_PAINTER_IDS: Set<String> =
  setOf("remote-m3/remote-button", REMOTE_LABEL_BUTTON_ID, "remote-m3/remote-card")

/** `RemoteIcon`, whose `imageVector` a design names by Material icon key. */
public const val REMOTE_ICON_COMPONENT_ID: String = "remote-m3/remote-icon"

/** `RemoteHorizontalPageIndicator`, written by hand: its state is built from two properties. */
public const val REMOTE_HORIZONTAL_PAGE_INDICATOR_ID: String =
  "remote-m3/remote-horizontal-page-indicator"

/** `RemoteVerticalPageIndicator`; see [REMOTE_HORIZONTAL_PAGE_INDICATOR_ID]. */
public const val REMOTE_VERTICAL_PAGE_INDICATOR_ID: String =
  "remote-m3/remote-vertical-page-indicator"

/**
 * The icon a `remote-m3/remote-icon` draws when its design names none: the published catalog's own
 * default for `imageVector`, so the canvas, the browser preview and the export agree on one glyph.
 */
public const val REMOTE_ICON_DEFAULT_KEY: String = "addCircle"

private val recordJson = Json { ignoreUnknownKeys = true }

private val embeddedRecord: ComponentRecordFile? by
  lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
      recordJson.decodeFromString(ComponentRecordFile.serializer(), EMBEDDED_REMOTE_M3_RECORD_JSON)
    }
      .getOrNull()
  }
