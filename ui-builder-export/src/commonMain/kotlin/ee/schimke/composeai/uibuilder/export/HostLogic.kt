package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A design's host logic (`document.hostLogic`): the program that stands in for the host feeding the
 * design. It answers the design's host actions ([HostActions]) and responds by setting the design's
 * host inputs — state variables whose `persistence` is `host`. See
 * `docs/design/UI_BUILDER_HOST_LOGIC.md`.
 *
 * The program is a pure reducer over a private state, written as a JavaScript module:
 * ```text
 * init(env)                    -> { state, values }
 * onAction(state, action, env) -> { state, values }
 * ```
 *
 * This object is everything about it that is not running it: reading it off a document, what an
 * engine is handed, and checking what the engine hands back. An engine (the editor's QuickJS one)
 * only evaluates.
 */
object HostLogic {
  const val LANGUAGE_JAVASCRIPT: String = "javascript"

  /** The longest source accepted: host logic is a reducer, not an application. */
  const val MAX_SOURCE_LENGTH: Int = 64 * 1024

  /** A design's program, as stored. */
  data class Program(val language: String, val source: String)

  /** [document]'s program, or null when it has none or [issue] refuses the one it has. */
  fun of(document: UiBuilderDocument): Program? {
    val logic = document.hostLogic ?: return null
    if (issue(logic) != null) return null
    return Program(logic.string("language")!!, logic.string("source")!!)
  }

  /** `{"language": "javascript", "source": [source]}`. */
  fun encode(source: String): JsonObject =
    JsonObject(
      mapOf(
        "language" to JsonPrimitive(LANGUAGE_JAVASCRIPT),
        "source" to JsonPrimitive(source),
      ),
    )

  /** What is wrong with [hostLogic] as stored, or null when an engine can be handed it. */
  fun issue(hostLogic: JsonObject): String? {
    val language = hostLogic.string("language")
    if (language != LANGUAGE_JAVASCRIPT)
      return "host logic is written in JavaScript; `${language ?: "nothing"}` is not supported"
    val source = hostLogic.string("source") ?: return "host logic needs its source"
    if (source.length > MAX_SOURCE_LENGTH)
      return "host logic is at most ${MAX_SOURCE_LENGTH / 1024} KiB; this is ${source.length / 1024} KiB"
    return null
  }

  /** The state variables [document]'s host logic may set: its host inputs, by name. */
  fun hostInputs(document: UiBuilderDocument): Map<String, JsonObject> =
    document.stateVariables
      .mapNotNull { (name, declaration) ->
        (declaration as? JsonObject)?.takeIf { it.isHostInput() }?.let { name to it }
      }
      .toMap()

  /**
   * The `env` a call is handed: the host inputs' current [values] typed as declared, the design's
   * `environment`, and `now`, its fixed time in epoch milliseconds (0 when it has none).
   */
  fun environment(document: UiBuilderDocument, values: Map<String, String?>): JsonObject =
    JsonObject(
      mapOf(
        "values" to
          JsonObject(
            hostInputs(document).mapValues { (name, declaration) ->
              typed(values[name], declaration)
            },
          ),
        "environment" to document.environment,
        "now" to JsonPrimitive(now(document)),
      ),
    )

  /** [document]'s fixed time in epoch milliseconds, the program's only clock; 0 when unset. */
  fun now(document: UiBuilderDocument): Long =
    document.environment.string("fixedTime")?.let(UiExpressions.Clock::instantMillis) ?: 0L

  /** The arguments of `init(env)`, as the JSON array an engine spreads. */
  fun initArguments(document: UiBuilderDocument, values: Map<String, String?>): String =
    JsonArray(listOf(environment(document, values))).toString()

  /** The arguments of `onAction(state, action, env)`, as the JSON array an engine spreads. */
  fun actionArguments(
    document: UiBuilderDocument,
    state: JsonElement,
    name: String,
    value: JsonPrimitive?,
    values: Map<String, String?>,
  ): String =
    JsonArray(
        listOf(
          state,
          JsonObject(
            buildMap {
              put("name", JsonPrimitive(name))
              if (value != null) put("value", value)
            },
          ),
          environment(document, values),
        ),
      )
      .toString()

  /** What one call returned, checked against [document]'s host inputs. */
  sealed interface Step {
    /** The program's next private [state], and the host inputs it set, as canvas strings. */
    data class Ok(val state: JsonElement, val values: Map<String, String?>) : Step

    /** Why the result cannot be used; the canvas keeps what it last showed. */
    data class Failed(val message: String) : Step
  }

  /**
   * [resultJson] — what `init` or `onAction` returned, as JSON — checked: an object with an
   * optional `state` and an optional `values`, every value naming a host input and holding what it
   * is declared to hold. A design-local variable is the design's, not the host's, so writing one is
   * refused rather than ignored.
   */
  fun step(document: UiBuilderDocument, resultJson: String): Step {
    val result =
      runCatching { Json.parseToJsonElement(resultJson) }.getOrNull() as? JsonObject
        ?: return Step.Failed("host logic must return an object, `{ state, values }`")
    val values = result["values"] ?: JsonNull
    if (values !is JsonNull && values !is JsonObject)
      return Step.Failed("host logic's `values` must be an object of host inputs")
    val inputs = hostInputs(document)
    val written = linkedMapOf<String, String?>()
    (values as? JsonObject).orEmpty().forEach { (name, value) ->
      val declaration =
        inputs[name]
          ?: return Step.Failed(
            if (name in document.stateVariables)
              "host logic set `$name`, which is the design's own state, not a host input"
            else "host logic set `$name`, which the design does not declare",
          )
      val held =
        canvasValue(value, declaration)
          ?: return Step.Failed(
            "host logic set `$name` to $value, which a ${declaration.string("valueType")} " +
              "host input cannot hold",
          )
      written[name] = held.value
    }
    return Step.Ok(result["state"] ?: JsonNull, written)
  }

  /** A canvas string (how the canvas holds state) as the JSON the program reads. */
  private fun typed(value: String?, declaration: JsonObject): JsonElement {
    if (value == null) return JsonNull
    return when (declaration.string("valueType")) {
      "bool" -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonNull
      "int" -> value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull
      "float" -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull
      else -> JsonPrimitive(value)
    }
  }

  /** A value a host input can hold, as the canvas holds it; [value] is null for a null. */
  private class Held(val value: String?)

  /** [value] as the canvas string a [declaration] holds, or null when it cannot hold it. */
  private fun canvasValue(value: JsonElement, declaration: JsonObject): Held? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull)
      return Held(null).takeIf {
        (declaration["nullable"] as? JsonPrimitive)?.booleanOrNull == true
      }
    val text =
      when (declaration.string("valueType")) {
        "bool" -> primitive.takeUnless { it.isString }?.booleanOrNull?.toString()
        "int" -> primitive.takeUnless { it.isString }?.intOrNull?.toString()
        "float" ->
          primitive.takeUnless { it.isString }?.doubleOrNull?.takeIf(Double::isFinite)?.toString()
        // Text holds anything printable, as a formula's text does: `7` shows as `7`.
        else -> primitive.contentOrNull
      }
    return text?.let(::Held)
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
