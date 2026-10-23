package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `TransformingLazyColumn`'s own default spacing, and the reference render's. */
private const val WEAR_LIST_SPACING_DP = 4f

/**
 * `10:10`, frozen as wear-m3-catalog freezes its own: a moving clock churns render diffs, and
 * dropping the strip would under-report the top content padding.
 */
private const val WEAR_FROZEN_CLOCK = "10:10"

/**
 * The rows the Wear screen sample holds, identical to wear-m3-catalog's `TransformingLazyColumn`
 * component so both renders show the same design.
 */
private val WEAR_SCREEN_ROWS = (1..6).map { "Session $it" to "${it * 4} min" }

/**
 * A Wear list screen: a `ScreenScaffold` over a `TransformingLazyColumn` of title cards, under a
 * frozen curved status strip. Reproduces `Template/TimeText` from compose-ai-tools'
 * `samples/design-catalog-wear-m3`; new `wear-m3` designs opened on it until the catalog published
 * its own templates, and tests and previews still build it.
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

private fun literal(type: String, value: JsonPrimitive): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

private fun colorToken(token: String): JsonObject = literal("colorToken", JsonPrimitive(token))

private fun modifier(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))
