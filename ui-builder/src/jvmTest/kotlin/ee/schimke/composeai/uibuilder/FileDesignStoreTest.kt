package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class FileDesignStoreTest {
  private val propertyValidator = CollaborationPropertyValidator { _, _, _, _ -> null }

  @Test
  fun `acknowledged accepted and rejected operations replay after restart`() {
    val root = Files.createTempDirectory("ui-builder-store")
    val store = FileDesignStore(root)
    val initial = document()
    store.create(initial)
    val acceptedCommand = command("accepted", 0, literal("saved"))
    val accepted =
      CollaborationReducer.apply(CollaborationState(initial), acceptedCommand, propertyValidator)
    assertIs<CommandOutcome.Accepted>(accepted.outcome)
    store.append(
      "design",
      CollaborationEvent(RejectedMutation.Design(acceptedCommand), accepted.outcome),
    )

    val rejectedCommand = command("rejected", 99, literal("ignored"))
    val rejected = CollaborationReducer.apply(accepted.state, rejectedCommand, propertyValidator)
    assertIs<CommandOutcome.Rejected>(rejected.outcome)
    store.append(
      "design",
      CollaborationEvent(RejectedMutation.Design(rejectedCommand), rejected.outcome),
    )

    val recovered = assertNotNull(FileDesignStore(root).load("design"))
    assertEquals(0, recovered.ignoredPartialTailBytes)
    assertEquals(0L, recovered.snapshotLastSequence)
    assertEquals(listOf("accepted", "rejected"), recovered.events.map { it.mutation.operationId })
    val replayed =
      CollaborationReducer.replayEvents(
        CollaborationState(recovered.initialDocument),
        recovered.events,
        propertyValidator,
      )
    assertEquals(rejected.state, replayed.state)
    assertEquals(rejected.outcome, replayed.outcome)
  }

  @Test
  fun `partial tail is reported and last acknowledged record survives`() {
    val root = Files.createTempDirectory("ui-builder-store-tail")
    val store = FileDesignStore(root)
    val initial = document()
    store.create(initial)
    val command = command("accepted", 0, literal("saved"))
    val applied =
      CollaborationReducer.apply(CollaborationState(initial), command, propertyValidator)
    store.append("design", CollaborationEvent(RejectedMutation.Design(command), applied.outcome))
    Files.writeString(
      root.resolve("design/events.jsonl"),
      "{\"interrupted\":",
      StandardOpenOption.APPEND,
    )

    val recovered = assertNotNull(FileDesignStore(root).load("design"))
    assertEquals(1, recovered.events.size)
    assertTrue(recovered.ignoredPartialTailBytes > 0)
    val replayed =
      CollaborationReducer.replayEvents(
        CollaborationState(recovered.initialDocument),
        recovered.events,
        propertyValidator,
      )
    assertEquals(1, replayed.state.document.revision)
  }

  @Test
  fun `checksum corruption in an acknowledged record fails closed`() {
    val root = Files.createTempDirectory("ui-builder-store-corrupt")
    val store = FileDesignStore(root)
    val initial = document()
    store.create(initial)
    val command = command("accepted", 0, literal("saved"))
    val applied =
      CollaborationReducer.apply(CollaborationState(initial), command, propertyValidator)
    store.append("design", CollaborationEvent(RejectedMutation.Design(command), applied.outcome))
    val events = root.resolve("design/events.jsonl")
    Files.writeString(events, Files.readString(events).replace("saved", "tampered"))

    assertFailsWith<DesignStoreCorruptionException> { FileDesignStore(root).load("design") }
  }

  @Test
  fun `identity and create operations are bounded to their design directory`() {
    val root = Files.createTempDirectory("ui-builder-store-identity")
    val store = FileDesignStore(root)
    store.create(document())

    assertEquals(listOf("design"), store.listDesignIds())
    assertFailsWith<IllegalStateException> { store.create(document()) }
    assertFailsWith<IllegalArgumentException> { store.load("../escape") }
    assertFailsWith<IllegalArgumentException> {
      store.append(
        "missing",
        CollaborationEvent(
          RejectedMutation.Design(command("x", 0, literal("x"))),
          CommandOutcome.Rejected(RejectionCode.DESIGN_MISMATCH, "missing"),
        ),
      )
    }
    assertNull(store.load("not-created"))
  }

  @Test
  fun `snapshot and event quotas reject before acknowledgment`() {
    val snapshotRoot = Files.createTempDirectory("ui-builder-store-snapshot-limit")
    assertFailsWith<DesignStoreLimitException> {
      FileDesignStore(
          snapshotRoot,
          DesignStoreLimits(
            maxSnapshotBytes = 10,
            maxEventBytes = 1_000,
            maxEventLogBytes = 2_000,
          ),
        )
        .create(document())
    }

    val eventRoot = Files.createTempDirectory("ui-builder-store-event-limit")
    val initial = document()
    FileDesignStore(eventRoot).create(initial)
    val command = command("large", 0, literal("x".repeat(10_000)))
    val applied =
      CollaborationReducer.apply(CollaborationState(initial), command, propertyValidator)
    val limited =
      FileDesignStore(
        eventRoot,
        DesignStoreLimits(
          maxSnapshotBytes = 100_000,
          maxEventBytes = 1_000,
          maxEventLogBytes = 100_000,
        ),
      )
    assertFailsWith<DesignStoreLimitException> {
      limited.append(
        "design",
        CollaborationEvent(RejectedMutation.Design(command), applied.outcome),
      )
    }
    assertTrue(!Files.exists(eventRoot.resolve("design/events.jsonl")))
  }

  @Test
  fun `cumulative event log quota rejects without changing acknowledged events`() {
    val root = Files.createTempDirectory("ui-builder-store-log-limit")
    val initial = document()
    val store = FileDesignStore(root)
    store.create(initial)
    val command = command("accepted", 0, literal("saved"))
    val applied =
      CollaborationReducer.apply(CollaborationState(initial), command, propertyValidator)
    val event = CollaborationEvent(RejectedMutation.Design(command), applied.outcome)
    store.append("design", event)
    val eventFile = root.resolve("design/events.jsonl")
    val acknowledgedBytes = Files.size(eventFile)

    val limited =
      FileDesignStore(
        root,
        DesignStoreLimits(
          maxSnapshotBytes = 100_000,
          maxEventBytes = acknowledgedBytes,
          maxEventLogBytes = acknowledgedBytes * 2 - 1,
        ),
      )
    assertFailsWith<DesignStoreLimitException> { limited.append("design", event) }

    assertEquals(acknowledgedBytes, Files.size(eventFile))
    assertEquals(listOf(event), assertNotNull(store.load("design")).events)
  }

  @Test
  fun `oversized recovery files are rejected before whole file reads`() {
    val root = Files.createTempDirectory("ui-builder-store-recovery-limit")
    val initial = document()
    val store = FileDesignStore(root)
    store.create(initial)
    val command = command("accepted", 0, literal("saved"))
    val applied =
      CollaborationReducer.apply(CollaborationState(initial), command, propertyValidator)
    store.append(
      "design",
      CollaborationEvent(RejectedMutation.Design(command), applied.outcome),
    )
    val directory = root.resolve("design")
    val snapshotBytes = Files.size(directory.resolve("snapshot.json"))
    val eventBytes = Files.size(directory.resolve("events.jsonl"))

    assertFailsWith<DesignStoreLimitException> {
      FileDesignStore(
          root,
          DesignStoreLimits(
            maxSnapshotBytes = snapshotBytes - 1,
            maxEventBytes = eventBytes,
            maxEventLogBytes = eventBytes,
          ),
        )
        .load("design")
    }
    assertFailsWith<DesignStoreLimitException> {
      FileDesignStore(
          root,
          DesignStoreLimits(
            maxSnapshotBytes = snapshotBytes,
            maxEventBytes = eventBytes - 1,
            maxEventLogBytes = eventBytes - 1,
          ),
        )
        .load("design")
    }
  }

  @Test
  fun `legacy snapshots without a durable sequence fail with a migration diagnostic`() {
    val root = Files.createTempDirectory("ui-builder-store-legacy-sequence")
    val store = FileDesignStore(root)
    store.create(document())
    val snapshot = root.resolve("design/snapshot.json")
    val encoded = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(snapshot))
    val currentPayload = encoded.jsonObject.getValue("payload").jsonObject
    val legacyPayload = buildJsonObject {
      put("schema", "compose-ui-builder-store-snapshot/v1")
      put("initialDocument", currentPayload.getValue("initialDocument"))
    }
    val legacyEnvelope = buildJsonObject {
      put("checksumSha256", sha256Hex(canonicalJson(legacyPayload)))
      put("payload", legacyPayload)
    }
    Files.writeString(snapshot, canonicalJson(legacyEnvelope))

    val failure = assertFailsWith<DesignStoreCorruptionException> { store.load("design") }
    assertTrue(failure.message.orEmpty().contains("migration required"))
  }

  private fun command(operationId: String, revision: Int, value: JsonObject) =
    DesignCommand(
      designId = "design",
      operationId = operationId,
      actorId = "actor",
      clientId = "client",
      baseRevision = revision,
      operations = listOf(DesignOperation.SetProperty("text", "text", value)),
    )

  private fun document() =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Durable design",
      revision = 0,
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
    put("value", value)
  }
}
