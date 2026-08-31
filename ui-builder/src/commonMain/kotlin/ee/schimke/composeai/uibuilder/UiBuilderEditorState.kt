package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class EditorComponentKind(val label: String) {
  Scaffold("Scaffolds"),
  Container("Containers"),
  Composable("Composables"),
}

data class EditorCatalogItem(
  val componentId: String,
  val displayName: String,
  val kind: EditorComponentKind,
)

data class EditorTreeRow(
  val nodeId: String,
  val componentId: String,
  val label: String,
  val depth: Int,
  val parent: ParentSlot?,
)

enum class EditorMoveDirection {
  Before,
  After,
}

data class UiBuilderEditorState(
  val collaboration: CollaborationState,
  val selectedNodeId: String? = null,
  val catalogQuery: String = "",
  val operationSequence: Int = 0,
  val lastOutcome: CommandOutcome? = null,
) {
  val document: UiBuilderDocument
    get() = collaboration.document
}

sealed interface UiBuilderEditorEvent {
  data class SearchCatalog(val query: String) : UiBuilderEditorEvent

  data class SelectNode(val nodeId: String) : UiBuilderEditorEvent

  data class InsertComponent(val componentId: String, val target: ParentSlot) : UiBuilderEditorEvent

  data class MoveNode(
    val nodeId: String,
    val targetNodeId: String,
    val placeAfterTarget: Boolean,
  ) : UiBuilderEditorEvent

  data class SetText(val nodeId: String, val text: String) : UiBuilderEditorEvent
}

/** Pure editor interaction reducer. Every document mutation delegates to CollaborationReducer. */
class UiBuilderEditorReducer(private val catalog: CapabilityCatalog) {
  private val capabilityValidator = CapabilityValidator(catalog)
  private val validator = CapabilityPropertyWriteValidator(capabilityValidator)

  fun initial(document: UiBuilderDocument, selectedNodeId: String? = null): UiBuilderEditorState =
    UiBuilderEditorState(
      collaboration = CollaborationState(document),
      selectedNodeId = selectedNodeId?.takeIf(document.nodes::containsKey),
    )

  fun reduce(state: UiBuilderEditorState, event: UiBuilderEditorEvent): UiBuilderEditorState =
    when (event) {
      is UiBuilderEditorEvent.SearchCatalog -> state.copy(catalogQuery = event.query)
      is UiBuilderEditorEvent.SelectNode ->
        if (event.nodeId in state.document.nodes) state.copy(selectedNodeId = event.nodeId)
        else state
      is UiBuilderEditorEvent.InsertComponent -> insert(state, event.componentId, event.target)
      is UiBuilderEditorEvent.MoveNode -> move(state, event)
      is UiBuilderEditorEvent.SetText -> setText(state, event.nodeId, event.text)
    }

  fun catalogItems(query: String): List<EditorCatalogItem> {
    val needle = query.trim().lowercase()
    return catalog.components
      .asSequence()
      .map {
        EditorCatalogItem(
          componentId = it.componentId,
          displayName = it.displayName,
          kind = it.editorKind(),
        )
      }
      .filter {
        needle.isEmpty() ||
          it.displayName.lowercase().contains(needle) ||
          it.componentId.lowercase().contains(needle) ||
          it.kind.label.lowercase().contains(needle)
      }
      .sortedWith(compareBy(EditorCatalogItem::kind, EditorCatalogItem::displayName))
      .toList()
  }

  fun treeRows(document: UiBuilderDocument): List<EditorTreeRow> {
    val rows = mutableListOf<EditorTreeRow>()
    fun visit(nodeId: String, depth: Int, parent: ParentSlot?) {
      val node = document.nodes.getValue(nodeId)
      val capability = catalog.componentsById[node.componentId]
      rows +=
        EditorTreeRow(
          nodeId = nodeId,
          componentId = node.componentId,
          label = capability?.displayName ?: node.componentId,
          depth = depth,
          parent = parent,
        )
      node.slots.forEach { (slot, children) ->
        children.forEach { visit(it, depth + 1, ParentSlot(nodeId, slot)) }
      }
    }
    document.roots.forEach { visit(it, 0, null) }
    return rows
  }

  fun dropTarget(state: UiBuilderEditorState, componentId: String): ParentSlot? {
    val component = catalog.componentsById[componentId] ?: return null
    return findDestination(state.document, state.selectedNodeId, component)
  }

  fun dropTargetLabel(state: UiBuilderEditorState, componentId: String = "m3/text"): String =
    dropTarget(state, componentId)?.let { "${it.nodeId}.${it.slot}" } ?: "No compatible slot"

  fun moveTarget(
    state: UiBuilderEditorState,
    nodeId: String,
    direction: EditorMoveDirection,
  ): UiBuilderEditorEvent.MoveNode? {
    val parent = state.document.location(nodeId) ?: return null
    val siblings = state.document.children(parent)
    val index = siblings.indexOf(nodeId)
    val targetIndex =
      when (direction) {
        EditorMoveDirection.Before -> index - 1
        EditorMoveDirection.After -> index + 1
      }
    val target = siblings.getOrNull(targetIndex) ?: return null
    return UiBuilderEditorEvent.MoveNode(
      nodeId = nodeId,
      targetNodeId = target,
      placeAfterTarget = direction == EditorMoveDirection.After,
    )
  }

