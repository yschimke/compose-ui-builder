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
 * Nothing is left out any more. The two page indicators were: each takes a
 * `RemotePageIndicatorState`, which no property can carry. They are offered now, with the state's
 * two inputs — `pageCount` and `selectedPage` — as properties, and `RemoteContentEmitter` writes
 * `rememberRemotePageIndicatorState(pageCount, selectedPage)` by hand ahead of the record fallback,
 * so `selectedPage` can read a design's Int state and follow it as an action writes it.
 *
 * `remote-m3/remote-icon` takes an `ImageVector` too, and is offered anyway: the vector is chosen
 * by a Material icon key — the same table `m3/icon` and `wear-m3/icon` read — which
 * `RemoteContentEmitter` writes as `Icons.<Style>.<Name>.toRemoteImageVector()` by hand rather than
 * through the record fallback. A launcher or shortcut widget is a row of icon buttons, and without
 * it there was no way to put a glyph inside one (yschimke/remote-m3-catalog#12).
 *
 * `remote-m3/remote-text` is offered beside `m3/text`: both are written as the same `RemoteText`,
 * but the published catalog declares only the former, so a design or template that must open
 * against both catalogs names `remote-m3/remote-text`. `m3/text` stays for the designs already
 * stored against it.
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
   * Each component's record, keyed by its component id and carrying that id — the shape
   * `RemoteContentEmitter` takes, which is the published catalog's aliased record.
   *
   * Empty only if the embedded record failed to parse, which a test in this module rules out.
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
 * `RemoteButton`'s label overload — `label`, `secondaryLabel` and `icon` slots in place of one
 * `content` row — the Wear button shape most designs want.
 *
 * The record holds one overload per symbol, and for `RemoteButton` it is the `content` one, so this
 * one is assembled from it: the button's own parameters, with the three slots as
 * `RemoteCheckboxButton` declares them (the same types: `label` and `secondaryLabel` row-scoped,
 * `icon` plain and optional). `label` is placed before the defaulted parameters rather than last,
 * so the emitter names it instead of writing a trailing lambda: `RemoteButton(onClick = …) { … }`
 * would match both overloads, and a named `label` matches only this one.
 */
public const val REMOTE_LABEL_BUTTON_ID: String = "remote-m3/remote-label-button"

/**
 * Rows this table offers that the published catalog's policy does not have yet. The published
 * record holds one overload per symbol, so an overload assembled here has no row there until the
 * catalog adds one; a design using it opens only against this built-in catalog, as one using
 * [UiTimeText] or [UiRemoteTheme] does.
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
 * The asset whose picture fills a button's or card's container: `RemoteButton`'s and `RemoteCard`'s
 * `containerPainter` overloads, written `painterRemoteImageBitmap(…)`. The record holds the
 * overload without one, so this is a stated property rather than a derived parameter.
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
