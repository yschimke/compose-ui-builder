package ee.schimke.composeai.uibuilder

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesignServiceSubscriptionConcurrencyTest {
  private val propertyValidator = CollaborationPropertyValidator { _, _, _, _ -> null }

  @Test
  fun `captured catch up is serialized before a concurrent live commit`() {
    val service = service()
    assertIs<CreateDesignResult.Created>(service.create(document()))
    accepted(service, command("first", 0, "one"))

    val callbackEntered = CountDownLatch(1)
    val releaseCallback = CountDownLatch(1)
    val delivered = CopyOnWriteArrayList<Long>()
    val activeCallbacks = AtomicInteger()
    val concurrentCallback = AtomicBoolean()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val subscription =
        executor.submit<Closeable?> {
          service.subscribe("design", 0) { update ->
            if (activeCallbacks.incrementAndGet() != 1) concurrentCallback.set(true)
            try {
              if (update.sequence == 1L) {
                callbackEntered.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
              }
              delivered += update.sequence
            } finally {
              activeCallbacks.decrementAndGet()
            }
          }
        }

      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
      val liveCommit =
        executor.submit<DesignSubmission> { service.apply(command("second", 1, "two")) }
      accepted(liveCommit.get(2, TimeUnit.SECONDS))
      assertTrue(delivered.isEmpty(), "the live callback must not overtake blocked catch-up")

      releaseCallback.countDown()
      assertNotNull(subscription.get(5, TimeUnit.SECONDS)).close()
      assertEquals(listOf(1L, 2L), delivered)
      assertFalse(concurrentCallback.get(), "one subscriber must never be called concurrently")
    } finally {
      releaseCallback.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `compaction snapshot remains ahead of a concurrent live commit`() {
    val service = service(retained = 2)
    assertIs<CreateDesignResult.Created>(service.create(document(revision = 10)))
    repeat(3) { index -> accepted(service, command("write-$index", 10 + index, "$index")) }

    val snapshotEntered = CountDownLatch(1)
    val releaseSnapshot = CountDownLatch(1)
    val delivered = CopyOnWriteArrayList<DesignServiceUpdate>()
    val executor = Executors.newFixedThreadPool(2)
    try {
      val subscription =
        executor.submit<Closeable?> {
          service.subscribe("design", 0) { update ->
            if (update is DesignServiceUpdate.Snapshot) {
              snapshotEntered.countDown()
              assertTrue(releaseSnapshot.await(5, TimeUnit.SECONDS))
            }
            delivered += update
          }
        }

      assertTrue(snapshotEntered.await(5, TimeUnit.SECONDS))
      val liveCommit =
        executor.submit<DesignSubmission> { service.apply(command("write-3", 13, "3")) }
      accepted(liveCommit.get(2, TimeUnit.SECONDS))
      assertTrue(delivered.isEmpty())

      releaseSnapshot.countDown()
      assertNotNull(subscription.get(5, TimeUnit.SECONDS)).close()
      assertIs<DesignServiceUpdate.Snapshot>(delivered[0])
      assertEquals(3L, delivered[0].sequence)
      assertIs<DesignServiceUpdate.Committed>(delivered[1])
      assertEquals(4L, delivered[1].sequence)
    } finally {
      releaseSnapshot.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `retry rejection and presence never advance or enter durable catch up`() {
    val service = service()
    assertIs<CreateDesignResult.Created>(service.create(document()))
    val live = mutableListOf<DesignServiceUpdate>()
    val subscription = assertNotNull(service.subscribe("design", 0, live::add))

    val write = command("write", 0, "accepted")
    accepted(service, write)
    val retry = assertIs<CommandOutcome.Accepted>(service.apply(write).application.outcome)
    assertTrue(retry.idempotentReplay)
    assertIs<CommandOutcome.Rejected>(
      service.apply(command("rejected", 99, "ignored")).application.outcome
    )
    assertFalse(service.publishPresence("missing", "actor", "browser"))
    assertFalse(service.publishPresence("design", "", "browser"))
    assertFalse(service.publishPresence("design", "actor", "browser", listOf("missing")))
    assertTrue(service.publishPresence("design", "actor", "browser", listOf("text")))

    val current = assertNotNull(service.open("design"))
    assertEquals(1L, current.sequence)
    assertEquals(1, current.revision)
    assertEquals(2, live.size)
    assertIs<DesignServiceUpdate.Committed>(live[0])
    val presence = assertIs<DesignServiceUpdate.Presence>(live[1])
    assertEquals(1L, presence.sequence)
    assertEquals(1, presence.revision)
    assertEquals(listOf("text"), presence.selectedNodeIds)

    val replay = mutableListOf<DesignServiceUpdate>()
    assertNotNull(service.subscribe("design", 0, replay::add)).close()
    assertEquals(1, replay.size)
    assertIs<DesignServiceUpdate.Committed>(replay.single())
    assertEquals(1L, replay.single().sequence)
    subscription.close()
  }

  private fun service(retained: Int = 1_024) =
    InMemoryDesignService(
      validationProvider =
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      retainedCommittedUpdates = retained,
    )

  private fun accepted(service: InMemoryDesignService, command: DesignCommand) {
    accepted(service.apply(command))
  }

  private fun accepted(submission: DesignSubmission) {
    assertIs<CommandOutcome.Accepted>(
      submission.application.outcome,
      "command was rejected: ${submission.application.outcome}",
    )
  }

  private fun command(operationId: String, revision: Int, value: String) =
    DesignCommand(
      designId = "design",
      operationId = operationId,
      actorId = "actor",
      clientId = "browser",
      baseRevision = revision,
      operations = listOf(DesignOperation.SetProperty("text", "text", literal(value))),
    )

  private fun document(revision: Int = 0) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Subscription ordering",
      revision = revision,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            UiBuilderNode(
              id = "text",
              componentId = "m3/text",
              properties = buildJsonObject { put("text", literal("initial")) },
            )
        ),
    )

  private fun literal(value: String) = buildJsonObject {
    put("type", "string")
    put("value", JsonPrimitive(value))
  }
}
