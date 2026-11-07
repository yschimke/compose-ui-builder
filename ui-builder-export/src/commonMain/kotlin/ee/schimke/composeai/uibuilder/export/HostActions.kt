package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * An action that hands an event to the host rather than writing the design's own state: `{"type":
 * "hostAction", "name": "calc.key", "value": "7"}`.
 *
 * It is Remote Compose's named host action, and its shape is that action's: one name, and at most
 * one value, which is text, a whole number or a decimal (`hostAction(name)`, `hostAction(name,
 * RemoteString | RemoteInt | RemoteFloat)`). A host that wants more than one argument names the
 * action for it (`calc.digit` / `calc.operator`) or encodes the value itself.
 *
 * Every lane reads the action through this object, so the canvas, the inspector and each export
 * agree on what is a well-formed one. See `docs/design/UI_BUILDER_HOST_LOGIC.md`.
 */
object HostActions {
  const val TYPE: String = "hostAction"

  /** The longest name accepted: a name is an identifier the host matches on, not a payload. */
  const val MAX_NAME_LENGTH: Int = 256

  /** A host action's value, typed as the player delivers it. */
  sealed interface Value {
    /** The value as a protocol literal, as the canvas reports it. */
    val json: JsonPrimitive

    data class Text(val value: String) : Value {
      override val json: JsonPrimitive
        get() = JsonPrimitive(value)
    }

    data class Integer(val value: Int) : Value {
      override val json: JsonPrimitive
        get() = JsonPrimitive(value)
    }

    /**
     * A `Float`, as `RemoteFloat` is: parsed to that precision once, so the canvas, the Compose
     * export and the Remote document all send the same number.
     */
    data class Decimal(val value: Float) : Value {
      override val json: JsonPrimitive
        get() = JsonPrimitive(value)
    }
  }

  /** Whether [action] is a host action, well-formed or not. */
  fun isHostAction(action: JsonObject): Boolean =
    (action["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == TYPE

  /** [action]'s name, or null when it has none a host could match on. */
  fun name(action: JsonObject): String? =
    (action["name"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_LENGTH }

  /** [action]'s value, or null when it carries none or one [issue] refuses. */
  fun value(action: JsonObject): Value? = parse(action["value"]).getOrNull()

  /**
   * What is wrong with [action] as a host action, or null when every lane can deliver it. The
   * message names the field and says what would work instead.
   */
  fun issue(action: JsonObject): String? {
    if (name(action) == null) {
      return if ((action["name"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
        "a host action's name is at most $MAX_NAME_LENGTH characters"
      else "a host action needs a name the host matches on"
    }
    return parse(action["value"]).exceptionOrNull()?.message
  }

  private fun parse(value: JsonElement?): Result<Value?> {
    if (value == null || value is JsonNull) return Result.success(null)
    val primitive =
      value as? JsonPrimitive
        ?: return Result.failure(
          IllegalArgumentException(
            "a host action's value is a literal in this version; a formula or a state read is " +
              "not sent yet",
          ),
        )
    if (primitive.isString) return Result.success(Value.Text(primitive.content))
    if (primitive.booleanOrNull != null)
      return Result.failure(
        IllegalArgumentException(
          "a host action sends text or a number, and Remote Compose has no flag value; send 1 or 0",
        ),
      )
    val whole = primitive.content.takeUnless { '.' in it || 'e' in it || 'E' in it }
    whole?.toIntOrNull()?.let {
      return Result.success(Value.Integer(it))
    }
    if (whole != null)
      return Result.failure(
        IllegalArgumentException("a host action's whole number must fit an Int, as RemoteInt does"),
      )
    val decimal = primitive.doubleOrNull?.takeIf(Double::isFinite)
    if (decimal == null || !decimal.toFloat().isFinite())
      return Result.failure(
        IllegalArgumentException(
          "a host action's decimal must be a finite Float, as RemoteFloat is",
        ),
      )
    return Result.success(Value.Decimal(decimal.toFloat()))
  }

  /** `{"type": "hostAction", "name": [name]}`, plus `value` when there is one. */
  fun encode(name: String, value: Value? = null): JsonObject =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive(TYPE))
        put("name", JsonPrimitive(name))
        if (value != null) put("value", value.json)
      },
    )

  /**
   * The value an author typed into a text field: a whole number or a decimal when it reads back as
   * one, otherwise text. A number only when it is spelled as one, so `007` and `+1` stay text, and
   * quoting (`"7"`) forces text. Blank is no value.
   */
  fun authoredValue(raw: String): Value? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"'))
      return Value.Text(trimmed.substring(1, trimmed.length - 1))
    trimmed
      .toIntOrNull()
      ?.takeIf { it.toString() == trimmed }
      ?.let {
        return Value.Integer(it)
      }
    if (NUMBER.matches(trimmed))
      trimmed
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it.toFloat().isFinite() }
        ?.let {
          return Value.Decimal(it.toFloat())
        }
    return Value.Text(raw)
  }

  /**
   * [value] as the text an author edits, so that [authoredValue] reads it back unchanged: text that
   * would read as a number or as nothing (`"7"`, `""`) is quoted, everything else is as it is.
   */
  fun editableText(value: JsonElement?): String {
    val primitive = (value as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return ""
    if (!primitive.isString) return primitive.content
    val text = primitive.content
    return if (authoredValue(text) == Value.Text(text)) text else "\"$text\""
  }

  /** A decimal as an author spells one: `0.5`, `-2.25`, `1e3`. Not `+1`, `.5` or `NaN`. */
  private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")
}
