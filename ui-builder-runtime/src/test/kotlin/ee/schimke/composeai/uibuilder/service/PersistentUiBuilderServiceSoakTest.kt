package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.Closeable
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** Restart/reconnect soak against the production service and per-design file store. */
class PersistentUiBuilderServiceSoakTest {
  @TempDir lateinit var root: Path

  @Test
  fun `bounded three client persistence soak converges without acknowledged loss`() {
    val result =
      runSoak(
        operationLimit = FAST_OPERATION_COUNT,
        restartEvery = FAST_RESTART_EVERY,
      )

    assertEquals(FAST_OPERATION_COUNT, result.acceptedOperations)
    assertEquals(3, result.periodicRestarts)
    assertEquals(23, result.idempotentRetries)
    assertEquals(14, result.presencePublications)
    assertTrue(result.snapshotFallbacks > 0)
    assertTrue(result.staleWriteConflicts >= 4)
    println("bounded collaboration soak: $result")
  }

  @Test
  fun `opt in duration controlled three client persistence soak`() {
    val minutes = System.getProperty(SOAK_MINUTES_PROPERTY)?.toDoubleOrNull() ?: return
    require(minutes > 0.0) { "$SOAK_MINUTES_PROPERTY must be positive" }
    val durationNanos = (minutes * 60.0 * TimeUnit.SECONDS.toNanos(1)).toLong()
    require(durationNanos > 0) { "$SOAK_MINUTES_PROPERTY is too small" }
    val expectedOperations =
      (durationNanos / TimeUnit.MILLISECONDS.toNanos(LONG_OPERATION_PACE_MILLIS)).coerceAtLeast(1)
    val restartEvery = minOf(LONG_RESTART_EVERY, (expectedOperations / 2).coerceAtLeast(1).toInt())

    val result =
      runSoak(
        deadlineNanos = System.nanoTime() + durationNanos,
        restartEvery = restartEvery,
        paceMillis = LONG_OPERATION_PACE_MILLIS,
      )

    assertTrue(result.acceptedOperations > 0)
    assertTrue(result.periodicRestarts > 0, "duration soak must exercise restart recovery")
    assertTrue(result.snapshotFallbacks > 0, "duration soak must exercise snapshot fallback")
    assertTrue(result.staleWriteConflicts > 0, "duration soak must exercise stale edits")
    println("duration controlled collaboration soak: $result")
  }

