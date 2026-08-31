package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DeltaDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OutcomeDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceLeaveV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceDeltaV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiBuilderProtocolClientTest {
  @Test
  fun `HTTP requests use deterministic ids authenticated actor envelope and strict correlation`() {
    val transport = RecordingHttpTransport { request ->
      val envelope = decodeRequest(request.body)
      assertEquals("actor", envelope.actorId)
      HttpResponseEnvelopeV1(
          requestId = envelope.requestId,
          response = CatalogsResponseV1(emptyList()),
        )
        .encoded()
    }
    val client =
      UiBuilderProtocolHttpClient(
        actorId = "actor",
        endpoint = "/api/ui-builder/v1",
        transport = transport,
        requestIds = MonotonicUiBuilderRequestIds("browser"),
      )

    val first = runImmediate { client.execute(ListCatalogsRequestV1) }
    val second = runImmediate { client.execute(ListCatalogsRequestV1) }

    assertEquals("browser-00000001", first.requestId)
    assertEquals("browser-00000002", second.requestId)
    assertTrue(transport.requests.all { it.contentType == "application/json" })
    assertTrue(transport.requests.all { it.endpoint == "/api/ui-builder/v1" })
  }

  @Test
  fun `nested requester actor must match configured HTTP actor before transport`() {
    val transport = RecordingHttpTransport { error("transport must not be called") }
    val client =
      UiBuilderProtocolHttpClient(
        actorId = "trusted",
        endpoint = "/api/ui-builder/v1",
        transport = transport,
        requestIds = MonotonicUiBuilderRequestIds("browser"),
      )
    val forged =
      ApplyOperationRequestV1(DesignCommandV1("design", "op", "forged", "browser", 0, emptyList()))

    assertFailsWith<IllegalArgumentException> { runImmediate { client.execute(forged) } }
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun `HTTP responses correlate request id and map snapshot required distinctly`() {
    val mismatch = RecordingHttpTransport {
      HttpResponseEnvelopeV1(
          requestId = "other",
          response = CatalogsResponseV1(emptyList()),
        )
        .encoded()
    }
    val mismatchedClient = client(mismatch)
    assertFailsWith<UiBuilderProtocolException> {
      runImmediate { mismatchedClient.execute(ListCatalogsRequestV1) }
    }

    val snapshotRequired = RecordingHttpTransport { request ->
      val requestId = decodeRequest(request.body).requestId
      HttpResponseEnvelopeV1(
          requestId = requestId,
          response =
            ErrorResponseV1(
              ServiceErrorV1(
                code = ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
                message = "cursor compacted",
                currentRevision = 9,
                retainedFromSequence = 7,
              )
            ),
        )
        .encoded()
    }
    val result = runImmediate { client(snapshotRequired).execute(ListCatalogsRequestV1) }
    val mapped = assertIs<UiBuilderHttpResult.SnapshotRequired>(result)
    assertEquals(7, mapped.error.retainedFromSequence)
    assertEquals(9, mapped.error.currentRevision)
  }

  @Test
  fun `WebSocket maps every update and reconnects from exclusive durable cursor`() {
    val transport = RecordingWebSocketTransport()
    val updates = mutableListOf<UiBuilderClientUpdate>()
    val client =
      UiBuilderProtocolUpdateClient(
        designId = "design",
        endpoint = "/api/ui-builder/v1/updates",
        transport = transport,
        listener = updates::add,
      )

    client.connect()
    assertEquals(null, transport.opens.single().afterSequence)
    transport.emit(SnapshotDesignUpdateV1(snapshot(4)))
    transport.emit(PresenceDesignUpdateV1(PresenceLeaveV1("actor-b")))
    transport.emit(
      OutcomeDesignUpdateV1(
        RejectedOutcomeV1(
          operationId = "rejected",
          currentRevision = 4,
          code = RejectionCodeV1.INVALID_COMMAND,
          message = "invalid",
        )
      )
    )
    transport.emit(DeltaDesignUpdateV1(delta(after = 4, through = 5)))

    assertIs<UiBuilderClientUpdate.Snapshot>(updates[0])
    assertIs<UiBuilderClientUpdate.Presence>(updates[1])
    assertIs<UiBuilderClientUpdate.Outcome>(updates[2])
    assertIs<UiBuilderClientUpdate.Delta>(updates[3])
    assertEquals(5, client.afterSequence)

    client.reconnect()
    assertEquals(5, transport.opens.last().afterSequence)
    assertEquals(1, transport.closedConnections)
    client.close()
  }

  @Test
  fun `replayed and overlapping deltas deliver each durable operation once`() {
    val transport = RecordingWebSocketTransport()
    val updates = mutableListOf<UiBuilderClientUpdate>()
    val client =
      UiBuilderProtocolUpdateClient(
        designId = "design",
        endpoint = "ws://updates",
        initialAfterSequence = 4,
        transport = transport,
        listener = updates::add,
      )
    client.connect()

    val first = DeltaDesignUpdateV1(delta(after = 4, through = 6))
    transport.emit(first)
    transport.emit(first)
    transport.emit(DeltaDesignUpdateV1(delta(after = 5, through = 7)))

    val delivered = updates.map { assertIs<UiBuilderClientUpdate.Delta>(it).update.delta }
    assertEquals(2, delivered.size)
    assertEquals(listOf(5L, 6L), delivered[0].operations.map { it.outcome.sequence })
    assertEquals(6, delivered[1].afterSequence)
    assertEquals(listOf(7L), delivered[1].operations.map { it.outcome.sequence })
    assertEquals(7, client.afterSequence)
  }

  @Test
  fun `cursor gaps require one replacement snapshot before durable delivery resumes`() {
    val transport = RecordingWebSocketTransport()
    val updates = mutableListOf<UiBuilderClientUpdate>()
    val client =
      UiBuilderProtocolUpdateClient(
        designId = "design",
        endpoint = "ws://updates",
        initialAfterSequence = 4,
        transport = transport,
        listener = updates::add,
      )
    client.connect()

    val gap = DeltaDesignUpdateV1(delta(after = 8, through = 9, retainedFrom = 7))
    transport.emit(gap)
    transport.emit(gap)
    assertEquals(1, updates.size)
    val required = assertIs<UiBuilderClientUpdate.SnapshotRequired>(updates.single())
    assertEquals(4, required.afterSequence)
    assertEquals(8, required.receivedAfterSequence)
    assertEquals(7, required.retainedFromSequence)

    transport.emit(SnapshotDesignUpdateV1(snapshot(9)))
    transport.emit(SnapshotDesignUpdateV1(snapshot(9)))
    transport.emit(DeltaDesignUpdateV1(delta(after = 9, through = 10)))

    assertEquals(3, updates.size)
    assertIs<UiBuilderClientUpdate.Snapshot>(updates[1])
    assertIs<UiBuilderClientUpdate.Delta>(updates[2])
    assertEquals(10, client.afterSequence)
  }

  private fun client(transport: RecordingHttpTransport) =
    UiBuilderProtocolHttpClient(
      actorId = "actor",
      endpoint = "/api/ui-builder/v1",
      transport = transport,
      requestIds = MonotonicUiBuilderRequestIds("request"),
    )

  private fun snapshot(sequence: Long) =
    ServiceSnapshotV1(
      designId = "design",
      state = DesignStateV1(lastSequence = sequence, document = document(sequence)),
      catalog = catalog(),
      retainedFromSequence = sequence,
    )

  private fun delta(after: Long, through: Long, retainedFrom: Long = 0): ServiceDeltaV1 =
    ServiceDeltaV1(
      designId = "design",
      afterSequence = after,
      throughSequence = through,
      currentRevision = through,
      retainedFromSequence = retainedFrom,
      operations = ((after + 1)..through).map(::committed),
    )

  private fun committed(sequence: Long) =
    CommittedOperationV1(
      submission =
        DesignCommandV1(
          designId = "design",
          operationId = "op-$sequence",
          actorId = "actor",
          clientId = "browser",
          baseRevision = sequence - 1,
          operations = emptyList(),
        ),
      outcome =
        AcceptedOutcomeV1(
          operationId = "op-$sequence",
          committedRevision = sequence,
          sequence = sequence,
          documentHash = "hash-$sequence",
          idempotentReplay = false,
        ),
    )

  private fun document(revision: Long) =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Client test",
      revision = revision,
      catalogPin = CatalogReferenceV1("m3", "revision", "digest", "runtime"),
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-GB",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
        ),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private fun catalog() =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3", "revision", "runtime"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
    )

  private class RecordingHttpTransport(private val response: (UiBuilderHttpRequest) -> String) :
    UiBuilderHttpTransport {
    val requests = mutableListOf<UiBuilderHttpRequest>()

    override suspend fun post(request: UiBuilderHttpRequest): UiBuilderHttpResponse {
      requests += request
      return UiBuilderHttpResponse(200, response(request))
    }
  }

  private class RecordingWebSocketTransport : UiBuilderWebSocketTransport {
    val opens = mutableListOf<UiBuilderWebSocketRequest>()
    var closedConnections = 0
    private var receiver: ((String) -> Unit)? = null

    override fun open(
      request: UiBuilderWebSocketRequest,
      onTextMessage: (String) -> Unit,
    ): UiBuilderClientConnection {
      opens += request
      receiver = onTextMessage
      return UiBuilderClientConnection { closedConnections += 1 }
    }

    fun emit(update: ee.schimke.composeai.uibuilder.protocol.DesignUpdateV1) {
      val envelope = DesignUpdateEnvelopeV1(designId = "design", update = update)
      receiver?.invoke(protocolJson.encodeToString(DesignUpdateEnvelopeV1.serializer(), envelope))
        ?: error("socket is not open")
    }
  }

  private fun decodeRequest(encoded: String): HttpRequestEnvelopeV1 =
    protocolJson.decodeFromString(HttpRequestEnvelopeV1.serializer(), encoded)

  private fun HttpResponseEnvelopeV1.encoded(): String =
    protocolJson.encodeToString(HttpResponseEnvelopeV1.serializer(), this)

  private fun <T> runImmediate(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completed = result
        }
      }
    )
    return completed?.getOrThrow() ?: error("fake transport suspended unexpectedly")
  }
}
