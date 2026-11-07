package ee.schimke.composeai.uibuilder.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasHostAction
import kotlinx.coroutines.delay

/** How many fired host actions the editor keeps, newest last. */
internal const val HOST_ACTION_LOG_LIMIT: Int = 20

/** How many of them the overlay shows at once. */
private const val HOST_ACTION_LOG_SHOWN = 3

/** How long the overlay stays after the last host action fired. */
private const val HOST_ACTION_LOG_LINGER_MS = 4_000L

/** [log] with [action] appended, keeping the newest [HOST_ACTION_LOG_LIMIT]. */
internal fun appendHostAction(
  log: List<CanvasHostAction>,
  action: CanvasHostAction,
): List<CanvasHostAction> = (log + action).takeLast(HOST_ACTION_LOG_LIMIT)

/** One fired host action as the overlay prints it: `key-7 → calc.key "7"`. */
internal fun CanvasHostAction.logLine(): String = buildString {
  append(nodeId)
  if (event != "click") append(" (").append(event).append(')')
  append(" → ").append(name)
  value?.let { append(' ').append(it.toString()) }
}

/**
 * What the canvas sent to the host, briefly, over the canvas: a host action changes nothing in the
 * design, so without this a click on a fed key would look like a click that did nothing. It fades
 * once the canvas has been quiet for a few seconds; [fired] counts every action so a repeat of the
 * same one shows it again.
 */
@Composable
internal fun HostActionLog(log: List<CanvasHostAction>, fired: Int, modifier: Modifier = Modifier) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(fired) {
    if (fired == 0) return@LaunchedEffect
    visible = true
    delay(HOST_ACTION_LOG_LINGER_MS)
    visible = false
  }
  AnimatedVisibility(visible && log.isNotEmpty(), modifier, enter = fadeIn(), exit = fadeOut()) {
    Surface(
      color = MaterialTheme.colorScheme.inverseSurface,
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      shape = MaterialTheme.shapes.small,
      modifier =
        Modifier.widthIn(max = 320.dp).semantics {
          liveRegion = LiveRegionMode.Polite
          // Empty-safe: a design switch empties the log while the fade-out still draws this.
          log.lastOrNull()?.let { contentDescription = "Sent to the host: ${it.logLine()}" }
        },
    ) {
      Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text("Sent to the host", style = MaterialTheme.typography.labelSmall)
        log.takeLast(HOST_ACTION_LOG_SHOWN).forEach { action ->
          Text(
            action.logLine(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
