package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class WearWidgetScaffoldSize(val componentId: String, val label: String) {
  Small("remote-m3/widget-container-small", "Small (216×76dp)"),
  Large("remote-m3/widget-container-large", "Large (216×124dp)"),
}

/** A slot-ready Wear widget host frame whose dimensions match the upstream preview contract. */
fun wearWidgetUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  size: WearWidgetScaffoldSize,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear widget design id must not be blank" }
  val scaffoldId = "wear-widget-${size.name.lowercase()}"
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Wear widget · ${size.label}",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(scaffoldId),
    nodes =
      mapOf(
        scaffoldId to
          UiBuilderNode(
            id = scaffoldId,
            componentId = size.componentId,
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("content" to emptyList()),
          )
      ),
  )
}

/**
 * A ready-made Wear widget design, reproducing one of the widgets in
 * [android/wear-os-samples' `WearWidget` sample](https://github.com/android/wear-os-samples/pull/1386).
 *
 * The frame's geometry comes from `WearWidgetParams` and its colour is the widget's background,
 * both modelled by the scaffold. These reproduce the sample's design, not a compile of it; fidelity
 * belongs to the parity lanes.
 */
enum class WearWidgetSample(
  val templateId: String,
  val size: WearWidgetScaffoldSize,
  val label: String,
  val supportingText: String,
) {
  /**
   * `HelloWidgetContent`: centred text on the theme's primary, in the Small host (one line of text
   * does not need the Large canvas).
   */
  Hello(
    templateId = "hello-widget",
    size = WearWidgetScaffoldSize.Small,
    label = "Hello widget",
    supportingText = "Centred text on the primary colour, from the Wear widget sample.",
  ),
  /**
   * `WeatherContent`: location over a large reading, on the sample's sunny blue, in the Large host.
   */
  Weather(
    templateId = "weather-widget",
    size = WearWidgetScaffoldSize.Large,
    label = "Weather widget",
    supportingText = "Location and temperature on the sample's sunny blue.",
  );

  /** This sample as a document, ready to open in the editor. */
  fun document(designId: String, catalogPin: JsonObject, environment: JsonObject) =
    when (this) {
      Hello -> helloWidgetUiBuilderDocument(designId, catalogPin, environment)
      Weather -> weatherWidgetUiBuilderDocument(designId, catalogPin, environment)
    }

  public companion object {
    /** The sample with this template id, or null. */
    public fun forTemplate(templateId: String): WearWidgetSample? = entries.firstOrNull {
      it.templateId == templateId
    }
  }
}

/**
 * The sample's Hello widget: `RemoteText` centred in a `RemoteBox` on a primary background.
 *
 * The background is the scaffold's, as `WearWidgetBrush.color(colorScheme.primary)` is in the
 * sample. Centring uses the child's `align` modifier, since `remote-m3/remote-text` declares no
 * `alignment` property and every Remote leaf accepts `align`.
 */
fun helloWidgetUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  wearWidgetSampleDocument(
    designId = designId,
    title = "Hello widget",
    catalogPin = catalogPin,
    environment = environment,
    size = WearWidgetScaffoldSize.Small,
    background = colorToken("primary"),
    contentId = "hello-content",
    content =
      listOf(
        UiBuilderNode(
          id = "hello-content",
          componentId = "layout/box",
          properties = JsonObject(emptyMap()),
          modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
          slots = mapOf("children" to listOf("hello-text")),
        ),
        UiBuilderNode(
          id = "hello-text",
          componentId = REMOTE_TEXT_COMPONENT_ID,
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("Hello, World!")),
                "color" to colorToken("onPrimary"),
                "fontSize" to literal("float", JsonPrimitive(20)),
              )
            ),
          modifiers =
            JsonArray(
              listOf(
                JsonObject(
                  mapOf("type" to JsonPrimitive("align"), "alignment" to JsonPrimitive("center"))
                )
              )
            ),
          slots = emptyMap(),
        ),
      ),
  )

/**
 * The sample's Weather widget, in its sunny state: location over the reading, on `ColorSunny`.
 *
 * A literal colour, not a theme token: the sample picks it by weather condition. Each line is
 * `fillMaxWidth` with `textAlign = center` because the renderer does not read the column's
 * `horizontalAlignment`.
 */
