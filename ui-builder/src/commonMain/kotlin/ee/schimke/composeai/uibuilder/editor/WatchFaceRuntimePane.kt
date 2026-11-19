package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The native pane of a Watch Face Format design. A watch face is drawn by Google's watch-face
 * runtime on the host, not by generated Compose, so there is nothing to compile: this pane asks the
 * host whether the runtime can run ([status], shown as a banner when it cannot), starts a session
 * on the exported WFF, shows each loading stage the host reports until the first frame, and then
 * draws the frames the session streams, with an ambient switch that is sent back as a `controls`
 * patch. The watch-face tab stays a secondary action.
 *
 * It is the opt-in, real-renderer preview: the host starts an Android runtime for it, so nothing is
 * asked of the host until this pane is composed, which is only while the Native pane is open.
 */
@Composable
internal fun WatchFaceRuntimePane(
  wffXml: () -> String?,
  start: (suspend (String) -> UiBuilderWatchFaceStart)?,
  modifier: Modifier = Modifier,
  status: (suspend () -> String?)? = null,
) {
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  var starting by remember { mutableStateOf(false) }
  // True until the host's status is known: a start before it would race the pane's own.
  var checking by remember { mutableStateOf(status != null) }
  var unavailable by remember { mutableStateOf<String?>(null) }
  var result by remember { mutableStateOf<UiBuilderWatchFaceStart?>(null) }
  var ambient by remember { mutableStateOf(false) }
  // The pane is the one owner of the session. A start that finishes after it was superseded or the
  // pane closed ends the session it returns instead of leaking the socket and its runtime slot.
  val generation = remember { intArrayOf(0) }

  fun startSession() {
    val begin = start ?: return
    if (starting) return
    val xml = wffXml()
    // Before anything overwrites `result`: a refused export must not strand the previous session.
    result?.end()
    // Cleared with it: the old result's Ambient switch would send to the closed stream while the
    // new session, which never received it, showed it checked.
    result = null
    if (xml == null) {
      result =
        UiBuilderWatchFaceStart(
          failure = "This design does not export to Watch Face Format yet; see the code pane.",
        )
      return
    }
    ambient = false
    starting = true
    val mine = ++generation[0]
    scope.launch {
      val outcome =
        try {
          begin(xml)
        } catch (failure: kotlin.coroutines.cancellation.CancellationException) {
          throw failure
        } catch (failure: Throwable) {
          UiBuilderWatchFaceStart(failure = failure.message ?: "the request failed")
        }
      if (generation[0] != mine) {
        outcome.end()
        return@launch
      }
      result = outcome
      starting = false
    }
  }
  // Asked once as the pane opens: a host that cannot run the runtime says why instead of failing a
  // start; one that can starts the session at once.
  LaunchedEffect(Unit) {
    unavailable =
      try {
        status?.invoke()
      } catch (failure: kotlin.coroutines.cancellation.CancellationException) {
        throw failure
      } catch (_: Throwable) {
        null
      }
    checking = false
    if (unavailable == null) startSession()
  }
  DisposableEffect(Unit) {
    onDispose {
      generation[0]++
      result?.end()
    }
  }

  val outcome = result
  // The socket's newest stage, else the one the start answered with; null from an older host.
  val stage = outcome?.stream?.stage ?: outcome?.stage
  // The host's elapsed time, counted on locally between its messages (about one a second).
  val received = remember(stage) { TimeSource.Monotonic.markNow() }
  var ticks by remember { mutableIntStateOf(0) }
  LaunchedEffect(stage) {
    if (stage == null || stage.settled) return@LaunchedEffect
    while (true) {
      delay(250)
      ticks++
    }
  }
  val elapsed = stage?.let {
    ticks // read, so each tick recomposes
    if (it.settled) it.elapsedMillis
    else it.elapsedMillis + received.elapsedNow().inWholeMilliseconds
  }
  val sendControls = outcome?.sendControls
  WatchFaceRuntimePaneContent(
    modifier = modifier,
    unavailable = unavailable,
    canReload = !starting && !checking && start != null,
    starting = starting,
    stage = stage,
    elapsedMillis = elapsed ?: 0,
    frame = outcome?.stream?.frame?.image,
    connected = outcome?.stream != null,
    failure = outcome?.failure,
    // A failed stage already says why, with the packager's own text.
    streamFailure = outcome?.stream?.failure?.takeIf { stage?.failed != true },
    ambient = ambient,
    onAmbientChange =
      sendControls?.let { send ->
        { checked: Boolean ->
          ambient = checked
          send("{\"ambient\":$checked}")
        }
      },
    pagePath = outcome?.pagePath,
    onReload = { startSession() },
    onOpenTab = { uriHandler.openUri(it) },
  )
}

