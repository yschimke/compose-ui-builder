package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.editor.DesignComment
import ee.schimke.composeai.uibuilder.editor.DesignCommentAnchor
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignCommentBoard
import ee.schimke.composeai.uibuilder.editor.DesignCommentThread
import ee.schimke.composeai.uibuilder.editor.DesignReview
import ee.schimke.composeai.uibuilder.editor.DesignReviewDecision
import ee.schimke.composeai.uibuilder.editor.DesignReviewVerdict
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor

/**
 * The discussion on a design: the Talk panel, and the pins that put each thread on the frame. Diffs
 * the anchor chips, the agent badge, a resolved thread's greyed pin, an expanded thread's reply
 * field, and a pin sitting on its mark.
 *
 * Fully fixed: the board is built here, the reference is the overlay previews' SVG, and nothing
 * reads a clock, the network or randomness.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderCommentsPanelPreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Comments,
    initialInspectorOpen = true,
    restoredReference = referencePreviewOverlay,
    comments = commentsPreviewBoard,
    onPostComment = {},
    onResolveCommentThread = { _, _ -> },
    // An agent's verdict on an earlier revision and none on this one: the section says both, and
    // offers Approve and Request changes for the revision on screen.
    review = reviewPreview,
    onDecide = { _, _ -> },
  )
}

/**
 * An agent's verdict on the previous revision, so the review section's "nothing on this revision
 * yet, but…" line is drawn.
 */
private val reviewPreview =
  DesignReview(
    decisions =
      listOf(
        DesignReviewDecision(
          revision = referencePreviewDocument.revision.toLong() - 1,
          verdict = DesignReviewVerdict.Reject,
          decidedBy = "agent:design-reviewer",
          displayName = "Design reviewer",
          kind = DesignCommentAuthorKind.Agent,
          note = "The play icon reads as a cross at 24dp",
        )
      )
  )

/**
 * The same discussion with the markup panel open, proving the features join: pins beside their
 * strokes while the Screen tab's Markup section (with its Discuss button) is shown.
 */
// Taller than its sibling (as `UiBuilderReferenceComponentPiecePreview` is) so the Markup section's
// Discuss button, below the frame controls, is in the render.
@Preview(widthDp = 1600, heightDp = 2000)
@Composable
fun UiBuilderCommentPinsOverMarkupPreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Screen,
    initialInspectorOpen = true,
    restoredReference = referencePreviewOverlay,
    comments = commentsPreviewBoard,
    // A pin shows only for the thread being looked at, so open the arrow's: the one this render
    // exists to show beside its stroke.
    linkedThreadId = "t-arrow",
    onPostComment = {},
    onResolveCommentThread = { _, _ -> },
  )
}

/**
 * Four threads covering the four anchor kinds (a mark, a node, a bare point, and one resolved) plus
 * an agent reply. Ids use the host's real shapes, so account names render; Sam's name equals their
 * login, so it collapses to the handle.
 */
private val commentsPreviewBoard =
  DesignCommentBoard(
    sequence = 7,
    threads =
      listOf(
        DesignCommentThread(
          id = "t-arrow",
          anchor = DesignCommentAnchor(markId = "mark-arrow"),
          updatedAtEpochMillis = 400,
          comments =
            listOf(
              DesignComment(
                id = "c-1",
                authorId = "github:yschimke",
                displayName = "Yuri",
                body = "This arrow is pointing at the wrong card — the hero is the one below.",
                createdAtEpochMillis = 100,
              ),
              DesignComment(
                id = "c-2",
                authorId = "agent:3f9a0c1d",
                displayName = "Review agent",
                kind = DesignCommentAuthorKind.Agent,
                body =
                  "Agreed. `discover-grid` places the hero second; I can move it and re-render " +
                    "if you want that change made.",
                createdAtEpochMillis = 400,
              ),
            ),
        ),
        DesignCommentThread(
          id = "t-node",
          anchor = DesignCommentAnchor(nodeId = "discover-grid"),
          updatedAtEpochMillis = 300,
          comments =
            listOf(
              DesignComment(
                id = "c-3",
                authorId = "github:sam-designs",
                displayName = "sam-designs",
                body = "Two columns on a phone is tight. Can we drop to one under 400dp?",
                createdAtEpochMillis = 300,
              )
            ),
        ),
        DesignCommentThread(
          id = "t-point",
          anchor = DesignCommentAnchor(x = 0.72f, y = 0.62f),
          updatedAtEpochMillis = 200,
          comments =
            listOf(
              DesignComment(
                id = "c-4",
                authorId = "github:yschimke",
                displayName = "Yuri",
                body = "Nothing lives in this gap. Is it meant to?",
                createdAtEpochMillis = 200,
              )
            ),
        ),
        DesignCommentThread(
          id = "t-done",
          anchor = DesignCommentAnchor(markId = "mark-box"),
          resolved = true,
          resolvedBy = "github:yschimke",
          updatedAtEpochMillis = 150,
          comments =
            listOf(
              DesignComment(
                id = "c-5",
                authorId = "agent:3f9a0c1d",
                displayName = "Review agent",
                kind = DesignCommentAuthorKind.Agent,
                body = "The header is 8dp short of the mock. Fixed at revision 41.",
                createdAtEpochMillis = 150,
              )
            ),
        ),
      ),
  )
