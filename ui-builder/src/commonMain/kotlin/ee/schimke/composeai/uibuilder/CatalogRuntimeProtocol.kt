package ee.schimke.composeai.uibuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val CATALOG_RUNTIME_PROTOCOL_VERSION = 1
const val CATALOG_RUNTIME_PROTOCOL_SCHEMA = "compose-ui-builder-renderer/v1"

@Serializable
data class CatalogRuntimeMessage(
  val schema: String = CATALOG_RUNTIME_PROTOCOL_SCHEMA,
  val protocolVersion: Int,
  val runtimeId: String,
  val requestId: String,
  val type: String,
  val payload: JsonObject = JsonObject(emptyMap()),
)

sealed interface CatalogRuntimeCommand {
  data class Reply(val message: CatalogRuntimeMessage) : CatalogRuntimeCommand

  data class Render(val requestId: String, val document: UiBuilderDocument) : CatalogRuntimeCommand

  data class DispatchAction(val requestId: String, val action: CatalogRuntimeAction) :
    CatalogRuntimeCommand
}

@Serializable
data class CatalogRuntimeAction(
  val documentId: String,
  val documentRevision: Int,
  val nodeId: String,
  val kind: String,
  val deltaX: Double? = null,
  val deltaY: Double? = null,
)

private data class DocumentRef(val id: String, val revision: Int)

/**
 * Security and compatibility gate for a renderer frame.
 *
 * The browser adapter first verifies `MessageEvent.source === parent`; this state machine then
 * locks the first valid initialize request's exact origin. Messages from another source/origin are
 * ignored rather than reflected. Every accepted request is correlated by its caller-supplied id.
 */
