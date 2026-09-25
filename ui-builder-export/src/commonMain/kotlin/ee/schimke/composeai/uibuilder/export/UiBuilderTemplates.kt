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
 * Declared at creation when the author already knows the screen's state. State may also be added or
 * edited later through the Screen inspector; both paths produce the same state-variable
 * declarations.
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
 * The id a new design may take: the shape the create route validates and the shape a
 * `/ui-builder/<designId>` path segment can hold.
 *
 * Stated here because three surfaces ask the same question — the New design form in the browser,
 * the server's create and copy routes, and the Designs page's own `pattern` attribute — and an id
 * one of them accepts and another refuses is a create that fails after the click.
 */
val NEW_DESIGN_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

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

/**
 * The frame a Wear design is created on: the small round watch, at its own density, dark.
 *
 * Not the fixture's phone frame. The scaffold reads its diameter from the document, so a Wear
 * design seeded on a 411dp handset would fall back to the smallest watch to draw itself while the
 * Screen inspector said "Pixel" — a disagreement between the picture and the frame menu that nobody
 * could act on. `wearos_small_round`'s own numbers, so the menu opens on the right entry.
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
 * The two round watches a Wear screen is checked on: 192dp and 240dp.
 *
 * Wear's layout question is where the list wraps, and it wraps differently at the small and the
 * extra-large round — 48dp is a quarter of the small screen's width, so a row that fits one and not
 * the other is the common case rather than a corner one. The screen opens *at* 192dp, the smaller
 * of the two, because a list that works there works at 240 and not the other way round.
 *
 * Two rather than all four round sizes: 227dp sits between these and has never yet been the size
 * that showed something 192 and 240 did not, and a default that draws four panes for a two-pane
 * question spends the preview strip on nothing.
 */
private val WEAR_EXPORT_DEVICES = listOf("id:wearos_small_round", "id:wearos_xl_round")

/**
 * [environment] on the frame a new mobile screen opens at: `pixel_6`.
 *
 * A blank design used to inherit the fixture's own 1280x800 at density 1 — the Jetcaster screen's
 * expanded supporting-pane canvas, which is the right frame for *that* design and matches no device
 * in the catalog, so the Screen inspector opened reading "Custom size" on every new screen. A phone
 * is what a screen is drawn for, and a typical one is easier to reason about than a canvas whose
 * width no handset has.
 *
 * `pixel_6` rather than a newer id because its geometry is the one four catalog entries share
 * (`pixel_6`, `pixel_6a`, `pixel_7`, `pixel_7a`), so the frame stays matched — and named — as the
 * catalog grows. The fixture's own template keeps the fixture's frame: re-pinning Jetcaster to a
 * phone would redraw the design the pane was built to show.
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
 * The five frames a new mobile screen is checked on, one per width the layout has to survive.
 *
 * Empty was the old default, which made "does this work on a phone?" a question you had to think to
 * ask. These make it the thing you see. Real devices rather than round numbers, because the point
 * of the strip is a frame somebody ships, and one per size class rather than per handset:
 *
 * |                  |          |why this one                                                                          |
 * |------------------|----------|--------------------------------------------------------------------------------------|
 * |`pixel_4a`        |393 × 851 |the narrowest real Pixel the render catalog knows — where a row runs out of room first|
 * |`pixel_9`         |411 × 923 |the modal phone; four catalog entries share this geometry                             |
 * |`pixel_9_pro_xl`  |438 × 997 |the widest phone, and the one whose 3.0 density catches dp-vs-px mistakes             |
 * |`pixel_9_pro_fold`|791 × 819 |unfolded: the width where a pane scaffold is still deciding                           |
 * |`pixel_tablet`    |1280 × 800|expanded, where two panes are the answer                                              |
 *
 * **What this set cannot cover**, and neither can any other set drawn from this catalog: a
 * foldable's *outer* display. `pixel_9_pro_fold` is the inner screen, and the render catalog has no
 * folded frame at all — so the one transition adaptive layout exists for, a device that is a narrow
 * phone and then a small tablet, cannot be looked at here. Landscape is missing for the same
 * reason: every preset is portrait, so a rotation is a hand-typed frame.
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
 * `10:10`, frozen, exactly as wear-m3-catalog freezes its own.
 *
 * A status strip that moved would churn every render diff; dropping it instead would under-report
 * the top margin the content lays out around, because the list's top content padding is what makes
 * room for it.
 */
private const val WEAR_FROZEN_CLOCK = "10:10"

/**
 * The rows the Wear screen template opens on.
 *
 * Character for character the rows of wear-m3-catalog's own `TransformingLazyColumn` component, so
 * the builder's canvas and that repository's stitched `ScrollMode.LONG` render are the same design
 * drawn by two renderers — which is the only way a difference between them means anything.
 */
