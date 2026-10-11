package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Two-way binding of a control's value property to a state variable.
 *
 * A control that reports its own change (`Checkbox`, `Switch`, Wear's `CheckboxButton` /
 * `SwitchButton`) whose value property is a bare `{"type": "state", "variable": …}` read writes the
 * reported value back, then runs the event's actions — like `onValueChange = { x = it }`.
 *
 * Explicit actions win: when they already write the bound variable (a `toggle` or `set`), the write
 * back is skipped. `stateEquals` reads are never written back. One rule for the canvas SDK and the
 * Compose, Wear and Remote exporters.
 */
object TwoWayStateBinding {
  /** The action kinds that write the variable they name. */
  val WRITING_ACTIONS: Set<String> =
    setOf("set", "select", "selectOrClear", "setText", "toggle", "increment")

  /** The variable [value] reads with a bare `state` wrapper, or null for anything else. */
  fun boundVariable(value: JsonElement?): String? {
    val wrapper = value as? JsonObject ?: return null
    if ((wrapper["type"] as? JsonPrimitive)?.contentOrNull != "state") return null
    return (wrapper["variable"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.takeIf(String::isNotBlank)
  }

  /** Whether any action in [actions], an event's ordered action list, writes [variable]. */
  fun actionsWrite(actions: JsonElement?, variable: String): Boolean =
    (actions as? JsonArray).orEmpty().any { element ->
      val action = element as? JsonObject ?: return@any false
      (action["type"] as? JsonPrimitive)?.contentOrNull in WRITING_ACTIONS &&
        (action["variable"] as? JsonPrimitive)?.contentOrNull == variable
    }

  /**
   * The event a control's own change runs: the callback name without `on` (`checkedChange`,
   * `select`, `selectionClick`). Null when there is no such callback, so interaction stays `click`.
   */
  fun changeEvent(componentId: String, properties: Set<String>): String? =
    when {
      "checked" in properties -> CHECKED_CHANGE
      else -> SELECTION_EVENTS[componentId]
    }

  /**
   * The event a change of [property] runs: its own change event when bound; else `click` when bound
   * (older designs); else the change event, for new actions.
   */
  fun eventFor(property: String, eventBindings: Map<String, JsonElement>): String {
    val candidates = PROPERTY_EVENTS[property].orEmpty()
    candidates
      .firstOrNull { it in eventBindings }
      ?.let {
        return it
      }
    if (CLICK in eventBindings) return CLICK
    return candidates.firstOrNull() ?: CLICK
  }

  const val CLICK = "click"
  const val CHECKED_CHANGE = "checkedChange"
  const val SELECT = "select"
  const val SELECTION_CLICK = "selectionClick"

  /** Each two-way property's change events, preferred first. A split radio row's is its own. */
  private val PROPERTY_EVENTS: Map<String, List<String>> =
    mapOf("checked" to listOf(CHECKED_CHANGE), "selected" to listOf(SELECT, SELECTION_CLICK))

  /**
   * The radio rows whose selection callback is not `onClick`, by the event it names. No catalog
   * publishes a component's callbacks yet, so the builder has to know these.
   */
  private val SELECTION_EVENTS: Map<String, String> =
    mapOf(
      "wear-m3/radio-button" to SELECT,
      "remote-m3/remote-radio-button" to SELECT,
      "remote-m3/remote-split-radio-button" to SELECTION_CLICK,
    )

  /**
   * The variable a change of [property] should write back to, or null when none should be written:
   * the property is not a bare state read, or the event's [actions] already write that variable.
   */
  fun writeBackVariable(property: JsonElement?, actions: JsonElement?): String? =
    boundVariable(property)?.takeUnless { actionsWrite(actions, it) }
}