class CatalogRuntimeProtocolEndpoint(
  private val runtimeId: String,
  private val protocolVersion: Int = CATALOG_RUNTIME_PROTOCOL_VERSION,
) {
  private var parentOrigin: String? = null
  private var activeDocument: DocumentRef? = null
  private var pendingRender: Pair<String, DocumentRef>? = null
  private val pendingActions = mutableMapOf<String, DocumentRef>()
  private val acceptedRequestIds = mutableSetOf<String>()

  fun receive(origin: String, sourceIsParent: Boolean, encoded: String): CatalogRuntimeCommand? {
    if (!sourceIsParent) return null
    val message =
      try {
        RUNTIME_PROTOCOL_JSON.decodeFromString(CatalogRuntimeMessage.serializer(), encoded)
      } catch (_: SerializationException) {
        return null
      } catch (_: IllegalArgumentException) {
        return null
      }
    if (
      message.schema != CATALOG_RUNTIME_PROTOCOL_SCHEMA ||
        message.runtimeId != runtimeId ||
        message.protocolVersion != protocolVersion ||
        message.requestId.isBlank()
    ) {
      return CatalogRuntimeCommand.Reply(
        message.reply(
          "error",
          buildJsonObject {
            put("code", "PROTOCOL_MISMATCH")
            put("message", "runtime identity or protocol does not match this renderer")
          },
        )
      )
    }
    val lockedOrigin = parentOrigin
    if (lockedOrigin != null && origin != lockedOrigin) return null
    if (!acceptedRequestIds.add(message.requestId)) {
      return CatalogRuntimeCommand.Reply(
        message.reply(
          "error",
          buildJsonObject {
            put("code", "DUPLICATE_REQUEST")
            put("message", "requestId has already been accepted")
          },
        )
      )
    }
    return when (message.type) {
      "initialize" -> {
        if (lockedOrigin == null) parentOrigin = origin
        CatalogRuntimeCommand.Reply(
          message.reply("initialized", buildJsonObject { put("interaction", "semantic-actions") })
        )
      }
      "renderDocument" -> {
        if (lockedOrigin == null) return null
        val document =
          try {
            RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(
              UiBuilderDocument.serializer(),
              message.payload.getValue("document"),
            )
          } catch (_: Exception) {
            return CatalogRuntimeCommand.Reply(
              message.reply(
                "error",
                buildJsonObject {
                  put("code", "INVALID_DOCUMENT")
                  put("message", "renderDocument payload is not a UI-builder document")
                },
              )
            )
          }
        if (document.catalogPin["nativeRuntimeId"]?.jsonPrimitive?.contentOrNull != runtimeId) {
          return CatalogRuntimeCommand.Reply(
            message.reply(
              "error",
              buildJsonObject {
                put("code", "RUNTIME_PIN_MISMATCH")
                put("message", "document is not pinned to this exact native runtime")
              },
            )
          )
        }
        activeDocument = null
        pendingActions.clear()
        pendingRender = message.requestId to DocumentRef(document.id, document.revision)
        CatalogRuntimeCommand.Render(message.requestId, document)
      }
      "dispatchAction" -> parseAction(message)
      else ->
        CatalogRuntimeCommand.Reply(
          message.reply(
            "error",
            buildJsonObject {
              put("code", "UNSUPPORTED_MESSAGE")
              put("message", "unsupported renderer request type")
            },
          )
        )
    }
  }

  fun rendered(requestId: String, snapshot: UiBuilderInspectionSnapshot): CatalogRuntimeMessage {
    val ref = DocumentRef(snapshot.documentId, snapshot.documentRevision)
    if (pendingRender == requestId to ref) {
      activeDocument = ref
      pendingRender = null
      return inspectionReply(requestId, "rendered", snapshot)
    }
    return actionRejected(
      requestId,
      "STALE_RENDER_COMPLETION",
      "render completion does not match the pending request and document revision",
    )
  }

  fun actionDispatched(
    requestId: String,
    snapshot: UiBuilderInspectionSnapshot,
  ): CatalogRuntimeMessage {
    val ref = DocumentRef(snapshot.documentId, snapshot.documentRevision)
    if (pendingActions.remove(requestId) == ref && activeDocument == ref) {
      return inspectionReply(requestId, "actionDispatched", snapshot)
    }
    return actionRejected(
      requestId,
      "STALE_ACTION_COMPLETION",
      "action completion does not match the accepted request and document revision",
    )
  }

  fun actionRejected(requestId: String, code: String, description: String): CatalogRuntimeMessage {
    pendingActions.remove(requestId)
    return CatalogRuntimeMessage(
      protocolVersion = protocolVersion,
      runtimeId = runtimeId,
      requestId = requestId,
      type = "error",
      payload =
        buildJsonObject {
          put("code", code)
          put("message", description)
        },
    )
  }

  private fun inspectionReply(
    requestId: String,
    type: String,
    snapshot: UiBuilderInspectionSnapshot,
  ): CatalogRuntimeMessage =
    CatalogRuntimeMessage(
      protocolVersion = protocolVersion,
      runtimeId = runtimeId,
      requestId = requestId,
      type = type,
      payload =
        buildJsonObject {
          put(
            "inspection",
            RUNTIME_PROTOCOL_JSON.encodeToJsonElement(
              UiBuilderInspectionSnapshot.serializer(),
              snapshot,
            ),
          )
        },
    )

  private fun parseAction(message: CatalogRuntimeMessage): CatalogRuntimeCommand {
    val current =
      activeDocument
        ?: return message.error("NO_DOCUMENT", "renderDocument must complete before dispatchAction")
    val action =
      try {
        RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(
          CatalogRuntimeAction.serializer(),
          message.payload,
        )
      } catch (_: Exception) {
        return message.error("INVALID_ACTION", "dispatchAction payload is malformed")
      }
    val target = DocumentRef(action.documentId, action.documentRevision)
    if (target != current) {
      return message.error("STALE_DOCUMENT", "action does not target the active document revision")
    }
    if (action.nodeId.isBlank() || action.nodeId.length > MAX_NODE_ID_LENGTH) {
      return message.error("INVALID_ACTION", "action nodeId is invalid")
    }
    val valid =
      when (action.kind) {
        "activate" -> action.deltaX == null && action.deltaY == null
        "scrollBy" ->
          action.deltaX == 0.0 &&
            action.deltaY?.isFinite() == true &&
            action.deltaY != 0.0 &&
            kotlin.math.abs(action.deltaY) <= MAX_SCROLL_DELTA
        else -> return message.error("UNSUPPORTED_ACTION", "action kind is not supported")
      }
    if (!valid) return message.error("INVALID_ACTION", "semantic action fields are invalid")
    pendingActions[message.requestId] = target
    return CatalogRuntimeCommand.DispatchAction(message.requestId, action)
  }

  fun encode(message: CatalogRuntimeMessage): String =
    RUNTIME_PROTOCOL_JSON.encodeToString(CatalogRuntimeMessage.serializer(), message)
}

