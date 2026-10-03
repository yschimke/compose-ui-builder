package ee.schimke.composeai.uibuilder.mcpapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.editor.FocusedCanvasUiBuilderChrome
import ee.schimke.composeai.uibuilder.editor.MaterialUiBuilderChrome
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import kotlinx.coroutines.launch

/**
 * What the MCP App shows once a design is open: the slim file bar, and the editor under it in the
 * [layout] the host or the person chose.
 *
 * Common code rather than the browser's, so the layout switch and the comment path are tested on
 * the JVM against a fake host; `McpAppHostApp` adds the `postMessage` transport, autosave and the
 * selection's model context around it.
 *
 * The focused layout is a chrome ([FocusedCanvasUiBuilderChrome]) handed to the same
 * [UiBuilderEditor] call, so switching layouts keeps the editor's state — selection, zoom, undo
 * history — where it is. A reload of the file is still a new editor
 * ([McpAppDesignState.generation]).
 */
@Composable
fun McpAppEditorScreen(
  state: McpAppDesignState,
  session: McpAppDesignSession,
  catalog: CapabilityCatalog,
  layout: McpAppLayout,
  onLayoutChange: (McpAppLayout) -> Unit,
  /**
   * Whether the host takes `ui/message`: the node menu offers Comment only where it can be sent.
   */
  commentsEnabled: Boolean,
  onEditorState: (UiBuilderEditorState) -> Unit,
  onHelp: (() -> Unit)? = null,
  /** Called once each editor has been composed: the browser marks the page ready. */
  onEditorShown: () -> Unit = {},
  /**
   * The device frames the Screen dock offers and the variant strip draws, when the host supplies
   * them (see `mcpAppDevicePresetsUrl`); empty leaves the raw width, height and density fields.
   */
  devicePresets: List<UiBuilderDevicePreset> = emptyList(),
  modifier: Modifier = Modifier,
) {
  val document = state.document ?: return
  val scope = rememberCoroutineScope()
  val focusedChrome = remember { FocusedCanvasUiBuilderChrome() }
  Column(modifier.fillMaxSize()) {
    McpAppFileBar(
      state = state,
      layout = layout,
      onToggleLayout = { onLayoutChange(layout.toggled()) },
      onSave = { scope.launch { session.save() } },
      onReload = { scope.launch { session.reload() } },
      onOverwrite = { scope.launch { session.overwrite() } },
      onKeepMine = session::keepLocalChanges,
      onDismiss = session::dismissNotice,
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // A reload is a new design to the editor, as an `open` is to the host bridge: its undo
      // history was made against the version that was replaced. A save is not a reload, so the
      // history outlives every save. The layout is not in the key: switching it is a chrome.
      key(state.generation) {
        UiBuilderEditor(
          document = document,
          catalog = catalog,
          chrome = if (layout == McpAppLayout.Focused) focusedChrome else MaterialUiBuilderChrome,
          sessionLabel = session.file.name,
          devicePresets = devicePresets,
          onStateChanged = onEditorState,
          onHelp = onHelp,
          onCommentOnNode =
            if (commentsEnabled) {
              { comment ->
                scope.launch {
                  session.comment(
                    comment.nodeId,
                    comment.text,
                    comment.image?.let { McpAppImage(it.base64, it.mediaType) },
                  )
                }
              }
            } else null,
        )
        LaunchedEffect(Unit) { onEditorShown() }
      }
    }
  }
}

/**
 * The file's name, whether it is saved, the switch between the focused and the full editor, and the
 * decisions a notice asks for. It is all the chrome the focused layout has.
 */
@Composable
internal fun McpAppFileBar(
  state: McpAppDesignState,
  layout: McpAppLayout,
  onToggleLayout: () -> Unit,
  onSave: () -> Unit,
  onReload: () -> Unit,
  onOverwrite: () -> Unit,
  onKeepMine: () -> Unit,
  onDismiss: () -> Unit,
) {
  Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          state.file.name,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          saveStatus(state),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Named for where it goes rather than for where it is: the switch a person reaches for is
        // the editor they want next.
        val toggleLabel = if (layout == McpAppLayout.Focused) "Full editor" else "Focused canvas"
        TextButton(
          onClick = onToggleLayout,
          modifier = Modifier.semantics { contentDescription = toggleLabel },
        ) {
          Text(toggleLabel)
        }
        if (state.writable) {
          Button(onClick = onSave, enabled = state.dirty && !state.saving) { Text("Save") }
        }
      }
      val notice = state.notice
      if (notice != null) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .background(noticeColor(notice))
              .padding(horizontal = 12.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            noticeText(notice, state),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1D1B20),
            modifier = Modifier.weight(1f),
          )
          when (notice) {
            is McpAppNotice.Conflict -> {
              TextButton(onClick = onReload) { Text("Reload theirs") }
              TextButton(onClick = onOverwrite) { Text("Overwrite with mine") }
            }
            is McpAppNotice.ExternalChange -> {
              TextButton(onClick = onReload) { Text("Reload") }
              TextButton(onClick = onKeepMine) { Text("Keep mine") }
            }
            is McpAppNotice.TooLarge,
            is McpAppNotice.Error -> TextButton(onClick = onDismiss) { Text("Dismiss") }
            McpAppNotice.ReadOnly -> Unit
          }
        }
      }
    }
  }
}

private fun saveStatus(state: McpAppDesignState): String = buildList {
  add(
    when {
      !state.writable -> "Read-only"
      state.saving -> "Saving…"
      state.dirty -> "Unsaved changes"
      else -> "Saved"
    }
  )
  if (!state.live) add("not following external edits")
}
  .joinToString(" · ")

private fun noticeText(notice: McpAppNotice, state: McpAppDesignState): String =
  when (notice) {
    McpAppNotice.ReadOnly ->
      "Read-only: the host did not allow writing ${state.file.name}. Edits stay in this view and " +
        "are not saved."
    is McpAppNotice.Conflict ->
      "${state.file.name} changed outside the editor since it was opened. Your edits are not saved."
    is McpAppNotice.ExternalChange ->
      "${state.file.name} changed outside the editor, and you have unsaved edits."
    is McpAppNotice.TooLarge ->
      "Not saved: the design is ${notice.bytes} bytes and the host accepts at most " +
        "${notice.maxBytes}."
    is McpAppNotice.Error -> notice.message
  }

private fun noticeColor(notice: McpAppNotice): Color =
  when (notice) {
    McpAppNotice.ReadOnly -> Color(0xFFE8DEF8)
    is McpAppNotice.ExternalChange -> Color(0xFFFFF4D6)
    else -> Color(0xFFFFDAD6)
  }
