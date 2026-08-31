package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.DeltaDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OutcomeDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import kotlinx.serialization.json.Json

/** One browser-capable HTTP exchange without binding common code to a networking implementation. */
data class UiBuilderHttpRequest(
  val endpoint: String,
  val contentType: String = "application/json",
  val body: String,
)

data class UiBuilderHttpResponse(val statusCode: Int, val body: String)

fun interface UiBuilderHttpTransport {
  suspend fun post(request: UiBuilderHttpRequest): UiBuilderHttpResponse
}

fun interface UiBuilderRequestIdGenerator {
  fun nextRequestId(): String
}

/** Single-client monotonic request IDs, deterministic across tests and one browser session. */
class MonotonicUiBuilderRequestIds(
  private val prefix: String,
  initialSequence: Long = 1,
) : UiBuilderRequestIdGenerator {
  private var nextSequence = initialSequence

  init {
    require(prefix.isNotBlank()) { "request id prefix must not be blank" }
    require(initialSequence >= 0) { "initial request sequence must not be negative" }
  }

  override fun nextRequestId(): String {
    check(nextSequence != Long.MAX_VALUE) { "request id sequence exhausted" }
    val requestId = "$prefix-${nextSequence.toString().padStart(8, '0')}"
    nextSequence += 1
    return requestId
  }
}

sealed interface UiBuilderHttpResult {
  val requestId: String

  data class Response(
    override val requestId: String,
    val response: UiBuilderResponseV1,
  ) : UiBuilderHttpResult

  data class SnapshotRequired(
    override val requestId: String,
    val error: ServiceErrorV1,
  ) : UiBuilderHttpResult

  data class ServiceError(
    override val requestId: String,
    val error: ServiceErrorV1,
  ) : UiBuilderHttpResult
}

class UiBuilderProtocolException(message: String) : IllegalStateException(message)

/** Strict v1 HTTP envelope codec and response correlator shared by JVM tests and Wasm callers. */
class UiBuilderProtocolHttpClient(
  private val actorId: String,
  private val endpoint: String,
  private val transport: UiBuilderHttpTransport,
  private val requestIds: UiBuilderRequestIdGenerator,
) {
  init {
    require(actorId.isNotBlank()) { "actor id must not be blank" }
    require(endpoint.isNotBlank()) { "HTTP endpoint must not be blank" }
  }

  suspend fun execute(request: UiBuilderRequestV1): UiBuilderHttpResult {
    request.requesterActorId()?.let { nestedActor ->
      require(nestedActor == actorId) {
        "nested requester actor $nestedActor does not match configured actor $actorId"
      }
    }
    val requestId = requestIds.nextRequestId()
    val envelope =
      HttpRequestEnvelopeV1(requestId = requestId, actorId = actorId, request = request)
    val encoded = protocolJson.encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
    val raw = transport.post(UiBuilderHttpRequest(endpoint = endpoint, body = encoded))
    val response =
      try {
        protocolJson.decodeFromString(HttpResponseEnvelopeV1.serializer(), raw.body)
      } catch (failure: Exception) {
        throw UiBuilderProtocolException(
          "invalid UI-builder HTTP response for $requestId (status ${raw.statusCode}): " +
            (failure.message ?: failure::class.simpleName)
        )
      }
    if (response.requestId != requestId) {
      throw UiBuilderProtocolException(
        "UI-builder response ${response.requestId} does not correlate to request $requestId"
      )
    }
    val payload = response.response
    return when {
      payload is ErrorResponseV1 && payload.error.code == ServiceErrorCodeV1.SNAPSHOT_REQUIRED ->
        UiBuilderHttpResult.SnapshotRequired(requestId, payload.error)
      payload is ErrorResponseV1 -> UiBuilderHttpResult.ServiceError(requestId, payload.error)
      else -> UiBuilderHttpResult.Response(requestId, payload)
    }
  }
}

data class UiBuilderWebSocketRequest(
  val endpoint: String,
  val designId: String,
  /** Exclusive cursor: the server must send only durable events with a larger sequence. */
  val afterSequence: Long?,
)

fun interface UiBuilderClientConnection {
  fun close()
}

fun interface UiBuilderWebSocketTransport {
  fun open(
    request: UiBuilderWebSocketRequest,
    onTextMessage: (String) -> Unit,
  ): UiBuilderClientConnection
}

sealed interface UiBuilderClientUpdate {
  data class Snapshot(val update: SnapshotDesignUpdateV1) : UiBuilderClientUpdate

  data class Delta(val update: DeltaDesignUpdateV1) : UiBuilderClientUpdate

  data class Presence(val update: PresenceDesignUpdateV1) : UiBuilderClientUpdate

  data class Outcome(val update: OutcomeDesignUpdateV1) : UiBuilderClientUpdate

  /** Local recovery signal when a pushed durable delta is not contiguous with the cursor. */
  data class SnapshotRequired(
    val designId: String,
    val afterSequence: Long?,
    val receivedAfterSequence: Long,
    val retainedFromSequence: Long,
  ) : UiBuilderClientUpdate
}

