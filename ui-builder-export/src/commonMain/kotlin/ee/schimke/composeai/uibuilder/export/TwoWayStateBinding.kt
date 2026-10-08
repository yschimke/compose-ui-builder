package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Two-way binding of a control's value property to a state variable.
 *
 * A control that reports its own change — `Checkbox(checked, onCheckedChange)`, `Switch`, Wear's
 * `CheckboxButton` and `SwitchButton` — is two-way bound when its value property reads a variable
 * with a bare `{"type": "state", "variable": …}` wrapper: the change callback writes the reported
 * value back to that variable, then runs the event's authored actions. That is the shape a Slider
 * and a TextField already had (`onValueChange = { x = it }`); without it a bound checkbox drew the
 * variable but never changed it unless its author also added a `toggle`.
 *
 * Explicit actions win. When the event's own actions already write the bound variable — the
 * `toggle` an author added by hand before this existed, or a `set` that forces a value — the write
 * back is skipped, so the variable is written once, by the action the author chose, rather than
 * twice in an order the reader has to work out. A `stateEquals` read is a comparison, not a
 * variable the control holds, and is never written back.
 *
 * One rule for every lane — the canvas SDK, the Compose, Wear and Remote Compose exporters — so the
 * preview and the generated code cannot disagree about what a tap does.
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
   * The event a control's own change runs, named as every lane names an event: the callback
   * parameter without `on`. A checked control's `onCheckedChange` is `checkedChange`; a Wear or
   * Remote radio row's `onSelect` is `select`. Null for a component with no such callback, whose
   * interaction stays `click` (`onClick`) — an m3 `RadioButton` among them.
   */
  fun changeEvent(componentId: String, properties: Set<String>): String? =
    when {
      "checked" in properties -> CHECKED_CHANGE
      componentId in SELECT_COMPONENTS -> SELECT
      else -> null
    }

  /**
   * The event a change of [property] runs on a node carrying [eventBindings].
   *
   * Its own change event when the node binds it; otherwise `click` when the node binds that, which
   * is how designs authored before the names were made consistent wired a checkbox; otherwise the
   * change event, so a newly authored action lands under the consistent name.
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

  /** Each two-way property's change events, preferred first. A split radio row's is its own. */
  private val PROPERTY_EVENTS: Map<String, List<String>> =
    mapOf("checked" to listOf(CHECKED_CHANGE), "selected" to listOf(SELECT, "selectionClick"))

  /** The radio rows whose callback is `onSelect` rather than `onClick`. */
  private val SELECT_COMPONENTS: Set<String> =
    setOf("wear-m3/radio-button", "remote-m3/remote-radio-button")

  /**
   * The variable a change of [property] should write back to, or null when none should be written:
   * the property is not a bare state read, or the event's [actions] already write that variable.
   */
  fun writeBackVariable(property: JsonElement?, actions: JsonElement?): String? =
    boundVariable(property)?.takeUnless { actionsWrite(actions, it) }
}
