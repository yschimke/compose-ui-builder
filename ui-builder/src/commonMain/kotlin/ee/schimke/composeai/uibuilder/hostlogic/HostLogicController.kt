package ee.schimke.composeai.uibuilder.hostlogic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasHostAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where a design's host logic stands on the canvas. */
sealed interface HostLogicStatus {
  /** The design has no host logic: host actions are logged and nothing answers. */
  data object None : HostLogicStatus

  /** The design has host logic and this platform cannot run it. */
  data object Unavailable : HostLogicStatus

  data object Loading : HostLogicStatus

  /** The program is answering host actions. */
  data object Running : HostLogicStatus

  /**
   * The program could not load, or its last call failed. The canvas keeps the values it last
   * showed, and the next action is still sent, so a fixed bug recovers without a reset.
   */
  data class Failed(val message: String) : HostLogicStatus
}

/**
 * A design's host logic on the canvas: the loaded program, its private state, and the host inputs
 * it has set. The canvas draws [values] over the design's own state, so a host input shows what the
 * logic last pushed. Not persisted: a reload, a [reset] and a changed program all start from
 * `init`.
 */
@Stable
class HostLogicController internal constructor(private val engine: HostLogicEngine?) {
  var status: HostLogicStatus by mutableStateOf(HostLogicStatus.None)
    private set

  /** Every host input's value: its declared initial value, overlaid by what the logic set. */
  var values: Map<String, String?> by mutableStateOf(emptyMap())
    private set

  /** The program's private state, after its last successful call. */
  var state: JsonElement by mutableStateOf(JsonNull)
    private set

  private var program: HostLogicProgram? = null
  private var document: UiBuilderDocument? = null
  private var calls = 0

  /**
   * Loads [document]'s host logic, replacing any program already loaded, and runs `init`. A design
   * without host logic, or a platform without an engine, says so in [status] and loads nothing.
   */
  suspend fun load(document: UiBuilderDocument) {
    close()
    this.document = document
    values = initialValues(document)
    state = JsonNull
    val source = HostLogic.of(document)?.source
    status =
      when {
        source == null -> HostLogicStatus.None
        engine == null -> HostLogicStatus.Unavailable
        else -> HostLogicStatus.Loading
      }
    if (source == null || engine == null) return
    engine
      .load(source)
      .onSuccess { loaded ->
        program = loaded
        reset()
      }
      .onFailure { status = HostLogicStatus.Failed(it.message ?: "host logic did not load") }
  }

  /** The design changed without its program changing: later calls see its current declarations. */
  fun update(document: UiBuilderDocument) {
    this.document = document
  }

  /** Starts the program again from `init`, with every host input at its initial value. */
  fun reset() {
    val document = document ?: return
    val program = program ?: return
    values = initialValues(document)
    state = JsonNull
    calls = 0
    apply(program.call("init", HostLogic.initArguments(document, values), now(), seed()))
  }

  /** A node fired [action]: the program answers it, and the values it sets are drawn. */
  fun onAction(action: CanvasHostAction) {
    val document = document ?: return
    val program = program ?: return
    apply(
      program.call(
        "onAction",
        HostLogic.actionArguments(document, state, action.name, action.value, values),
        now(),
        seed(),
      ),
    )
  }

  fun close() {
    program?.close()
    program = null
  }

  private fun apply(result: Result<String>) {
    val document = document ?: return
    result
      .onFailure { status = HostLogicStatus.Failed(it.message ?: "host logic failed") }
      .onSuccess { json ->
        when (val step = HostLogic.step(document, json)) {
          is HostLogic.Step.Ok -> {
            state = step.state
            values = values + step.values
            status = HostLogicStatus.Running
          }
          is HostLogic.Step.Failed -> status = HostLogicStatus.Failed(step.message)
        }
      }
  }

  private fun now(): Long = document?.let(HostLogic::now) ?: 0L

  /** A different seed per call, the same sequence from every reset: reproducible, not constant. */
  private fun seed(): Int = ++calls

  private fun initialValues(document: UiBuilderDocument): Map<String, String?> =
    HostLogic.hostInputs(document).mapValues { (_, declaration) ->
      (declaration["initialValue"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    }
}

/**
 * [document]'s host logic, loaded on this platform's engine and reloaded whenever the program
 * changes. Changes elsewhere in the design keep the running program and its state.
 */
@Composable
fun rememberHostLogicController(
  document: UiBuilderDocument,
  engine: HostLogicEngine? = platformHostLogicEngine,
): HostLogicController {
  val controller = remember(document.id, engine) { HostLogicController(engine) }
  val program: JsonObject? = document.hostLogic
  LaunchedEffect(controller, program, HostLogic.hostInputs(document).keys) {
    controller.load(document)
  }
  controller.update(document)
  DisposableEffect(controller) { onDispose { controller.close() } }
  return controller
}
