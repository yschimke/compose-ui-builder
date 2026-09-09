package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the insert panel *says* when an Add beside is refused.
 *
 * These are the two questions the render-and-compare lane could not ask. A committed PNG proves the
 * picture has not changed; it cannot say whether a sentence appears once or on every row, and both
 * regressions #619 fixes were exactly that. The second test here fails against the code that
 * shipped in #607.
 */
@OptIn(ExperimentalTestApi::class)
class CatalogRowRefusalTest {
  private val wearCatalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))

  /** The sentence the *document* refuses a wrap with — panel-wide, and about the design. */
  private val documentRefusal =
    "A Wear screen or widget is exported as itself, so it cannot become one item of a board"

  @Test
  fun `a root-only component's row says why it cannot be an item of a board`() = runWideEditor {
    setContent {
      UiBuilderEditor(
        document = wearBoard(),
        catalog = wearCatalog,
        initialCatalogQuery = "scaffold",
        initialAddBeside = true,
        initialComponentsOpen = true,
      )
    }
    // The reason is on the row, where the disabled Add is — not only in the code that computed it.
    onNodeWithText("cannot be one item of a board", substring = true).assertExists()
    // And the row still refuses. Selected by the Add button's own description rather than by text:
    // "Add" as a substring matches the destination line ("Adds beside 2 item(s) on the board")
    // first, and a message sitting next to a working Add would be worse than no message at all.
    onNode(hasContentDescription("cannot be one item of a board", substring = true))
      .assertIsNotEnabled()
  }

  /**
   * The regression #607 shipped: `besideRefusal(state, componentId)` falls through to the
   * document's own answer, so asking it per row put the wrap refusal on every component in the
   * catalog.
   *
   * Pinned by *count*, which is the whole point — the sentence is correct, and belongs on the
   * destination line. What was wrong was how many times it appeared.
   */
  @Test
  fun `the document's refusal appears once, not on every component row`() = runWideEditor {
    setContent {
      UiBuilderEditor(
        document = wearScreen(),
        catalog = wearCatalog,
        initialAddBeside = true,
        initialComponentsOpen = true,
      )
    }
    onAllNodes(hasText(documentRefusal, substring = true)).assertCountEquals(1)
  }

  /** A design already rooted in a column: a board, so only a component can still refuse. */
  private fun wearBoard(): UiBuilderDocument {
    val items = mapOf("item-1" to "Now playing", "item-2" to "Up next")
    return document(
      roots = listOf("board"),
      nodes =
        buildMap {
          put(
            "board",
            UiBuilderBoard.node("board")
              .copy(slots = mapOf(UiBuilderBoard.SLOT to items.keys.toList())),
          )
          items.forEach { (id, label) ->
            put(
              id,
              UiBuilderNode(
                id = id,
                componentId = "wear-m3/text",
                properties =
                  JsonObject(
                    mapOf(
                      "text" to
                        JsonObject(
                          mapOf(
                            "type" to JsonPrimitive("string"),
                            "value" to JsonPrimitive(label),
                          )
                        )
                    )
                  ),
              ),
            )
          }
        },
    )
  }

  /** A Wear screen that is not a board: here the *document* refuses the wrap. */
  private fun wearScreen(): UiBuilderDocument =
    document(
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to UiBuilderNode(id = "screen", componentId = WearScreenCodeExporter.SCAFFOLD)
        ),
    )

  private fun document(roots: List<String>, nodes: Map<String, UiBuilderNode>) =
    UiBuilderDocument(
      schema = "ui-builder/v1",
      id = "wear-refusal",
      title = "Wear refusal",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("wear-m3"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(900),
            "heightDp" to JsonPrimitive(1400),
            "density" to JsonPrimitive(2.0),
            "theme" to JsonPrimitive("dark"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = roots,
      nodes = nodes,
    )

  /**
   * A scene wide enough for the editor's desktop layout.
   *
   * Below 840 dp it draws a different chrome — the insert panel becomes a sheet — so a test about
   * the panel has to say which layout it is about rather than inherit whatever the default scene
   * size happens to be.
   */
  private fun runWideEditor(block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
    runDesktopComposeUiTest(width = 1600, height = 900) { block() }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
