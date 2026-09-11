package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.*

/** Values a template property takes at its actual authored instances, for catalog validation. */
data class UiBuilderArgumentBindings(
  val scopedNodes: Set<String>,
  val propertiesByNode: Map<String, List<JsonObject>>,
  val issues: List<Issue>,
) {
  data class Issue(val nodeId: String, val field: String, val message: String)
}

/**
 * Resolve the same lexical scopes as the canvas. Definitions without instances may declare a
 * parameter; every actual instance must supply it. This does not evaluate runtime state or expand
 * modifier/action expressions. The authored document is never changed.
 */
fun inspectUiBuilderArgumentBindings(document: UiBuilderDocument): UiBuilderArgumentBindings {
  fun isBinding(value: JsonElement) =
    (value as? JsonObject)?.get("type") == JsonPrimitive("binding")
  if (
    document.nodes.values.none { node ->
      node.properties.values.any(::isBinding) ||
        (node.component?.get("arguments") as? JsonObject).orEmpty().values.any(::isBinding)
    }
  )
    return UiBuilderArgumentBindings(emptySet(), emptyMap(), emptyList())
  val scoped = mutableSetOf<String>()
  val pending = ArrayDeque<String>()
  document.components.values.forEach { definition ->
    ((definition as? JsonObject)?.get("root") as? JsonPrimitive)
      ?.contentOrNull
      ?.let(pending::addLast)
  }
  document.nodes.values
    .filter { it.componentId == "layout/for-each" }
    .forEach { pending.addAll(it.slots["template"].orEmpty()) }
  while (pending.isNotEmpty()) {
    val id = pending.removeFirst()
    if (scoped.add(id)) document.nodes[id]?.slots?.values?.forEach(pending::addAll)
  }
  val values = linkedMapOf<String, MutableList<JsonObject>>()
  val issues = mutableListOf<UiBuilderArgumentBindings.Issue>()
  fun issue(id: String, field: String, message: String) {
    if (issues.size < 100) issues += UiBuilderArgumentBindings.Issue(id, field, message)
  }
  fun resolve(value: JsonElement, arguments: JsonObject, id: String, field: String): JsonElement {
    val binding =
      (value as? JsonObject)?.takeIf { it["type"] == JsonPrimitive("binding") } ?: return value
    val key = (binding["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val supplied = key?.let { arguments[it] }
    if (
      binding.keys != setOf("type", "value") ||
        supplied == null ||
        (supplied as? JsonObject)?.get("type") == JsonPrimitive("binding")
    ) {
      issue(id, field, "missing or invalid argument ${key ?: "binding key"}")
      return value
    }
    return supplied
  }
  val active = mutableSetOf<String>()
  var count = 0
  fun visit(id: String, arguments: JsonObject) {
    if (++count > 10_000 || active.size >= 128) {
      issue(id, "component", "argument expansion exceeds 10000 nodes or 128 levels")
      return
    }
    if (!active.add(id)) {
      issue(id, "component", "cyclic component or child reference")
      return
    }
    try {
      val node = document.nodes[id] ?: return
      val properties =
        JsonObject(
          node.properties.mapValues { (name, value) -> resolve(value, arguments, id, name) }
        )
      if (
        node.properties.values.any { (it as? JsonObject)?.get("type") == JsonPrimitive("binding") }
      )
        values.getOrPut(id) { mutableListOf() }.add(properties)
      val placement = node.component
      if (placement != null) {
        val key = (placement["componentKey"] as? JsonPrimitive)?.contentOrNull
        val root =
          ((document.components[key] as? JsonObject)?.get("root") as? JsonPrimitive)?.contentOrNull
        val supplied =
          JsonObject(
            (placement["arguments"] as? JsonObject).orEmpty().mapValues { (name, value) ->
              resolve(value, arguments, id, "component.arguments.$name")
            }
          )
        if (root != null) visit(root, supplied)
      } else if (node.componentId == "layout/for-each") {
        val rows = ((properties["data"] as? JsonObject)?.get("values") as? JsonArray).orEmpty()
        if (rows.size > 10_000) {
          issue(id, "data", "argument expansion exceeds 10000 rows")
          return
        }
        val template = node.slots["template"]?.singleOrNull() ?: return
        for (row in rows) {
          if (count > 10_000) break
          val fields = (row as? JsonObject)?.get("fields") as? JsonObject ?: continue
          visit(template, fields)
        }
      } else {
        for (child in node.slots.values.flatten()) {
          if (count > 10_000) break
          visit(child, arguments)
        }
      }
    } finally {
      active.remove(id)
    }
  }
  document.roots.forEach { visit(it, JsonObject(emptyMap())) }
  return UiBuilderArgumentBindings(scoped, values, issues.distinct())
}

/** Null delegates ordinary values to existing catalog checks; false is a located binding error. */
fun UiBuilderArgumentBindings.propertyMatches(
  nodeId: String,
  name: String,
  value: JsonElement,
  accepts: (JsonElement) -> Boolean,
): Boolean? {
  val binding =
    (value as? JsonObject)?.takeIf { it["type"] == JsonPrimitive("binding") } ?: return null
  if (
    nodeId !in scopedNodes ||
      binding.keys != setOf("type", "value") ||
      (binding["value"] as? JsonPrimitive)?.isString != true
  )
    return false
  return propertiesByNode[nodeId].orEmpty().all { resolved -> resolved[name]?.let(accepts) == true }
}