  private fun insert(
    state: UiBuilderEditorState,
    componentId: String,
    target: ParentSlot,
  ): UiBuilderEditorState {
    val component = catalog.componentsById[componentId] ?: return state
    val sequence = state.operationSequence + 1
    val resolvedTarget = findDestination(state.document, state.selectedNodeId, component)
    if (resolvedTarget == null || resolvedTarget != target) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_LOCATION,
        "${component.displayName} has no compatible selected slot",
      )
    }
    val nodeId = "editor-${componentId.replace('/', '-')}-${sequence.toString().padStart(3, '0')}"
    val operations = mutableListOf<DesignOperation>()
    val defaultError =
      component.appendDefaultSubtree(
        catalog = catalog,
        document = state.document,
        nodeId = nodeId,
        parent = target,
        afterNodeId = state.document.children(target).lastOrNull(),
        operations = operations,
      )
    if (defaultError != null) {
      return state.rejected(sequence, RejectionCode.INVALID_PROPERTY, defaultError)
    }
    return state.apply(sequence, operations, selectionAfter = nodeId, validateDocument = true)
  }

  private fun move(
    state: UiBuilderEditorState,
    event: UiBuilderEditorEvent.MoveNode,
  ): UiBuilderEditorState {
    val location = state.document.location(event.nodeId) ?: return state
    val siblings = state.document.children(location)
    val nodeIndex = siblings.indexOf(event.nodeId)
    val targetIndex = siblings.indexOf(event.targetNodeId)
    if (nodeIndex < 0 || targetIndex < 0 || event.nodeId == event.targetNodeId) return state
    val afterNodeId =
      if (event.placeAfterTarget) event.targetNodeId else siblings.getOrNull(targetIndex - 1)
    return state.apply(
      state.operationSequence + 1,
      listOf(DesignOperation.MoveNode(event.nodeId, location, afterNodeId)),
      selectionAfter = event.nodeId,
    )
  }

  private fun setText(
    state: UiBuilderEditorState,
    nodeId: String,
    text: String,
  ): UiBuilderEditorState {
    val node = state.document.nodes[nodeId] ?: return state
    if (node.componentId != "m3/text") return state
    return state.apply(
      state.operationSequence + 1,
      listOf(DesignOperation.SetProperty(nodeId, "text", literal("string", JsonPrimitive(text)))),
      selectionAfter = nodeId,
    )
  }

  private fun UiBuilderEditorState.apply(
    sequence: Int,
    operations: List<DesignOperation>,
    selectionAfter: String,
    validateDocument: Boolean = false,
  ): UiBuilderEditorState {
    val command =
      DesignCommand(
        designId = document.id,
        operationId = "editor-operation-${sequence.toString().padStart(4, '0')}",
        actorId = "wasm-editor",
        clientId = "interactive-canvas",
        baseRevision = document.revision,
        operations = operations,
      )
    val application = CollaborationReducer.apply(collaboration, command, validator)
    if (validateDocument && application.outcome is CommandOutcome.Accepted) {
      capabilityValidator.validate(application.state.document).issues.firstOrNull()?.let { issue ->
        return rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "${issue.nodeId}.${issue.field ?: "node"}: ${issue.message}",
        )
      }
    }
    return copy(
      collaboration = application.state,
      selectedNodeId =
        if (application.outcome is CommandOutcome.Accepted) selectionAfter else selectedNodeId,
      operationSequence = sequence,
      lastOutcome = application.outcome,
    )
  }

  private fun UiBuilderEditorState.rejected(
    sequence: Int,
    code: RejectionCode,
    message: String,
  ): UiBuilderEditorState =
    copy(
      operationSequence = sequence,
      lastOutcome = CommandOutcome.Rejected(code = code, message = message),
    )

  private fun findDestination(
    document: UiBuilderDocument,
    selectedNodeId: String?,
    inserted: ComponentCapability,
  ): ParentSlot? {
    val selected = selectedNodeId?.let(document.nodes::get)
    val selectedCapability = selected?.let { catalog.componentsById[it.componentId] }
    selectedCapability
      ?.slots
      ?.firstOrNull { slot ->
        slot.accepts(inserted) && slot.hasRoom(selected.slots[slot.name].orEmpty().size)
      }
      ?.let {
        return ParentSlot(selected.id, it.name)
      }

    val selectedParent = selectedNodeId?.let(document::location)
    if (selectedParent != null) {
      val parent = document.nodes.getValue(selectedParent.nodeId)
      val slot = catalog.componentsById[parent.componentId]?.slotsByName?.get(selectedParent.slot)
      if (
        slot?.accepts(inserted) == true &&
          slot.hasRoom(parent.slots[selectedParent.slot].orEmpty().size)
      )
        return selectedParent
    }
    return null
  }
}

private fun ComponentCapability.editorKind(): EditorComponentKind =
  when (role) {
    "Scaffold" -> EditorComponentKind.Scaffold
    "Container" -> EditorComponentKind.Container
    else -> EditorComponentKind.Composable
  }

