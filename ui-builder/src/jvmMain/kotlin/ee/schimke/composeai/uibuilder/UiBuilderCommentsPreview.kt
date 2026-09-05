package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * The discussion on a design: the Talk panel, and the pins that put each thread on the frame.
 *
 * A new visual surface is wired into the preview workflow so the next change to it is diffed
 * without anyone remembering to — the reason [UiBuilderReferenceOverlayPreview] exists. What this
 * one has to keep honest is the half no state test can see: the anchor chips, the agent badge, a
 * resolved thread's greyed pin, an expanded thread's reply field, and — the point of the feature —
 * a pin sitting on the mark its thread is about.
 *
 * Everything is fixed. The board is built here, the reference is the same SVG the overlay previews
 * use, and nothing reads a clock, the network or a random source.
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
  )
}

/**
 * The same discussion with the markup panel open instead.
 *
 * A second preview rather than a second state on the first, because this is the render that proves
 * the two features are actually joined: the pins are on the canvas beside the strokes they belong
 * to while the Screen tab's Markup section — with its Discuss button — is the thing on screen.
 */
// Taller than its sibling on purpose, for the reason `UiBuilderReferenceComponentPiecePreview`
// is: the control this render exists to diff — the Markup section's Discuss button — sits below
// the frame controls in a panel that scrolls, and a 900 dp render would prove only that the Screen
// tab still has a heading.
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
    onPostComment = {},
    onResolveCommentThread = { _, _ -> },
  )
}

/**
 * Four threads, chosen to cover the four things a thread can be about and the two who can say it.
 *
 * One on a mark (the arrow), one on a node, one on a bare point, and one resolved — plus a reply
 * from an agent, so the badge that tells a person from an agent is drawn rather than assumed.
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
                authorId = "yuri",
                displayName = "Yuri",
                body = "This arrow is pointing at the wrong card — the hero is the one below.",
                createdAtEpochMillis = 100,
              ),
              DesignComment(
                id = "c-2",
                authorId = "agent-review",
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
                authorId = "sam",
                displayName = "Sam",
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
                authorId = "yuri",
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
          resolvedBy = "yuri",
          updatedAtEpochMillis = 150,
          comments =
            listOf(
              DesignComment(
                id = "c-5",
                authorId = "agent-review",
                displayName = "Review agent",
                kind = DesignCommentAuthorKind.Agent,
                body = "The header is 8dp short of the mock. Fixed at revision 41.",
                createdAtEpochMillis = 150,
              )
            ),
        ),
      ),
  )
