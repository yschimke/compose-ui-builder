package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.RedoCommand
import ee.schimke.composeai.uibuilder.UndoCommand
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.EnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.MoveNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.RemoveNodePropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.RemoveStateVariableMutationV1
import ee.schimke.composeai.uibuilder.protocol.ResetExportDevicesEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.RestoreNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetDensityEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetEventBindingMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetExportDevicesEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetFontScaleEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetHeightDpEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetLayoutDirectionEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetLocaleEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetModifiersMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetStateVariableMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetThemeEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetWidthDpEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.StateVariableV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UpdateEnvironmentMutationV1
import ee.schimke.composeai.uibuilder.toUiBuilderNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

/**
 * `EditorSubmission.toProtocolSubmission` read backwards, for the session that lives in the page.
 *
 * The editor speaks the released v1 wire shape to *its own* service in local mode, exactly as it
 * does to the server, so that one client — the drain loop, the revision bookkeeping, the deferral
 * rules in `UiBuilderLiveSessionSync` — serves both modes rather than being written twice. The
 * price of that reuse is this file: something has to turn the wire spelling back into the reducer's
 * own commands, because the reducer is what actually applies them.
 *
 * Deliberately partial. The wire carries mutations the editor has no way to emit — a catalog
 * upgrade — and the honest answer for those is a refusal naming the mutation, not a silent no-op
 * that would commit a revision doing nothing. When the editor learns to author one, it gains a case
 * here in the same change.
 */
internal sealed interface LocalSubmissionMapping {
  data class Mapped(val record: LocalSubmissionRecordV1) : LocalSubmissionMapping

  data class Rejected(val message: String) : LocalSubmissionMapping
}

internal fun DesignSubmissionV1.toLocalSubmission(): LocalSubmissionMapping =
  when (this) {
    is DesignCommandV1 -> {
      val operations = mutableListOf<DesignOperation>()
      var refusal: String? = null
      for (mutation in this.operations) {
        when (val mapped = mutation.toDesignOperations()) {
          is MutationMapping.Mapped -> operations += mapped.operations
          is MutationMapping.Rejected -> {
            refusal = mapped.message
            break
          }
        }
      }
      refusal?.let { LocalSubmissionMapping.Rejected(it) }
        ?: LocalSubmissionMapping.Mapped(
          LocalSubmissionRecordV1.Batch(
            DesignCommand(
              designId = designId,
              operationId = operationId,
              actorId = actorId,
              clientId = clientId,
              baseRevision = baseRevision.toRevision(),
              operations = operations,
            )
          )
        )
    }
    is UndoCommandV1 ->
      LocalSubmissionMapping.Mapped(
        LocalSubmissionRecordV1.Undo(
          UndoCommand(
            designId = designId,
            operationId = operationId,
            actorId = actorId,
            clientId = clientId,
            baseRevision = baseRevision.toRevision(),
            targetOperationId = targetOperationId,
          )
        )
      )
    is RedoCommandV1 ->
      LocalSubmissionMapping.Mapped(
        LocalSubmissionRecordV1.Redo(
          RedoCommand(
            designId = designId,
            operationId = operationId,
            actorId = actorId,
            clientId = clientId,
            baseRevision = baseRevision.toRevision(),
            targetUndoOperationId = targetUndoOperationId,
          )
        )
      )
  }

private sealed interface MutationMapping {
  data class Mapped(val operations: List<DesignOperation>) : MutationMapping

  data class Rejected(val message: String) : MutationMapping
}