fun weatherWidgetUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument =
  wearWidgetSampleDocument(
    designId = designId,
    title = "Weather widget",
    catalogPin = catalogPin,
    environment = environment,
    size = WearWidgetScaffoldSize.Large,
    background = literal("color", JsonPrimitive(WEATHER_SUNNY_ARGB)),
    contentId = "weather-content",
    content =
      listOf(
        UiBuilderNode(
          id = "weather-content",
          componentId = "layout/box",
          properties = JsonObject(emptyMap()),
          modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
          slots = mapOf("children" to listOf("weather-column")),
        ),
        UiBuilderNode(
          id = "weather-column",
          componentId = "layout/column",
          properties =
            JsonObject(
              mapOf(
                "verticalSpacingDp" to literal("float", JsonPrimitive(4)),
                // `RemoteBox(contentAlignment = Center)` in the sample. `layout/box` aligns each
                // child by that child's own `alignment`, so this is where centring lives.
                "alignment" to literal("enum", JsonPrimitive("center")),
              )
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = mapOf("children" to listOf("weather-location", "weather-reading")),
        ),
        UiBuilderNode(
          id = "weather-location",
          componentId = REMOTE_TEXT_COMPONENT_ID,
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("London")),
                "color" to literal("color", JsonPrimitive(WEATHER_ON_SUNNY_ARGB)),
                "fontSize" to literal("float", JsonPrimitive(14)),
                "textAlign" to literal("enum", JsonPrimitive("center")),
              )
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = emptyMap(),
        ),
        UiBuilderNode(
          id = "weather-reading",
          componentId = REMOTE_TEXT_COMPONENT_ID,
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("75° ☀️")),
                "color" to literal("color", JsonPrimitive(WEATHER_ON_SUNNY_ARGB)),
                "fontSize" to literal("float", JsonPrimitive(36)),
                "textAlign" to literal("enum", JsonPrimitive("center")),
              )
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = emptyMap(),
        ),
      ),
  )

/** `ColorSunny` from the sample's `WeatherWidget.kt`. */
private const val WEATHER_SUNNY_ARGB = "#FF2196F3"

/** The sample's text colour for every condition but snowy, which alone flips to black. */
private const val WEATHER_ON_SUNNY_ARGB = "#FFFFFFFF"

/**
 * A widget host scaffold whose content slot holds a filled surface wrapping [content]; the surface
 * is the widget's own background, which the host frame does not draw.
 */
private fun wearWidgetSampleDocument(
  designId: String,
  title: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  size: WearWidgetScaffoldSize,
  background: JsonObject,
  contentId: String,
  content: List<UiBuilderNode>,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear widget design id must not be blank" }
  val scaffoldId = "wear-widget-${size.name.lowercase()}"
  val nodes =
    listOf(
      UiBuilderNode(
        id = scaffoldId,
        componentId = size.componentId,
        // On the scaffold: `WearWidgetContainer` paints the background as the rounded rect itself.
        properties = JsonObject(mapOf("background" to background)),
        modifiers = JsonArray(emptyList()),
        slots = mapOf("content" to listOf(contentId)),
      )
    ) + content
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "$title · ${size.label}",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(scaffoldId),
    nodes = nodes.associateBy(UiBuilderNode::id),
  )
}

private fun literal(type: String, value: JsonPrimitive): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

private fun colorToken(token: String): JsonObject = literal("colorToken", JsonPrimitive(token))

private fun modifier(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))

/**
 * One state variable a new design starts with. State can also be added later through the Screen
 * inspector; both produce the same declarations.
 */
data class NewDesignState(
  val name: String,
  val type: NewDesignStateType,
  val initialValue: JsonElement,
) {
  init {
    require(name.isNotBlank()) { "state variable name must not be blank" }
  }
}

/** The declaration shapes `StateVariableV1` admits, narrowed to what a blank screen can use. */
enum class NewDesignStateType(val wireType: String, val valueType: String, val label: String) {
  Flag("value", "bool", "Flag"),
  Text("text", "string", "Text"),
  Number("value", "int", "Number");

  /** What an empty initial value box is offering to mean. */
  val placeholder: String
    get() =
      when (this) {
        Flag -> "false"
        Text -> "Hello"
        Number -> "0"
      }

