package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.MoveNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.RestoreNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val bridgeJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}

/** Lossless for the renderer-owned subset; newer protocol-only metadata is deliberately ignored. */
fun DesignDocumentV1.toRendererDocument(): UiBuilderDocument {
  require(revision in 0..Int.MAX_VALUE.toLong()) { "design revision does not fit the renderer" }
  return bridgeJson.decodeFromString(bridgeJson.encodeToString(this))
}

/** Creates a v1 service document from the renderer model used by deterministic fixture replay. */
fun UiBuilderDocument.toProtocolDocument(): DesignDocumentV1 =
  bridgeJson.decodeFromString(bridgeJson.encodeToString(this))

fun EditorSubmission.toProtocolSubmission(
  actorId: String,
  clientId: String,
  authoritativeRevision: Int,
): DesignSubmissionV1 =
  when (this) {
    is EditorSubmission.Batch ->
      DesignCommandV1(
        designId = command.designId,
        operationId = command.operationId,
        actorId = actorId,
        clientId = clientId,
        baseRevision = authoritativeRevision.toLong(),
        operations = command.operations.map(DesignOperation::toProtocolMutation),
      )
    is EditorSubmission.Undo ->
      UndoCommandV1(
        designId = command.designId,
        operationId = command.operationId,
        actorId = actorId,
        clientId = clientId,
        baseRevision = authoritativeRevision.toLong(),
        targetOperationId = command.targetOperationId,
      )
    is EditorSubmission.Redo ->
      RedoCommandV1(
        designId = command.designId,
        operationId = command.operationId,
        actorId = actorId,
        clientId = clientId,
        baseRevision = authoritativeRevision.toLong(),
        targetUndoOperationId = command.targetUndoOperationId,
      )
  }

private fun DesignOperation.toProtocolMutation(): DesignMutationV1 =
  when (this) {
    is DesignOperation.InsertNode ->
      InsertNodeMutationV1(node.toProtocolNode(), NodeLocationV1(parent.toProtocol(), afterNodeId))
    is DesignOperation.MoveNode ->
      MoveNodeMutationV1(nodeId, NodeLocationV1(parent.toProtocol(), afterNodeId))
    is DesignOperation.DeleteNode -> DeleteNodeMutationV1(nodeId)
    is DesignOperation.RestoreNode -> RestoreNodeMutationV1(nodeId)
    is DesignOperation.SetProperty ->
      SetPropertyMutationV1(
        nodeId,
        property,
        bridgeJson.decodeFromString(UiValueV1.serializer(), value.toString()),
      )
  }

private fun UiBuilderNode.toProtocolNode(): DesignNodeV1 =
  bridgeJson.decodeFromString(bridgeJson.encodeToString(this))

private fun ParentSlot?.toProtocol(): ParentSlotV1? = this?.let { ParentSlotV1(it.nodeId, it.slot) }
