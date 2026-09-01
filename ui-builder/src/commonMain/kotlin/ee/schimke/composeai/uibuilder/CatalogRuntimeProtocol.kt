package ee.schimke.composeai.uibuilder

import kotlin.math.absoluteValue
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

  data class DispatchInput(val requestId: String, val input: CatalogRuntimeInput) :
    CatalogRuntimeCommand
}

@Serializable
data class CatalogRuntimeInput(
  val documentRevision: Int,
  val kind: String,
  val phase: String? = null,
  val x: Double,
  val y: Double,
  val pointerId: Int? = null,
  val button: Int? = null,
  val buttons: Int? = null,
  val deltaMode: Int? = null,
  val deltaX: Double? = null,
  val deltaY: Double? = null,
)

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
  private var documentRevision: Int? = null
  private var pendingRender: Pair<String, Int>? = null
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
          message.reply("initialized", buildJsonObject { put("interaction", "pointer-wheel") })
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
        documentRevision = null
        pendingRender = message.requestId to document.revision
        CatalogRuntimeCommand.Render(message.requestId, document)
      }
      "dispatchInput" -> parseInput(message)
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
    if (pendingRender == requestId to snapshot.documentRevision) {
      documentRevision = snapshot.documentRevision
      pendingRender = null
    }
    return inspectionReply(requestId, "rendered", snapshot)
  }

  fun inputDispatched(
    requestId: String,
    snapshot: UiBuilderInspectionSnapshot,
  ): CatalogRuntimeMessage = inspectionReply(requestId, "inputDispatched", snapshot)

  fun inputRejected(requestId: String, code: String, description: String): CatalogRuntimeMessage =
    CatalogRuntimeMessage(
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

  private fun parseInput(message: CatalogRuntimeMessage): CatalogRuntimeCommand {
    val currentRevision =
      documentRevision
        ?: return message.error("NO_DOCUMENT", "renderDocument must complete before dispatchInput")
    val input =
      try {
        RUNTIME_PROTOCOL_JSON.decodeFromJsonElement(
          CatalogRuntimeInput.serializer(),
          message.payload,
        )
      } catch (_: Exception) {
        return message.error("INVALID_INPUT", "dispatchInput payload is malformed")
      }
    if (input.documentRevision != currentRevision) {
      return message.error("STALE_DOCUMENT", "input does not target the active document revision")
    }
    if (!input.x.isFinite() || !input.y.isFinite() || input.x < 0 || input.y < 0) {
      return message.error("INVALID_INPUT", "input coordinates must be finite and non-negative")
    }
    return when (input.kind) {
      "pointer" -> {
        if (
          input.phase !in POINTER_PHASES ||
            input.pointerId == null ||
            input.pointerId !in 0..MAX_POINTER_ID ||
            input.button == null ||
            input.button !in -1..4 ||
            input.buttons == null ||
            input.buttons !in 0..31 ||
            input.deltaMode != null ||
            input.deltaX != null ||
            input.deltaY != null
        ) {
          message.error("INVALID_INPUT", "pointer input fields are invalid")
        } else CatalogRuntimeCommand.DispatchInput(message.requestId, input)
      }
      "wheel" -> {
        if (
          input.phase != null ||
            input.pointerId != null ||
            input.button != null ||
            input.buttons != null ||
            input.deltaMode != PIXEL_DELTA_MODE ||
            input.deltaX == null ||
            input.deltaY == null ||
            !input.deltaX.isFinite() ||
            !input.deltaY.isFinite() ||
            input.deltaX.absoluteValue > MAX_WHEEL_DELTA ||
            input.deltaY.absoluteValue > MAX_WHEEL_DELTA
        ) {
          message.error("INVALID_INPUT", "wheel input fields are invalid")
        } else CatalogRuntimeCommand.DispatchInput(message.requestId, input)
      }
      else -> message.error("UNSUPPORTED_INPUT", "input kind is not supported")
    }
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
  private val pending = mutableMapOf<String, String>()

  fun request(
    requestId: String,
    type: String,
    payload: JsonObject = JsonObject(emptyMap()),
  ): String {
    require(requestId.isNotBlank() && requestId !in pending)
    pending[requestId] = type
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
    val expectedType = pending[response.requestId]
    val expectedResponseType =
      when (expectedType) {
        "initialize" -> "initialized"
        "renderDocument" -> "rendered"
        "dispatchInput" -> "inputDispatched"
        else -> null
      }
    if (
      response.schema != CATALOG_RUNTIME_PROTOCOL_SCHEMA ||
        response.protocolVersion != protocolVersion ||
        response.runtimeId != runtimeId ||
        expectedType == null ||
        (response.type != "error" && response.type != expectedResponseType)
    ) {
      return null
    }
    pending.remove(response.requestId)
    return response
  }
}

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

private val POINTER_PHASES = setOf("down", "move", "up", "cancel")
private const val MAX_POINTER_ID = 1024
private const val PIXEL_DELTA_MODE = 0
private const val MAX_WHEEL_DELTA = 100_000.0

internal val RUNTIME_PROTOCOL_JSON = Json {
  encodeDefaults = true
  ignoreUnknownKeys = false
  explicitNulls = false
}