  /**
   * The typed initial value for what someone typed. Always succeeds: a flag reads anything but
   * `true` as off, and a non-number starts at zero.
   */
  fun parse(raw: String): JsonPrimitive =
    when (this) {
      Flag -> JsonPrimitive(raw.trim().toBooleanStrictOrNull() ?: false)
      Text -> JsonPrimitive(raw)
      Number -> JsonPrimitive(raw.trim().toLongOrNull() ?: 0L)
    }
}

/**
 * The name a state variable may take: a Kotlin identifier, since the Compose exporter declares it
 * as one. Checked here so the dialog can say so while typing.
 */
val NEW_DESIGN_STATE_NAME: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * The id a new design may take: what the create route validates and a `/ui-builder/<designId>`
 * segment can hold. Shared by the browser form, the server's create/copy routes and the Designs
 * page's `pattern`.
 */
val NEW_DESIGN_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * State declarations as one string (JSON, since initial text values can contain any separator), for
 * carrying across a navigation as a single query parameter.
 */
fun encodeNewDesignStates(state: List<NewDesignState>): String =
  Json.encodeToString(
    JsonArray.serializer(),
    JsonArray(
      state.map {
        JsonObject(
          mapOf(
            "name" to JsonPrimitive(it.name),
            "kind" to JsonPrimitive(it.type.name),
            "initial" to it.initialValue,
          )
        )
      }
    ),
  )

/**
 * The inverse, defensively: the input comes from the address bar, so anything unparsable, with a
 * non-identifier name or an unknown kind is dropped rather than failing the session.
 */
fun decodeNewDesignStates(encoded: String): List<NewDesignState> {
  val parsed =
    runCatching { Json.parseToJsonElement(encoded) as? JsonArray }.getOrNull() ?: return emptyList()
  return parsed
    .mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      if (!NEW_DESIGN_STATE_NAME.matches(name)) return@mapNotNull null
      val kind =
        NewDesignStateType.entries.firstOrNull {
          it.name == (entry["kind"] as? JsonPrimitive)?.contentOrNull
        } ?: return@mapNotNull null
      val initial = entry["initial"] as? JsonPrimitive ?: return@mapNotNull null
      NewDesignState(name, kind, initial)
    }
    .distinctBy(NewDesignState::name)
}

/**
 * Refuses [state] a new design could never export: duplicate names, or names that collide or become
 * a Kotlin keyword once written as identifiers. Shared by every seed that takes declarations.
 */
internal fun requireExportableState(state: List<NewDesignState>) {
  require(state.map(NewDesignState::name).distinct().size == state.size) {
    "state variable names must be unique"
  }
  // Unique as the exporter writes them: `identifier()` folds `foo-bar` and `foo_bar` together and
  // does not escape keywords. Creation is the only point to refuse, since the wire cannot rename a
  // variable later.
  state.forEach { declared ->
    val identifier = exportedStateIdentifier(declared.name)
    require(identifier !in KOTLIN_HARD_KEYWORDS) {
      "state variable `${declared.name}` becomes the Kotlin keyword `$identifier` when exported"
    }
  }
  require(state.map { exportedStateIdentifier(it.name) }.distinct().size == state.size) {
    "state variable names must stay distinct once exported as Kotlin identifiers"
  }
}

/** A valid minimal screen for an honest from-scratch browser session. */
fun blankUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  require(designId.isNotBlank()) { "blank design id must not be blank" }
  requireExportableState(state)
  val scaffoldId = "screen-scaffold"
  val contentId = "screen-content"
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Untitled Compose screen",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(state.associate { it.name to it.declaration() }),
    roots = listOf(scaffoldId),
    nodes =
      mapOf(
        scaffoldId to
          UiBuilderNode(
            id = scaffoldId,
            componentId = "layout/scaffold",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots =
              mapOf(
                "topBar" to emptyList(),
                "snackbarHost" to emptyList(),
                "content" to listOf(contentId),
              ),
          ),
        contentId to
          UiBuilderNode(
            id = contentId,
            componentId = "layout/box",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("children" to emptyList()),
          ),
      ),
  )
}

/**
 * [blankUiBuilderDocument] with a headline and a sentence in a column: the trivial sample the home
 * screen offers beside the blank one, so a first design already draws something to select.
 */
