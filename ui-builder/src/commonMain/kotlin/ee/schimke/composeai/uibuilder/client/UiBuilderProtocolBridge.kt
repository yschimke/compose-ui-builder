package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.MoveNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.RemoveNodePropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.RemoveStateVariableMutationV1
import ee.schimke.composeai.uibuilder.protocol.ResetExportDevicesEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.RestoreNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.ServiceDeltaV1
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
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UpdateEnvironmentMutationV1
import ee.schimke.composeai.uibuilder.sha256Hex
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val bridgeJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}

// The authoritative JVM service hashes the complete serializable document, including explicit
// nulls. Keep renderer conversion permissive while matching that exact hash representation here.
private val documentHashJson = Json(bridgeJson) { explicitNulls = true }

/** Lossless for the renderer-owned subset; newer protocol-only metadata is deliberately ignored. */
fun DesignDocumentV1.toRendererDocument(): UiBuilderDocument {
  require(revision in 0..Int.MAX_VALUE.toLong()) { "design revision does not fit the renderer" }
  return bridgeJson.decodeFromString(bridgeJson.encodeToString(this))
}

/**
 * Creates a v1 service document from the renderer model used by deterministic fixture replay.
 *
 * Delegated to the shared conversion the seeding module owns, so the browser and the server turn a
 * candidate document into a service document by exactly one piece of code.
 */
fun UiBuilderDocument.toProtocolDocument(): DesignDocumentV1 = toDesignDocumentV1()

/**
 * Applies the authoritative pushed fast path used by live property editing.
 *
 * Structural edits and undo/redo deliberately fall back to a snapshot because the renderer model
 * does not retain the server's tombstones and compensation history. A property-only delta is safe
 * to reduce directly after the strict update client has established sequence continuity. Retaining
 * and reducing the authoritative protocol document avoids a lossy renderer round-trip; callers must
 * still verify [expectedHash] before displaying the projected renderer document. Outcomes written
 * before the authoritative document timestamp was added cannot reproduce the hashed document and
 * deliberately return null so the caller retains the snapshot fallback.
 */
/** One property write out of a committed batch; a null [value] is a removal. */
private data class PropertyWrite(val nodeId: String, val property: String, val value: UiValueV1?)

internal data class PropertyDeltaCandidate(
  val protocolDocument: DesignDocumentV1,
  val rendererDocument: UiBuilderDocument,
  val expectedHash: String,
) {
  fun hasVerifiedHash(): Boolean = protocolDocument.canonicalDocumentHash() == expectedHash
}

internal fun DesignDocumentV1.preparePropertyDelta(
  rendererDocument: UiBuilderDocument,
  delta: ServiceDeltaV1,
): PropertyDeltaCandidate? {
  if (delta.operations.isEmpty()) return null
  if (revision.toInt() != rendererDocument.revision) return null
  var protocol = this
  var renderer = rendererDocument
  delta.operations.forEach { committed ->
    val command = committed.submission as? DesignCommandV1 ?: return null
    // A property write in either spelling: a set, a null set, or the explicit removal. A removal
    // (#480) takes the property off both documents rather than storing a null the canvas would
    // then read as a value.
    val writes =
      command.operations.map { mutation ->
        when (mutation) {
          is SetPropertyMutationV1 ->
            PropertyWrite(
              mutation.nodeId,
              mutation.property,
              mutation.value.takeUnless { it is NullValueV1 },
            )
          is RemoveNodePropertyMutationV1 -> PropertyWrite(mutation.nodeId, mutation.property, null)
          else -> return null
        }
      }
    var protocolNodes = protocol.nodes
    var rendererNodes = renderer.nodes
    writes.forEach { write ->
      val protocolNode = protocolNodes[write.nodeId] ?: return null
      val rendererNode = rendererNodes[write.nodeId] ?: return null
      val value = write.value
      protocolNodes =
        protocolNodes +
          (protocolNode.id to
            protocolNode.copy(
              properties =
                if (value == null) protocolNode.properties - write.property
                else protocolNode.properties + (write.property to value)
            ))
      rendererNodes =
        rendererNodes +
          (rendererNode.id to
            rendererNode.copy(
              properties =
                kotlinx.serialization.json.JsonObject(
                  if (value == null) rendererNode.properties - write.property
                  else
                    rendererNode.properties +
                      (write.property to
                        bridgeJson.encodeToJsonElement(UiValueV1.serializer(), value))
                )
            ))
    }
    val revision = committed.outcome.committedRevision
    val updatedAtEpochMillis = committed.outcome.documentUpdatedAtEpochMillis ?: return null
    if (revision !in 0..Int.MAX_VALUE.toLong()) return null
    protocol =
      protocol.copy(
        revision = revision,
        updatedAtEpochMillis = updatedAtEpochMillis,
        nodes = protocolNodes,
      )
    renderer = renderer.copy(revision = revision.toInt(), nodes = rendererNodes)
  }
  if (protocol.revision != delta.currentRevision) return null
  return PropertyDeltaCandidate(
    protocolDocument = protocol,
    rendererDocument = renderer,
    expectedHash = delta.operations.last().outcome.documentHash,
  )
}