/**
 * What [WatchFaceRuntimePane] shows for one moment of a session, without the session: the previews
 * draw every stage through it.
 */
@Composable
internal fun WatchFaceRuntimePaneContent(
  modifier: Modifier = Modifier,
  unavailable: String? = null,
  canReload: Boolean = true,
  starting: Boolean = false,
  stage: UiBuilderWatchFaceStage? = null,
  elapsedMillis: Long = stage?.elapsedMillis ?: 0,
  frame: ImageBitmap? = null,
  /** Whether a socket is open: an older host's session, before its first frame. */
  connected: Boolean = false,
  /** Why the start failed: the host's refusal, or the request's own failure. */
  failure: String? = null,
  /** Why the open socket has no frame. */
  streamFailure: String? = null,
  ambient: Boolean = false,
  onAmbientChange: ((Boolean) -> Unit)? = null,
  pagePath: String? = null,
  onReload: () -> Unit = {},
  onOpenTab: (String) -> Unit = {},
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(
      Modifier.fillMaxSize().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        if (frame != null) "Native · live on the Wear OS watch-face runtime"
        else "Native · Wear OS watch-face runtime",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        "The real renderer: the host starts an Android watch-face runtime for this pane, only " +
          "while it is open, so it is slower than the canvas. To try the face on your own " +
          "watch, use Test on your watch.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      unavailable?.let { reason ->
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(8.dp),
          modifier =
            Modifier.fillMaxWidth().semantics { contentDescription = "Runtime unavailable" },
        ) {
          SelectionContainer {
            Text(
              reason,
              Modifier.padding(12.dp),
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
      if (unavailable == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(enabled = canReload, onClick = onReload) {
            Text(if (starting) "Starting…" else "Reload with the current design")
          }
          if (onAmbientChange != null) {
            Row(
              Modifier.toggleable(
                value = ambient,
                role = Role.Checkbox,
                onValueChange = onAmbientChange,
              ),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              androidx.compose.material3.Checkbox(checked = ambient, onCheckedChange = null)
              Text("Ambient", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
        when {
          failure != null ->
            SelectionContainer {
              Text(
                failure,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }
          stage?.failed == true -> WatchFaceStageFailure(stage)
          frame != null -> {
            Image(
              bitmap = frame,
              contentDescription = "Watch face on the real runtime",
              modifier = Modifier.fillMaxWidth().weight(1f, fill = false).clip(CircleShape),
            )
            stage
              ?.takeIf { it.live }
              ?.let { live ->
                Text(
                  watchFaceLiveCaption(live),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
          }
          streamFailure != null ->
            Text(
              streamFailure,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          // A live stage just ahead of its first frame is still loading, as far as anyone can see.
          stage != null -> WatchFaceStageProgress(stage, elapsedMillis)
          starting ->
            Text(
              "Asking the host for a watch-face runtime…",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          // An older host reports no stages: the socket is open and the first frame is all there
          // is.
          connected ->
            Text(
              "Waiting for the first frame…",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
        }
      }
      // From the `202` on: the tab shows the same session, stages included.
      pagePath?.let { page -> TextButton(onClick = { onOpenTab(page) }) { Text("Open in a tab") } }
    }
  }
}

/**
 * One loading stage as a stepper: the host's label with the elapsed time, a bar of [steps]
 * segments, and the step (or, while queued, the place in the queue).
 */
@Composable
private fun WatchFaceStageProgress(stage: UiBuilderWatchFaceStage, elapsedMillis: Long) {
  val headline = stage.label.ifBlank { watchFaceStageName(stage) }
  val detail = buildList {
    if (stage.queued) {
      add("Queued")
      when (val ahead = stage.queueAhead) {
        null -> Unit
        0 -> add("next in line")
        1 -> add("1 session ahead")
        else -> add("$ahead sessions ahead")
      }
    } else add("Step ${stage.step.coerceIn(0, stage.steps)} of ${stage.steps}")
    if (stage.cachedPackage) add("cached package")
  }
  Column(
    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
      contentDescription = "Watch-face runtime loading"
      stateDescription = "$headline, ${detail.joinToString(", ")}"
    },
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        headline,
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        watchFaceElapsed(elapsedMillis),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
      )
    }
    WatchFaceStepBar(stage)
    Text(
      detail.joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/** The steps as segments: done, the one running (or failed), and those still to come. */
@Composable
private fun WatchFaceStepBar(stage: UiBuilderWatchFaceStage) {
  val colors = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    for (index in 1..stage.steps) {
      val color =
        when {
          stage.live || index < stage.step -> colors.primary
          index == stage.step && stage.failed -> colors.error
          index == stage.step -> colors.primary.copy(alpha = 0.4f)
          else -> colors.surfaceVariant
        }
      Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
  }
}

/** A failed start: where it stopped, its code, and the host's reason exactly as it gave it. */
@Composable
private fun WatchFaceStageFailure(stage: UiBuilderWatchFaceStage) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    WatchFaceStepBar(stage)
    Surface(
      color = MaterialTheme.colorScheme.errorContainer,
      shape = RoundedCornerShape(8.dp),
      modifier =
        Modifier.fillMaxWidth().semantics { contentDescription = "Watch-face runtime failed" },
    ) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val step = WATCH_FACE_STEP_NAMES.getOrNull(stage.step - 1)
        Text(
          if (step != null) "Failed at step ${stage.step} of ${stage.steps}: $step"
          else "The watch-face runtime did not start",
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.titleSmall,
        )
        stage.code?.let {
          Text(
            it,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
          )
        }
        // Verbatim: the packager's text is the diagnosis, and rewording it loses the line numbers.
        SelectionContainer {
          Text(
            stage.reason ?: stage.label.ifBlank { "the host gave no reason" },
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    }
  }
}

/** The five working steps, in the host's order (`step` 1–5). */
private val WATCH_FACE_STEP_NAMES =
  listOf("Export", "Package", "Start the runtime", "Install", "Load the face")

private fun watchFaceStageName(stage: UiBuilderWatchFaceStage): String =
  when (stage.stage) {
    UiBuilderWatchFaceStage.QUEUED -> "Waiting for a free runtime"
    UiBuilderWatchFaceStage.EXPORTING -> "Exporting the watch face"
    UiBuilderWatchFaceStage.PACKAGING -> "Packaging the watch face"
    UiBuilderWatchFaceStage.STARTING_RUNTIME -> "Starting the watch-face runtime"
    UiBuilderWatchFaceStage.INSTALLING -> "Installing the watch face"
    UiBuilderWatchFaceStage.LOADING_FACE,
    UiBuilderWatchFaceStage.LIVE -> "Loading the watch face"
    else -> stage.stage
  }

/** "Live · ready in 41 s · redraws every 250 ms · cached package". */
internal fun watchFaceLiveCaption(stage: UiBuilderWatchFaceStage): String = buildList {
  add("Live")
  if (stage.elapsedMillis > 0) add("ready in ${watchFaceElapsed(stage.elapsedMillis)}")
  stage.frameIntervalMillis?.let {
    add(
      if (it >= 1000 && it % 1000 == 0L) "redraws every ${it / 1000} s" else "redraws every $it ms",
    )
  }
  if (stage.cachedPackage) add("cached package")
}
  .joinToString(" · ")

/** Whole seconds, then minutes and seconds: "7 s", "1 min 04 s". */
internal fun watchFaceElapsed(millis: Long): String {
  val seconds = (millis.coerceAtLeast(0) / 1000)
  return if (seconds < 60) "$seconds s"
  else "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"
}