fun helloUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument {
  val blank = blankUiBuilderDocument(designId, catalogPin, environment)
  val contentId = "screen-content"
  val columnId = "hello-column"
  fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive(value)))),
      modifiers = JsonArray(emptyList()),
    )
  return blank.copy(
    title = "Hello, Compose",
    nodes =
      blank.nodes +
        mapOf(
          contentId to
            blank.nodes.getValue(contentId).copy(slots = mapOf("children" to listOf(columnId))),
          columnId to
            UiBuilderNode(
              id = columnId,
              componentId = "layout/column",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("children" to listOf("hello-headline", "hello-body")),
            ),
          "hello-headline" to text("hello-headline", "Hello, Compose"),
          "hello-body" to
            text("hello-body", "Select this text and change it, or drag in a component."),
        ),
  )
}

/**
 * A new A2UI design: an `a2ui/Column` root with one `a2ui/Text` headline. The smallest surface that
 * draws and exports; [A2uiDocumentExporter] renames the column `root`.
 */
fun a2uiUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "a2ui design id must not be blank" }
  val columnId = "a2ui-column"
  val headlineId = "a2ui-headline"
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Untitled A2UI surface",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(columnId),
    nodes =
      mapOf(
        columnId to
          UiBuilderNode(
            id = columnId,
            componentId = "${A2uiDocumentExporter.COMPONENT_PREFIX}Column",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("children" to listOf(headlineId)),
          ),
        headlineId to
          UiBuilderNode(
            id = headlineId,
            componentId = "${A2uiDocumentExporter.COMPONENT_PREFIX}Text",
            properties =
              JsonObject(
                mapOf(
                  "text" to literal("string", JsonPrimitive("Hello, A2UI")),
                  "variant" to literal("enum", JsonPrimitive("h2")),
                )
              ),
            modifiers = JsonArray(emptyList()),
          ),
      ),
  )
}

/**
 * The wire declaration for one variable. `persistence` is `preview`, the only value a
 * browser-authored design can honestly claim.
 */
internal fun NewDesignState.declaration(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive(type.wireType),
      "valueType" to JsonPrimitive(type.valueType),
      "nullable" to JsonPrimitive(false),
      "initialValue" to initialValue,
      "persistence" to JsonPrimitive("preview"),
    )
  )

/**
 * An empty Wear screen: the scaffold, its clock and scroll indicator, and an empty list. The list
 * is included because `ScreenScaffold` exists to hold a `TransformingLazyColumn`, and the generator
 * refuses other top-level content.
 */
fun blankWearScreenUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear screen design id must not be blank" }
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Untitled Wear screen",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("wear-screen"),
    nodes =
      mapOf(
        "wear-screen" to
          UiBuilderNode(
            id = "wear-screen",
            componentId = "wear-m3/screen-scaffold",
            properties =
              JsonObject(
                mapOf(
                  "timeText" to literal("string", JsonPrimitive(WEAR_FROZEN_CLOCK)),
                  "scrollIndicator" to literal("bool", JsonPrimitive(true)),
                )
              ),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("content" to listOf("wear-list"), "edgeButton" to emptyList()),
          ),
        "wear-list" to
          UiBuilderNode(
            id = "wear-list",
            componentId = "wear-m3/transforming-lazy-column",
            properties =
              JsonObject(
                mapOf("verticalSpacingDp" to literal("float", JsonPrimitive(WEAR_LIST_SPACING_DP)))
              ),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("items" to emptyList()),
          ),
      ),
  )
}

/**
 * The frame a Wear design is created on: `wearos_small_round` at its own density, dark, so the
 * scaffold's diameter and the Screen inspector's device menu agree.
 */
fun wearScreenEnvironment(environment: JsonObject): JsonObject =
  JsonObject(
    environment.toMutableMap().also {
      it["widthDp"] = JsonPrimitive(WEAR_SMALL_ROUND_DP)
      it["heightDp"] = JsonPrimitive(WEAR_SMALL_ROUND_DP)
      it["density"] = JsonPrimitive(WEAR_SMALL_ROUND_DENSITY)
      it["theme"] = JsonPrimitive("dark")
      it["exportDevices"] = JsonArray(WEAR_EXPORT_DEVICES.map(::JsonPrimitive))
    }
  )

/**
 * The two round watches a Wear screen is checked on: 192dp and 240dp, where list wrapping differs
 * most. The screen opens at 192dp, since what works there works at 240dp.
 */
private val WEAR_EXPORT_DEVICES = listOf("id:wearos_small_round", "id:wearos_xl_round")

