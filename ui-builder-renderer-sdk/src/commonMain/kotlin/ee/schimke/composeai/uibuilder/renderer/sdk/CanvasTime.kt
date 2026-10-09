package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.UiTimeText
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The value of `environment.animations` that lets time run.
 *
 * Every saved document says `settled`, and a render of one is pinned to its `fixedTime`: a render
 * that moved would be a render nothing could diff. An interactive surface — the editor canvas, the
 * device previews — hands its renderer a copy that says `running` instead, so a clock hand turns
 * and a pulse pulses while somebody designs it. The copy is never saved.
 */
const val ANIMATIONS_RUNNING = "running"

/** Whether [this] asks its renderer to let time run; see [ANIMATIONS_RUNNING]. */
val UiBuilderDocument.timeRuns: Boolean
  get() = (environment["animations"] as? JsonPrimitive)?.contentOrNull == ANIMATIONS_RUNNING

/**
 * Whether [this] reads the clock: the one way letting time run changes what the canvas renderer
 * draws. A player or a catalog runtime can animate more, which only it knows.
 */
val UiBuilderDocument.readsClock: Boolean
  get() = clockReads() != ClockReads.NONE

/** [this] with time running or settled, for a surface to hand its renderer. */
fun UiBuilderDocument.withTimeRunning(running: Boolean): UiBuilderDocument {
  val animations = if (running) ANIMATIONS_RUNNING else "settled"
  if ((environment["animations"] as? JsonPrimitive)?.contentOrNull == animations) return this
  return copy(environment = JsonObject(environment + ("animations" to JsonPrimitive(animations))))
}

/**
 * The wall clock [document] is drawn at this frame, or null to draw it at its `fixedTime`.
 *
 * Live only when the document lets time run and something in it reads the clock: a design with no
 * `time.*` value never recomposes for one. Whole-second values only change once a second, so a
 * design that reads neither `time.continuousSecond` nor `time.animation` recomposes once a second
 * rather than every frame. `time.animation` counts from the moment time started running, as the
 * player's counts from the moment it started the document.
 */
@Composable
internal fun rememberLiveCanvasClock(document: UiBuilderDocument): UiExpressions.Clock? {
  val reads = remember(document.nodes) { document.clockReads() }
  if (!document.timeRuns || reads == ClockReads.NONE) return null
  val continuous = reads == ClockReads.CONTINUOUS
  val start = remember { Clock.System.now().toEpochMilliseconds() }
  var clock by remember(continuous) { mutableStateOf(wallClock(start, continuous)) }
  LaunchedEffect(continuous) {
    while (true) withFrameMillis { clock = wallClock(start, continuous) }
  }
  return clock
}

private fun wallClock(start: Long, continuous: Boolean): UiExpressions.Clock {
  val now = Clock.System.now().toEpochMilliseconds()
  val clock =
    UiExpressions.Clock.at(now, localUtcOffsetSeconds(now))
      .copy(animationSeconds = (now - start) / 1000.0)
  // Equal from frame to frame within a second, so the state write is a no-op until it ticks.
  return if (continuous) clock else clock.copy(millisecond = 0, animationSeconds = 0.0)
}

/** The local zone's offset from UTC at [epochMillis], in seconds. */
internal expect fun localUtcOffsetSeconds(epochMillis: Long): Int

/** The clock values that move within a second, and so ask for a frame every frame. */
private val CONTINUOUS_READS = setOf("time.continuousSecond", "time.animation")

internal enum class ClockReads {
  NONE,
  WHOLE_SECONDS,
  CONTINUOUS,
}

internal fun UiBuilderDocument.clockReads(): ClockReads {
  var reads = ClockReads.NONE
  fun visit(element: JsonElement) {
    when (element) {
      is JsonObject -> {
        if ((element["type"] as? JsonPrimitive)?.contentOrNull == "system") {
          val id = (element["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
          if (id.startsWith("time.")) {
            reads =
              if (id in CONTINUOUS_READS) ClockReads.CONTINUOUS
              else maxOf(reads, ClockReads.WHOLE_SECONDS)
          }
        }
        element.values.forEach(::visit)
      }
      is JsonArray -> element.forEach(::visit)
      is JsonPrimitive -> Unit
    }
  }
  for (node in nodes.values) {
    // A time text reads the render's clock itself, through no `time.*` value of its own.
    if (node.componentId == UiTimeText.ID) reads = maxOf(reads, ClockReads.WHOLE_SECONDS)
    visit(node.properties)
    node.modifiers.forEach(::visit)
    if (reads == ClockReads.CONTINUOUS) break
  }
  return reads
}