  private fun runSoak(
    operationLimit: Int? = null,
    deadlineNanos: Long? = null,
    restartEvery: Int,
    paceMillis: Long = 0,
  ): SoakResult {
    require((operationLimit == null) != (deadlineNanos == null))
    val schedule = SeededSchedule(SEED)
    var service = service()
    val created =
      assertIs<UiBuilderServiceResponse.Snapshot>(
          execute(service, OWNER, UiBuilderServiceRequest.CreateDesign(document()))
        )
        .snapshot
    val clients =
      listOf(
        SoakClient("browser-a", "browser-a", created),
        SoakClient("browser-b", "browser-b", created),
        SoakClient("mcp", "mcp-c", created),
      )
    clients.forEach { it.connect(service) }

    val acknowledgedIds = mutableListOf<String>()
    var acceptedOperations = 0
    var periodicRestarts = 0
    var retries = 0
    var presencePublications = 0
    var staleWriteConflicts = 0
    val startedNanos = System.nanoTime()

    fun shouldContinue(): Boolean =
      operationLimit?.let { acceptedOperations < it }
        ?: (System.nanoTime() < assertNotNull(deadlineNanos))

    while (shouldContinue()) {
      val cycleOffset = acceptedOperations % CLIENT_CYCLE
      if (cycleOffset == DISCONNECT_AT) clients[1].disconnect()
      if (cycleOffset == RECONNECT_AT) clients[1].connect(service)

      val client =
        if (cycleOffset == FORCE_STALE_BROWSER_AT) clients[1]
        else clients.filter(SoakClient::connected).let { it[schedule.nextInt(it.size)] }
      val baseRevision =
        if (cycleOffset == FORCE_STALE_BROWSER_AT)
          (acceptedOperations - 1).coerceAtLeast(0).toLong()
        else client.revision
      val operationId = "soak-${acceptedOperations.toString().padStart(8, '0')}-${client.name}"
      val request =
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(
            DESIGN_ID,
            operationId,
            client.clientId,
            baseRevision,
            listOf(
              SetPropertyMutationV1(
                TEXT_NODE_ID,
                "text",
                StringValueV1("$operationId-${schedule.nextInt(10_000)}"),
              )
            ),
          )
        )
      val outcome = accepted(execute(service, OWNER, request))
      assertTrue(!outcome.idempotentReplay)
      acceptedOperations += 1
      assertEquals(acceptedOperations.toLong(), outcome.committedRevision)
      assertEquals(acceptedOperations.toLong(), outcome.sequence)
      staleWriteConflicts +=
        outcome.conflicts.count { it.code == ConflictCodeV1.STALE_PROPERTY_WRITE }
      acknowledgedIds += operationId

      if ((acceptedOperations - 1) % RETRY_EVERY == 0) {
        assertIdempotentRetry(service, request, acceptedOperations.toLong())
        retries += 1
      }

      if ((acceptedOperations - 1) % PRESENCE_EVERY == 0) {
        val before = snapshot(service)
        assertIs<UiBuilderServiceResponse.PresenceAccepted>(
          execute(
            service,
            OWNER,
            UiBuilderServiceRequest.UpdatePresence(
              DESIGN_ID,
              UiBuilderPresence(
                client.clientId,
                client.name,
                "#FF336699",
                listOf(TEXT_NODE_ID),
                null,
                null,
                before.state.document.revision,
              ),
            ),
          )
        )
        assertEquals(
          before.state,
          snapshot(service).state,
          "presence must not advance durable state",
        )
        presencePublications += 1
      }

      val hasMore =
        operationLimit?.let { acceptedOperations < it }
          ?: (System.nanoTime() < assertNotNull(deadlineNanos))
      if (hasMore && acceptedOperations % restartEvery == 0) {
        clients.forEach(SoakClient::suspendForRestart)
        service = service()
        val recovered = snapshot(service)
        assertEquals(acceptedOperations.toLong(), recovered.state.document.revision)
        assertEquals(acceptedOperations.toLong(), recovered.state.lastSequence)
        assertIdempotentRetry(service, request, acceptedOperations.toLong())
        retries += 1
        periodicRestarts += 1
        clients.filter(SoakClient::connected).forEach { it.connect(service) }
      }

      if (paceMillis > 0 && shouldContinue()) Thread.sleep(paceMillis)
    }

    val reconnectFallbacks = clients.sumOf(SoakClient::snapshotFallbacks)
    val finalSnapshot = snapshot(service)
    clients.forEach { client ->
      assertEquals(finalSnapshot.state, client.state, "${client.name} live state")
    }
    clients.forEach { it.connect(service, forceSnapshot = true) }
    clients.forEach { client ->
      assertEquals(finalSnapshot.state, client.state, "${client.name} recovered snapshot state")
      client.disconnect()
    }

    val recovery = FileUiBuilderDesignStore(root).load().designs.getValue(DESIGN_ID)
    assertEquals(
      acknowledgedIds.takeLast(RETAINED_UPDATES),
      recovery.history.map { it.outcome.operationId },
      "the retained acknowledgement window must be durable once and in order",
    )
    assertEquals(RETAINED_UPDATES, recovery.history.size, "retries and presence are not durable")
    assertEquals(acceptedOperations.toLong(), recovery.lastSequence)

    val finalRecovered = snapshot(service())
    assertEquals(finalSnapshot.state, finalRecovered.state)

