package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

internal data class ExportValidationIssue(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val componentId: String? = null,
)

/** Closed fail-closed validation shared by code and SVG projections. */
internal fun validateDocumentForExport(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): List<ExportValidationIssue> {
  val issues = mutableListOf<ExportValidationIssue>()
  CapabilityValidator(catalog).validate(document).issues.forEach { issue ->
    issues +=
      ExportValidationIssue(
        code = issue.code.name,
        message = issue.message,
        nodeId = issue.nodeId,
        componentId = issue.componentId,
      )
  }
  issues += validateCatalogPin(document, catalog)
  issues += validateEnvironment(document)
  issues += validateGraph(document)
  return issues.distinct()
}

private fun validateEnvironment(document: UiBuilderDocument): List<ExportValidationIssue> {
  val issues = mutableListOf<ExportValidationIssue>()
  if (document.environment.isEmpty()) {
    issues +=
      ExportValidationIssue(
        code = "INVALID_EXPORT_ENVIRONMENT",
        message = "environment pin must not be empty",
      )
    return issues
  }
  listOf("widthDp", "heightDp", "density").forEach { field ->
    val value = (document.environment[field] as? JsonPrimitive)?.floatOrNull
    if (value == null || !value.isFinite() || value <= 0f) {
      issues +=
        ExportValidationIssue(
          code = "INVALID_EXPORT_ENVIRONMENT",
          message = "environment.$field must be a finite number greater than zero",
        )
    }
  }
  val theme =
    (document.environment["theme"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
  if (theme.isNullOrBlank()) {
    issues +=
      ExportValidationIssue(
        code = "INVALID_EXPORT_ENVIRONMENT",
        message = "environment.theme must be nonblank text",
      )
  }
  return issues
}

private fun validateCatalogPin(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): List<ExportValidationIssue> {
  // Candidate manifests do not yet expose a separate digest field. Until that wire shape moves to
  // contracts, the frozen catalog revision is also the expected candidate digest.
  val expected =
    mapOf(
      "systemId" to catalog.benchmark.catalogSystemId,
      "catalogRevision" to catalog.benchmark.catalogRevision,
      "capabilityDigest" to catalog.benchmark.catalogRevision,
      "nativeRuntimeId" to catalog.benchmark.nativeRuntimeId,
    )
  val actual =
    document.catalogPin.mapValues { (_, value) ->
      (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }
  val mismatches = expected.mapNotNull { (field, expectedValue) ->
    val actualValue = actual[field]
    if (actualValue == expectedValue) null
    else
      ExportValidationIssue(
        code = "CATALOG_PIN_MISMATCH",
        message =
          "catalogPin.$field expected '$expectedValue' but was '${actualValue ?: "<missing>"}'",
      )
  }
  val unexpected =
    (actual.keys - expected.keys).sorted().map { field ->
      ExportValidationIssue(
        code = "CATALOG_PIN_MISMATCH",
        message = "catalogPin contains unexpected field '$field'",
      )
    }
  return mismatches + unexpected
}

/**
 * Where each component's body starts, by component key.
 *
 * A body is an ordinary subtree in the ordinary `nodes` map — that is what lets every walk here
 * treat it as one — reached from the component rather than from a slot, so it is named among the
 * graph's entry points beside the document's own root.
 */
private fun UiBuilderDocument.componentRoots(): Map<String, String> =
  components.entries
    .sortedBy { it.key }
    .mapNotNull { (key, value) ->
      val root = (value as? JsonObject)?.optionalString("root") ?: return@mapNotNull null
      if (root in nodes) key to root else null
    }
    .toMap()

private fun validateGraph(document: UiBuilderDocument): List<ExportValidationIssue> {
  val issues = mutableListOf<ExportValidationIssue>()
  if (document.roots.size != 1) {
    issues +=
      ExportValidationIssue(
        code = "ROOT_CARDINALITY",
        message = "export requires exactly one root; found ${document.roots.size}",
      )
  }
  document.roots.forEach { root ->
    if (root !in document.nodes) {
      issues += ExportValidationIssue("UNKNOWN_ROOT", "root references unknown node $root", root)
    }
  }

  val references = linkedMapOf<String, MutableList<String>>()
  document.nodes.values.sortedBy(UiBuilderNode::id).forEach { parent ->
    parent.slots.entries
      .sortedBy { it.key }
      .forEach { (slot, children) ->
        children.forEach { child ->
          references.getOrPut(child) { mutableListOf() } += "${parent.id}.$slot"
        }
      }
  }
  document.roots.forEach { root -> references.getOrPut(root) { mutableListOf() } += "<root>" }
  // A component body is referenced by the component that owns it, not by a slot. Counted here so
  // it is neither unreachable — it is drawn wherever the component is placed — nor a duplicate the
  // moment a second design places the same component.
  document.componentRoots().forEach { (key, root) ->
    references.getOrPut(root) { mutableListOf() } += "<component:$key>"
  }
  references.entries
    .sortedBy { it.key }
    .forEach { (nodeId, parents) ->
      if (parents.size > 1) {
        issues +=
          ExportValidationIssue(
            code = "DUPLICATE_NODE_REFERENCE",
            message = "node is referenced ${parents.size} times: ${parents.joinToString()}",
            nodeId = nodeId,
            componentId = document.nodes[nodeId]?.componentId,
          )
      }
    }

  val visited = mutableSetOf<String>()
  val active = mutableSetOf<String>()
  fun visit(nodeId: String) {
    if (nodeId in active) {
      issues +=
        ExportValidationIssue(
          code = "GRAPH_CYCLE",
          message = "cycle reaches node $nodeId",
          nodeId = nodeId,
          componentId = document.nodes[nodeId]?.componentId,
        )
      return
    }
    if (!visited.add(nodeId)) return
    val node = document.nodes[nodeId] ?: return
    active += nodeId
    node.slots.entries.sortedBy { it.key }.forEach { (_, children) -> children.forEach(::visit) }
    active -= nodeId
  }
  document.roots.forEach(::visit)
  document.componentRoots().values.forEach(::visit)
  (document.nodes.keys - visited).sorted().forEach { nodeId ->
    issues +=
      ExportValidationIssue(
        code = "UNREACHABLE_NODE",
        message = "node is not reachable from the document root",
        nodeId = nodeId,
        componentId = document.nodes[nodeId]?.componentId,
      )
  }
  return issues
}