/**
 * Strict pushed-update decoder with an exclusive durable cursor.
 *
 * The injected transport can be backed by browser `WebSocket`, Ktor, or a deterministic fake. Only
 * snapshots and deltas advance the cursor. Presence and outcomes remain non-durable. Replayed and
 * overlapping deltas are suppressed or trimmed before delivery.
 */
class UiBuilderProtocolUpdateClient(
  private val designId: String,
  private val endpoint: String,
  initialAfterSequence: Long? = null,
  private val transport: UiBuilderWebSocketTransport,
  private val listener: (UiBuilderClientUpdate) -> Unit,
) : UiBuilderClientConnection {
  private var connection: UiBuilderClientConnection? = null
  private var snapshotPending = false

  var afterSequence: Long? = initialAfterSequence
    private set

  init {
    require(designId.isNotBlank()) { "design id must not be blank" }
    require(endpoint.isNotBlank()) { "WebSocket endpoint must not be blank" }
    require(initialAfterSequence == null || initialAfterSequence >= 0) {
      "initial sequence must not be negative"
    }
  }

  fun connect() {
    check(connection == null) { "UI-builder update client is already connected" }
    connection =
      transport.open(
        UiBuilderWebSocketRequest(endpoint, designId, afterSequence),
        ::receive,
      )
  }

  fun reconnect() {
    connection?.close()
    connection = null
    connect()
  }

  override fun close() {
    connection?.close()
    connection = null
  }

  private fun receive(encoded: String) {
    val envelope =
      try {
        protocolJson.decodeFromString(DesignUpdateEnvelopeV1.serializer(), encoded)
      } catch (failure: Exception) {
        throw UiBuilderProtocolException(
          "invalid UI-builder update: ${failure.message ?: failure::class.simpleName}"
        )
      }
    if (envelope.designId != designId) {
      throw UiBuilderProtocolException(
        "update for ${envelope.designId} received on design $designId subscription"
      )
    }
    when (val update = envelope.update) {
      is SnapshotDesignUpdateV1 -> receiveSnapshot(update)
      is DeltaDesignUpdateV1 -> receiveDelta(update)
      is PresenceDesignUpdateV1 -> listener(UiBuilderClientUpdate.Presence(update))
      is OutcomeDesignUpdateV1 -> listener(UiBuilderClientUpdate.Outcome(update))
    }
  }

  private fun receiveSnapshot(update: SnapshotDesignUpdateV1) {
    if (update.snapshot.designId != designId) {
      throw UiBuilderProtocolException(
        "snapshot for ${update.snapshot.designId} received on design $designId subscription"
      )
    }
    val sequence = update.snapshot.state.lastSequence
    val current = afterSequence
    if (current != null && sequence < current) {
      throw UiBuilderProtocolException(
        "snapshot sequence $sequence would rewind design $designId cursor $current"
      )
    }
    if (!snapshotPending && current != null && sequence <= current) return
    afterSequence = sequence
    snapshotPending = false
    listener(UiBuilderClientUpdate.Snapshot(update))
  }

  private fun receiveDelta(update: DeltaDesignUpdateV1) {
    val delta = update.delta
    if (delta.designId != designId) {
      throw UiBuilderProtocolException(
        "delta for ${delta.designId} received on design $designId subscription"
      )
    }
    if (snapshotPending) return
    val current = afterSequence
    if (current == null || delta.afterSequence > current) {
      snapshotPending = true
      listener(
        UiBuilderClientUpdate.SnapshotRequired(
          designId = designId,
          afterSequence = current,
          receivedAfterSequence = delta.afterSequence,
          retainedFromSequence = delta.retainedFromSequence,
        )
      )
      return
    }
    if (delta.throughSequence <= current) return

    val unseen = delta.operations.filter { it.outcome.sequence > current }
    if (!isContiguous(current, delta.throughSequence, unseen.map { it.outcome.sequence })) {
      snapshotPending = true
      listener(
        UiBuilderClientUpdate.SnapshotRequired(
          designId = designId,
          afterSequence = current,
          receivedAfterSequence = delta.afterSequence,
          retainedFromSequence = delta.retainedFromSequence,
        )
      )
      return
    }
    val delivered =
      if (delta.afterSequence == current) update
      else DeltaDesignUpdateV1(delta.copy(afterSequence = current, operations = unseen))
    afterSequence = delta.throughSequence
    listener(UiBuilderClientUpdate.Delta(delivered))
  }

  private fun isContiguous(current: Long, through: Long, sequences: List<Long>): Boolean {
    if (through == current) return sequences.isEmpty()
    if (sequences.isEmpty()) return false
    var expected = current + 1
    sequences.forEach { sequence ->
      if (sequence != expected) return false
      expected += 1
    }
    return expected - 1 == through
  }
}

private fun UiBuilderRequestV1.requesterActorId(): String? =
  when (this) {
    is ApplyOperationRequestV1 -> submission.requesterActorId()
    is UpdatePresenceRequestV1 -> presence.actorId
    else -> null
  }

private fun DesignSubmissionV1.requesterActorId(): String =
  when (this) {
    is DesignCommandV1 -> actorId
    is UndoCommandV1 -> actorId
    is RedoCommandV1 -> actorId
  }

internal val protocolJson = Json {
  classDiscriminator = "type"
  encodeDefaults = false
  explicitNulls = false
  ignoreUnknownKeys = false
}