/** Correlation/identity check used by editor-side adapters before applying measurements. */
class CatalogRuntimeHostSession(
  private val runtimeId: String,
  private val protocolVersion: Int = CATALOG_RUNTIME_PROTOCOL_VERSION,
  private val rendererOrigin: String = "null",
) {
  private sealed interface Pending {
    data object Initialize : Pending

    data class Inspection(val responseType: String, val document: DocumentRef) : Pending
  }

  private val pending = mutableMapOf<String, Pending>()

  fun request(
    requestId: String,
    type: String,
    payload: JsonObject = JsonObject(emptyMap()),
  ): String {
    require(requestId.isNotBlank() && requestId !in pending)
    pending[requestId] =
      when (type) {
        "initialize" -> Pending.Initialize
        "renderDocument" -> {
          val document =
            RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(
              UiBuilderDocument.serializer(),
              payload.getValue("document"),
            )
          Pending.Inspection("rendered", DocumentRef(document.id, document.revision))
        }
        "dispatchAction" -> {
          val action =
            RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(CatalogRuntimeAction.serializer(), payload)
          Pending.Inspection(
            "actionDispatched",
            DocumentRef(action.documentId, action.documentRevision),
          )
        }
        else -> error("unsupported runtime request: $type")
      }
    return RUNTIME_PROTOCOL_JSON.encodeToString(
      CatalogRuntimeMessage(
        protocolVersion = protocolVersion,
        runtimeId = runtimeId,
        requestId = requestId,
        type = type,
        payload = payload,
      )
    )
  }

  fun accept(origin: String, sourceIsRenderer: Boolean, encoded: String): CatalogRuntimeMessage? {
    if (!sourceIsRenderer || origin != rendererOrigin) return null
    val response =
      try {
        RUNTIME_PROTOCOL_JSON.decodeFromString(CatalogRuntimeMessage.serializer(), encoded)
      } catch (_: Exception) {
        return null
      }
    val expected = pending[response.requestId]
    if (
      response.schema != CATALOG_RUNTIME_PROTOCOL_SCHEMA ||
        response.protocolVersion != protocolVersion ||
        response.runtimeId != runtimeId ||
        expected == null
    ) {
      return null
    }
    if (response.type != "error") {
      when (expected) {
        Pending.Initialize -> if (response.type != "initialized") return null
        is Pending.Inspection -> {
          if (response.type != expected.responseType) return null
          val snapshot = validatedInspection(response.payload) ?: return null
          if (
            snapshot.documentId != expected.document.id ||
              snapshot.documentRevision != expected.document.revision
          ) {
            return null
          }
        }
      }
    }
    pending.remove(response.requestId)
    return response
  }

  private fun validatedInspection(payload: JsonObject): UiBuilderInspectionSnapshot? {
    val snapshot =
      try {
        RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(
          UiBuilderInspectionSnapshot.serializer(),
          payload.getValue("inspection"),
        )
      } catch (_: Exception) {
        return null
      }
    if (
      snapshot.schema != INSPECTION_SCHEMA ||
        snapshot.coordinateSpace != "root-render-pixels" ||
        snapshot.coordinatePrecision != "1/64px" ||
        snapshot.nodes.isEmpty() ||
        snapshot.nodes.size > MAX_INSPECTION_NODES ||
        snapshot.slots.size > MAX_INSPECTION_SLOTS ||
        snapshot.nodes.map { it.nodeId }.toSet().size != snapshot.nodes.size ||
        snapshot.generation.key != "${snapshot.documentId}@${snapshot.documentRevision}" ||
        snapshot.generation.stabilityFrames !in 1..MAX_STABILITY_FRAMES ||
        snapshot.generation.expectedAuthoredNodeIds.size > MAX_INSPECTION_NODES ||
        snapshot.generation.expectedAuthoredTextNodeIds.size > MAX_INSPECTION_NODES ||
        snapshot.generation.measuredNodeIds.size > MAX_INSPECTION_NODES ||
        snapshot.generation.measuredTextNodeIds.size > MAX_INSPECTION_NODES
    ) {
      return null
    }
    if (
      snapshot.nodes.any { node ->
        node.nodeId.isBlank() ||
          node.nodeId.length > MAX_NODE_ID_LENGTH ||
          node.semantics.actions.size > MAX_SEMANTIC_ACTIONS ||
          node.bounds?.isValid() == false ||
          node.text?.let { text ->
            text.lineCount < 0 ||
              !text.firstBaselineY.isFinite() ||
              !text.lastBaselineY.isFinite() ||
              kotlin.math.abs(text.firstBaselineY) > MAX_INSPECTION_COORDINATE ||
              kotlin.math.abs(text.lastBaselineY) > MAX_INSPECTION_COORDINATE
          } == true
      } ||
        snapshot.slots.any { slot ->
          slot.parentNodeId.isBlank() ||
            slot.childNodeIds.size > MAX_SLOT_CHILDREN ||
            slot.measuredChildNodeIds.size > MAX_SLOT_CHILDREN ||
            slot.bounds?.isValid() == false
        }
    ) {
      return null
    }
    return snapshot
  }
}

private fun UiBuilderPixelBounds.isValid(): Boolean =
  x.isFinite() &&
    y.isFinite() &&
    width.isFinite() &&
    height.isFinite() &&
    width >= 0f &&
    height >= 0f &&
    kotlin.math.abs(x) <= MAX_INSPECTION_COORDINATE &&
    kotlin.math.abs(y) <= MAX_INSPECTION_COORDINATE &&
    width <= MAX_INSPECTION_COORDINATE &&
    height <= MAX_INSPECTION_COORDINATE

private fun CatalogRuntimeMessage.reply(type: String, payload: JsonObject) =
  copy(type = type, payload = payload)

private fun CatalogRuntimeMessage.error(code: String, description: String) =
  CatalogRuntimeCommand.Reply(
    reply(
      "error",
      buildJsonObject {
        put("code", code)
        put("message", description)
      },
    )
  )

private const val MAX_NODE_ID_LENGTH = 512
private const val MAX_SCROLL_DELTA = 100_000.0
private const val INSPECTION_SCHEMA = "compose-ui-builder-inspection/v1"
private const val MAX_INSPECTION_NODES = 10_000
private const val MAX_INSPECTION_SLOTS = 20_000
private const val MAX_SLOT_CHILDREN = 10_000
private const val MAX_SEMANTIC_ACTIONS = 64
private const val MAX_STABILITY_FRAMES = 120
private const val MAX_INSPECTION_COORDINATE = 1_000_000f

internal val RUNTIME_PROTOCOL_JSON = Json {
  encodeDefaults = true
  ignoreUnknownKeys = false
  explicitNulls = false
}