internal fun DesignDocumentV1.canonicalDocumentHash(): String {
  val element = documentHashJson.encodeToJsonElement(DesignDocumentV1.serializer(), this)
  return sha256Hex(canonicalProtocolJson(element))
}

private fun canonicalProtocolJson(element: JsonElement): String =
  when (element) {
    is kotlinx.serialization.json.JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${kotlinx.serialization.json.JsonPrimitive(key)}:${canonicalProtocolJson(value)}"
        }
    is kotlinx.serialization.json.JsonArray ->
      element.joinToString(",", "[", "]", transform = ::canonicalProtocolJson)
    is kotlinx.serialization.json.JsonPrimitive -> element.toString()
  }

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
    // Its own mutation since compose-preview-contracts 2.10.0 (#480); the server reads it as the
    // same removal a null `setProperty` is, which is what this sent before the wire could say it.
    is DesignOperation.RemoveNodeProperty -> RemoveNodePropertyMutationV1(nodeId, property)
    is DesignOperation.SetStateVariable ->
      SetStateVariableMutationV1(
        name,
        bridgeJson.decodeFromString(StateVariableV1.serializer(), declaration.toString()),
      )
    is DesignOperation.RemoveStateVariable -> RemoveStateVariableMutationV1(name)
    is DesignOperation.SetEventBinding ->
      SetEventBindingMutationV1(
        nodeId,
        event,
        actions.map { bridgeJson.decodeFromString(DesignActionV1.serializer(), it.toString()) },
      )
    is DesignOperation.SetModifiers ->
      SetModifiersMutationV1(
        nodeId,
        modifiers.map { bridgeJson.decodeFromString(DesignModifierV1.serializer(), it.toString()) },
      )
    is DesignOperation.SetEnvironment ->
      UpdateEnvironmentMutationV1(
        listOf(
          when (field) {
            "widthDp" -> SetWidthDpEnvironmentChangeV1(value.jsonPrimitive.int)
            "heightDp" -> SetHeightDpEnvironmentChangeV1(value.jsonPrimitive.int)
            "density" -> SetDensityEnvironmentChangeV1(value.jsonPrimitive.double)
            "fontScale" -> SetFontScaleEnvironmentChangeV1(value.jsonPrimitive.double)
            "locale" -> SetLocaleEnvironmentChangeV1(value.jsonPrimitive.content)
            "theme" ->
              SetThemeEnvironmentChangeV1(ThemeV1.valueOf(value.jsonPrimitive.content.uppercase()))
            "layoutDirection" ->
              SetLayoutDirectionEnvironmentChangeV1(
                LayoutDirectionV1.valueOf(value.jsonPrimitive.content.uppercase())
              )
            // Reset rather than an empty Set, because the protocol distinguishes them and the
            // document's own default is the empty set: clearing the picker should leave a design
            // saying exactly what one written before the field ever existed says.
            "exportDevices" ->
              value.jsonArray
                .map { it.jsonPrimitive.content }
                .let { devices ->
                  if (devices.isEmpty()) ResetExportDevicesEnvironmentChangeV1
                  else SetExportDevicesEnvironmentChangeV1(devices)
                }
            else -> error("unsupported editor environment field: $field")
          }
        )
      )
  }

private fun UiBuilderNode.toProtocolNode(): DesignNodeV1 =
  bridgeJson.decodeFromString(bridgeJson.encodeToString(this))

private fun ParentSlot?.toProtocol(): ParentSlotV1? = this?.let { ParentSlotV1(it.nodeId, it.slot) }
