package ee.schimke.composeai.uibuilder.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignReview
import ee.schimke.composeai.uibuilder.editor.DesignReviewDecision
import ee.schimke.composeai.uibuilder.editor.DesignReviewVerdict

/**
 * Approve this revision, or ask for changes — at the top of the comments tab.
 *
 * The verdict an agent waits for with `ui_builder_await_decision` (compose-preview-server#1255).
 * Beside the discussion rather than in a toolbar because it is the end of one: somebody reads what
 * was said, looks at the canvas, and decides. It is about the revision on screen, which is why the
 * revision is named on the buttons' line: approving r4 says nothing about r5, and an agent waiting
 * on r5 is not answered by it.
 *
 * Like the comments panel it holds no cache: [review] is whatever the host last said, and a click
 * shows nothing until the host has stored it and answered.
 */
@Composable
internal fun ReviewDecisionSection(
  review: DesignReview,
  revision: Long,
  /** Null where the host keeps no reviews, or this page cannot record one. */
  onDecide: ((DesignReviewVerdict, String?) -> Unit)?,
  /** A sentence from the host — a refusal, a failed save. */
  hostStatus: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  var note by remember(revision) { mutableStateOf("") }
  val current = review.latestFor(revision)
  val earlier = review.latest?.takeIf { current == null && it.revision != revision }

  Column(Modifier.fillMaxWidth()) {
    Text(
      "Review · revision $revision",
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Medium,
    )
    Text(
      when {
        current != null -> current.describe()
        earlier != null -> "No verdict on this revision yet. ${earlier.describe()}"
        else -> "Nobody has approved or rejected this design yet."
      },
      Modifier.padding(top = 2.dp),
      style = MaterialTheme.typography.bodySmall,
      color =
        when (current?.verdict) {
          DesignReviewVerdict.Approve -> MaterialTheme.colorScheme.primary
          DesignReviewVerdict.Reject -> MaterialTheme.colorScheme.error
          null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    if (onDecide != null) {
      CommentField(
        value = note,
        placeholder = "Why, in a sentence (optional)…",
        label = "Review note",
        onChange = { note = it },
        onFocusChanged = onTextInputFocusChanged,
      )
      Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = {
            onDecide(DesignReviewVerdict.Approve, note.ifBlank { null })
            note = ""
          },
          modifier = Modifier.semantics { contentDescription = "Approve revision $revision" },
        ) {
          Text("Approve")
        }
        OutlinedButton(
          onClick = {
            onDecide(DesignReviewVerdict.Reject, note.ifBlank { null })
            note = ""
          },
          modifier =
            Modifier.semantics { contentDescription = "Request changes on revision $revision" },
        ) {
          Text("Request changes")
        }
      }
    }
    hostStatus?.let {
      SelectionContainer {
        Text(
          it,
          Modifier.padding(top = 6.dp),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
  }
}

private fun DesignReviewDecision.describe(): String = buildString {
  append("${verdict.label} r$revision by $who")
  if (kind == DesignCommentAuthorKind.Agent) append(" (${kind.badge})")
  note?.takeIf { it.isNotBlank() }?.let { append(" — “$it”") }
  append('.')
}
