package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignSuggestion
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionCodec
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionOutcome
import ee.schimke.composeai.uibuilder.editor.DesignSuggestions
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorNodeDiffKind
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.describe
import ee.schimke.composeai.uibuilder.editor.isBehind
import ee.schimke.composeai.uibuilder.editor.suggestionDiff
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.selectionLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Suggestion mode in the editor: the list, the diff it shows, the canvas swap, Accept and Reject
 * reaching the host, the section staying away on a host without suggestions, and the words a merge
 * report turns into. The runtime half is `DesignSuggestionsTest` in `:ui-builder-runtime`.
 */
@OptIn(ExperimentalTestApi::class)
class DesignSuggestionsUiTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  private val design: UiBuilderDocument = run {
    val blank = blankUiBuilderDocument("design", JsonObject(emptyMap()), JsonObject(emptyMap()))
    blank.copy(
      revision = 4,
      nodes =
        blank.nodes +
          ("title" to text("title", "Hello")) +
          ("screen-content" to
            blank.nodes
              .getValue("screen-content")
              .copy(slots = mapOf("children" to listOf("title")))),
    )
  }

  /** The agent's proposal: a new title, and a button beneath it. */
  private val proposed: UiBuilderDocument =
    design.copy(
      id = "design-suggestion-1",
      revision = 6,
      nodes =
        design.nodes +
          ("title" to text("title", "Hello, listener")) +
          ("subtitle" to text("subtitle", "New this week")) +
          ("screen-content" to
            design.nodes
              .getValue("screen-content")
              .copy(slots = mapOf("children" to listOf("title", "subtitle")))),
    )

  private val suggestion =
    DesignSuggestion(
      suggestionId = "design-suggestion-1",
      summary = "Warmer title with a subtitle",
      proposedBy = "agent:designer",
      displayName = "Design agent",
      kind = DesignCommentAuthorKind.Agent,
      forkRevision = 4,
      operationIds = listOf("s-1", "s-2"),
      document = proposed,
    )

  @Test
  fun `a suggestion's diff is its own change against the revision it was made on`() {
    val state = UiBuilderEditorReducer(catalog).initial(design)
    val diff = assertNotNull(suggestionDiff(state, suggestion, catalog))
    assertEquals(1, diff.added)
    // The title's text, and the content column that gained a child.
    assertEquals(2, diff.changed)
    assertEquals(
      setOf("subtitle" to EditorNodeDiffKind.Added, "title" to EditorNodeDiffKind.Changed),
      diff.nodes
        .filter { it.nodeId in setOf("title", "subtitle") }
        .map { it.nodeId to it.kind }
        .toSet(),
    )
    assertFalse(suggestion.isBehind(state))
    assertTrue(suggestion.copy(forkRevision = 2).isBehind(state))
    // No document yet: no diff, rather than a guess.
    assertNull(suggestionDiff(state, suggestion.copy(document = null), catalog))
  }

  @Test
  fun `the list renders, and Accept and Reject reach the host`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val accepted = mutableListOf<String>()
      val rejected = mutableListOf<String>()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            design,
            catalog,
            initialInspectorMode = EditorInspectorMode.Comments,
            initialInspectorOpen = true,
            suggestions = DesignSuggestions(open = listOf(suggestion)),
            onAcceptSuggestion = { accepted += it },
            onRejectSuggestion = { rejected += it },
          )
        }
      }
      onNodeWithText("Suggestions · 1 waiting").assertIsDisplayed()
      onNodeWithText("Warmer title with a subtitle").assertIsDisplayed()
      onNodeWithText("by Design agent (agent) · 2 edits · on r4").assertIsDisplayed()
      onNodeWithText("1 added · 2 changed").assertIsDisplayed()

      // Show swaps the canvas for the proposal; nothing is decided by looking.
      onNodeWithContentDescription("Show Warmer title with a subtitle on the canvas").performClick()
      onNodeWithText("Suggestion: Warmer title with a subtitle").assertIsDisplayed()
      onNodeWithText("Suggested").assertIsDisplayed()
      runOnIdle { assertTrue(accepted.isEmpty() && rejected.isEmpty()) }

      onNodeWithContentDescription("Reject Warmer title with a subtitle").performClick()
      runOnIdle { assertEquals(listOf("design-suggestion-1"), rejected) }
      onNodeWithContentDescription("Accept Warmer title with a subtitle").performClick()
      runOnIdle { assertEquals(listOf("design-suggestion-1"), accepted) }
      // Deciding puts the design back on the canvas.
      assertTrue(
        onAllNodesWithText("Suggestion: Warmer title with a subtitle")
          .fetchSemanticsNodes()
          .isEmpty()
      )
    }

  @Test
  fun `a refused accept is said under the list`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val outcome =
        DesignSuggestionOutcome.Refused(
          suggestionId = "design-suggestion-1",
          summary = "Warmer title with a subtitle",
          reason = "edit s-2 changes subtitle, which is no longer in the design.",
          operationId = "s-2",
        )
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            design,
            catalog,
            initialInspectorMode = EditorInspectorMode.Comments,
            initialInspectorOpen = true,
            suggestions = DesignSuggestions(open = listOf(suggestion), lastOutcome = outcome),
            onAcceptSuggestion = {},
            onRejectSuggestion = {},
          )
        }
      }
      onNodeWithContentDescription(outcome.describe()).assertIsDisplayed()
    }

  @Test
  fun `without suggestion support and with nothing proposed the section is not drawn`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            design,
            catalog,
            initialInspectorMode = EditorInspectorMode.Comments,
            initialInspectorOpen = true,
          )
        }
      }
      waitForIdle()
      assertTrue(
        onAllNodesWithText("Suggestions", substring = true).fetchSemanticsNodes().isEmpty()
      )
    }

  @Test
  fun `the host's list and merge reports decode into what the panel says`() {
    val listed =
      assertNotNull(
        DesignSuggestionCodec.decodeList(
          """
          {"suggestions":[{"suggestionId":"design-s1","summary":"Bigger button",
            "proposedBy":"agent:x","proposerKind":"agent","displayName":"X",
            "createdAtEpochMillis":5,"forkRevision":12,"operationIds":["a","b"],"extra":true}]}
          """
        )
      )
    val one = listed.single()
    assertEquals("design-s1", one.suggestionId)
    assertEquals(DesignCommentAuthorKind.Agent, one.kind)
    assertEquals(12, one.forkRevision)
    assertEquals(listOf("a", "b"), one.operationIds)
    assertNull(DesignSuggestionCodec.decodeList("<html>not found</html>"))

    val merged =
      assertIs<DesignSuggestionOutcome.Accepted>(
        DesignSuggestionCodec.decodeAcceptOutcome(
          """
          {"merged":true,"parentRevisionAfter":14,"commands":[
            {"operationId":"a","actorId":"agent:x","status":"APPLIED","conflicts":[
              {"code":"STALE_PROPERTY_WRITE","nodeId":"cta","field":"text","overwrittenRevision":13}]},
            {"operationId":"b","actorId":"agent:x","status":"APPLIED"}],
           "skippedOperationIds":[]}
          """,
          one,
        )
      )
    assertEquals(2, merged.landed)
    assertEquals(14, merged.atRevision)
    assertEquals(listOf("text of cta (set at r13)"), merged.overwrites)
    assertEquals(
      "Accepted “Bigger button”: 2 changes landed at r14. It overwrote: text of cta (set at r13).",
      merged.describe(),
    )

    val refused =
      assertIs<DesignSuggestionOutcome.Refused>(
        DesignSuggestionCodec.decodeAcceptOutcome(
          """
          {"merged":false,"commands":[
            {"operationId":"a","status":"APPLIED"},
            {"operationId":"b","status":"REFUSED","code":"DELETED_NODE","nodeId":"cta"}],
           "remaining":0}
          """,
          one,
        )
      )
    assertEquals("b", refused.operationId)
    assertEquals("edit b changes cta, which is no longer in the design.", refused.reason)
    assertTrue(refused.describe().contains("Nothing was applied"))

    assertEquals(
      """{"acceptOperationIds":["a"]}""",
      DesignSuggestionCodec.encodeAccept(listOf("a")),
    )
    assertEquals("{}", DesignSuggestionCodec.encodeAccept(null))
  }

  private fun text(id: String, value: String) =
    UiBuilderNode(
      id,
      "m3/text",
      buildJsonObject { put("text", selectionLiteral(JsonPrimitive(value))) },
    )
}