private fun SlotCapability.accepts(component: ComponentCapability): Boolean =
  "AnyContent" in acceptedTraits ||
    component.role in acceptedRoles ||
    component.traits.any(acceptedTraits::contains)

private fun SlotCapability.hasRoom(childCount: Int): Boolean =
  cardinality.max?.let { childCount < it } ?: true

private fun ComponentCapability.defaultNode(
  nodeId: String,
  document: UiBuilderDocument,
): UiBuilderNode =
  UiBuilderNode(
    id = nodeId,
    componentId = componentId,
    properties =
      JsonObject(
        properties.filter(PropertyCapability::required).associate { property ->
          property.name to property.defaultEncodedValue(nodeId, document)
        }
      ),
    modifiers = JsonArray(emptyList()),
    slots = slots.associate { it.name to emptyList() },
  )

private fun PropertyCapability.defaultEncodedValue(
  nodeId: String,
  document: UiBuilderDocument,
): JsonObject {
  allowedValues.firstOrNull()?.let { value ->
    return value.asLiteral(name)
  }
  return when (name) {
    "text" -> literal("string", JsonPrimitive("New text"))
    "assetKey" -> literal("assetKey", JsonPrimitive("editor.placeholder"))
    "iconKey" -> literal("enum", JsonPrimitive("addCircle"))
    "layoutMode" -> literal("enum", JsonPrimitive("adaptive"))
    "mainPaneVisible",
    "supportingPaneVisible" -> literal("bool", JsonPrimitive(true))
    "columns" ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("adaptiveGrid"),
          "minimumCellWidthDp" to JsonPrimitive(362),
        )
      )
    "scrollStateKey" -> literal("string", JsonPrimitive("$nodeId-scroll"))
    "itemWidthDp" -> literal("float", JsonPrimitive(128.0))
    "expanded",
    "selected",
    "visible" -> literal("bool", JsonPrimitive(false))
    "value" ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("state"),
          "variable" to JsonPrimitive(document.stateVariables.keys.firstOrNull() ?: "value"),
        )
      )
    "startColor" -> literal("color", JsonPrimitive("#00000000"))
    "endColor" -> literal("color", JsonPrimitive("#FF000000"))
    else -> JsonPrimitive("").asLiteral(name)
  }
}

private fun ComponentCapability.appendDefaultSubtree(
  catalog: CapabilityCatalog,
  document: UiBuilderDocument,
  nodeId: String,
  parent: ParentSlot,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
  componentPath: Set<String> = emptySet(),
  depth: Int = 0,
): String? {
  if (depth >= 32) return "required-slot defaults exceed the maximum depth of 32"
  if (componentId in componentPath) {
    return "required-slot default cycle: ${(componentPath + componentId).joinToString(" -> ")}"
  }
  operations += DesignOperation.InsertNode(defaultNode(nodeId, document), parent, afterNodeId)
  slots
    .filter { it.cardinality.min > 0 }
    .forEach { slot ->
      repeat(slot.cardinality.min) { index ->
        val child =
          defaultChildFor(slot, catalog)
            ?: return "catalog has no safe required-slot default for $componentId.${slot.name}"
        val childId = "$nodeId-${slot.name}-${index + 1}"
        val error =
          child.appendDefaultSubtree(
            catalog = catalog,
            document = document,
            nodeId = childId,
            parent = ParentSlot(nodeId, slot.name),
            afterNodeId = if (index == 0) null else "$nodeId-${slot.name}-$index",
            operations = operations,
            componentPath = componentPath + componentId,
            depth = depth + 1,
          )
        if (error != null) return error
      }
    }
  return null
}

private fun defaultChildFor(
  slot: SlotCapability,
  catalog: CapabilityCatalog,
): ComponentCapability? {
  val preferredId =
    when {
      "IconContent" in slot.acceptedTraits -> "m3/icon"
      "SearchInput" in slot.acceptedTraits -> "m3/search-input-field"
      "TextContent" in slot.acceptedTraits || "Leaf" in slot.acceptedRoles -> "m3/text"
      else -> "layout/box"
    }
  return catalog.componentsById[preferredId]?.takeIf(slot::accepts)
}

private fun JsonElement.asLiteral(propertyName: String): JsonObject {
  if (this is JsonNull) return literal("string", JsonPrimitive(""))
  val primitive = jsonPrimitive
  val type =
    when {
      primitive.booleanOrNull != null -> "bool"
      primitive.doubleOrNull != null -> "float"
      propertyName.endsWith("Color") -> "color"
      propertyName in setOf("style", "layoutMode", "iconKey") -> "enum"
      else -> "string"
    }
  return literal(type, primitive)
}

private fun literal(type: String, value: JsonElement): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

private fun UiBuilderDocument.location(nodeId: String): ParentSlot? {
  nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) ->
      if (nodeId in children) return ParentSlot(parent.id, slot)
    }
  }
  return null
}

private fun UiBuilderDocument.children(parent: ParentSlot?): List<String> =
  if (parent == null) roots else nodes.getValue(parent.nodeId).slots[parent.slot].orEmpty()
