package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1

/**
 * The properties a stored design carries that its catalog no longer declares.
 *
 * ## Why this exists
 *
 * A catalog is not frozen. A component loses a property, or a published catalog replaces a
 * synthesised one that declared more, and every design that used the property stops validating. The
 * verdict was fatal: `unusableReason` turned one undeclared property into `invalid stored design
 * <id>`, and from then on *every* request naming that design was refused. Thirty-six designs on
 * `preview.coo.ee` died that way after the catalog source flip, twenty-six of them for nothing
 * worse than `property containerColor is not declared by m3/surface`.
 *
 * Nothing was lost, which is the part worth noticing: the value is still sitting in the stored
 * document, untouched. Only the verdict was fatal. So there is no need to park the value somewhere
 * new — and a good reason not to, since the document schema belongs to `compose-preview-contracts`
 * and a design that stores its own damage has to be migrated back out of it later. Leaving the
 * property where it is means a catalog that declares it again simply resumes: no migration, no
 * un-parking, and the design is exactly what its author wrote.
 *
 * ## The probe
 *
 * [withoutProperties] builds the document as it WOULD be if the undeclared properties were absent,
 * and that copy is validated instead of the real one. It exists only to answer "is anything else
 * wrong?" — a document whose sole complaint is an undeclared property validates clean as a probe
 * and is served as itself. Anything the probe still refuses is a real defect and stays fatal: an
 * unknown component has nothing to draw, and no amount of tolerance produces a node.
 *
 * The probe is never stored, never handed to a client, and never exported as the design. It is a
 * question, not a document.
 *
 * ## Tolerated by value, not by name
 *
 * What is tolerated is the exact value the design already held — never the property name. Keying on
 * the name alone would let a write rewrite a tolerated property to something new and have the
 * rewrite stripped from its own validation, so a design would go on authoring against what the
 * catalog lacks under cover of what it once had. So this carries the stored value, and
 * [withoutProperties] drops a property only where the document still holds that same value: change
 * it and it is back in the probe, refused exactly as a newly invented property is. Clearing it
 * removes the key outright, which is the recovery a designer has without waiting for the catalog.
 */
internal fun undeclaredProperties(
  document: DesignDocumentV1,
  catalog: CatalogCapabilityV1,
): Map<String, Map<String, UiValueV1>> {
  val declared = catalog.components.associateBy { it.componentId }
  return buildMap {
    document.nodes.forEach { (nodeId, node) ->
      // Only nodes the catalog DOES define. A node naming a component the catalog does not have is
      // the blocking case, and one naming a design-defined component is not the catalog's to judge
      // — `validate` already separates those two, and guessing here would answer for it.
      val component = declared[node.componentId] ?: return@forEach
      val names = component.properties.mapTo(mutableSetOf()) { it.name }
      val undeclared = node.properties.filterKeys { it !in names }
      if (undeclared.isNotEmpty()) put(nodeId, undeclared)
    }
  }
}

/**
 * The same document with [drop] removed, for asking a question about it.
 *
 * A property is dropped only where this document still holds the value [drop] carries for it: a
 * rewritten value is not the value that was tolerated, and stays in the probe to be refused.
 *
 * Returns the receiver unchanged when there is nothing to drop, so the common path allocates
 * nothing and the probe is the real document by identity.
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
