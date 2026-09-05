package ee.schimke.composeai.uibuilder

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
 * The sample writes each widget as `GlanceWearWidget.provideWidgetData` returning a
 * `WearWidgetDocument` of Remote Compose composables. The frame's *geometry* — the content size,
 * the 8dp padding, the 26dp radius — comes from `WearWidgetParams`, while its *colour* is the
 * widget's own background, which the container paints as the round rect. The scaffold models both,
 * so the question these two answer is whether the designer can express the widget half at all, and
 * the answer is yes, from ordinary catalog components.
 *
 * What they are not: a compile of the sample. The builder draws with Compose Material 3 and the
 * widget runs Remote Compose on a watch, so these reproduce the sample's *design* — layout,
 * colours, type sizes, strings — as a design, and the fidelity question belongs to the parity lanes
 * rather than to a template.
 */
enum class WearWidgetSample(
  val templateId: String,
  val size: WearWidgetScaffoldSize,
  val label: String,
  val supportingText: String,
) {
  /**
   * `HelloWidgetContent`: centred text on the theme's primary, in the Small host.
   *
   * Small because that is what the content asks for — one line of 20sp text has nothing to do with
   * the extra 48dp of the Large canvas, and a template that opens on a mostly empty widget teaches
   * the wrong default.
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
    /**
     * The sample with this template id, or null — so an unknown template falls through as before.
     */
    public fun forTemplate(templateId: String): WearWidgetSample? = entries.firstOrNull {
      it.templateId == templateId
    }
  }
}

/**
 * The sample's Hello widget: `RemoteText` centred in a `RemoteBox` on a primary background.
 *
 * The background is the scaffold's, matching `WearWidgetBrush.color(colorScheme.primary)` passed to
 * `WearWidgetDocument` — the container paints it as the round rect, so the whole squircle is
 * primary and the text sits on it inside the padding.
 *
 * Centring is the child's `alignment`, not the box's: `layout/box` aligns each child by that
 * child's own property, so `contentAlignment` on the box would render nothing.
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
          componentId = "m3/text",
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("Hello, World!")),
                "color" to colorToken("onPrimary"),
                "fontSizeSp" to literal("float", JsonPrimitive(20)),
                "alignment" to literal("enum", JsonPrimitive("center")),
              )
            ),
          modifiers = JsonArray(emptyList()),
          slots = emptyMap(),
        ),
      ),
  )

/**
 * The sample's Weather widget, in its sunny state: location over the reading, on `ColorSunny`.
 *
 * A literal `#FF2196F3` rather than a theme token, because the sample's colour is chosen by the
 * *weather*, not by the theme — it swaps between four hard-coded colours per condition — and naming
 * it `primary` here would claim a relationship to the theme that the widget does not have. The
 * sunny state is the one the sample's own previews render.
 *
 * Each line is `fillMaxWidth` with `textAlign = center` rather than relying on the column's
 * `horizontalAlignment`, which the catalog declares and the renderer does not read; centring the
 * text inside a full-width line is the same picture through a path that works. The column's own
 * vertical centring is its `alignment` in the parent box, which does.
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
          componentId = "m3/text",
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("London")),
                "color" to literal("color", JsonPrimitive(WEATHER_ON_SUNNY_ARGB)),
                "fontSizeSp" to literal("float", JsonPrimitive(14)),
                "textAlign" to literal("enum", JsonPrimitive("center")),
              )
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = emptyMap(),
        ),
        UiBuilderNode(
          id = "weather-reading",
          componentId = "m3/text",
          properties =
            JsonObject(
              mapOf(
                "text" to literal("string", JsonPrimitive("75° ☀️")),
                "color" to literal("color", JsonPrimitive(WEATHER_ON_SUNNY_ARGB)),
                "fontSizeSp" to literal("float", JsonPrimitive(36)),
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
 * A widget host scaffold whose content slot holds a filled surface wrapping [content].
 *
 * The surface is inserted here rather than by each sample because both need it and for the same
 * reason: it is the widget's own background, which the host frame does not draw.
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
        // On the scaffold, not on a surface inside it. `WearWidgetContainer` paints the widget's
        // own background as the rounded rect, so the coloured squircle IS the widget; a filled
        // surface in the content slot would instead draw a coloured rectangle inside a
        // differently-coloured frame, which is not what any widget looks like.
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
 * One state variable a new design starts with.
 *
 * Declared at creation because that is the only moment a client can put state into a design: the
 * wire's mutation set reaches nodes, properties and the environment, and never `stateVariables`. A
 * design that starts without state can never gain any, so a screen that reacts to anything has to
 * say so up front.
 *
 * That is a real limitation rather than a design preference, and the fix is a protocol addition
 * (`setStateVariable`), not a workaround here.
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
   * The typed initial value for what someone typed.
   *
   * Every kind has a total answer, because the alternative is a dialog that refuses to create a
   * design over a typo in a default. A flag reads anything that is not `true` as off, and a number
   * that is not a number starts at zero.
   */
  fun parse(raw: String): JsonPrimitive =
    when (this) {
      Flag -> JsonPrimitive(raw.trim().toBooleanStrictOrNull() ?: false)
      Text -> JsonPrimitive(raw)
      Number -> JsonPrimitive(raw.trim().toLongOrNull() ?: 0L)
    }
}

/**
 * The name a state variable may take.
 *
 * A Kotlin identifier, because that is what it becomes: the Compose exporter declares it as a
 * property and the generator refuses a name it cannot write. Checking it here means the dialog can
 * say so while someone types rather than the export saying so weeks later.
 */
