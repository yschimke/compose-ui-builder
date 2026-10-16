package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The SDK-owned behavior and modifier chain prepared for one resolved canvas node. */
class PreparedCanvasNode
internal constructor(
  val modifier: Modifier,
  val enabled: Boolean,
  private val dispatchEvent: (String) -> Unit,
) {
  fun dispatch(event: String) = dispatchEvent(event)
}

/**
 * Apply the generic behavior around a resolved node before an adapter draws it. The SDK owns
 * modifier ordering, bounds correlation, events and semantic activation; [applyModifier] is
 * injected because theme resolution belongs to the catalog runtime. [handlesClick] avoids a second
 * clickable wrapper.
 */
@Composable
fun CanvasRenderNode.prepare(
  modifier: Modifier,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  onNavigate: (String) -> Unit,
  handlesClick: Boolean,
  applyModifier: @Composable (Modifier, JsonObject) -> Modifier,
  onBounds: (UiBuilderInstancePath, LayoutCoordinates) -> Unit,
  onSemanticAction: (String, UiBuilderSemanticActionEntry) -> Unit,
): PreparedCanvasNode {
  val enabled = node.canvasBoolean("enabled", true)
  val dispatch = { event: String ->
    dispatchCanvasEvent(node, event, state, onState, onNavigate, expressionScope, clock)
  }
  val activate = { dispatch("click") }
  if (node.eventBindings["click"] != null) {
    onSemanticAction(
      node.id,
      UiBuilderSemanticActionEntry(enabled = enabled, activate = activate),
    )
  }

  var measured = modifier.onGloballyPositioned { onBounds(path, it) }
  node.modifiers.forEach { encoded ->
    val value = encoded as? JsonObject
    if (value != null) measured = applyModifier(measured, value)
  }
  val longClick = node.eventBindings["longClick"] != null
  val doubleClick = node.eventBindings["doubleClick"] != null
  if ((longClick || doubleClick) && !handlesClick) {
    // Remote Compose's `combinedClickable`: a press, a long press and a double tap, each its own
    // ordered action list.
    measured =
      measured.combinedClickable(
        enabled = enabled,
        onClick = activate,
        onLongClick = if (longClick) ({ dispatch("longClick") }) else null,
        onDoubleClick = if (doubleClick) ({ dispatch("doubleClick") }) else null,
      )
  } else if (node.eventBindings["click"] != null && !handlesClick) {
    measured = measured.clickable(enabled = enabled, onClick = activate)
  }
  if (enabled && TOUCH_EVENTS.any { node.eventBindings[it] != null }) {
    // Remote Compose's `onTouchDown` / `onTouchUp` / `onTouchCancel`. Nothing is consumed, so a
    // click on the same node still fires.
    val touch = rememberUpdatedState(dispatch)
    measured =
      measured.pointerInput(node.id) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          touch.value("touchDown")
          // Released is an up even when a click consumed it; a move another handler took while
          // still pressed (a scroll) cancels the touch.
          while (true) {
            val changes = awaitPointerEvent().changes
            if (changes.none { it.pressed }) {
              touch.value("touchUp")
              break
            }
            if (changes.any { it.isConsumed }) {
              touch.value("touchCancel")
              break
            }
          }
        }
      }
  }
  return PreparedCanvasNode(measured, enabled, dispatch)
}

private val TOUCH_EVENTS = listOf("touchDown", "touchUp", "touchCancel")

/** Execute one event's ordered state and navigation actions. */
fun dispatchCanvasEvent(
  node: UiBuilderNode,
  event: String,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  onNavigate: (String) -> Unit,
  /** The document's state kinds, which a formula-valued action is typed against. */
  scope: UiExpressions.Scope? = null,
  clock: UiExpressions.Clock = UiExpressions.Clock.DEFAULT,
) {
  val actions = node.eventBindings[event] as? JsonArray ?: return
  val working = state.toMutableMap()
  actions.forEach { element ->
    val action = element as? JsonObject ?: return@forEach
    if ((action["type"] as? JsonPrimitive)?.content == "navigatePage") {
      (action["pageKey"] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)
        ?.let(onNavigate)
    } else {
      canvasStateWrite(action, working, scope, clock)?.also { (name, value) ->
        working[name] = value
        onState(name, value)
      }
    }
  }
}

private fun UiBuilderNode.canvasBoolean(name: String, fallback: Boolean): Boolean =
  ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.booleanOrNull ?: fallback
