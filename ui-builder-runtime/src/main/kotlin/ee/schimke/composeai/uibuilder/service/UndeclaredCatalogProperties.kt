package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1

/**
 * The properties a stored design carries that its catalog no longer declares.
 *
 * Catalogs change; an undeclared property must not make a whole design unusable. The value stays
 * in the stored document (no migration), so a catalog that declares it again simply resumes.
 *
 * [withoutProperties] builds a probe without these properties, which is validated instead: if only
 * undeclared properties were wrong it validates clean and the design is served as itself; anything
 * else it refuses (e.g. an unknown component) stays fatal. The probe is never stored, sent or
 * exported.
 *
 * Tolerated by value, not by name: a write that changes a tolerated property's value puts it back
 * in the probe to be refused. Clearing it removes the key.
 */
internal fun undeclaredProperties(
  document: DesignDocumentV1,
  catalog: CatalogCapabilityV1,
): Map<String, Map<String, UiValueV1>> {
  val declared = catalog.components.associateBy { it.componentId }
  return buildMap {
    document.nodes.forEach { (nodeId, node) ->
      // Only components the catalog defines; unknown and design-defined components are `validate`'s
      // to judge.
      val component = declared[node.componentId] ?: return@forEach
      val names = component.properties.mapTo(mutableSetOf()) { it.name }
      val undeclared = node.properties.filterKeys { it !in names }
      if (undeclared.isNotEmpty()) put(nodeId, undeclared)
    }
  }
}

/**
 * The document with [drop] removed, as a probe. A property is dropped only where it still holds
 * the tolerated value. Returns the receiver unchanged when there is nothing to drop.
 */
internal fun DesignDocumentV1.withoutProperties(
  drop: Map<String, Map<String, UiValueV1>>
): DesignDocumentV1 {
  if (drop.isEmpty()) return this
  return copy(
    nodes =
      nodes.mapValues { (nodeId, node) ->
        val tolerated = drop[nodeId] ?: return@mapValues node
        val kept = node.properties.filterNot { (name, value) -> tolerated[name] == value }
        if (kept.size == node.properties.size) node else node.copy(properties = kept)
      }
  )
}