val NEW_DESIGN_STATE_NAME: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * State declarations as one string, for a host that has to carry them across a navigation.
 *
 * JSON rather than a separator scheme, because a `Text` variable's initial value is free text and
 * every separator worth choosing can appear inside one. The browser encodes the whole thing as a
 * single query parameter, so the only question left is whether it parses.
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
 * The inverse, defensively.
 *
 * The input is a query parameter, so it is whatever the address bar contained. Anything that does
 * not parse, names something that is not an identifier, or claims a kind this build does not have
 * is dropped rather than failing the session: a mistyped URL should open an empty design, not a
 * blank page.
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

/** A valid minimal screen for an honest from-scratch browser session. */
fun blankUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  require(designId.isNotBlank()) { "blank design id must not be blank" }
  require(state.map(NewDesignState::name).distinct().size == state.size) {
    "state variable names must be unique"
  }
  // Unique *as the exporter will write them*, and legal there. `identifier()` drops separators, so
  // `foo-bar` and `foo_bar` both become `fooBar` and declare the same variable twice; and it does
  // not escape keywords, so `when` becomes `var when: Boolean`. Neither compiles, and neither is
  // visible until somebody exports. Refusing at creation is the only moment this design can be
  // stopped from holding a name it can never generate — the wire cannot rename a variable later.
  state.forEach { declared ->
    val identifier = exportedStateIdentifier(declared.name)
    require(identifier !in KOTLIN_HARD_KEYWORDS) {
      "state variable `${declared.name}` becomes the Kotlin keyword `$identifier` when exported"
    }
  }
  require(state.map { exportedStateIdentifier(it.name) }.distinct().size == state.size) {
    "state variable names must stay distinct once exported as Kotlin identifiers"
  }
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
 * The wire declaration for one variable.
 *
 * `persistence` is `preview`, the only value a design authored in a browser can honestly claim:
 * anything durable is a promise about a host this document knows nothing about.
 */
private fun NewDesignState.declaration(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive(type.wireType),
      "valueType" to JsonPrimitive(type.valueType),
      "nullable" to JsonPrimitive(false),
      "initialValue" to initialValue,
      "persistence" to JsonPrimitive("preview"),
    )
  )

/** The rows the Wear screen template opens on, matching the wear-m3 catalog's own list sticker. */
private val WEAR_SCREEN_ROWS =
  listOf(
    "Morning run" to "5.2 km · 28 min",
    "Heart rate" to "72 bpm",
    "Sleep" to "7h 14m",
    "Steps" to "6,482",
    "Calories" to "412 kcal",
    "Cycle" to "18 km · 41 min",
  )

/**
 * The Wear list screen a `wear-m3` design opens on: a `ScreenScaffold` over a
 * `TransformingLazyColumn` of title cards, under a frozen curved status strip.
 *
 * It reproduces `Template/TimeText` from compose-ai-tools' `samples/design-catalog-wear-m3` — the
 * base Wear screen, and the shape well over half of Wear Material 3's surface area takes — for the
 * same reason the widget templates reproduce the `WearWidget` sample: the question a first template
 * has to answer is whether the designer can express a real screen, and a blank scaffold does not
 * answer it.
 *
 * The clock is frozen at `10:10` rather than live, exactly as that catalog freezes its own: a
 * status strip that moved would churn every render diff, and dropping the strip instead would
 * under-report the top margin the content lays out around.
 */
fun wearScreenUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  title: String = "Activity",
  edgeButtonLabel: String? = "Done",
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear screen design id must not be blank" }
  val rowNodes = WEAR_SCREEN_ROWS.flatMapIndexed { index, (rowTitle, subtitle) ->
    listOf(
      UiBuilderNode(
        id = "row-$index",
        componentId = "m3/card",
        properties = JsonObject(mapOf("variant" to literal("enum", JsonPrimitive("filled")))),
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        // One column, not two texts. `m3/card` draws its content slot in a `Box`, so two
        // children would be stacked on top of each other and the card would take the whole
        // remaining height — which is exactly what the first render of this template did.
        slots = mapOf("content" to listOf("row-$index-lines")),
      ),
      UiBuilderNode(
        id = "row-$index-lines",
        componentId = "layout/column",
        properties =
          JsonObject(mapOf("horizontalAlignment" to literal("enum", JsonPrimitive("start")))),
        modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
        slots = mapOf("children" to listOf("row-$index-title", "row-$index-subtitle")),
      ),
      wearScreenText("row-$index-title", rowTitle, "titleMedium", "onSurface", "start"),
      wearScreenText("row-$index-subtitle", subtitle, "bodySmall", "onSurfaceVariant", "start"),
    )
  }
  val edgeButtonNodes =
    edgeButtonLabel?.let {
      listOf(
        UiBuilderNode(
          id = "edge-button",
          componentId = "m3/button",
          properties = JsonObject(mapOf("style" to literal("enum", JsonPrimitive("filled")))),
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
              "timeText" to literal("string", JsonPrimitive("10:10")),
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
        properties = JsonObject(mapOf("verticalSpacingDp" to literal("float", JsonPrimitive(4)))),
        modifiers = JsonArray(emptyList()),
        slots =
          mapOf("items" to listOf("list-header") + WEAR_SCREEN_ROWS.indices.map { "row-$it" }),
      ),
      wearScreenText("list-header", title, "titleSmall", "onSurfaceVariant"),
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

private fun wearScreenText(
  id: String,
  text: String,
  style: String,
  color: String,
  textAlign: String = "center",
): UiBuilderNode =
  UiBuilderNode(
    id = id,
    componentId = "m3/text",
    properties =
      JsonObject(
        mapOf(
          "text" to literal("string", JsonPrimitive(text)),
          "style" to literal("typographyToken", JsonPrimitive(style)),
          "color" to colorToken(color),
          "textAlign" to literal("enum", JsonPrimitive(textAlign)),
        )
      ),
    modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
    slots = emptyMap(),
  )
