package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