internal val WEAR_SCREEN_ROWS = (1..6).map { "Session $it" to "${it * 4} min" }

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
  /**
   * Absent by default, so the template *is* wear-m3-catalog's list rather than that plus a control.
   *
   * The slot stays available to an author; what it must not do is sit in the design the parity
   * check compares, because `EdgeButton` hugs the bottom curve on a watch and the canvas draws the
   * borrowed flat button at the bottom cap. A difference nobody is testing for is a difference that
   * makes the ones you are testing for harder to see.
   */
  edgeButtonLabel: String? = null,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear screen design id must not be blank" }
  val rowNodes = WEAR_SCREEN_ROWS.flatMapIndexed { index, (rowTitle, subtitle) ->
    listOf(
      UiBuilderNode(
        id = "row-$index",
        componentId = "wear-m3/card",
        // No `shape`, and the absence is the point. The row carried `shape = "large"` with a
        // comment saying it "is what reaches the theme's corner radius", which was true while
        // `wear-m3/card` was drawn as a borrowed mobile card through the canvas's corner-radius
        // local. Wear's own `TitleCard` takes its shape from `CardDefaults.shape` — one shape,
        // 26dp,
        // the reference card's — and neither the canvas nor the exporter reads the property, so it
        // was a control an author could move that moved nothing. Dropped from the catalog's
        // declaration in the same change.
        properties =
          JsonObject(
            mapOf(
              // `title`, which is the `TitleCard` this row has always generated. It read
              // `filled` while the variant list was the borrowed mobile one, where Material's
              // three are filled / elevated / outlined; Wear's four are the four cards it
              // publishes, and a filled Wear card is not one of them.
              "variant" to literal("enum", JsonPrimitive("title"))
            )
          ),
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
        // NO padding, and the absence is the point. This column used to carry a measured
        // 12.2 / 9.7 / 14.7dp to make the row reach the reference card's 64dp, because the canvas
        // drew `wear-m3/card` as a borrowed mobile Material 3 card, which has none of Wear's
        // padding. Since the CMP port landed, `wear-m3/card` is the real Wear `TitleCard` and
        // carries `CardDefaults.ContentPadding` (12dp on every edge) itself — so that compensation
        // was applied on top of it and the row drew 82dp with its text 24.2dp in from the card
        // edge, against the reference's 64dp and 12dp. Measured, not reasoned: with the padding
        // removed the row is exactly 64dp and the title starts exactly 12dp in, which is what the
        // kit's own render measures and what `WearScreenParityTest` now pins.
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
          // `variant`, not `style`: the borrowed mobile property carried `fab` and `elevated`,
          // which no watch publishes, so `wear-m3/button` declares Wear's four under the name
          // every other Wear component uses for the same idea.
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
      // The real `ListHeader` id, which carries its own 48dp. It used to be a padded `m3/text`:
      // the canvas matched the reference, and the *generated* screen came out 31.5dp shorter,
      // because a padded Text is not a ListHeader and the generator was right not to pretend.
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
 * Type sizes measured off the reference render's glyph boxes, not read off a token table.
 *
 * These are not the role sizes — Wear's `titleMedium` is 16sp and its `bodySmall` 12sp — and they
 * are not meant to be: the canvas resolves the role through `wearTextStyle`, which reads Wear's own
 * typography, and then draws it with the font the canvas has. That font is the port's vendored
 * Roboto Flex, which is wider than the Roboto the Robolectric lane rasterises, so a title set at
 * Wear's own 16sp came out 73dp against the reference's 67dp. Pinning the size to the measured 14
 * is what closes that: rendered, the title is 64.5dp wide against the reference's 67.0dp, and the
 * subtitle 35.5 against 36.5.
 *
 * The generated Kotlin deliberately carries neither pin — `WearScreenCodeExporter` writes
 * `Text(text = …)` with no size — because the platform lane has the real font and the real role
 * size and needs no correction. So these numbers are a canvas-side measurement, and they are only
 * live because `wear-m3/text` reads `fontSizeSp`: the branch used to ignore every property but
 * `text`, `color`, `style` and `maxLines`, which made both pins inert and the canvas 6dp wide of
 * the reference.
 */
private const val WEAR_CARD_TITLE_SP = 14f

private const val WEAR_CARD_SUBTITLE_SP = 13f

// `WEAR_LIST_HEADER_SP` (14.5f) stood here, and it is deleted rather than left unused.
//
// It was the label size for a padded `m3/text` faking `ListHeader`'s 48dp. `wear-m3/list-header` is
// the real one now and carries its own height and type, so the constant stopped being read — the
// renderer's copy went with the stand-in it belonged to, and this one stayed behind because nothing
// fails when a private constant goes quiet. A dead number beside live ones is worse than dead code:
// the next reader has to work out which of the three still matters.

// The row's own padding constants are GONE, and this note is what stops them coming back.
//
// `WEAR_CARD_PADDING_DP` (12.2), `WEAR_CARD_TOP_PADDING_DP` (9.7) and
// `WEAR_CARD_BOTTOM_PADDING_DP` (14.7) sat on `row-N-lines` to bring a *borrowed* mobile Material 3
// card up to the reference row's 64dp. `wear-m3/card` is the real Wear `TitleCard` now — it carries
// `CardDefaults.ContentPadding`, 12dp on every edge, and is 64dp on its own — so the same numbers
// made the canvas draw an 82dp row with its text 24.2dp in, while the generated Kotlin (which
// collapses the column into `TitleCard(title = …, subtitle = …)`) drew the right thing. Canvas and
// native disagreed by 18dp a row, in the direction the canvas was wrong.
//
// `WEAR_LIST_HEADER_TOP_DP` / `WEAR_LIST_HEADER_BOTTOM_DP` were the same story one component over:
// they padded a `Text` faking `ListHeader`, and went unused the day `wear-m3/list-header` became
// the
// real one. Dead constants are how a compensation outlives its reason, so they are deleted rather
// than left for the next reader to wonder about.

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
