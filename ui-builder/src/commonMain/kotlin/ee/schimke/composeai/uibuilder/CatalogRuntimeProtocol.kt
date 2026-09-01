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
}

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
          message.reply("initialized", buildJsonObject { put("interaction", "unsupported") })
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
        CatalogRuntimeCommand.Render(message.requestId, document)
      }
      "dispatchInput" ->
        CatalogRuntimeCommand.Reply(
          message.reply(
            "error",
            buildJsonObject {
              put("code", "UNSUPPORTED_INPUT")
              put("message", "this renderer protocol version is render-and-measure only")
            },
          )
        )
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

  fun rendered(requestId: String, snapshot: UiBuilderInspectionSnapshot): CatalogRuntimeMessage =
    CatalogRuntimeMessage(
      protocolVersion = protocolVersion,
      runtimeId = runtimeId,
      requestId = requestId,
      type = "rendered",
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

internal val RUNTIME_PROTOCOL_JSON = Json {
  encodeDefaults = true
  ignoreUnknownKeys = false
  explicitNulls = false
}