/**
 * [environment] on the frame a new mobile screen opens at: `pixel_6`, whose geometry four catalog
 * entries share. The fixture's own template keeps the fixture's frame.
 */
fun mobileScreenEnvironment(environment: JsonObject): JsonObject =
  JsonObject(
    environment.toMutableMap().also {
      it["widthDp"] = JsonPrimitive(PIXEL_6_WIDTH_DP)
      it["heightDp"] = JsonPrimitive(PIXEL_6_HEIGHT_DP)
      it["density"] = JsonPrimitive(PIXEL_6_DENSITY)
      it["exportDevices"] = JsonArray(MOBILE_EXPORT_DEVICES.map(::JsonPrimitive))
    }
  )

/**
 * The five frames a new mobile screen is checked on, one per width the layout has to survive:
 *
 * |                    |            | why this one                                                |
 * |--------------------|------------|-------------------------------------------------------------|
 * | `pixel_4a`         | 393 × 851  | the narrowest real Pixel in the render catalog              |
 * | `pixel_9`          | 411 × 923  | the modal phone; four catalog entries share this geometry   |
 * | `pixel_9_pro_xl`   | 438 × 997  | the widest phone; its 3.0 density catches dp-vs-px mistakes |
 * | `pixel_9_pro_fold` | 791 × 819  | unfolded: the width where a pane scaffold is still deciding |
 * | `pixel_tablet`     | 1280 × 800 | expanded, where two panes are the answer                    |
 *
 * Not covered by any catalog frame: a foldable's outer display, and landscape.
 */
private val MOBILE_EXPORT_DEVICES =
  listOf(
    "id:pixel_4a",
    "id:pixel_9",
    "id:pixel_9_pro_xl",
    "id:pixel_9_pro_fold",
    "id:pixel_tablet",
  )

/** `pixel_6` from `DeviceDimensions`, which is what the frame menu matches this against. */
private const val PIXEL_6_WIDTH_DP = 411

private const val PIXEL_6_HEIGHT_DP = 914

private const val PIXEL_6_DENSITY = 2.625

/** `wearos_small_round` from `DeviceDimensions`, which is what the frame menu will match. */
private const val WEAR_SMALL_ROUND_DP = 192

private const val WEAR_SMALL_ROUND_DENSITY = 2.0

/** `TransformingLazyColumn`'s own default spacing, and the reference render's. */
private const val WEAR_LIST_SPACING_DP = 4f

/**
 * `10:10`, frozen as wear-m3-catalog freezes its own: a moving clock churns render diffs, and
 * dropping the strip would under-report the top content padding.
 */
private const val WEAR_FROZEN_CLOCK = "10:10"

/**
 * The rows the Wear screen template opens on, identical to wear-m3-catalog's
 * `TransformingLazyColumn` component so both renders show the same design.
 */
private val WEAR_SCREEN_ROWS = (1..6).map { "Session $it" to "${it * 4} min" }

/**
 * The Wear list screen a `wear-m3` design opens on: a `ScreenScaffold` over a
 * `TransformingLazyColumn` of title cards, under a frozen curved status strip. Reproduces
 * `Template/TimeText` from compose-ai-tools' `samples/design-catalog-wear-m3`.
 */
