package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A `m3/card` with no height wraps its content, the way `Card` does.
 *
 * It did not (compose-preview-server #483): the card drew its content slot in a
 * `Box(Modifier.fillMaxSize())`, a Column child's height constraint is bounded by what the column
 * has left, and a fill against a bounded constraint is a fill — so the first card in a column took
 * the rest of the frame and every sibling after it was never measured. The six-node repro from the
 * report is the first test, laid out at the frame it was reported on.
 *
 * Measured through the inspection bounds rather than probed, because "how tall did this get" is the
 * question, and the second test needs the same bounds to show that a card *with* a height still
 * lets its children align inside it — which is what the fill was for.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CardWrapsContentTest {
  private val reference =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json")).readText()
          )
          .jsonObject
      )
      .document

  @Test
  fun `two cards with no height stack, and the text after them is laid out`() {
    val bounds = measure(document())

    val cardA = bounds.getValue("card-a")
    val cardB = bounds.getValue("card-b")
    val tail = bounds.getValue("tail")
    assertTrue(cardA.height < HEIGHT / 4, "card A wraps its one line of text: $cardA")
    assertTrue(cardB.y >= cardA.y + cardA.height, "card B is under card A: $cardA then $cardB")
    assertTrue(tail.y >= cardB.y + cardB.height, "the tail is under card B: $cardB then $tail")
    assertTrue(
      tail.height > 0f && tail.y + tail.height <= HEIGHT,
      "the tail is on the frame: $tail",
    )
  }

  @Test
  fun `a card given a height still fills it, so a child aligned to its bottom lands there`() {
    val bounds = measure(document(cardHeightDp = 200f, dotAlignment = "bottomEnd"))

    val card = bounds.getValue("card-a")
    val dot = bounds.getValue("text-a")
    assertEquals(200f, card.height, "the height the document asked for")
    assertEquals(card.y + card.height, dot.y + dot.height, "the dot sits on the card's bottom edge")
  }

  @Test
  fun `a card with no height wraps an aligned child, rather than filling to align it`() {
    val bounds = measure(document(dotAlignment = "bottomEnd"))

    val card = bounds.getValue("card-a")
    assertEquals(DOT.toFloat(), card.height, "the card is exactly its one child tall")
  }

  /** The generated Kotlin has to wrap the same card the canvas wraps. */
  @Test
  fun `the exporter fills the card's box only along the axes the document sized`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    // The screen alone: the compatibility helpers appended after it hold a
    // `Box(Modifier.fillMaxSize())`
    // of their own, whatever the card emitter wrote.
    fun screen(document: UiBuilderDocument) =
      CapabilityComposeCodeExporter.export(document, catalog)
        .requireSource()
        .substringBefore("// Compatibility helpers are")

    val unsized = screen(document())
    assertTrue("Box(Modifier.fillMaxWidth()) {" in unsized, unsized)
    assertTrue("Box(Modifier.fillMaxSize())" !in unsized, unsized)

    val sized = screen(document(cardHeightDp = 200f))
    assertTrue("Box(Modifier.fillMaxSize()) {" in sized, sized)
  }

  private fun measure(document: UiBuilderDocument): Map<String, UiBuilderPixelBounds> {
    var snapshot: UiBuilderInspectionSnapshot? = null
    renderComposeScene(WIDTH, HEIGHT, Density(1f)) {
      UiBuilderSurface(document, onInspectionSnapshot = { snapshot = it })
    }
    return assertNotNull(snapshot).nodes.associate {
      it.nodeId to assertNotNull(it.bounds, it.nodeId)
    }
  }

  /**
   * The report's six nodes: a surface, a padded full-width column, two full-width cards holding one
   * line each, and a text after them. [cardHeightDp] pins card A the way the workaround did;
   * [dotAlignment] swaps card A's text for an 8dp dot aligned inside it.
   */
  private fun document(cardHeightDp: Float? = null, dotAlignment: String? = null) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "delegation-check",
      title = "Delegation check",
      revision = 1,
      catalogPin = reference.catalogPin,
      environment = reference.environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes =
        mapOf(
          "root" to
            UiBuilderNode(
              id = "root",
              componentId = "m3/surface",
              modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
              slots = mapOf("content" to listOf("column")),
            ),
          "column" to
            UiBuilderNode(
              id = "column",
              componentId = "layout/column",
              modifiers =
                JsonArray(
                  listOf(
                    modifier("fillMaxWidth"),
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("padding"),
                        "startDp" to JsonPrimitive(16),
                        "topDp" to JsonPrimitive(16),
                        "endDp" to JsonPrimitive(16),
                        "bottomDp" to JsonPrimitive(16),
                      )
                    ),
                  )
                ),
              slots = mapOf("children" to listOf("card-a", "card-b", "tail")),
            ),
          "card-a" to
            UiBuilderNode(
              id = "card-a",
              componentId = "m3/card",
              modifiers =
                JsonArray(
                  listOfNotNull(
                    modifier("fillMaxWidth"),
                    cardHeightDp?.let {
                      JsonObject(
                        mapOf("type" to JsonPrimitive("height"), "heightDp" to JsonPrimitive(it))
                      )
                    },
                  )
                ),
              slots = mapOf("content" to listOf("text-a")),
            ),
          "card-b" to
            UiBuilderNode(
              id = "card-b",
              componentId = "m3/card",
              modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
              slots = mapOf("content" to listOf("text-b")),
            ),
          "text-a" to (dotAlignment?.let { dot("text-a", it) } ?: text("text-a", "Card A")),
          "text-b" to text("text-b", "Card B"),
          "tail" to text("tail", "tail"),
        ),
    )

  private fun text(id: String, text: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to
              JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(text)))
          )
        ),
    )

  private fun dot(id: String, alignment: String) =
    UiBuilderNode(
      id = id,
      componentId = "shape/colour-dot",
      properties =
        JsonObject(
          mapOf(
            "color" to
              JsonObject(
                mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FFFFFFFF"))
              ),
            "diameterDp" to
              JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(DOT))),
          )
        ),
      modifiers =
        JsonArray(
          listOf(
            JsonObject(
              mapOf("type" to JsonPrimitive("align"), "alignment" to JsonPrimitive(alignment))
            )
          )
        ),
    )

  private fun modifier(type: String) = JsonObject(mapOf("type" to JsonPrimitive(type)))

  private companion object {
    /** The frame the report rendered on, in dp at density 1. */
    const val WIDTH = 412
    const val HEIGHT = 915
    const val DOT = 8
  }
}
