package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignSuggestion
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionOutcome
import ee.schimke.composeai.uibuilder.editor.DesignSuggestions
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Suggestion mode: an agent's proposed edits waiting on the design, in the Talk panel, and one of
 * them shown in place of the canvas beside the design as it is now.
 *
 * A new visual surface is wired into the preview workflow so the next change to it is diffed
 * without anyone remembering to. What these keep honest is what no state test sees: the card's
 * who/when/diff lines, the Accept / Reject / Show row, the outcome sentence, and the two pictures
 * and change list of the canvas swap. Everything is fixed — the proposal is the Jetcaster Discover
 * screen with two labels rewritten, built here.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderSuggestionsPanelPreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialInspectorMode = EditorInspectorMode.Comments,
    initialInspectorOpen = true,
    suggestions =
      DesignSuggestions(
        open = suggestionsPreview,
        // The answer to an accept a moment ago that collided with a person's edit.
        lastOutcome =
          DesignSuggestionOutcome.Refused(
            suggestionId = "discover-suggestion-old",
            summary = "Drop the comedy chip",
            reason = "edit s-9 changes chip-comedy, which is no longer in the design.",
            operationId = "s-9",
          ),
      ),
    onAcceptSuggestion = {},
    onRejectSuggestion = {},
  )
}

/** The first suggestion shown on the canvas: now and suggested side by side, with the diff. */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderSuggestionOnCanvasPreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialInspectorMode = EditorInspectorMode.Comments,
    initialInspectorOpen = true,
    suggestions = DesignSuggestions(open = suggestionsPreview),
    onAcceptSuggestion = {},
    onRejectSuggestion = {},
    initialShownSuggestionId = "discover-suggestion-1",
  )
}

private val suggestionsPreview: List<DesignSuggestion> by lazy {
  val design = referencePreviewDocument
  listOf(
    DesignSuggestion(
      suggestionId = "discover-suggestion-1",
      summary = "Clearer search prompt and category name",
      proposedBy = "agent:design-assistant",
      displayName = "Design assistant",
      kind = DesignCommentAuthorKind.Agent,
      forkRevision = design.revision.toLong(),
      operationIds = listOf("s-1", "s-2"),
      document =
        design
          .withText("search-placeholder", "Search podcasts and episodes")
          .withText("chip-news-label", "Daily news")
          .copy(id = "discover-suggestion-1", revision = design.revision + 2),
    ),
    DesignSuggestion(
      suggestionId = "discover-suggestion-2",
      summary = "Rename the Crime chip",
      proposedBy = "agent:design-assistant",
      displayName = "Design assistant",
      kind = DesignCommentAuthorKind.Agent,
      // Made two revisions ago: the card says the design has moved on since.
      forkRevision = design.revision.toLong() - 2,
      operationIds = listOf("s-3"),
      document =
        design
          .withText("chip-crime-label", "True crime")
          .copy(id = "discover-suggestion-2", revision = design.revision - 1),
    ),
  )
}

private fun UiBuilderDocument.withText(nodeId: String, value: String): UiBuilderDocument {
  val node = nodes.getValue(nodeId)
  val text = JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
  return copy(
    nodes =
      nodes + (nodeId to node.copy(properties = JsonObject(node.properties + ("text" to text))))
  )
}
