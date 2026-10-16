package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Render the two structural nodes owned by the generic interpreter: a loop (row arguments for
 * repeated entries) and a component instance (placement arguments for a body). Adapters receive
 * only resolved descendants.
 *
 * @return whether this entry is a structural node, including an invalid component placement whose
 *   diagnostic was drawn by [missingComponent].
 */
@Composable
fun CanvasRenderNode.renderStructure(
  modifier: Modifier,
  renderChild: @Composable (CanvasRenderNode, Modifier) -> Unit,
  missingComponent: @Composable (String, Modifier) -> Unit,
): Boolean =
  when (node.componentId) {
    FOR_EACH_COMPONENT_ID -> {
      Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(node.canvasFloat("verticalSpacingDp").dp),
      ) {
        repeatedTemplateEntries().forEach { renderChild(it, Modifier) }
      }
      true
    }
    DESIGN_COMPONENT_INSTANCE_ID -> {
      val child = placedComponentEntry()
      if (child == null) {
        missingComponent(
          "${node.componentId} → ${node.componentKey().ifEmpty { "(none)" }}",
          modifier,
        )
      } else {
        Box(modifier) { renderChild(child, Modifier) }
      }
      true
    }
    else -> false
  }

internal fun CanvasRenderNode.repeatedTemplateEntries(): List<CanvasRenderNode> {
  val template = node.slots["template"].orEmpty().firstOrNull() ?: return emptyList()
  return node.forEachRows().mapIndexedNotNull { index, row ->
    // `@index` is the row's position unless the row authors a field of that name.
    val fields =
      if ("index" in row) row
      else
        JsonObject(
          row +
            ("index" to
              JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(index))))
        )
    occurrenceChild(template, index, fields)
  }
}

internal fun CanvasRenderNode.placedComponentEntry(): CanvasRenderNode? {
  val root = componentRoot() ?: return null
  return placementChild(root, node.componentArguments(bindingArguments))
}

private fun UiBuilderNode.forEachRows(): List<JsonObject> {
  val data = properties["data"] as? JsonObject ?: return emptyList()
  if (data.wrapperType() != "list") return emptyList()
  val values = data["values"] as? JsonArray ?: return emptyList()
  return values.mapNotNull { row ->
    val value = row as? JsonObject ?: return@mapNotNull null
    if (value.wrapperType() != "object") return@mapNotNull null
    value["fields"] as? JsonObject
  }
}

private fun UiBuilderNode.componentArguments(scope: JsonObject): JsonObject {
  val declared = component?.get("arguments") as? JsonObject ?: return JsonObject(emptyMap())
  if (scope.isEmpty() || declared.isEmpty()) return declared
  return JsonObject(
    declared.mapValues { (_, value) ->
      val binding = value as? JsonObject ?: return@mapValues value
      val key = binding.bindingKey() ?: return@mapValues value
      scope[key] ?: value
    }
  )
}

private fun UiBuilderNode.componentKey(): String =
  (component?.get("componentKey") as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun UiBuilderNode.canvasFloat(name: String): Float =
  ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.floatOrNull ?: 0f

private fun JsonObject.bindingKey(): String? {
  if (wrapperType() != "binding") return null
  return (this["value"] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.wrapperType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

private const val FOR_EACH_COMPONENT_ID = "layout/for-each"
private const val DESIGN_COMPONENT_INSTANCE_ID = "design/component-instance"
