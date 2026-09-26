@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The selection's values, beside the selection.
 *
 * Deliberately the smallest thing that can be an editor: the properties this node actually carries
 * and the numbers inside its modifiers, each one row, each committed where it is typed. No adding,
 * no removing, no binding and no wrapping — those change what the node *is*, they belong in the
 * panel that has room to say so, and a card floating over the design is the wrong place to be
 * offered them. What is left is the thing people do most while looking at a design: change a number
 * and watch it move.
 */
@Composable
internal fun SelectionHoverEditor(
  label: String,
  fields: List<EditorPropertyField>,
  modifierFields: List<EditorModifierField>,
  /** How the node is sized, for the Hug / Fill chips — or null where it cannot be resized. */
  sizing: EditorNodeSizing? = null,
  onResize: (EditorSizing?, EditorSizing?) -> Unit = { _, _ -> },
  /**
   * `property:<name>` or `modifier:<type>.<field>`, for the control a just-run action should land
   * in.
   */
  focusTarget: String?,
  onFocusHandled: () -> Unit,
  onCommitProperty: (String, String) -> Unit,
  onCommitModifier: (EditorModifierField, String) -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  /** Closes the card; the selection and the Properties panel are untouched. */
  onDismiss: (() -> Unit)? = null,
  /**
   * The grip that moves the card, applied to its title row. The canvas owns where the card is, so
   * it supplies the gesture; the card only says which part of it is the handle.
   */
  dragHandle: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    shadowElevation = 8.dp,
    modifier = Modifier.semantics { contentDescription = "Selection editor" },
  ) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(dragHandle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          label,
          Modifier.weight(1f),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (onDismiss != null) {
          Box(
            Modifier.padding(start = 6.dp)
              .size(20.dp)
              .clip(CircleShape)
              .clickable(onClick = onDismiss)
              .semantics { contentDescription = "Close selection editor" },
            contentAlignment = Alignment.Center,
          ) {
            Text(
              "\u00D7",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
      Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        // First, because it is the one decision every layer has and the one a handle can only
        // half make: Fill and Hug are a press here, and a number is the handle's, or the field
        // below it once the handle has written one.
        sizing?.let { nodeSizing ->
          listOf(nodeSizing.width, nodeSizing.height)
            .filter { it.resizable }
            .forEach { axis ->
              HoverSizingRow(axis) { chosen ->
                when (axis.axis) {
                  EditorAxis.Width -> onResize(chosen, null)
                  EditorAxis.Height -> onResize(null, chosen)
                }
              }
            }
        }
        fields.forEach { field ->
          HoverEditorRow(
            label = field.label,
            value = field.value,
            control = field.control,
            choices = field.choices,
            focused = focusTarget == "property:${field.name}",
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
          ) {
            onCommitProperty(field.name, it)
          }
        }
        modifierFields.forEach { field ->
          HoverEditorRow(
            label = field.label,
            value = field.value,
            control =
              if (field.choices.isEmpty()) EditorPropertyControl.Number
              else EditorPropertyControl.Enum,
            choices = field.choices,
            focused = focusTarget == "modifier:${field.type}.${field.field}",
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
          ) {
            onCommitModifier(field, it)
          }
        }
        if (fields.isEmpty() && modifierFields.isEmpty()) {
          Text(
            "Nothing is set on this layer.",
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

/**
 * One axis of the selection's size as two chips — Hug and Fill — with a fixed size shown as a
 * third, selected, when that is what the node has. The fixed chip is a readout, not a button: the
 * number it would need comes from the handle or from the width field under it.
 */
@Composable
private fun HoverSizingRow(axis: EditorAxisSizing, onChoose: (EditorSizing) -> Unit) {
  val name = if (axis.axis == EditorAxis.Width) "Width" else "Height"
  Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      name,
      // The same column the rows below it use, less the 4dp the chips gave back.
      Modifier.width(82.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
    HoverSizingChip("Hug", "$name hugs content", axis.current == EditorSizing.Hug, true) {
      onChoose(EditorSizing.Hug)
    }
    HoverSizingChip("Fill", "$name fills parent", axis.current == EditorSizing.Fill, axis.canFill) {
      onChoose(EditorSizing.Fill)
    }
    (axis.current as? EditorSizing.Fixed)?.let { fixed ->
      HoverSizingChip(fixed.label(), "$name fixed at ${fixed.label()}", true, false) {}
    }
  }
}

@Composable
private fun HoverSizingChip(
  label: String,
  description: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    enabled = enabled && !selected,
    shape = RoundedCornerShape(8.dp),
    color =
      if (selected) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surface,
    contentColor =
      if (selected) MaterialTheme.colorScheme.onSecondaryContainer
      else if (enabled) MaterialTheme.colorScheme.onSurface
      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    border =
      if (selected) null
      else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      Modifier.semantics {
        contentDescription = description
        this.selected = selected
      },
  ) {
    Text(
      label,
      Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      softWrap = false,
    )
  }
}

/** One row of the hover editor: what it is called, and the smallest control that can change it. */
@Composable
internal fun HoverEditorRow(
  label: String,
  value: String,
  control: EditorPropertyControl,
  choices: List<String>,
  focused: Boolean,
  onFocusHandled: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onCommit: (String) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      label,
      Modifier.width(86.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Box(Modifier.weight(1f)) {
      when (control) {
        // Committed on the press rather than on a later Apply: a switch that needs confirming is a
        // switch nobody believes.
        EditorPropertyControl.Boolean ->
          Switch(
            checked = value == "true",
            onCheckedChange = { onCommit(it.toString()) },
            modifier = Modifier.semantics { contentDescription = "$label value" },
          )
        EditorPropertyControl.Enum ->
          HoverEnumControl(label = label, value = value, choices = choices, onCommit = onCommit)
        else ->
          HoverTextControl(
            label = label,
            value = value,
            focused = focused,
            onFocusHandled = onFocusHandled,
            onTextInputFocusChanged = onTextInputFocusChanged,
            onCommit = onCommit,
          )
      }
    }
  }
}

/**
 * A one-line field that commits what was typed when the caret leaves it, or on Enter.
 *
 * No Apply button, which the panel has room for and this does not: the rule here is that leaving
 * the field is the commit, and Enter is the way to say so without moving the pointer.
 */
@Composable
private fun HoverTextControl(
  label: String,
  value: String,
  focused: Boolean,
  onFocusHandled: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onCommit: (String) -> Unit,
) {
  var draft by remember(value) { mutableStateOf(value) }
  // What this field has already sent. Enter commits, and so does losing focus — including the
  // focus loss that *disposal* is, when the commit's own document change rebuilds this card.
  // Without remembering it, one press of Enter wrote the same value twice: two revisions, two
  // undo steps and two rounds to every collaborator for one edit.
  var sent by remember(value) { mutableStateOf(value) }
  val requester = remember { FocusRequester() }
  // A modifier the menu just added lands the caret in its first number, so "add padding" is one
  // press and then a number rather than a press and a hunt for where it went.
  LaunchedEffect(focused) {
    if (focused) {
      requester.requestFocus()
      onFocusHandled()
    }
  }
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    BasicTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      textStyle =
        MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp)
          .focusRequester(requester)
          .onFocusChanged { state ->
            onTextInputFocusChanged(state.isFocused)
            if (!state.isFocused && draft != sent) {
              sent = draft
              onCommit(draft)
            }
          }
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in ENTER_KEYS) {
              if (draft != sent) {
                sent = draft
                onCommit(draft)
              }
              true
            } else false
          }
          .semantics { contentDescription = "$label value" },
    )
  }
}

/** The same row for a property whose values the catalog names. */
@Composable
private fun HoverEnumControl(
  label: String,
  value: String,
  choices: List<String>,
  onCommit: (String) -> Unit,
) {
  var open by remember(label) { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
      modifier = Modifier.semantics { contentDescription = "$label value" },
    ) {
      Text(
        value.ifEmpty { "Choose…" },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    TrackEditorOverlay(open)
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      choices.forEach { choice ->
        DropdownMenuItem(
          text = { Text(choice) },
          onClick = {
            open = false
            onCommit(choice)
          },
        )
      }
    }
  }
}
