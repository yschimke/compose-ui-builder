package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
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
 * Apply the generic behavior around a resolved node before an adapter or compatibility renderer
 * draws it.
 *
 * The SDK owns modifier ordering, bounds correlation, event execution and semantic activation.
 * [applyModifier] remains injected because resolving theme colors and shapes belongs to the catalog
 * runtime. [handlesClick] keeps a real interactive component from receiving a second clickable
 * wrapper around the callback its adapter already supplies.
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
  val dispatch = { event: String -> dispatchCanvasEvent(node, event, state, onState, onNavigate) }
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
  if (node.eventBindings["click"] != null && !handlesClick) {
    measured = measured.clickable(enabled = enabled, onClick = activate)
  }
  return PreparedCanvasNode(measured, enabled, dispatch)
}

/** Execute one event's ordered state and navigation actions. */
fun dispatchCanvasEvent(
  node: UiBuilderNode,
  event: String,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  onNavigate: (String) -> Unit,
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
      canvasStateWrite(action, working)?.also { (name, value) ->
        working[name] = value
        onState(name, value)
      }
    }
  }
}

private fun UiBuilderNode.canvasBoolean(name: String, fallback: Boolean): Boolean =
  ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.booleanOrNull ?: fallback
