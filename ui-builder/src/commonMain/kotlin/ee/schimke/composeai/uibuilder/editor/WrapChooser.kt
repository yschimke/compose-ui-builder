package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The three container roles the wrap chord offers, in digit order. The digit belongs to the role,
 * not to its row: `Ctrl/Cmd+Alt+G` then `2` is always a Row, even on a selection a Column cannot
 * hold, so the hand learns one sequence rather than reading the list every time.
 *
 * Roles, not component ids: the floor's are `layout/column`, A2UI's are `a2ui/Column`, and naming
 * either here would be catalog knowledge the catalog-id ratchet keeps out of the builder. No
 * catalog publishes an axis trait yet, so a candidate's role is read from its display name, which
 * every catalog in use spells the same way. A catalog with no Box simply has no row 3. The full
 * list of containers stays behind the node menu's "Wrap in…"; this is the short path for the three
 * that cover most wraps.
 */
internal val QUICK_WRAP_ROLES: List<String> = listOf("Column", "Row", "Box")

/** One row of the wrap chooser: the container and the digit that picks it. */
internal data class QuickWrapChoice(val digit: Int, val item: EditorCatalogItem)

/**
 * The quick containers the selection can actually be wrapped in, from
 * [UiBuilderEditorReducer.wrapCandidates] — the same both-ends check the node menu's "Wrap in…"
 * uses, so the chooser never offers a wrap the reducer would refuse. Empty when none fits, which is
 * when the chord says so instead of opening an empty list.
 */
internal fun quickWrapChoices(candidates: List<EditorCatalogItem>): List<QuickWrapChoice> =
  QUICK_WRAP_ROLES.mapIndexedNotNull { index, role ->
    candidates
      .filter { it.displayName.trim().equals(role, ignoreCase = true) }
      // Should two components answer to one name (a pack's own Row beside the catalog's), the
      // one the catalog files as a container is the layout primitive.
      .minByOrNull { if (it.kind == EditorComponentKind.Container) 0 else 1 }
      ?.let { QuickWrapChoice(index + 1, it) }
  }

/** What a key does to the open chooser. Pure, so the keyboard contract is testable without UI. */
internal sealed interface WrapChooserKeyResult {
  data class Highlight(val index: Int) : WrapChooserKeyResult

  data class Pick(val choice: QuickWrapChoice) : WrapChooserKeyResult

  data object Dismiss : WrapChooserKeyResult

  /** Not the chooser's key; swallowed anyway, since the chooser is modal. */
  data object Ignored : WrapChooserKeyResult
}

internal fun wrapChooserKey(
  key: Key,
  choices: List<QuickWrapChoice>,
  highlighted: Int,
): WrapChooserKeyResult {
  if (choices.isEmpty()) return WrapChooserKeyResult.Dismiss
  val digit =
    when (key) {
      Key.One,
      Key.NumPad1 -> 1
      Key.Two,
      Key.NumPad2 -> 2
      Key.Three,
      Key.NumPad3 -> 3
      else -> null
    }
  if (digit != null) {
    // A digit whose container does not fit here does nothing, rather than picking whatever row
    // happens to sit at that position.
    return choices.firstOrNull { it.digit == digit }?.let(WrapChooserKeyResult::Pick)
      ?: WrapChooserKeyResult.Ignored
  }
  return when (key) {
    // Wraps around, like the node menu's own arrows: three rows are never far apart.
    Key.DirectionDown -> WrapChooserKeyResult.Highlight((highlighted + 1).mod(choices.size))
    Key.DirectionUp -> WrapChooserKeyResult.Highlight((highlighted - 1).mod(choices.size))
    Key.Enter,
    Key.NumPadEnter,
    Key.Spacebar -> WrapChooserKeyResult.Pick(choices[highlighted.coerceIn(choices.indices)])
    Key.Escape -> WrapChooserKeyResult.Dismiss
    else -> WrapChooserKeyResult.Ignored
  }
}

/**
 * The chooser the wrap chord opens. Modal, so its keys never reach the editor's shortcut table
 * underneath: an arrow moves the highlight here rather than the selection on the canvas.
 */
@Composable
internal fun WrapChooserDialog(
  choices: List<QuickWrapChoice>,
  onPick: (QuickWrapChoice) -> Unit,
  onDismiss: () -> Unit,
) {
  TrackEditorOverlay(true)
  var highlighted by remember(choices) { mutableIntStateOf(0) }
  val focus = remember { FocusRequester() }
  Dialog(onDismissRequest = onDismiss) {
    WrapChooserPanel(
      choices = choices,
      highlighted = highlighted,
      onPick = onPick,
      modifier =
        Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          when (val result = wrapChooserKey(event.key, choices, highlighted)) {
            is WrapChooserKeyResult.Highlight -> highlighted = result.index
            is WrapChooserKeyResult.Pick -> onPick(result.choice)
            WrapChooserKeyResult.Dismiss -> onDismiss()
            WrapChooserKeyResult.Ignored -> Unit
          }
          true
        },
    )
  }
  // Focused on open, so the digit typed straight after the chord lands here without a click.
  LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The chooser's contents, separate from the dialog so it can be previewed. */
@Composable
internal fun WrapChooserPanel(
  choices: List<QuickWrapChoice>,
  highlighted: Int,
  onPick: (QuickWrapChoice) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier.width(240.dp),
    shape = RoundedCornerShape(12.dp),
    tonalElevation = 6.dp,
    shadowElevation = 6.dp,
  ) {
    Column(Modifier.padding(vertical = 8.dp)) {
      Text(
        "Wrap in",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      choices.forEachIndexed { index, choice ->
        val selected = index == highlighted
        Row(
          Modifier.fillMaxWidth()
            .then(
              if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
              else Modifier
            )
            .clickable(role = Role.Button) { onPick(choice) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Text(
              "${choice.digit}",
              Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
              style = MaterialTheme.typography.labelMedium,
            )
          }
          Text(
            choice.item.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (selected) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      Text(
        "↑↓ and Enter, or the digit",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
