package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Changes the design's display title. The id — and with it the URL, links, history and access —
 * stays as it is; only what the home screen, the toolbar and exports call the design changes.
 *
 * [onRename] is the host's: it answers null once the title is stored, or the sentence saying why
 * not, which is shown here so the person can correct it. [onRenamed] runs only after a stored
 * rename.
 */
@Composable
internal fun RenameDesignDialog(
  designId: String,
  currentTitle: String,
  onRename: suspend (String) -> String?,
  onRenamed: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var title by remember(designId) { mutableStateOf(currentTitle) }
  var error by remember(designId) { mutableStateOf<String?>(null) }
  var saving by remember(designId) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val trimmed = title.trim()
  val canSave = !saving && trimmed.isNotEmpty() && trimmed != currentTitle
  val submit = {
    if (canSave) {
      saving = true
      error = null
      scope.launch {
        val refusal =
          try {
            onRename(trimmed)
          } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            failure.message ?: "the design could not be renamed"
          }
        saving = false
        if (refusal == null) {
          onRenamed(trimmed)
          onDismiss()
        } else {
          error = refusal
        }
      }
    }
  }
  TrackEditorOverlay(true)
  AlertDialog(
    onDismissRequest = { if (!saving) onDismiss() },
    title = { Text("Rename design") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = title,
          onValueChange = {
            title = it.take(MAX_DESIGN_TITLE_LENGTH)
            error = null
          },
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Design title" },
          label = { Text("Title") },
          isError = error != null || trimmed.isEmpty(),
          supportingText = {
            Text(
              error
                ?: if (trimmed.isEmpty()) "A design needs a title"
                else "The id $designId, and every link to it, stays the same.",
            )
          },
          singleLine = true,
          enabled = !saving,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        if (saving) {
          Text(
            "Saving…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = { Button(onClick = { submit() }, enabled = canSave) { Text("Rename") } },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
  )
}

/**
 * The refusal in a host `fetch` answer (`{"status": …, "body": …}`) to a rename, or null when it
 * was stored. The server's own sentence when it sent one; a `405` (a server without the route) and
 * an HTML error page are said in words instead.
 */
internal fun renameRefusal(encodedResponse: String): String? {
  val response =
    try {
      kotlinx.serialization.json.Json.parseToJsonElement(encodedResponse)
        as? kotlinx.serialization.json.JsonObject
    } catch (_: Exception) {
      null
    } ?: return "the server answered something unreadable"
  val status =
    (response["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
  if (status in 200..299) return null
  val body = (response["body"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
  return when {
    status == 405 -> "this server cannot rename designs yet"
    body.isNotBlank() && body.length <= 300 && !body.trimStart().startsWith("<") -> body.trim()
    else -> "the server answered $status while renaming"
  }
}
