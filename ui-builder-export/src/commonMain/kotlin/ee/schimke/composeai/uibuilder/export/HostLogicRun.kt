package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One loaded host-logic program ([HostLogic]), holding its own interpreter until [close]. An engine
 * — QuickJS on Wasm in the browser, QuickJS on the JVM for the server's simulator — loads one; a
 * [HostLogicRun] drives it.
 */
interface HostLogicProgram {
  /**
   * Calls the program's [function] (`init` or `onAction`) with [argumentsJson], a JSON array the
   * engine spreads. [now] is what the program's `Date.now()` reads and [seed] seeds its
   * `Math.random()`, so a call is reproducible. Returns the result as JSON, or why the call failed
   * — the program threw, ran past its time budget, or ran out of memory.
   */
  fun call(function: String, argumentsJson: String, now: Long, seed: Int): Result<String>

  fun close()
}

/**
 * A run of a design's host logic: the private state, the host inputs' values, and the order and
 * seeds of the calls that move them. The canvas and the server's simulator both drive a program
 * through this, so they hand it the same arguments and draw the same answers from it.
 *
 * Every call's answer is checked by [HostLogic.step]. A failed call — the program threw, overran
 * its budget, or answered something the design cannot hold — keeps the state and values from the
 * last good one, and the next action is still sent, so a fixed bug recovers without a reset.
 */
class HostLogicRun(document: UiBuilderDocument, private val program: HostLogicProgram) {
  /** The design the calls are made against: its host inputs' declarations, environment and time. */
  var document: UiBuilderDocument = document

  /** Every host input's value: its declared initial value, overlaid by what the logic set. */
  var values: Map<String, String?> = initialValues(document)
    private set

  /** The program's private state, after its last successful call. */
  var state: JsonElement = JsonNull
    private set

  private var calls = 0

  /** Starts again from `init`, with every host input at its initial value. */
  fun reset(): HostLogic.Step {
    values = initialValues(document)
    state = JsonNull
    calls = 0
    return apply(program.call("init", HostLogic.initArguments(document, values), now(), seed()))
  }

  /** The design fired host action [name] with [value]: the program answers it. */
  fun onAction(name: String, value: JsonPrimitive?): HostLogic.Step =
    apply(
      program.call(
        "onAction",
        HostLogic.actionArguments(document, state, name, value, values),
        now(),
        seed(),
      ),
    )

  fun close() = program.close()

  private fun apply(result: Result<String>): HostLogic.Step {
    val step =
      result.fold(
        onSuccess = { HostLogic.step(document, it) },
        onFailure = { HostLogic.Step.Failed(it.message ?: "host logic failed") },
      )
    if (step is HostLogic.Step.Ok) {
      state = step.state
      values = values + step.values
    }
    return step
  }

  private fun now(): Long = HostLogic.now(document)

  /** A different seed per call, the same sequence from every reset: reproducible, not constant. */
  private fun seed(): Int = ++calls

  companion object {
    /** [document]'s host inputs at their declared initial values. */
    fun initialValues(document: UiBuilderDocument): Map<String, String?> =
      HostLogic.hostInputs(document).mapValues { (_, declaration) ->
        (declaration["initialValue"] as? JsonPrimitive)
          ?.takeUnless { it is JsonNull }
          ?.contentOrNull
      }
  }
}
