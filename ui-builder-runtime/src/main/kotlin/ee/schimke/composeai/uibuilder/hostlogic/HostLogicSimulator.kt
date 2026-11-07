package ee.schimke.composeai.uibuilder.hostlogic

import ee.schimke.composeai.uibuilder.export.HostActions
import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.HostLogicRun
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A design's host logic, run headless on the JVM: checked for `check_design`, and driven through a
 * sequence of host actions for `simulate_action`, so an agent can show "the calculator works"
 * without a browser. See `docs/design/UI_BUILDER_HOST_LOGIC.md`, "On the server".
 *
 * A run is driven by [HostLogicRun], exactly as the canvas drives one, on QuickJS: the same calls,
 * in the same order, with the same seeds and the same clock, and every answer checked by
 * [HostLogic.step].
 */
public object HostLogicSimulator {
  /** The most actions one [simulate] sends: a scenario, not a load test. */
  public const val MAX_ACTIONS: Int = 200

  /**
   * [document]'s host logic checked as an engine would take it: stored as one can be handed it,
   * loading, exporting `init` and `onAction`, and `init` answering what the design can hold. A
   * design without host logic has no digest and nothing to say.
   */
  public fun check(document: UiBuilderDocument): HostLogicCheck {
    val logic = document.hostLogic ?: return HostLogicCheck(null, emptyList())
    val digest = digest(logic)
    HostLogic.issue(logic)?.let {
      return HostLogicCheck(digest, listOf(HostLogicDiagnostic(it)))
    }
    val program = HostLogic.of(document)!!
    val loaded =
      engine().load(program.source).getOrElse {
        return HostLogicCheck(digest, listOf(HostLogicDiagnostic(message(it, "did not load"))))
      }
    val run = HostLogicRun(document, loaded)
    try {
      val step = run.reset()
      val diagnostics =
        if (step is HostLogic.Step.Failed) listOf(HostLogicDiagnostic("init: ${step.message}"))
        else emptyList()
      return HostLogicCheck(digest, diagnostics)
    } finally {
      run.close()
    }
  }

  /**
   * Runs `init`, then answers each of [actions] in order, each `{"name": …, "value": …}` as a host
   * action carries them. Every step reports the program's private state and every host input's
   * value after it, and which values that step set.
   *
   * A failed step is reported and the next action still sent, as on the canvas, except that a
   * program which overruns its budget stops the simulation there: the next call would only pay the
   * budget again.
   */
  public fun simulate(document: UiBuilderDocument, actions: List<JsonObject>): HostLogicSimulation {
    require(actions.size <= MAX_ACTIONS) {
      "a simulation sends at most $MAX_ACTIONS actions; this asked for ${actions.size}"
    }
    actions.forEachIndexed { index, action ->
      HostActions.issue(hostAction(action))?.let {
        throw IllegalArgumentException("action ${index + 1}: $it")
      }
    }
    val program =
      HostLogic.of(document)
        ?: return HostLogicSimulation(
          emptyList(),
          document.hostLogic?.let(HostLogic::issue) ?: "the design has no host logic",
        )
    val loaded =
      engine().load(program.source).getOrElse {
        return HostLogicSimulation(emptyList(), message(it, "did not load"))
      }
    val run = HostLogicRun(document, loaded)
    try {
      val steps = mutableListOf<HostLogicStep>()
      fun record(name: String?, value: JsonPrimitive?, step: HostLogic.Step) =
        HostLogicStep(
            action = name,
            value = value,
            state = run.state,
            values = run.values,
            set = (step as? HostLogic.Step.Ok)?.values.orEmpty(),
            error = (step as? HostLogic.Step.Failed)?.message,
          )
          .also(steps::add)

      val init = run.reset()
      record(null, null, init)
      if (init.overran()) return HostLogicSimulation(steps, init.message())
      for (action in actions) {
        val hostAction = hostAction(action)
        val name = HostActions.name(hostAction)!!
        val value = HostActions.value(hostAction)?.json
        val step = run.onAction(name, value)
        record(name, value, step)
        if (step.overran()) return HostLogicSimulation(steps, step.message())
      }
      return HostLogicSimulation(steps, null)
    } finally {
      run.close()
    }
  }

  /** `sha256:` and the hex digest of the stored program, which the server serves it by. */
  public fun digest(hostLogic: JsonObject): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(hostLogic.toString().toByteArray())
    return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
  }

  private fun engine() = JvmHostLogicEngine()

  private fun hostAction(action: JsonObject): JsonObject =
    JsonObject(action + ("type" to JsonPrimitive(HostActions.TYPE)))

  private fun message(error: Throwable, otherwise: String) =
    error.message ?: "host logic $otherwise"

  private fun HostLogic.Step.overran() =
    this is HostLogic.Step.Failed && message.contains(BUDGET_MARKER)

  private fun HostLogic.Step.message() = (this as HostLogic.Step.Failed).message

  internal const val BUDGET_MARKER = "budget, and was interrupted"
}

/** A design's host logic, checked: its [digest], and what stops an engine running it. */
public class HostLogicCheck
internal constructor(
  /** `sha256:…` of the stored program, or null when the design has none. */
  public val digest: String?,
  public val diagnostics: List<HostLogicDiagnostic>,
) {
  public fun toJson(): JsonObject = buildJsonObject {
    digest?.let { put("digest", it) }
    put("diagnostics", JsonArray(diagnostics.map(HostLogicDiagnostic::toJson)))
  }
}

/** One thing wrong with a design's host logic, in the guideline shape: a field and a message. */
public class HostLogicDiagnostic internal constructor(public val message: String) {
  /** The document field the diagnostic is about. */
  public val field: String = "hostLogic"

  public fun toJson(): JsonObject = buildJsonObject {
    put("field", field)
    put("message", message)
  }
}

/** A simulated run: `init`, then one step per action, and why it [stopped] early, if it did. */
public class HostLogicSimulation
internal constructor(public val steps: List<HostLogicStep>, public val stopped: String?) {
  public fun toJson(): JsonObject = buildJsonObject {
    put("steps", JsonArray(steps.map(HostLogicStep::toJson)))
    stopped?.let { put("stopped", it) }
  }
}

/** One call of a simulated run, and where it left the run. */
public class HostLogicStep
internal constructor(
  /** The host action answered, or null for `init`. */
  public val action: String?,
  public val value: JsonPrimitive?,
  /** The program's private state after the step: the last good one when the step failed. */
  public val state: JsonElement,
  /** Every host input's value after the step, as the canvas would draw it. */
  public val values: Map<String, String?>,
  /** The host inputs this step's answer set, changed or not. */
  public val set: Map<String, String?>,
  /** Why the step's answer could not be used, or null when it was. */
  public val error: String?,
) {
  public fun toJson(): JsonObject = buildJsonObject {
    if (action == null) put("call", "init")
    else {
      put("call", "onAction")
      put("action", action)
      value?.let { put("value", it) }
    }
    put("state", state)
    put("values", values.toJson())
    put("set", set.toJson())
    error?.let { put("error", it) }
  }

  private fun Map<String, String?>.toJson() =
    JsonObject(mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull })
}