fun wearScreenUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  title: String = "Activity",
  /**
   * Absent by default, so the template matches wear-m3-catalog's list for the parity check (the
   * canvas draws `EdgeButton` differently from a watch).
   */
  edgeButtonLabel: String? = null,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear screen design id must not be blank" }
  val rowNodes = WEAR_SCREEN_ROWS.flatMapIndexed { index, (rowTitle, subtitle) ->
    listOf(
      UiBuilderNode(
        id = "row-$index",
        componentId = "wear-m3/card",
        // No `shape`: Wear's `TitleCard` uses `CardDefaults.shape`, and nothing reads the property.
        properties =
          JsonObject(
            mapOf(
              // `title`: Wear's four variants are the four cards it publishes.
              "variant" to literal("enum", JsonPrimitive("title"))
            )
          ),
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        // One column, not two texts: a card's content slot is a `Box`, so two children would stack.
        slots = mapOf("content" to listOf("row-$index-lines")),
      ),
      UiBuilderNode(
        id = "row-$index-lines",
        componentId = "layout/column",
        properties =
          JsonObject(mapOf("horizontalAlignment" to literal("enum", JsonPrimitive("start")))),
        // No padding: `wear-m3/card` is the real `TitleCard` with `CardDefaults.ContentPadding`,
        // giving the reference 64dp row with text 12dp in, as `WearScreenParityTest` pins.
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        slots = mapOf("children" to listOf("row-$index-title", "row-$index-subtitle")),
      ),
      wearScreenText(
        "row-$index-title",
        rowTitle,
        "titleMedium",
        "onSurface",
        "start",
        fontSizeSp = WEAR_CARD_TITLE_SP,
      ),
      wearScreenText(
        "row-$index-subtitle",
        subtitle,
        "bodySmall",
        "onSurfaceVariant",
        "start",
        fontSizeSp = WEAR_CARD_SUBTITLE_SP,
      ),
    )
  }
  val edgeButtonNodes =
    edgeButtonLabel?.let {
      listOf(
        UiBuilderNode(
          id = "edge-button",
          componentId = "wear-m3/button",
          // `variant`, not `style`: `wear-m3/button` declares Wear's four variants.
          properties = JsonObject(mapOf("variant" to literal("enum", JsonPrimitive("filled")))),
          modifiers = JsonArray(emptyList()),
          slots = mapOf("content" to listOf("edge-button-label")),
        ),
        wearScreenText("edge-button-label", it, "labelLarge", "onPrimary"),
      )
    } ?: emptyList()
  val nodes =
    listOf(
      UiBuilderNode(
        id = "wear-screen",
        componentId = "wear-m3/screen-scaffold",
        properties =
          JsonObject(
            mapOf(
              "timeText" to literal("string", JsonPrimitive(WEAR_FROZEN_CLOCK)),
              "scrollIndicator" to literal("bool", JsonPrimitive(true)),
            )
          ),
        modifiers = JsonArray(emptyList()),
        slots =
          mapOf(
            "content" to listOf("wear-list"),
            "edgeButton" to edgeButtonNodes.take(1).map(UiBuilderNode::id),
          ),
      ),
      UiBuilderNode(
        id = "wear-list",
        componentId = "wear-m3/transforming-lazy-column",
        properties =
          JsonObject(
            mapOf("verticalSpacingDp" to literal("float", JsonPrimitive(WEAR_LIST_SPACING_DP)))
          ),
        modifiers = JsonArray(emptyList()),
        slots =
          mapOf("items" to listOf("list-header") + WEAR_SCREEN_ROWS.indices.map { "row-$it" }),
      ),
      // The real `ListHeader`, which carries its own 48dp.
      UiBuilderNode(
        id = "list-header",
        componentId = "wear-m3/list-header",
        properties = JsonObject(mapOf("text" to literal("string", JsonPrimitive(title)))),
        modifiers = JsonArray(emptyList()),
        slots = emptyMap(),
      ),
    ) + rowNodes + edgeButtonNodes
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "$title · Wear screen",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("wear-screen"),
    nodes = nodes.associateBy(UiBuilderNode::id),
  )
}

/**
 * Type sizes measured off the reference render's glyph boxes, not the role sizes.
 *
 * The canvas draws Wear's roles with the port's Roboto Flex, which is wider than Robolectric's
 * Roboto, so pinning 14sp makes the canvas match the reference. The generated Kotlin carries no
 * size: the platform lane has the real font.
 */
private const val WEAR_CARD_TITLE_SP = 14f

private const val WEAR_CARD_SUBTITLE_SP = 13f

private fun wearScreenText(
  id: String,
  text: String,
  style: String,
  color: String,
  textAlign: String = "center",
  fontSizeSp: Float? = null,
  modifiers: JsonArray = JsonArray(listOf(modifier("fillMaxWidth"))),
): UiBuilderNode =
  UiBuilderNode(
    id = id,
    componentId = "wear-m3/text",
    properties =
      JsonObject(
        mapOf(
          "text" to literal("string", JsonPrimitive(text)),
          "style" to literal("typographyToken", JsonPrimitive(style)),
          "color" to colorToken(color),
          "textAlign" to literal("enum", JsonPrimitive(textAlign)),
        ) + fontSizeSp?.let { mapOf("fontSizeSp" to literal("float", JsonPrimitive(it))) }.orEmpty()
      ),
    modifiers = modifiers,
    slots = emptyMap(),
  )
