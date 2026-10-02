package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * The canvas's stand-in while a suggestion is being looked at: the design now, the design as the
 * suggestion would leave it, and the list of what moves — with Accept and Reject on the bar.
 *
 * It **replaces** the editing canvas, for the reason [RevisionReviewPane] does: a canvas drawing a
 * proposal that nobody has accepted would take edits against a document that does not exist yet.
 * Not composing the editing surface is that rule holding itself. The pictures are drawn by the
 * renderer that draws the canvas ([RevisionPicture]), so they cannot disagree with what accepting
 * would show.
 */
@Composable
internal fun SuggestionReviewPane(
  suggestion: DesignSuggestion,
  current: UiBuilderDocument,
  diff: EditorRevisionDiff?,
  behind: Boolean,
  onAccept: (() -> Unit)?,
  onReject: (() -> Unit)?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.background(LocalUiBuilderEditorPalette.current.workspace)) {
    Surface(
      Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.tertiaryContainer,
      tonalElevation = 2.dp,
    ) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            "Suggestion: ${suggestion.summary}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            buildString {
              append("by ${suggestion.who}")
              diff?.let { append(" · ${it.summaryLine()}") }
              append(" · read-only until accepted")
              if (behind) append(" · made on r${suggestion.forkRevision}, the design has moved on")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
        if (onAccept != null) {
          Button(onClick = onAccept, modifier = Modifier.padding(start = 6.dp)) { Text("Accept") }
        }
        if (onReject != null) {
          OutlinedButton(onClick = onReject, modifier = Modifier.padding(start = 6.dp)) {
            Text("Reject")
          }
        }
        TextButton(onClick = onBack) { Text("Back to the design") }
      }
    }
    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
      SuggestionPicture("Now · r${current.revision}", current, Modifier.weight(1f).fillMaxSize())
      SuggestionPicture("Suggested", suggestion.document, Modifier.weight(1f).fillMaxSize())
      if (diff != null) {
        RevisionDiffList(diff, Modifier.width(260.dp).fillMaxSize().padding(start = 16.dp))
      }
    }
  }
}

@Composable
private fun SuggestionPicture(label: String, document: UiBuilderDocument?, modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 6.dp),
    )
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      if (document == null) {
        Text(
          "The suggestion has not arrived yet.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        RevisionPicture(document, Modifier.fillMaxSize())
      }
    }
  }
}
