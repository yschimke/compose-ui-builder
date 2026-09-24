package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What a Figma frame read back says was changed since it was exported, as one command.
 *
 * [command] is null when nothing changed. [diagnostics] are the import's, for the parts of the
 * frame a designer added that the map cannot name.
 */
data class FigmaRoundTripResult(
  val command: DesignCommand?,
  val diagnostics: List<FigmaImportDiagnostic>,
)

/**
 * Figma edits → a [DesignCommand] at the revision the scene was exported from. See
 * `docs/design/UI_BUILDER_FIGMA_INTEGRATION.md` § Round trip.
 *
 * The comparison is between two imports — of the [FigmaScene] Figma was given, and of the
 * [FigmaSnapshot] read back — never between the snapshot and the document. Both sides pass through
 * the same importer, so whatever the export could not express is lost from both identically and
 * cannot read as "removed in Figma". What differs is exactly what a designer changed, in the
 * vocabulary the map covers, and that is what is written onto the design: properties one by one,
 * the modifiers Figma can express merged into the node's chain without disturbing the rest, moves,
 * inserts and deletes.
 *
 * The command is authored against the scene's revision. The reducer applies it to whatever the
 * design has become since, with its ordinary per-property conflict notices — Figma is a
 * collaborator whose round trip took a day.
 */
class FigmaRoundTrip(catalog: CapabilityCatalog, map: FigmaComponentMap) {
  private val importer = FigmaSnapshotImporter(catalog, map)

  fun reconcile(
    base: UiBuilderDocument,
    scene: FigmaScene,
    snapshot: FigmaSnapshot,
    actorId: String,
    clientId: String,
    operationId: String,
  ): FigmaRoundTripResult {
    require(scene.designId == base.id) { "scene is of ${scene.designId}, not ${base.id}" }
    snapshot.root.stamp?.let { stamp ->
      require(stamp.designId == base.id) { "snapshot is of ${stamp.designId}, not ${base.id}" }
      require(stamp.revision == scene.revision) {
        "snapshot was exported at revision ${stamp.revision}, the scene at ${scene.revision}"
      }
    }
    val sent = importer.import(scene.asSnapshot(), base.id)
    val received = importer.import(snapshot, base.id)
    val before = UiBuilderReducer.replay(sent.operations).document
    val after = UiBuilderReducer.replay(received.operations).document
    val builtSizes = sceneSizes(scene.root)
    val diagnostics = received.diagnostics.toMutableList()
    fun UiBuilderNode.figmaId(): String = received.figmaNodeIds[id] ?: id

    val operations = mutableListOf<DesignOperation>()
    val beforeParents = before.parents()
    val afterParents = after.parents()

    // Deletes first, deepest last removed with their subtree by the reducer: only the topmost.
    before.nodes.keys
      .filter { it !in after.nodes && it in base.nodes }
      .filter { id -> beforeParents[id]?.first?.let { it in after.nodes } != false }
      .forEach { operations += DesignOperation.DeleteNode(it) }

    // Inserts and moves in the order the received tree lists its nodes, so a parent exists before
    // its children arrive and a sibling before the one placed after it.
    after.preorder().forEach { id ->
      val node = after.nodes.getValue(id)
      val parentInfo = afterParents[id]
      val parent = parentInfo?.let { (parentId, slot) ->
        ParentSlot(parentId, baseSlot(base, after, parentId, slot))
      }
      val afterNodeId = parentInfo?.let { (parentId, slot) ->
        previousSibling(after, parentId, slot, id)
      }
      if (id !in base.nodes && id !in before.nodes) {
        operations += DesignOperation.InsertNode(node.copy(slots = emptyMap()), parent, afterNodeId)
        return@forEach
      }
      if (id !in base.nodes) return@forEach
      val kept = before.nodes.keys intersect after.nodes.keys
      if (
        beforeParents[id] != afterParents[id] ||
          previousKept(before, beforeParents[id], id, kept) !=
            previousKept(after, afterParents[id], id, kept)
      ) {
        if (parent != null) operations += DesignOperation.MoveNode(id, parent, afterNodeId)
      }
    }

    // Values on nodes present on both sides.
    after.nodes.values.forEach { received ->
      val sentNode = before.nodes[received.id] ?: return@forEach
      val current = base.nodes[received.id] ?: return@forEach
      if (sentNode.componentId != received.componentId) {
        diagnostics +=
          FigmaImportDiagnostic(
            FigmaImportDiagnostic.COMPONENT_SWAPPED,
            received.figmaId(),
            received.id,
            "${received.id} was ${sentNode.componentId} and reads back as " +
              "${received.componentId}; swap it in the builder, the round trip does not",
          )
        return@forEach
      }
      (sentNode.properties.keys + received.properties.keys).forEach { name ->
        val was = sentNode.properties[name]
        val now = received.properties[name]
        when {
          was == now -> Unit
          now == null -> operations += DesignOperation.RemoveNodeProperty(received.id, name)
          else -> operations += DesignOperation.SetProperty(received.id, name, now)
        }
      }
      val sentModifiers = sentNode.modifiers.toList()
      val receivedModifiers =
        received.modifiers.filterNot { modifier ->
          builtSizes[received.id]?.let { size -> (modifier as JsonObject).isBuiltSize(size) } ==
            true &&
            sentModifiers.none { (it as JsonObject).type() == (modifier as JsonObject).type() }
        }
      if (sentModifiers != receivedModifiers) {
        operations +=
          DesignOperation.SetModifiers(
            received.id,
            mergeModifiers(current.modifiers, receivedModifiers.map { it as JsonObject }),
          )
      }
    }

    val command =
      operations
        .takeIf { it.isNotEmpty() }
        ?.let {
          DesignCommand(
            designId = base.id,
            operationId = operationId,
            actorId = actorId,
            clientId = clientId,
            baseRevision = scene.revision,
            operations = it,
          )
        }
    return FigmaRoundTripResult(command, diagnostics)
  }

