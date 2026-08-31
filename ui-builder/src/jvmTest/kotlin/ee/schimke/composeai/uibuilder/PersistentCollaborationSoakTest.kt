package ee.schimke.composeai.uibuilder

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PersistentCollaborationSoakTest {
  private val propertyValidator = CollaborationPropertyValidator { _, _, _, _ -> null }

  @Test
  fun `bounded three client persistence soak converges without acknowledged loss`() {
    val result = withTemporaryStore { root ->
      runSoak(
        root = root,
        seed = SEED,
        operationLimit = FAST_OPERATION_COUNT,
        restartEvery = FAST_RESTART_EVERY,
      )
    }

    assertEquals(FAST_OPERATION_COUNT, result.acceptedOperations)
    assertEquals(3, result.periodicRestarts)
    assertEquals(23, result.idempotentRetries)
    assertEquals(14, result.presencePublications)
    assertEquals(4, result.snapshotFallbacks)
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

    val result = withTemporaryStore { root ->
      runSoak(
        root = root,
        seed = SEED,
        deadlineNanos = System.nanoTime() + durationNanos,
        restartEvery = restartEvery,
        paceMillis = LONG_OPERATION_PACE_MILLIS,
      )
    }

    assertTrue(result.acceptedOperations > 0)
    assertTrue(result.periodicRestarts > 0, "duration soak must exercise restart recovery")
    assertTrue(result.snapshotFallbacks > 0, "duration soak must exercise snapshot fallback")
    assertTrue(
      result.staleWriteConflicts > 0,
      "duration soak must exercise overlapping stale edits",
    )
    println("duration controlled collaboration soak: $result")
  }

  private fun runSoak(
    root: Path,
    seed: Long,
    operationLimit: Int? = null,
    deadlineNanos: Long? = null,
    restartEvery: Int,
    paceMillis: Long = 0,
  ): SoakResult {
    require((operationLimit == null) != (deadlineNanos == null))
    val schedule = SeededSchedule(seed)
    var service = service(root)
    val created = assertIs<CreateDesignResult.Created>(service.create(document()))
    val clients =
      listOf(
        SoakClient("browser-a", "actor-a", "browser-a", created.snapshot),
        SoakClient("browser-b", "actor-b", "browser-b", created.snapshot),
        SoakClient("mcp", "agent-c", "mcp-c", created.snapshot),
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
        else clients[schedule.nextInt(clients.size)]
      val operationId = "soak-${acceptedOperations.toString().padStart(8, '0')}-${client.name}"
      val command =
        DesignCommand(
          designId = DESIGN_ID,
          operationId = operationId,
          actorId = client.actorId,
          clientId = client.clientId,
          baseRevision = client.revision,
          operations =
            listOf(
              DesignOperation.SetProperty(
                nodeId = TEXT_NODE_ID,
                property = "text",
                value = literal("$operationId-${schedule.nextInt(10_000)}"),
              )
            ),
        )
      val submission = service.apply(command)
      val outcome = assertIs<CommandOutcome.Accepted>(submission.application.outcome)
      assertTrue(!outcome.idempotentReplay)
      val update = assertNotNull(submission.update)
      acceptedOperations += 1
      assertEquals(acceptedOperations, outcome.committedRevision)
      assertEquals(acceptedOperations.toLong(), update.sequence)
      assertEquals(sha256Hex(outcome.canonicalDocument), update.documentHash)
      staleWriteConflicts +=
        outcome.conflicts.count { it.code == ConflictCode.STALE_PROPERTY_WRITE }
      acknowledgedIds += operationId

      if ((acceptedOperations - 1) % RETRY_EVERY == 0) {
        assertIdempotentRetry(service, command, acceptedOperations)
        retries += 1
      }

      if ((acceptedOperations - 1) % PRESENCE_EVERY == 0) {
        val beforePresence = assertNotNull(service.open(DESIGN_ID))
        assertTrue(
          service.publishPresence(
            DESIGN_ID,
            client.actorId,
            client.clientId,
            listOf(TEXT_NODE_ID),
          )
        )
        val afterPresence = assertNotNull(service.open(DESIGN_ID))
        assertEquals(beforePresence, afterPresence, "presence must not advance durable state")
        presencePublications += 1
      }

      val hasMore =
        operationLimit?.let { acceptedOperations < it }
          ?: (System.nanoTime() < assertNotNull(deadlineNanos))
      if (hasMore && acceptedOperations % restartEvery == 0) {
        clients.forEach(SoakClient::suspendForRestart)
        service = service(root)
        val recovered = assertNotNull(service.open(DESIGN_ID))
        assertEquals(acceptedOperations, recovered.revision)
        assertEquals(acceptedOperations.toLong(), recovered.sequence)
        assertEquals(update.documentHash, recovered.documentHash)
        assertIdempotentRetry(service, command, acceptedOperations)
        retries += 1
        periodicRestarts += 1
        clients.filter(SoakClient::connected).forEach { it.connect(service) }
      }

      if (paceMillis > 0 && shouldContinue()) Thread.sleep(paceMillis)
    }

    clients.forEach { it.connect(service) }
    val finalSnapshot = assertNotNull(service.open(DESIGN_ID))
    clients.forEach { client ->
      assertEquals(finalSnapshot.revision, client.revision, "${client.name} revision")
      assertEquals(finalSnapshot.sequence, client.sequence, "${client.name} sequence")
      assertEquals(finalSnapshot.documentHash, client.documentHash, "${client.name} hash")
    }
    clients.forEach(SoakClient::disconnect)

    val recovery = assertNotNull(FileDesignStore(root).load(DESIGN_ID))
    val durableAcceptedIds =
      recovery.events.mapNotNull { event ->
        if (event.outcome is CommandOutcome.Accepted) event.mutation.operationId else null
      }
    assertEquals(acknowledgedIds, durableAcceptedIds, "every acknowledgement must be durable once")
    assertEquals(acceptedOperations, recovery.events.size, "retries and presence are not durable")

    val finalRestart = service(root)
    val finalRecovered = assertNotNull(finalRestart.open(DESIGN_ID))
    assertEquals(finalSnapshot.revision, finalRecovered.revision)
    assertEquals(finalSnapshot.sequence, finalRecovered.sequence)
    assertEquals(finalSnapshot.documentHash, finalRecovered.documentHash)

    return SoakResult(
      acceptedOperations = acceptedOperations,
      periodicRestarts = periodicRestarts,
      idempotentRetries = retries,
      presencePublications = presencePublications,
      snapshotFallbacks = clients.sumOf(SoakClient::snapshotFallbacks),
      staleWriteConflicts = staleWriteConflicts,
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
      finalRevision = finalSnapshot.revision,
      finalSequence = finalSnapshot.sequence,
      finalDocumentHash = finalSnapshot.documentHash,
    )
  }

  private fun assertIdempotentRetry(
    service: PersistentDesignService,
    command: DesignCommand,
    expectedSequence: Int,
  ) {
    val before = assertNotNull(service.open(DESIGN_ID))
    val retry = service.apply(command)
    val outcome = assertIs<CommandOutcome.Accepted>(retry.application.outcome)
    assertTrue(outcome.idempotentReplay)
    assertEquals(null, retry.update)
    val after = assertNotNull(service.open(DESIGN_ID))
    assertEquals(before, after)
    assertEquals(expectedSequence.toLong(), after.sequence)
  }

  private fun service(root: Path) =
    PersistentDesignService(
      store = FileDesignStore(root),
      validationProvider =
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      retainedCommittedUpdates = RETAINED_UPDATES,
    )

  private fun document() =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = DESIGN_ID,
      title = "Collaboration soak",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(TEXT_NODE_ID),
      nodes =
        mapOf(
          TEXT_NODE_ID to
            UiBuilderNode(
              id = TEXT_NODE_ID,
              componentId = "m3/text",
              properties = buildJsonObject { put("text", literal("initial")) },
            )
        ),
    )

  private fun literal(value: String) = buildJsonObject {
    put("type", "string")
    put("value", JsonPrimitive(value))
  }

  private inline fun <T> withTemporaryStore(block: (Path) -> T): T {
    val root = Files.createTempDirectory("ui-builder-collaboration-soak")
    return try {
      block(root)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private class SoakClient(
    val name: String,
    val actorId: String,
    val clientId: String,
    initial: DesignServiceUpdate.Snapshot,
  ) {
    var revision: Int = initial.revision
    var sequence: Long = initial.sequence
    var documentHash: String = initial.documentHash
    var connected: Boolean = false
      private set

    var snapshotFallbacks: Int = 0
    private var subscription: Closeable? = null

    fun connect(service: PersistentDesignService) {
      subscription?.close()
      connected = true
      subscription =
        assertNotNull(service.subscribe(DESIGN_ID, sequence) { update -> receive(update) })
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

    private fun receive(update: DesignServiceUpdate) {
      when (update) {
        is DesignServiceUpdate.Snapshot -> snapshotFallbacks += 1
        is DesignServiceUpdate.Committed -> assertEquals(sequence + 1, update.sequence)
        is DesignServiceUpdate.Presence -> {
          assertEquals(sequence, update.sequence)
          assertEquals(revision, update.revision)
          assertEquals(documentHash, update.documentHash)
          return
        }
      }
      revision = update.revision
      sequence = update.sequence
      documentHash = update.documentHash
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
    val finalRevision: Int,
    val finalSequence: Long,
    val finalDocumentHash: String,
  )

  companion object {
    const val SOAK_MINUTES_PROPERTY = "uiBuilderCollaborationSoakMinutes"
    private const val DESIGN_ID = "collaboration-soak"
    private const val TEXT_NODE_ID = "shared-text"
    private const val FAST_OPERATION_COUNT = 96
    private const val FAST_RESTART_EVERY = 24
    private const val LONG_RESTART_EVERY = 600
    private const val LONG_OPERATION_PACE_MILLIS = 100L
    private const val RETAINED_UPDATES = 5
    private const val CLIENT_CYCLE = 24
    private const val DISCONNECT_AT = 3
    private const val FORCE_STALE_BROWSER_AT = 4
    private const val RECONNECT_AT = 12
    private const val RETRY_EVERY = 5
    private const val PRESENCE_EVERY = 7
    private const val SEED = 0x5EED_C011_AB0AL
  }
}
