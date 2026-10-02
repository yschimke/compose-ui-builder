package ee.schimke.composeai.uibuilder.inspector

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignSuggestion
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionOutcome
import ee.schimke.composeai.uibuilder.editor.EditorRevisionDiff
import ee.schimke.composeai.uibuilder.editor.describe
import ee.schimke.composeai.uibuilder.editor.summaryLine

/** One suggestion as the section draws it: the suggestion, and what it changes once known. */
internal data class SuggestionRow(
  val suggestion: DesignSuggestion,
  /** Null until the suggestion's document has arrived. */
  val diff: EditorRevisionDiff?,
  /** The design has moved on since the suggestion was made. */
  val behind: Boolean,
)

/**
 * The suggestions waiting on this design, at the top of the comments tab.
 *
 * Beside the discussion and the review verdict because it is the same conversation: an agent asked
 * "shall I?", and this is where a person answers — look ([onShow] swaps the canvas for the proposal
 * and its diff), then **Accept** (the branch's replay-merge) or **Reject** (archive it). Nothing is
 * applied by looking.
 *
 * It holds no state of its own: [rows] and [outcome] are whatever the host last said, and a click
 * shows nothing until the host has answered.
 */
@Composable
internal fun SuggestionsSection(
  rows: List<SuggestionRow>,
  /** The suggestion the canvas is showing, or null while it shows the design. */
  shownSuggestionId: String?,
  outcome: DesignSuggestionOutcome?,
  /** A sentence from the host — a list that failed, a refused reject. */
  hostStatus: String?,
  onShow: (String?) -> Unit,
  /** Null where this page cannot decide — the rows are then only reported. */
  onAccept: ((String) -> Unit)?,
  onReject: ((String) -> Unit)?,
) {
  Column(Modifier.fillMaxWidth()) {
    Text(
      when (rows.size) {
        0 -> "Suggestions"
        1 -> "Suggestions · 1 waiting"
        else -> "Suggestions · ${rows.size} waiting"
      },
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Medium,
    )
    if (rows.isEmpty()) {
      Text(
        "Nothing is waiting. An agent's proposed edits appear here to accept or reject.",
        Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    rows.forEach { row ->
      SuggestionCard(
        row = row,
        shown = row.suggestion.suggestionId == shownSuggestionId,
        onShow = onShow,
        onAccept = onAccept,
        onReject = onReject,
      )
    }
    outcome?.let {
      Text(
        it.describe(),
        Modifier.padding(top = 6.dp).semantics { contentDescription = it.describe() },
        style = MaterialTheme.typography.bodySmall,
        color =
          when (it) {
            is DesignSuggestionOutcome.Refused -> MaterialTheme.colorScheme.error
            is DesignSuggestionOutcome.Accepted -> MaterialTheme.colorScheme.primary
            is DesignSuggestionOutcome.Rejected -> MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
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

@Composable
private fun SuggestionCard(
  row: SuggestionRow,
  shown: Boolean,
  onShow: (String?) -> Unit,
  onAccept: ((String) -> Unit)?,
  onReject: ((String) -> Unit)?,
) {
  val suggestion = row.suggestion
  val id = suggestion.suggestionId
  Column(
    Modifier.fillMaxWidth()
      .padding(top = 6.dp)
      .border(
        1.dp,
        if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        RoundedCornerShape(6.dp),
      )
      .padding(8.dp)
  ) {
    Text(
      suggestion.summary,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      buildString {
        append("by ${suggestion.who}")
        if (suggestion.kind == DesignCommentAuthorKind.Agent) append(" (${suggestion.kind.badge})")
        val count = suggestion.operationIds.size
        if (count > 0) append(if (count == 1) " · 1 edit" else " · $count edits")
        append(" · on r${suggestion.forkRevision}")
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      row.diff?.let { if (it.identical) "Changes nothing on the design" else it.summaryLine() }
        ?: "Loading what it changes…",
      Modifier.padding(top = 2.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (row.behind) {
      Text(
        "The design has changed since; accepting applies it to the design as it is now.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
      )
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (onAccept != null) {
        Button(
          onClick = { onAccept(id) },
          modifier = Modifier.semantics { contentDescription = "Accept ${suggestion.summary}" },
        ) {
          Text("Accept")
        }
      }
      if (onReject != null) {
        OutlinedButton(
          onClick = { onReject(id) },
          modifier = Modifier.semantics { contentDescription = "Reject ${suggestion.summary}" },
        ) {
          Text("Reject")
        }
      }
      if (suggestion.document != null) {
        TextButton(
          onClick = { onShow(if (shown) null else id) },
          modifier =
            Modifier.semantics {
              contentDescription =
                if (shown) "Back to the design" else "Show ${suggestion.summary} on the canvas"
            },
        ) {
          Text(if (shown) "Hide" else "Show")
        }
      }
    }
  }
}