  private companion object {
    /** The modifiers a Figma frame can express. Every other one on a node is left where it was. */
    val FIGMA_MODIFIERS =
      setOf(
        "size",
        "width",
        "height",
        "fillMaxSize",
        "fillMaxWidth",
        "fillMaxHeight",
        "weight",
        "offset",
        "background",
        "border",
        "padding",
      )

    /**
     * [current]'s chain with its Figma-expressible modifiers replaced by [received]'s, placed where
     * the first of them was — Compose reads a chain in order, so what Figma cannot express keeps
     * its position relative to the rest.
     */
    fun mergeModifiers(current: JsonArray, received: List<JsonObject>): JsonArray {
      val merged = mutableListOf<JsonObject>()
      var placed = false
      current.forEach { element ->
        val modifier = element as? JsonObject ?: return@forEach
        if (modifier.type() in FIGMA_MODIFIERS) {
          if (!placed) {
            merged += received
            placed = true
          }
        } else {
          merged += modifier
        }
      }
      if (!placed) merged.addAll(0, received)
      return JsonArray(merged)
    }

    fun JsonObject.type(): String? = optionalString("type")

    /**
     * Figma cannot hug a frame without auto layout, so a box the scene built at its measured size
     * reads back with that size fixed. That is the size it was given, not an edit.
     */
    fun JsonObject.isBuiltSize(size: Pair<Double, Double>): Boolean {
      fun near(name: String, expected: Double) =
        (this[name] as? JsonPrimitive)?.doubleOrNull?.let { abs(it - expected) < 0.5 } == true
      return when (type()) {
        "size" -> near("widthDp", size.first) && near("heightDp", size.second)
        "width" -> near("widthDp", size.first)
        "height" -> near("heightDp", size.second)
        else -> false
      }
    }

    fun sceneSizes(node: FigmaSnapshotNode): Map<String, Pair<Double, Double>> =
      mapOf((node.stamp?.nodeId ?: node.id) to (node.width to node.height)) +
        node.children.flatMap { sceneSizes(it).entries }.associate { it.key to it.value }

    fun UiBuilderDocument.parents(): Map<String, Pair<String, String>> =
      nodes.values
        .flatMap { parent ->
          parent.slots.flatMap { (slot, ids) -> ids.map { it to (parent.id to slot) } }
        }
        .toMap()

    fun UiBuilderDocument.preorder(): List<String> {
      val order = mutableListOf<String>()
      fun visit(id: String) {
        val node: UiBuilderNode = nodes[id] ?: return
        order += id
        node.slots.values.flatten().forEach(::visit)
      }
      roots.forEach(::visit)
      return order
    }

    /** The sibling before [id] among the nodes both sides have: an insert beside it is no move. */
    fun previousKept(
      document: UiBuilderDocument,
      parent: Pair<String, String>?,
      id: String,
      kept: Set<String>,
    ): String? {
      val (parentId, slot) = parent ?: return null
      val siblings = document.nodes[parentId]?.slots?.get(slot).orEmpty().filter { it in kept }
      return siblings.getOrNull(siblings.indexOf(id) - 1)
    }

    fun previousSibling(document: UiBuilderDocument, parentId: String, slot: String, id: String) =
      document.nodes[parentId]?.slots?.get(slot)?.let { siblings ->
        siblings.getOrNull(siblings.indexOf(id) - 1)
      }

    /**
     * The slot a received child goes in on the design itself. The importer puts a frame's children
     * in `children`, which is right for Row, Column and Box but not for a component a frame stood
     * in for — so a parent the design already has keeps the slot its children were in.
     */
    fun baseSlot(
      base: UiBuilderDocument,
      after: UiBuilderDocument,
      parentId: String,
      slot: String,
    ): String {
      val parent = base.nodes[parentId] ?: return slot
      if (slot in parent.slots) return slot
      val siblings = after.nodes[parentId]?.slots?.get(slot).orEmpty()
      return parent.slots.entries.firstOrNull { (_, ids) -> ids.any { it in siblings } }?.key
        ?: parent.slots.keys.singleOrNull()
        ?: slot
    }
  }
}
