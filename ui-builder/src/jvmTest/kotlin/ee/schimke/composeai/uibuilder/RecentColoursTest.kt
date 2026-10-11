package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.RECENT_COLOUR_LIMIT
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.withRecentColour
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The inspector's "Recent colours": the exact colour just given to one text, one press away when
 * the next text should match it.
 */
class RecentColoursTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private val base = reducer.initial(document, selectedNodeId = "main-episode-title")

  private fun coloured(
    state: UiBuilderEditorState = base,
    value: String,
    property: String = "color",
  ): UiBuilderEditorState =
    reducer.reduce(
      state,
      UiBuilderEditorEvent.CommitProperty("main-episode-title", property, value),
    )

  @Test
  fun `the list is most recent first, each colour once whatever its case, and bounded`() {
    val list = emptyList<String>().withRecentColour("#FF1A73E8").withRecentColour("primary")
    assertEquals(listOf("primary", "#FF1A73E8"), list)
    // Typed in lower case, it is still the colour the swatch wrote; the latest spelling wins.
    assertEquals(listOf("#ff1a73e8", "primary"), list.withRecentColour("#ff1a73e8"))
    // A cleared field is the component default, not a colour.
    assertEquals(list, list.withRecentColour(""))

    val full =
      (1..10).fold(emptyList<String>()) { acc, n ->
        acc.withRecentColour("#FF" + "$n".padStart(6, '0'))
      }
    assertEquals(RECENT_COLOUR_LIMIT, full.size)
    assertEquals("#FF000010", full.first())
    assertEquals("#FF000005", full.last())
  }

  @Test
  fun `a fresh editor has no recent colours`() {
    assertTrue(base.recentColours.isEmpty())
  }

  @Test
  fun `accepted literals and theme roles are both recorded, newest first`() {
    val literal = coloured(value = "#FF1A73E8")
    val role = coloured(literal, "primary")
    val again = coloured(role, "#ff1a73e8")

    assertEquals(listOf("#FF1A73E8"), literal.recentColours)
    assertEquals(listOf("primary", "#FF1A73E8"), role.recentColours)
    assertEquals(listOf("#ff1a73e8", "primary"), again.recentColours)
  }

  @Test
  fun `a refused colour never reached the canvas, so it is not recorded`() {
    val refused = coloured(value = "not-a-colour")

    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(refused.recentColours.isEmpty())
  }

  @Test
  fun `clearing a colour back to the default records nothing`() {
    val used = coloured(value = "#FF1A73E8")

    assertEquals(listOf("#FF1A73E8"), coloured(used, "").recentColours)
  }

  @Test
  fun `an edit to a property that is not a colour records nothing`() {
    val text = coloured(value = "Hello", property = "text")

    assertIs<CommandOutcome.Accepted>(text.lastOutcome)
    assertTrue(text.recentColours.isEmpty())
  }

  @Test
  fun `recent colours survive the authoritative document that answers the edit`() {
    val used = coloured(value = "#FF1A73E8")

    assertEquals(listOf("#FF1A73E8"), reducer.reconciled(used, used.document).recentColours)
  }
}