private fun DesignMutationV1.toDesignOperations(): MutationMapping =
  when (this) {
    is InsertNodeMutationV1 ->
      location.reject()
        ?: MutationMapping.Mapped(
          listOf(
            DesignOperation.InsertNode(
              node = node.toUiBuilderNode(),
              parent = location.parent.toReducer(),
              afterNodeId = location.afterNodeId,
            )
          )
        )
    is MoveNodeMutationV1 ->
      location.reject()
        ?: MutationMapping.Mapped(
          listOf(
            DesignOperation.MoveNode(
              nodeId = nodeId,
              parent = location.parent.toReducer(),
              afterNodeId = location.afterNodeId,
            )
          )
        )
    is DeleteNodeMutationV1 -> MutationMapping.Mapped(listOf(DesignOperation.DeleteNode(nodeId)))
    // The reducer restores a tombstone to the location it retained; an override is a shape the
    // editor never sends and this session would silently ignore.
    is RestoreNodeMutationV1 ->
      if (location != null) unsupported("restoreNode with an explicit location")
      else MutationMapping.Mapped(listOf(DesignOperation.RestoreNode(nodeId)))
    is SetPropertyMutationV1 ->
      MutationMapping.Mapped(
        listOf(
          DesignOperation.SetProperty(
            nodeId = nodeId,
            property = property,
            value = localBridgeJson.encodeToJsonElement(UiValueV1.serializer(), value),
          )
        )
      )
    is RemoveNodePropertyMutationV1 ->
      MutationMapping.Mapped(listOf(DesignOperation.RemoveNodeProperty(nodeId, property)))
    is SetStateVariableMutationV1 ->
      MutationMapping.Mapped(
        listOf(
          DesignOperation.SetStateVariable(
            name,
            localBridgeJson
              .encodeToJsonElement(StateVariableV1.serializer(), declaration)
              .jsonObject,
          )
        )
      )
    is RemoveStateVariableMutationV1 ->
      MutationMapping.Mapped(listOf(DesignOperation.RemoveStateVariable(name)))
    is SetEventBindingMutationV1 ->
      MutationMapping.Mapped(
        listOf(
          DesignOperation.SetEventBinding(
            nodeId,
            event,
            JsonArray(
              actions.map { localBridgeJson.encodeToJsonElement(DesignActionV1.serializer(), it) }
            ),
          )
        )
      )
    is SetModifiersMutationV1 ->
      MutationMapping.Mapped(
        listOf(
          DesignOperation.SetModifiers(
            nodeId = nodeId,
            modifiers =
              JsonArray(
                modifiers.map {
                  localBridgeJson.encodeToJsonElement(DesignModifierV1.serializer(), it)
                }
              ),
          )
        )
      )
    // One reducer operation per field: the wire batches a design's environment changes into one
    // mutation, and the reducer versions them one field at a time so two authors editing different
    // fields do not conflict.
    is UpdateEnvironmentMutationV1 -> {
      val operations = mutableListOf<DesignOperation>()
      var refusal: String? = null
      for (change in changes) {
        val operation = change.toDesignOperation()
        if (operation == null) {
          refusal = "environment change ${change.field} is not supported by the local session"
          break
        }
        operations += operation
      }
      refusal?.let { MutationMapping.Rejected(it) } ?: MutationMapping.Mapped(operations)
    }
    else -> unsupported(this::class.simpleName ?: "mutation")
  }

private fun EnvironmentChangeV1.toDesignOperation(): DesignOperation.SetEnvironment? =
  when (this) {
    is SetWidthDpEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("widthDp", JsonPrimitive(value))
    is SetHeightDpEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("heightDp", JsonPrimitive(value))
    is SetDensityEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("density", JsonPrimitive(value))
    is SetFontScaleEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("fontScale", JsonPrimitive(value))
    is SetLocaleEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("locale", JsonPrimitive(value))
    is SetThemeEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("theme", JsonPrimitive(value.name.lowercase()))
    is SetLayoutDirectionEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("layoutDirection", JsonPrimitive(value.name.lowercase()))
    is SetExportDevicesEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment(
        "exportDevices",
        buildJsonArray { value.forEach { add(JsonPrimitive(it)) } },
      )
    // The reducer's own spelling of "export at this design's frame alone" is the empty list, which
    // is exactly what the forward direction turns back into this reset.
    ResetExportDevicesEnvironmentChangeV1 ->
      DesignOperation.SetEnvironment("exportDevices", JsonArray(emptyList()))
    else -> null
  }

/**
 * `beforeNodeId` is a wire anchor the editor never sends and the reducer's location has no field
 * for. Accepting it by dropping it would move a node somewhere other than where it was asked to go.
 */
private fun NodeLocationV1.reject(): MutationMapping.Rejected? =
  if (beforeNodeId != null) unsupported("a beforeNodeId location anchor") else null

private fun unsupported(what: String): MutationMapping.Rejected =
  MutationMapping.Rejected("the local session cannot apply $what")

private fun ParentSlotV1?.toReducer(): ParentSlot? = this?.let { ParentSlot(it.nodeId, it.slot) }

/**
 * The wire carries revisions as `Long` and the reducer counts them as `Int`, which is the same
 * narrowing `DesignDocumentV1.toRendererDocument` requires of a document.
 */
private fun Long.toRevision(): Int {
  require(this in 0..Int.MAX_VALUE.toLong()) { "design revision does not fit the reducer" }
  return toInt()
}

/** Matches the forward bridge's codec, so a value survives the round trip it is half of. */
private val localBridgeJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}
