package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesignServiceTest {
  private val propertyValidator = CollaborationPropertyValidator { _, _, _, _ -> null }

  @Test
  fun `browser and MCP shaped callers share one ordered reducer and retry is not rebroadcast`() {
    val service = service()
    assertIs<CreateDesignResult.Created>(service.create(document()))
    val browserUpdates = mutableListOf<DesignServiceUpdate>()
    val mcpUpdates = mutableListOf<DesignServiceUpdate>()
    val browser = assertNotNull(service.subscribe("design", 0, browserUpdates::add))
    val mcp = assertNotNull(service.subscribe("design", 0, mcpUpdates::add))

    val insert =
      command(
        operationId = "browser-insert",
        revision = 0,
        operation =
          DesignOperation.InsertNode(
            UiBuilderNode(
              id = "text-2",
              componentId = "m3/text",
              properties = buildJsonObject { put("text", literal("Second")) },
            )
          ),
      )
    val accepted = assertIs<CommandOutcome.Accepted>(service.apply(insert).application.outcome)
    assertEquals(1, accepted.committedRevision)
    val retry = assertIs<CommandOutcome.Accepted>(service.apply(insert).application.outcome)
    assertTrue(retry.idempotentReplay)
    val rejection =
      service.apply(
        command(
          operationId = "browser-rejected",
          revision = 99,
          operation = DesignOperation.SetProperty("text", "text", literal("ignored")),
        )
      )
    assertIs<CommandOutcome.Rejected>(rejection.application.outcome)

    assertEquals(listOf(1), browserUpdates.map(DesignServiceUpdate::revision))
    assertEquals(listOf(1L), browserUpdates.map(DesignServiceUpdate::sequence))
    assertEquals(browserUpdates, mcpUpdates)
    val snapshot = service.open("design")!!
    assertEquals(1L, snapshot.sequence, "retry and rejection must not advance the cursor")
    assertEquals(snapshot.documentHash, browserUpdates.single().documentHash)
    browser.close()
    mcp.close()
  }

  @Test
  fun `catch-up uses sequence and falls back to snapshot after retained history compaction`() {
    val service = service(retained = 2)
    val created = assertIs<CreateDesignResult.Created>(service.create(document(revision = 10)))
    assertEquals(10, created.snapshot.revision)
    assertEquals(0L, created.snapshot.lastSequence)
    repeat(3) { index ->
      val result =
        service.apply(
          command(
            operationId = "write-$index",
            revision = 10 + index,
            operation = DesignOperation.SetProperty("text", "text", literal("value-$index")),
          )
        )
      assertIs<CommandOutcome.Accepted>(
        result.application.outcome,
        "write $index was rejected: ${result.application.outcome}",
      )
    }

    val retained = mutableListOf<DesignServiceUpdate>()
    assertNotNull(service.subscribe("design", 1L, retained::add)).close()
    assertEquals(listOf(12, 13), retained.map(DesignServiceUpdate::revision))
    assertEquals(listOf(2L, 3L), retained.map(DesignServiceUpdate::sequence))
    assertTrue(retained.all { it is DesignServiceUpdate.Committed })

    val compacted = mutableListOf<DesignServiceUpdate>()
    assertNotNull(service.subscribe("design", 0L, compacted::add)).close()
    val snapshot = assertIs<DesignServiceUpdate.Snapshot>(compacted.single())
    assertEquals(13, snapshot.revision)
    assertEquals(3L, snapshot.lastSequence)
    assertEquals(2L, snapshot.retainedFromSequence)
    assertEquals("value-2", snapshot.document.nodes.getValue("text").literalText("text"))
  }

  @Test
  fun `validation create identity and subscription lifecycle fail closed`() {
    val rejecting =
      InMemoryDesignService(
        validationProvider =
          DesignValidationProvider {
            DesignValidators(
              propertyValidator,
              CollaborationDocumentValidator { DocumentWriteIssue("catalog mismatch") },
            )
          }
      )
    assertEquals(
      CreateDesignResult.Rejected("catalog mismatch"),
      rejecting.create(document()),
    )

    val service = service()
    assertIs<CreateDesignResult.Created>(service.create(document()))
    assertIs<CreateDesignResult.Rejected>(service.create(document()))
    assertNull(service.subscribe("missing", null) {})
    val updates = mutableListOf<DesignServiceUpdate>()
    val subscription = assertNotNull(service.subscribe("design", null, updates::add))
    assertIs<DesignServiceUpdate.Snapshot>(updates.single())
    subscription.close()
    service.apply(
      command(
        operationId = "after-close",
        revision = 0,
        operation = DesignOperation.SetProperty("text", "text", literal("closed")),
      )
    )
    assertEquals(1, updates.size)
  }

  @Test
  fun `persistent service acknowledges only after append and restarts with idempotency`() {
    val root = java.nio.file.Files.createTempDirectory("persistent-design-service")
    val first =
      PersistentDesignService(
        FileDesignStore(root),
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      )
    assertIs<CreateDesignResult.Created>(first.create(document()))
    val command =
      command(
        operationId = "durable",
        revision = 0,
        operation = DesignOperation.SetProperty("text", "text", literal("persisted")),
      )
    assertIs<CommandOutcome.Accepted>(first.apply(command).application.outcome)
    assertEquals(1L, first.open("design")!!.sequence)

    val restarted =
      PersistentDesignService(
        FileDesignStore(root),
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      )
    assertEquals(
      "persisted",
      restarted.open("design")!!.document.nodes.getValue("text").literalText("text"),
    )
    assertEquals(1L, restarted.open("design")!!.sequence)
    val retry = assertIs<CommandOutcome.Accepted>(restarted.apply(command).application.outcome)
    assertTrue(retry.idempotentReplay)
    val rejectedCommand =
      command(
        operationId = "durable-rejection",
        revision = 99,
        operation = DesignOperation.SetProperty("text", "text", literal("ignored")),
      )
    val rejection =
      assertIs<CommandOutcome.Rejected>(restarted.apply(rejectedCommand).application.outcome)
    assertEquals(1L, restarted.open("design")!!.sequence)

    val secondRestart =
      PersistentDesignService(
        FileDesignStore(root),
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      )
    assertEquals(rejection, secondRestart.apply(rejectedCommand).application.outcome)
    assertEquals(1L, secondRestart.open("design")!!.sequence)
    val secondAccepted =
      secondRestart.apply(
        command(
          operationId = "durable-second",
          revision = 1,
          operation = DesignOperation.SetProperty("text", "text", literal("again")),
        )
      )
    assertEquals(2L, assertNotNull(secondAccepted.update).sequence)
    assertEquals(3, FileDesignStore(root).load("design")!!.events.size)
  }

  @Test
  fun `failed durable append cannot publish or mutate process state`() {
    val service =
      InMemoryDesignService(
        validationProvider =
          DesignValidationProvider { DesignValidators(property = propertyValidator) },
        eventSink = { _, _ -> throw IllegalStateException("disk full") },
      )
    assertIs<CreateDesignResult.Created>(service.create(document()))
    val updates = mutableListOf<DesignServiceUpdate>()
    assertNotNull(service.subscribe("design", 0, updates::add)).use {
      assertFailsWith<IllegalStateException> {
        service.apply(
          command(
            operationId = "not-durable",
            revision = 0,
            operation = DesignOperation.SetProperty("text", "text", literal("lost")),
          )
        )
      }
    }
    val snapshot = service.open("design")!!
    assertEquals(0, snapshot.revision)
    assertEquals(0L, snapshot.sequence)
    assertEquals("Initial", snapshot.document.nodes.getValue("text").literalText("text"))
    assertTrue(updates.isEmpty())
  }

  private fun service(retained: Int = 1_024) =
    InMemoryDesignService(
      validationProvider =
        DesignValidationProvider { DesignValidators(property = propertyValidator) },
      retainedCommittedUpdates = retained,
    )

  private fun command(
    operationId: String,
    revision: Int,
    operation: DesignOperation,
  ) =
    DesignCommand(
      designId = "design",
      operationId = operationId,
      actorId = "actor",
      clientId = "client",
      baseRevision = revision,
      operations = listOf(operation),
    )

  private fun document(revision: Int = 0) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Service",
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
              properties = buildJsonObject { put("text", literal("Initial")) },
            )
        ),
    )

  private fun literal(value: String) = buildJsonObject {
    put("type", "string")
    put("value", value)
  }

  private fun UiBuilderNode.literalText(name: String): String =
    properties.getValue(name).let { it as JsonObject }["value"].let { it as JsonPrimitive }.content
}
