package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class EditorPropertyControl {
  Text,
  Boolean,
  Number,
  Enum,
  Color,
  Unsupported,
}

data class EditorNumberBounds(
  val minimum: Double,
  val maximum: Double,
  val step: Double,
  val integer: Boolean,
)

data class EditorPropertyField(
  val nodeId: String,
  val name: String,
  val label: String,
  val required: Boolean,
  val control: EditorPropertyControl,
  val value: String,
  val choices: List<String> = emptyList(),
  val numberBounds: EditorNumberBounds? = null,
  val error: String? = null,
  val notes: String? = null,
)

data class EditorPropertyLocation(val nodeId: String, val property: String)

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

enum class EditorScreenTheme(val wireValue: String, val label: String) {
  Light("light", "Light"),
  Dark("dark", "Dark"),
  System("system", "System"),
}

enum class EditorLayoutDirection(val wireValue: String, val label: String) {
  Ltr("ltr", "Left to right"),
  Rtl("rtl", "Right to left"),
}

data class ScreenEnvironmentSettings(
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
  val fontScale: Double,
  val locale: String,
  val theme: EditorScreenTheme,
  val layoutDirection: EditorLayoutDirection,
)

fun UiBuilderDocument.screenEnvironmentSettings(): ScreenEnvironmentSettings =
  ScreenEnvironmentSettings(
    widthDp = environment["widthDp"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1280,
    heightDp = environment["heightDp"]?.jsonPrimitive?.content?.toIntOrNull() ?: 800,
    density = environment["density"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
    fontScale = environment["fontScale"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
    locale = environment["locale"]?.jsonPrimitive?.content.orEmpty().ifBlank { "en-US" },
    theme =
      EditorScreenTheme.entries.firstOrNull {
        it.wireValue == environment["theme"]?.jsonPrimitive?.content
      } ?: EditorScreenTheme.System,
    layoutDirection =
      EditorLayoutDirection.entries.firstOrNull {
        it.wireValue == environment["layoutDirection"]?.jsonPrimitive?.content
      } ?: EditorLayoutDirection.Ltr,
  )

fun ScreenEnvironmentSettings.validationError(): String? =
  when {
    widthDp !in 240..3840 -> "Width must be between 240 and 3840 dp."
    heightDp !in 240..3840 -> "Height must be between 240 and 3840 dp."
    !density.isFinite() || density !in 0.5..4.0 -> "Density must be between 0.5 and 4.0."
    !fontScale.isFinite() || fontScale !in 0.5..3.0 -> "Font scale must be between 0.5 and 3.0."
    locale.length !in 2..64 || !Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{1,8})*").matches(locale) ->
      "Locale must be a BCP 47-style tag such as en-US."
    else -> null
  }

data class UiBuilderEditorState(
  val collaboration: CollaborationState,
  val selectedNodeId: String? = null,
  val catalogQuery: String = "",
  val operationSequence: Int = 0,
  val lastOutcome: CommandOutcome? = null,
  val selectionBeforeOperations: Map<String, String?> = emptyMap(),
  val selectionAfterOperations: Map<String, String?> = emptyMap(),
  val propertyErrors: Map<EditorPropertyLocation, String> = emptyMap(),
  val inspectorMode: EditorInspectorMode = EditorInspectorMode.Properties,
) {
  val document: UiBuilderDocument
    get() = collaboration.document

  val canUndo: Boolean
    get() = undoTargetOperationId() != null

  val canRedo: Boolean
    get() = redoTargetUndoId() != null
}

enum class EditorInspectorMode {
  Properties,
  Theme,
  Screen,
}

data class EditorThemeSettings(
  val primaryColor: String = "#FFD0BCFF",
  val backgroundColor: String = "#FF111318",
  val surfaceColor: String = "#FF1D1F25",
  val contentColor: String = "#FFE3E2E9",
  val typeScale: Float = 1f,
  val cornerRadiusDp: Float = 16f,
)

sealed interface UiBuilderEditorEvent {
  data class SearchCatalog(val query: String) : UiBuilderEditorEvent

  data class SelectNode(val nodeId: String) : UiBuilderEditorEvent

  data class InsertComponent(val componentId: String, val target: ParentSlot) : UiBuilderEditorEvent

  data class MoveNode(
    val nodeId: String,
    val targetNodeId: String,
    val placeAfterTarget: Boolean,
  ) : UiBuilderEditorEvent

  data class CommitProperty(val nodeId: String, val property: String, val draft: String) :
    UiBuilderEditorEvent

  data class UpdateEnvironment(val settings: ScreenEnvironmentSettings) : UiBuilderEditorEvent

  data class ShowInspector(val mode: EditorInspectorMode) : UiBuilderEditorEvent

  data class ApplyTheme(val settings: EditorThemeSettings) : UiBuilderEditorEvent

  data object DeleteSelected : UiBuilderEditorEvent

  data object DuplicateSelected : UiBuilderEditorEvent

  data object Undo : UiBuilderEditorEvent

  data object Redo : UiBuilderEditorEvent
}

/** Pure editor interaction reducer. Every document mutation delegates to CollaborationReducer. */
sealed interface EditorSubmission {
  data class Batch(val command: DesignCommand) : EditorSubmission

  data class Undo(val command: UndoCommand) : EditorSubmission

  data class Redo(val command: RedoCommand) : EditorSubmission
}

class UiBuilderEditorReducer(
  private val catalog: CapabilityCatalog,
  private val actorId: String = EDITOR_ACTOR_ID,
  private val clientId: String = EDITOR_CLIENT_ID,
  private val operationIdPrefix: String = clientId,
) {
  private val capabilityValidator = CapabilityValidator(catalog)
  private val validator = CapabilityPropertyWriteValidator(capabilityValidator)
  private val documentValidator = CapabilityDocumentWriteValidator(capabilityValidator)

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
      is UiBuilderEditorEvent.CommitProperty ->
        commitProperty(state, event.nodeId, event.property, event.draft)
      is UiBuilderEditorEvent.UpdateEnvironment -> updateEnvironment(state, event.settings)
      is UiBuilderEditorEvent.ShowInspector -> state.copy(inspectorMode = event.mode)
      is UiBuilderEditorEvent.ApplyTheme -> applyTheme(state, event.settings)
      UiBuilderEditorEvent.DeleteSelected -> deleteSelected(state)
      UiBuilderEditorEvent.DuplicateSelected -> duplicateSelected(state)
      UiBuilderEditorEvent.Undo -> undo(state)
      UiBuilderEditorEvent.Redo -> redo(state)
    }

  fun acceptedSubmission(
    previous: UiBuilderEditorState,
    current: UiBuilderEditorState,
  ): EditorSubmission? {
    if (current.operationSequence == previous.operationSequence) return null
    if (current.lastOutcome !is CommandOutcome.Accepted) return null
    current.collaboration.acceptedCommands.keys
      .firstOrNull { it !in previous.collaboration.acceptedCommands }
      ?.let {
        return EditorSubmission.Batch(current.collaboration.acceptedCommands.getValue(it).command)
      }
    current.collaboration.undoRecords.keys
      .firstOrNull { it !in previous.collaboration.undoRecords }
      ?.let {
        return EditorSubmission.Undo(current.collaboration.undoRecords.getValue(it).command)
      }
    current.collaboration.redoRecords.keys
      .firstOrNull { it !in previous.collaboration.redoRecords }
      ?.let {
        return EditorSubmission.Redo(current.collaboration.redoRecords.getValue(it).command)
      }
    return null
  }

  fun canDeleteSelected(state: UiBuilderEditorState): Boolean {
    val nodeId = state.selectedNodeId?.takeIf(state.document.nodes::containsKey) ?: return false
    val parent = state.document.location(nodeId)
    if (parent == null) return state.document.roots.size > 1
    val parentNode = state.document.nodes.getValue(parent.nodeId)
    val minimum =
      catalog.componentsById[parentNode.componentId]
        ?.slotsByName
        ?.get(parent.slot)
        ?.cardinality
        ?.min ?: return false
    return parentNode.slots[parent.slot].orEmpty().size > minimum
  }

  fun canUndo(state: UiBuilderEditorState): Boolean = state.undoTargetOperationId(actorId) != null

  fun canRedo(state: UiBuilderEditorState): Boolean = state.redoTargetUndoId(actorId) != null

  fun canDuplicateSelected(state: UiBuilderEditorState): Boolean {
    val nodeId = state.selectedNodeId?.takeIf(state.document.nodes::containsKey) ?: return false
    val parent = state.document.location(nodeId) ?: return true
    val parentNode = state.document.nodes.getValue(parent.nodeId)
    val slot =
      catalog.componentsById[parentNode.componentId]?.slotsByName?.get(parent.slot) ?: return false
    return slot.cardinality.max?.let { parentNode.slots[parent.slot].orEmpty().size < it } ?: true
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

  fun propertyFields(state: UiBuilderEditorState): List<EditorPropertyField> {
    val node = state.selectedNodeId?.let(state.document.nodes::get) ?: return emptyList()
    val component = catalog.componentsById[node.componentId] ?: return emptyList()
    return component.properties
      .filterNot { it.name in THEME_PROPERTIES }
      .map { property ->
        val encoded = node.properties[property.name] as? JsonObject
        val value = encoded?.get("value")
        val typeNames = property.typeNames() - "null"
        val declaredControl = property.editor?.control
        val numberBounds = property.numberBounds(typeNames)
        val control =
          when {
            declaredControl == PropertyEditorControl.COLOR -> EditorPropertyControl.Color
            property.allowedValues.isNotEmpty() || declaredControl == PropertyEditorControl.ENUM ->
              EditorPropertyControl.Enum
            declaredControl == PropertyEditorControl.TEXT -> EditorPropertyControl.Text
            declaredControl == PropertyEditorControl.BOOLEAN || typeNames == setOf("boolean") ->
              EditorPropertyControl.Boolean
            (declaredControl == PropertyEditorControl.NUMBER ||
              typeNames == setOf("number") ||
              typeNames == setOf("integer")) && numberBounds != null -> EditorPropertyControl.Number
            typeNames == setOf("string") -> EditorPropertyControl.Text
            else -> EditorPropertyControl.Unsupported
          }
        EditorPropertyField(
          nodeId = node.id,
          name = property.name,
          label = property.name.humanLabel(),
          required = property.required,
          control = control,
          value = value?.jsonPrimitive?.content ?: "",
          choices =
            property.allowedValues.mapNotNull { it.jsonPrimitive.contentOrNull } +
              property.editor?.suggestedValues.orEmpty(),
          numberBounds = numberBounds,
          error = state.propertyErrors[EditorPropertyLocation(node.id, property.name)],
          notes = property.notes,
        )
      }
  }

  fun themeSettings(state: UiBuilderEditorState): EditorThemeSettings {
    val host = state.document.themeHost() ?: return EditorThemeSettings()
    return EditorThemeSettings(
      primaryColor = host.stringValue(THEME_PRIMARY, "#FFD0BCFF"),
      backgroundColor = host.stringValue(THEME_BACKGROUND, "#FF111318"),
      surfaceColor = host.stringValue(THEME_SURFACE, "#FF1D1F25"),
      contentColor = host.stringValue(THEME_CONTENT, "#FFE3E2E9"),
      typeScale = host.floatValue(THEME_TYPE_SCALE, 1f),
      cornerRadiusDp = host.floatValue(THEME_CORNER_RADIUS, 16f),
    )
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
    return state.apply(sequence, operations, selectionAfter = nodeId)
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

  private fun commitProperty(
    state: UiBuilderEditorState,
    nodeId: String,
    propertyName: String,
    draft: String,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val node = state.document.nodes[nodeId] ?: return state
    val property = catalog.componentsById[node.componentId]?.propertiesByName?.get(propertyName)
    if (property == null) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Property $propertyName is not declared by ${node.componentId}",
        nodeId,
        propertyName,
      )
    }
    val field =
      propertyFields(state.copy(selectedNodeId = nodeId)).firstOrNull { it.name == propertyName }
        ?: return state
    val parsed = field.parseDraft(draft)
    if (parsed is PropertyDraft.Invalid) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        parsed.message,
        nodeId,
        propertyName,
      )
    }
    val value = (parsed as PropertyDraft.Valid).value
    val existingType =
      (node.properties[propertyName] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    val encoded = literal(existingType ?: field.defaultEncodedType(), value)
    validator.validate(state.document, nodeId, propertyName, encoded)?.let { issue ->
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        issue.message,
        nodeId,
        propertyName,
      )
    }
    return state.apply(
      sequence,
      listOf(DesignOperation.SetProperty(nodeId, propertyName, encoded)),
      selectionAfter = nodeId,
    )
  }

  private fun updateEnvironment(
    state: UiBuilderEditorState,
    settings: ScreenEnvironmentSettings,
  ): UiBuilderEditorState {
    settings.validationError()?.let { message ->
      return state.rejected(
        state.operationSequence + 1,
        RejectionCode.INVALID_DOCUMENT,
        message,
      )
    }
    val values =
      linkedMapOf(
        "widthDp" to JsonPrimitive(settings.widthDp),
        "heightDp" to JsonPrimitive(settings.heightDp),
        "density" to JsonPrimitive(settings.density),
        "fontScale" to JsonPrimitive(settings.fontScale),
        "locale" to JsonPrimitive(settings.locale),
        "theme" to JsonPrimitive(settings.theme.wireValue),
        "layoutDirection" to JsonPrimitive(settings.layoutDirection.wireValue),
      )
    val operations = values.mapNotNull { (field, value) ->
      DesignOperation.SetEnvironment(field, value).takeIf {
        state.document.environment[field] != value
      }
    }
    if (operations.isEmpty()) return state
    return state.apply(
      state.operationSequence + 1,
      operations,
      selectionAfter = state.selectedNodeId,
    )
  }

  private fun applyTheme(
    state: UiBuilderEditorState,
    settings: EditorThemeSettings,
  ): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val host =
      state.document.themeHost()
        ?: return state.rejected(
          sequence,
          RejectionCode.INVALID_DOCUMENT,
          "A root Material surface is required to host design theme settings",
        )
    val colors =
      listOf(
        THEME_PRIMARY to settings.primaryColor,
        THEME_BACKGROUND to settings.backgroundColor,
        THEME_SURFACE to settings.surfaceColor,
        THEME_CONTENT to settings.contentColor,
      )
    colors
      .firstOrNull { !it.second.isArgbColor() }
      ?.let { invalid ->
        return state.rejected(
          sequence,
          RejectionCode.INVALID_PROPERTY,
          "${invalid.first} must be #RRGGBB or #AARRGGBB; received '${invalid.second}'",
        )
      }
    if (settings.typeScale !in 0.75f..1.5f) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Type scale must be between 0.75 and 1.5",
      )
    }
    if (settings.cornerRadiusDp !in 0f..48f) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_PROPERTY,
        "Corner radius must be between 0 and 48dp",
      )
    }
    val operations =
      colors.map { (property, value) ->
        DesignOperation.SetProperty(host.id, property, literal("color", JsonPrimitive(value)))
      } +
        DesignOperation.SetProperty(
          host.id,
          THEME_TYPE_SCALE,
          literal("float", JsonPrimitive(settings.typeScale)),
        ) +
        DesignOperation.SetProperty(
          host.id,
          THEME_CORNER_RADIUS,
          literal("float", JsonPrimitive(settings.cornerRadiusDp)),
        )
    return state.apply(sequence, operations, selectionAfter = state.selectedNodeId)
  }

  private fun deleteSelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val nodeId = state.selectedNodeId ?: return state
    if (!canDeleteSelected(state)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        "Deleting $nodeId would violate root or slot cardinality",
      )
    }
    val parent = state.document.location(nodeId)
    val selectionAfter = parent?.nodeId ?: state.document.roots.firstOrNull { it != nodeId }
    return state.apply(
      sequence = sequence,
      operations = listOf(DesignOperation.DeleteNode(nodeId)),
      selectionAfter = selectionAfter,
    )
  }

  private fun duplicateSelected(state: UiBuilderEditorState): UiBuilderEditorState {
    val sequence = state.operationSequence + 1
    val nodeId = state.selectedNodeId ?: return state
    if (!canDuplicateSelected(state)) {
      return state.rejected(
        sequence,
        RejectionCode.INVALID_DOCUMENT,
        "Duplicating $nodeId would exceed slot cardinality",
      )
    }
    val operations = mutableListOf<DesignOperation>()
    val copyId = "$nodeId-copy-${sequence.toString().padStart(3, '0')}"
    state.document.appendDuplicateSubtree(
      sourceNodeId = nodeId,
      copyNodeId = copyId,
      parent = state.document.location(nodeId),
      afterNodeId = nodeId,
      operations = operations,
    )
    return state.apply(sequence, operations, selectionAfter = copyId)
  }

  private fun undo(state: UiBuilderEditorState): UiBuilderEditorState {
    val targetOperationId = state.undoTargetOperationId(actorId) ?: return state
    val sequence = state.operationSequence + 1
    val application =
      CollaborationReducer.undo(
        state.collaboration,
        UndoCommand(
          designId = state.document.id,
          operationId = "$operationIdPrefix-editor-undo-${sequence.toString().padStart(4, '0')}",
          actorId = actorId,
          clientId = clientId,
          baseRevision = state.document.revision,
          targetOperationId = targetOperationId,
        ),
        documentValidator,
      )
    val selectedAfter =
      state.selectionBeforeOperations[targetOperationId]?.takeIf(
        application.state.document.nodes::containsKey
      ) ?: application.state.document.roots.firstOrNull()
    return state.withApplication(application, sequence, selectedAfter)
  }

  private fun redo(state: UiBuilderEditorState): UiBuilderEditorState {
    val targetUndoId = state.redoTargetUndoId(actorId) ?: return state
    val targetOperationId =
      state.collaboration.undoRecords.getValue(targetUndoId).target.command.operationId
    val sequence = state.operationSequence + 1
    val application =
      CollaborationReducer.redo(
        state.collaboration,
        RedoCommand(
          designId = state.document.id,
          operationId = "$operationIdPrefix-editor-redo-${sequence.toString().padStart(4, '0')}",
          actorId = actorId,
          clientId = clientId,
          baseRevision = state.document.revision,
          targetUndoOperationId = targetUndoId,
        ),
        documentValidator,
      )
    val selectedAfter =
      state.selectionAfterOperations[targetOperationId]?.takeIf(
        application.state.document.nodes::containsKey
      ) ?: application.state.document.roots.firstOrNull()
    return state.withApplication(application, sequence, selectedAfter)
  }

  private fun UiBuilderEditorState.apply(
    sequence: Int,
    operations: List<DesignOperation>,
    selectionAfter: String?,
  ): UiBuilderEditorState {
    val operationId = "$operationIdPrefix-editor-operation-${sequence.toString().padStart(4, '0')}"
    val command =
      DesignCommand(
        designId = document.id,
        operationId = operationId,
        actorId = actorId,
        clientId = clientId,
        baseRevision = document.revision,
        operations = operations,
      )
    val application =
      CollaborationReducer.apply(collaboration, command, validator, documentValidator)
    return withApplication(
      application = application,
      sequence = sequence,
      selectionAfter = selectionAfter,
      acceptedOperationId = operationId,
    )
  }

  private fun UiBuilderEditorState.withApplication(
    application: CommandApplication,
    sequence: Int,
    selectionAfter: String?,
    acceptedOperationId: String? = null,
  ): UiBuilderEditorState {
    val accepted = application.outcome is CommandOutcome.Accepted
    return copy(
      collaboration = application.state,
      selectedNodeId = if (accepted) selectionAfter else selectedNodeId,
      operationSequence = sequence,
      lastOutcome = application.outcome,
      propertyErrors =
        if (accepted) {
          val touched =
            application.state.acceptedCommands.values
              .maxByOrNull(AcceptedCommand::committedRevision)
              ?.command
              ?.operations
              ?.filterIsInstance<DesignOperation.SetProperty>()
              .orEmpty()
              .map { EditorPropertyLocation(it.nodeId, it.property) }
              .toSet()
          propertyErrors - touched
        } else propertyErrors,
      selectionBeforeOperations =
        if (accepted && acceptedOperationId != null)
          selectionBeforeOperations + (acceptedOperationId to selectedNodeId)
        else selectionBeforeOperations,
      selectionAfterOperations =
        if (accepted && acceptedOperationId != null)
          selectionAfterOperations + (acceptedOperationId to selectionAfter)
        else selectionAfterOperations,
    )
  }

  private fun UiBuilderEditorState.rejected(
    sequence: Int,
    code: RejectionCode,
    message: String,
    nodeId: String? = null,
    field: String? = null,
  ): UiBuilderEditorState =
    copy(
      operationSequence = sequence,
      lastOutcome =
        CommandOutcome.Rejected(code = code, message = message, nodeId = nodeId, field = field),
      propertyErrors =
        if (nodeId != null && field != null)
          propertyErrors + (EditorPropertyLocation(nodeId, field) to message)
        else propertyErrors,
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

internal const val THEME_PRIMARY = "themePrimaryColor"
internal const val THEME_BACKGROUND = "themeBackgroundColor"
internal const val THEME_SURFACE = "themeSurfaceColor"
internal const val THEME_CONTENT = "themeContentColor"
internal const val THEME_TYPE_SCALE = "themeTypeScale"
internal const val THEME_CORNER_RADIUS = "themeCornerRadiusDp"
private val THEME_PROPERTIES =
  setOf(
    THEME_PRIMARY,
    THEME_BACKGROUND,
    THEME_SURFACE,
    THEME_CONTENT,
    THEME_TYPE_SCALE,
    THEME_CORNER_RADIUS,
  )

private fun UiBuilderDocument.themeHost(): UiBuilderNode? =
  roots.asSequence().mapNotNull(nodes::get).firstOrNull { it.componentId == "m3/surface" }

private fun UiBuilderNode.stringValue(name: String, fallback: String): String =
  properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: fallback

private fun UiBuilderNode.floatValue(name: String, fallback: Float): Float =
  properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull?.toFloat() ?: fallback

private fun String.isArgbColor(): Boolean =
  startsWith("#") &&
    length in setOf(7, 9) &&
    drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

const val EDITOR_ACTOR_ID = "wasm-editor"
const val EDITOR_CLIENT_ID = "interactive-canvas"

private fun UiBuilderEditorState.undoTargetOperationId(actorId: String = EDITOR_ACTOR_ID): String? =
  collaboration.acceptedCommands.values
    .asSequence()
    .filter { it.command.actorId == actorId }
    .filter { it.command.operationId !in collaboration.compensatedOperationIds }
    .maxByOrNull(AcceptedCommand::committedRevision)
    ?.command
    ?.operationId

private fun UiBuilderEditorState.redoTargetUndoId(actorId: String = EDITOR_ACTOR_ID): String? =
  collaboration.undoRecords.values
    .asSequence()
    .filter { it.command.actorId == actorId && it.redoneBy == null }
    .maxByOrNull(AcceptedUndo::committedRevision)
    ?.command
    ?.operationId

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

private fun UiBuilderDocument.appendDuplicateSubtree(
  sourceNodeId: String,
  copyNodeId: String,
  parent: ParentSlot?,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
) {
  val source = nodes.getValue(sourceNodeId)
  operations +=
    DesignOperation.InsertNode(
      node = source.copy(id = copyNodeId, slots = source.slots.mapValues { emptyList() }),
      parent = parent,
      afterNodeId = afterNodeId,
    )
  source.slots.forEach { (slot, children) ->
    var previousCopyId: String? = null
    children.forEach { childId ->
      val childCopyId = "$copyNodeId-${childId.replace('/', '-')}"
      appendDuplicateSubtree(
        sourceNodeId = childId,
        copyNodeId = childCopyId,
        parent = ParentSlot(copyNodeId, slot),
        afterNodeId = previousCopyId,
        operations = operations,
      )
      previousCopyId = childCopyId
    }
  }
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

private sealed interface PropertyDraft {
  data class Valid(val value: JsonElement) : PropertyDraft

  data class Invalid(val message: String) : PropertyDraft
}

private fun EditorPropertyField.parseDraft(draft: String): PropertyDraft {
  return when (control) {
    EditorPropertyControl.Text -> PropertyDraft.Valid(JsonPrimitive(draft))
    EditorPropertyControl.Boolean ->
      draft.toBooleanStrictOrNull()?.let { PropertyDraft.Valid(JsonPrimitive(it)) }
        ?: PropertyDraft.Invalid("$label must be true or false")
    EditorPropertyControl.Enum ->
      if (draft in choices) PropertyDraft.Valid(JsonPrimitive(draft))
      else PropertyDraft.Invalid("$label must be one of ${choices.joinToString()}")
    EditorPropertyControl.Number -> {
      val bounds = numberBounds ?: return PropertyDraft.Invalid("$label has no safe editor range")
      val number = draft.toDoubleOrNull()
      when {
        number == null || !number.isFinite() -> PropertyDraft.Invalid("$label must be a number")
        bounds.integer && number % 1.0 != 0.0 ->
          PropertyDraft.Invalid("$label must be a whole number")
        number < bounds.minimum || number > bounds.maximum ->
          PropertyDraft.Invalid(
            "$label must be ${bounds.minimum.format()}..${bounds.maximum.format()}"
          )
        bounds.integer -> PropertyDraft.Valid(JsonPrimitive(number.toLong()))
        else -> PropertyDraft.Valid(JsonPrimitive(number))
      }
    }
    EditorPropertyControl.Color -> {
      val color = draft.trim()
      if (color.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) || color in choices)
        PropertyDraft.Valid(JsonPrimitive(color))
      else
        PropertyDraft.Invalid("$label must be #RRGGBB, #AARRGGBB, or a listed Material color token")
    }
    EditorPropertyControl.Unsupported ->
      PropertyDraft.Invalid("$label cannot be safely edited from its catalog metadata")
  }
}

private fun EditorPropertyField.defaultEncodedType(): String =
  when (control) {
    EditorPropertyControl.Text -> "string"
    EditorPropertyControl.Boolean -> "bool"
    EditorPropertyControl.Number -> if (numberBounds?.integer == true) "int" else "float"
    EditorPropertyControl.Enum -> if (name == "style") "typographyToken" else "enum"
    EditorPropertyControl.Color -> "color"
    EditorPropertyControl.Unsupported -> "string"
  }

private fun PropertyCapability.typeNames(): Set<String> =
  when (jsonType) {
    is JsonArray -> jsonType.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    else -> setOf(jsonType.jsonPrimitive.content)
  }

private fun PropertyCapability.numberBounds(typeNames: Set<String>): EditorNumberBounds? {
  if (typeNames != setOf("number") && typeNames != setOf("integer")) return null
  val editor = editor
  val minimum = editor?.minimum ?: return null
  val maximum = editor.maximum ?: return null
  val step = editor.step ?: if (typeNames == setOf("integer")) 1.0 else 0.1
  if (!minimum.isFinite() || !maximum.isFinite() || !step.isFinite()) return null
  if (minimum > maximum || step <= 0.0) return null
  return EditorNumberBounds(minimum, maximum, step, typeNames == setOf("integer"))
}

private fun String.humanLabel(): String =
  replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

private fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

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