    return SoakResult(
      acceptedOperations,
      periodicRestarts,
      retries,
      presencePublications,
      reconnectFallbacks,
      staleWriteConflicts,
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
      finalSnapshot.state.document.revision,
      finalSnapshot.state.lastSequence,
    )
  }

  private fun assertIdempotentRetry(
    service: PersistentUiBuilderService,
    request: UiBuilderServiceRequest.ApplyOperation,
    expectedSequence: Long,
  ) {
    val before = snapshot(service).state
    val retry = accepted(execute(service, OWNER, request))
    assertTrue(retry.idempotentReplay)
    assertEquals(expectedSequence, retry.sequence)
    assertEquals(before, snapshot(service).state)
  }

  private fun service(): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = TestCatalogs,
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.systemUTC(),
      limits =
        UiBuilderServiceLimits(
          retainedCommittedOperations = RETAINED_UPDATES,
          retainedRevisionSnapshots = RETAINED_UPDATES + 1,
        ),
    )

  private fun snapshot(service: PersistentUiBuilderService): ServiceSnapshotV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, OWNER, UiBuilderServiceRequest.OpenDesign(DESIGN_ID))
      )
      .snapshot

  private class SoakClient(
    val name: String,
    val clientId: String,
    initial: ServiceSnapshotV1,
  ) {
    var state: DesignStateV1 = initial.state
      private set

    var connected: Boolean = false
      private set

    var snapshotFallbacks: Int = 0
      private set

    private var subscription: Closeable? = null

    val revision: Long
      get() = state.document.revision

    fun connect(service: PersistentUiBuilderService, forceSnapshot: Boolean = false) {
      subscription?.close()
      connected = true
      subscription =
        service.subscribe(
          UiBuilderSubscriptionCall(
            OWNER,
            DESIGN_ID,
            state.lastSequence.takeUnless { forceSnapshot },
          )
        ) { update ->
          receive(update)
        }
    }

    fun disconnect() {
      connected = false
      subscription?.close()
      subscription = null
    }

    fun suspendForRestart() {
      subscription?.close()
      subscription = null
    }

    private fun receive(update: UiBuilderServiceUpdate) {
      when (update) {
        is UiBuilderServiceUpdate.Snapshot -> {
          snapshotFallbacks += 1
          state = update.snapshot.state
        }
        is UiBuilderServiceUpdate.Delta -> {
          update.delta.operations.forEach { committed ->
            val command = assertIs<DesignCommandV1>(committed.submission)
            var nodes = state.document.nodes
            command.operations.forEach { mutation ->
              val property = assertIs<SetPropertyMutationV1>(mutation)
              val node = nodes.getValue(property.nodeId)
              nodes =
                nodes +
                  (node.id to
                    node.copy(
                      properties =
                        if (property.value is NullValueV1) node.properties - property.property
                        else node.properties + (property.property to property.value)
                    ))
            }
            val outcome = committed.outcome
            state =
              state.copy(
                lastSequence = outcome.sequence,
                document =
                  state.document.copy(
                    revision = outcome.committedRevision,
                    updatedAtEpochMillis = assertNotNull(outcome.documentUpdatedAtEpochMillis),
                    nodes = nodes,
                  ),
              )
          }
        }
        is UiBuilderServiceUpdate.Outcome -> {
          val accepted = update.outcome as? AcceptedOutcomeV1 ?: return
          state =
            state.copy(
              lastSequence = accepted.sequence,
              document = state.document.copy(revision = accepted.committedRevision),
            )
        }
        is UiBuilderServiceUpdate.Presence -> Unit
      }
    }
  }

  private class SeededSchedule(seed: Long) {
    private var state = seed

    fun nextInt(bound: Int): Int {
      require(bound > 0)
      state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
      return ((state ushr 1) % bound).toInt()
    }
  }

  private data class SoakResult(
    val acceptedOperations: Int,
    val periodicRestarts: Int,
    val idempotentRetries: Int,
    val presencePublications: Int,
    val snapshotFallbacks: Int,
    val staleWriteConflicts: Int,
    val elapsedMillis: Long,
    val finalRevision: Long,
    val finalSequence: Long,
  )

  private object TestCatalogs : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      reference == CATALOG_REFERENCE
    }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
      CATALOG_REFERENCE.takeIf {
        catalog == CATALOG
      }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? = null
  }

  private companion object {
    const val SOAK_MINUTES_PROPERTY = "uiBuilderCollaborationSoakMinutes"
    const val DESIGN_ID = "collaboration-soak"
    const val TEXT_NODE_ID = "shared-text"
    const val FAST_OPERATION_COUNT = 96
    const val FAST_RESTART_EVERY = 24
    const val LONG_RESTART_EVERY = 600
    const val LONG_OPERATION_PACE_MILLIS = 100L
    const val RETAINED_UPDATES = 5
    const val CLIENT_CYCLE = 24
    const val DISCONNECT_AT = 3
    const val FORCE_STALE_BROWSER_AT = 4
    const val RECONNECT_AT = 12
    const val RETRY_EVERY = 5
    const val PRESENCE_EVERY = 7
    const val SEED = 0x5EED_C011_AB0AL
    val OWNER = AuthenticatedUiBuilderActor("owner")
    val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    val CATALOG =
      CatalogCapabilityV1.Builder(
          "compose-catalog-capabilities/v1",
          CatalogBenchmarkV1.Builder("m3", "source", "m3", "catalog", "m3-runtime").build(),
          listOf(
            ComponentCapabilityV1.Builder(
                "m3.Text",
                "Text",
                "text",
                WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED)
                  .build(),
              )
              .also {
                it.properties =
                  listOf(
                    PropertyCapabilityV1.Builder("text", JsonPrimitive("string"))
                      .also { property -> property.required = false }
                      .build()
                  )
              }
              .build()
          ),
        )
        .also {
          it.exportCapabilities =
            ExportCapabilitiesV1.Builder().also { exports -> exports.composeCode = true }.build()
        }
        .build()

    fun document(): DesignDocumentV1 =
      DesignDocumentV1(
        schema = "compose-ui-builder/v1",
        id = DESIGN_ID,
        title = "Collaboration soak",
        revision = 0,
        catalogPin = CATALOG_REFERENCE,
        environment =
          DesignEnvironmentV1(
            widthDp = 240,
            heightDp = 240,
            density = 1.0,
            theme = ThemeV1.DARK,
            locale = "en-GB",
            fontScale = 1.0,
            layoutDirection = LayoutDirectionV1.LTR,
          ),
        roots = listOf(TEXT_NODE_ID),
        nodes =
          mapOf(
            TEXT_NODE_ID to
              DesignNodeV1(
                TEXT_NODE_ID,
                "m3.Text",
                properties = mapOf("text" to StringValueV1("initial")),
              )
          ),
      )

    fun execute(
      service: PersistentUiBuilderService,
      actor: AuthenticatedUiBuilderActor,
      request: UiBuilderServiceRequest,
    ): UiBuilderServiceResponse = runSuspend {
      service.execute(UiBuilderServiceCall(actor, request))
    }

    fun accepted(response: UiBuilderServiceResponse): AcceptedOutcomeV1 =
      when (val outcome = assertIs<UiBuilderServiceResponse.OperationOutcome>(response).outcome) {
        is AcceptedOutcomeV1 -> outcome
        is RejectedOutcomeV1 -> error("${outcome.code}: ${outcome.message}")
      }

    fun <T> runSuspend(block: suspend () -> T): T {
      var completion: Result<T>? = null
      block.startCoroutine(
        object : Continuation<T> {
          override val context = EmptyCoroutineContext

          override fun resumeWith(result: Result<T>) {
            completion = result
          }
        }
      )
      return completion?.getOrThrow() ?: error("synchronous service call suspended unexpectedly")
    }
  }
}
