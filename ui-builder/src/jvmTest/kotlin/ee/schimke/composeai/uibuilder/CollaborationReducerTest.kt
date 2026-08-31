package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityBenchmark
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.WasmAdapterStatus
import ee.schimke.composeai.uibuilder.capability.WasmCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CollaborationReducerTest {
  @Test
  fun `typed command schema round trips with stable operation discriminators`() {
    val command =
      command(
        "wire",
        4,
        DesignOperation.InsertNode(UiBuilderNode("inserted", "button")),
        DesignOperation.MoveNode("b", ParentSlot("container", "items"), "a"),
        DesignOperation.DeleteNode("a"),
        DesignOperation.RestoreNode("a"),
        DesignOperation.SetProperty("b", "enabled", typed("bool", JsonPrimitive(true))),
      )
    val encoded = Json.encodeToString(command)

    assertTrue(encoded.contains("\"type\":\"insertNode\""))
    assertTrue(encoded.contains("\"type\":\"moveNode\""))
    assertTrue(encoded.contains("\"type\":\"deleteNode\""))
    assertTrue(encoded.contains("\"type\":\"restoreNode\""))
    assertTrue(encoded.contains("\"type\":\"setProperty\""))
    assertEquals(command, Json.decodeFromString<DesignCommand>(encoded))

    val undo = UndoCommand("design", "undo", "actor-a", "browser-a", 5, "wire")
    val redo = RedoCommand("design", "redo", "actor-a", "browser-a", 6, "undo")
    assertEquals(undo, Json.decodeFromString<UndoCommand>(Json.encodeToString(undo)))
    assertEquals(redo, Json.decodeFromString<RedoCommand>(Json.encodeToString(redo)))
  }

  @Test
  fun `an atomic batch commits one revision`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "batch-1",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("updated"))),
          DesignOperation.SetProperty("b", "enabled", typed("bool", JsonPrimitive(false))),
        ),
        catalogPropertyValidator(),
      )

    val accepted = assertIs<CommandOutcome.Accepted>(application.outcome)
    assertEquals(5, accepted.committedRevision)
    assertFalse(accepted.idempotentReplay)
    assertEquals(
      typed("string", JsonPrimitive("updated")),
      application.state.document.nodes.getValue("a").properties["text"],
    )
    assertEquals(
      typed("bool", JsonPrimitive(false)),
      application.state.document.nodes.getValue("b").properties["enabled"],
    )
  }

  @Test
  fun `property mutation without capability validator is rejected before the batch runs`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "missing-validator",
          4,
          DesignOperation.MoveNode("b", ParentSlot("container", "items"), afterNodeId = "a"),
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("unsafe"))),
        ),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.MISSING_PROPERTY_VALIDATOR, rejected.code)
    assertEquals(1, rejected.operationIndex)
    assertEquals("a", rejected.nodeId)
    assertEquals("text", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `an untyped property object rejects its whole batch with a located diagnostic`() {
    val initial = CollaborationState(document())
    val malformed = buildJsonObject { put("value", false) }
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "malformed-property",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("rolled-back"))),
          DesignOperation.SetProperty("b", "enabled", malformed),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.MALFORMED_PROPERTY, rejected.code)
    assertEquals(1, rejected.operationIndex)
    assertEquals("b", rejected.nodeId)
    assertEquals("enabled", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `nonsense property wrapper tag is rejected before catalog validation`() {
    val initial = CollaborationState(document())
    val nonsense = typed("executeKotlin", JsonPrimitive("println(1)"))
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "nonsense-wrapper",
          4,
          DesignOperation.SetProperty("a", "text", nonsense),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.MALFORMED_PROPERTY, rejected.code)
    assertEquals(0, rejected.operationIndex)
    assertEquals("a", rejected.nodeId)
    assertEquals("text", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `state wrapper with literal shape is rejected before catalog validation`() {
    val initial = CollaborationState(document())
    val mismatched = typed("state", JsonPrimitive("selectedTrack"))
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "mismatched-wrapper",
          4,
          DesignOperation.SetProperty("a", "text", mismatched),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.MALFORMED_PROPERTY, rejected.code)
    assertEquals("a", rejected.nodeId)
    assertEquals("text", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `catalog hook rejects unknown property atomically`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "unknown-property",
          4,
          DesignOperation.SetProperty("a", "madeUp", typed("string", JsonPrimitive("x"))),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals(0, rejected.operationIndex)
    assertEquals("a", rejected.nodeId)
    assertEquals("madeUp", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `catalog hook rejects property value with wrong JSON type atomically`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "wrong-property-type",
          4,
          DesignOperation.SetProperty("b", "enabled", typed("bool", JsonPrimitive("yes"))),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("b", rejected.nodeId)
    assertEquals("enabled", rejected.field)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `a failed later operation rolls back the whole batch`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "batch-invalid",
          4,
          DesignOperation.SetProperty(
            "a",
            "text",
            typed("string", JsonPrimitive("must-not-commit")),
          ),
          DesignOperation.MoveNode("missing"),
        ),
        catalogPropertyValidator(),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.UNKNOWN_NODE, rejected.code)
    assertEquals(1, rejected.operationIndex)
    assertNoDesignMutation(initial, application.state)
    assertEquals(
      typed("string", JsonPrimitive("original")),
      application.state.document.nodes.getValue("a").properties["text"],
    )
  }

  @Test
  fun `stale scalar writes commit in server order and accepted retries remain idempotent`() {
    val initial = CollaborationState(document())
    val firstCommand =
      command(
        "first",
        4,
        DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("one"))),
      )
    val propertyValidator = catalogPropertyValidator()
    val first = CollaborationReducer.apply(initial, firstCommand, propertyValidator)
    val second =
      CollaborationReducer.apply(
        first.state,
        command(
          "second",
          5,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("two"))),
        ),
        propertyValidator,
      )

    val stale =
      CollaborationReducer.apply(
        second.state,
        command(
          "stale",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("stale"))),
        ),
        propertyValidator,
      )
    val staleOutcome = assertIs<CommandOutcome.Accepted>(stale.outcome)
    assertEquals(7, staleOutcome.committedRevision)
    assertEquals(
      listOf(ConflictNotice(ConflictCode.STALE_PROPERTY_WRITE, "a", "text", 6)),
      staleOutcome.conflicts,
    )
    assertEquals(
      typed("string", JsonPrimitive("stale")),
      stale.state.document.nodes.getValue("a").properties["text"],
    )

    val retry = CollaborationReducer.apply(stale.state, firstCommand, propertyValidator)
    val outcome = assertIs<CommandOutcome.Accepted>(retry.outcome)
    assertTrue(outcome.idempotentReplay)
    assertEquals(5, outcome.committedRevision)
    assertSame(stale.state, retry.state)
    assertEquals(
      typed("string", JsonPrimitive("stale")),
      retry.state.document.nodes.getValue("a").properties["text"],
    )
  }

  @Test
  fun `reusing an operation id for different content is rejected`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "same-id",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("one"))),
        ),
        catalogPropertyValidator(),
      )
    val collision =
      CollaborationReducer.apply(
        first.state,
        command(
          "same-id",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("other"))),
        ),
        catalogPropertyValidator(),
      )

    assertEquals(
      RejectionCode.OPERATION_ID_REUSED,
      assertIs<CommandOutcome.Rejected>(collision.outcome).code,
    )
    assertSame(first.state, collision.state)
  }

  @Test
  fun `move uses stable neighbours and rejects cycles atomically`() {
    val moved =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "move",
          4,
          DesignOperation.MoveNode("b", ParentSlot("container", "items"), afterNodeId = "a"),
        ),
      )
    assertEquals(
      listOf("a", "b"),
      moved.state.document.nodes.getValue("container").slots.getValue("items"),
    )
    assertEquals(listOf("container"), moved.state.document.roots)

    val cycle =
      CollaborationReducer.apply(
        moved.state,
        command(
          "cycle",
          5,
          DesignOperation.MoveNode("container", ParentSlot("a", "content")),
        ),
      )
    assertEquals(RejectionCode.CYCLE, assertIs<CommandOutcome.Rejected>(cycle.outcome).code)
    assertNoDesignMutation(moved.state, cycle.state)
  }

  @Test
  fun `delete tombstones a subtree and restore uses its stable original location`() {
    val initial = CollaborationState(document())
    val deleted =
      CollaborationReducer.apply(
        initial,
        command("delete", 4, DesignOperation.DeleteNode("a")),
      )
    assertNull(deleted.state.document.nodes["a"])
    assertNull(deleted.state.document.nodes["child"])
    assertEquals(listOf("container", "b"), deleted.state.document.roots)
    assertEquals(emptyList(), deleted.state.document.nodes.getValue("container").slots["items"])
    assertEquals(setOf("a", "child"), deleted.state.tombstones.getValue("a").nodes.keys)

    val mutateDeleted =
      CollaborationReducer.apply(
        deleted.state,
        command(
          "mutate-deleted",
          5,
          DesignOperation.SetProperty("child", "text", typed("string", JsonPrimitive("no"))),
        ),
        catalogPropertyValidator(),
      )
    assertEquals(
      RejectionCode.DELETED_NODE,
      assertIs<CommandOutcome.Rejected>(mutateDeleted.outcome).code,
    )

    val restored =
      CollaborationReducer.apply(
        deleted.state,
        command("restore", 5, DesignOperation.RestoreNode("a")),
      )
    assertEquals(
      listOf("a"),
      restored.state.document.nodes.getValue("container").slots.getValue("items"),
    )
    assertEquals(
      typed("string", JsonPrimitive("original")),
      restored.state.document.nodes.getValue("a").properties["text"],
    )
    assertTrue(restored.state.tombstones.isEmpty())
  }

  @Test
  fun `restore falls back to the surviving next neighbour when previous neighbour moved`() {
    val initial = CollaborationState(documentWithSiblings())
    val deleted =
      CollaborationReducer.apply(initial, command("delete-a", 4, DesignOperation.DeleteNode("a")))
    val movedAnchor =
      CollaborationReducer.apply(
        deleted.state,
        command(
          "move-anchor",
          5,
          DesignOperation.MoveNode("anchor", parent = null, afterNodeId = "b"),
        ),
      )
    val restored =
      CollaborationReducer.apply(
        movedAnchor.state,
        command("restore-a", 6, DesignOperation.RestoreNode("a")),
      )

    assertIs<CommandOutcome.Accepted>(restored.outcome)
    assertEquals(
      listOf("a", "tail"),
      restored.state.document.nodes.getValue("container").slots.getValue("items"),
    )
  }

  @Test
  fun `restore falls back to the surviving next neighbour when previous neighbour was deleted`() {
    val initial = CollaborationState(documentWithSiblings())
    val deleted =
      CollaborationReducer.apply(initial, command("delete-a", 4, DesignOperation.DeleteNode("a")))
    val deletedAnchor =
      CollaborationReducer.apply(
        deleted.state,
        command("delete-anchor", 5, DesignOperation.DeleteNode("anchor")),
      )
    val restored =
      CollaborationReducer.apply(
        deletedAnchor.state,
        command("restore-a", 6, DesignOperation.RestoreNode("a")),
      )

    assertIs<CommandOutcome.Accepted>(restored.outcome)
    assertEquals(
      listOf("a", "tail"),
      restored.state.document.nodes.getValue("container").slots.getValue("items"),
    )
  }

  @Test
  fun `the same command log replays to identical canonical state`() {
    val commands =
      listOf(
        command(
          "property",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", JsonPrimitive("replayed"))),
        ),
        command(
          "move",
          5,
          DesignOperation.MoveNode("b", ParentSlot("container", "items"), afterNodeId = "a"),
        ),
        command("delete", 6, DesignOperation.DeleteNode("a")),
        command("restore", 7, DesignOperation.RestoreNode("a")),
      )
    val propertyValidator = catalogPropertyValidator()
    val one = CollaborationReducer.replay(document(), commands, propertyValidator)
    val two = CollaborationReducer.replay(document(), commands, propertyValidator)

    assertIs<CommandOutcome.Accepted>(one.outcome)
    assertEquals(one.state.document, two.state.document)
    assertEquals(one.state.tombstones, two.state.tombstones)
    assertEquals(one.state.acceptedCommands, two.state.acceptedCommands)
    assertEquals(canonicalDocument(one.state.document), canonicalDocument(two.state.document))
  }

  private fun command(
    operationId: String,
    baseRevision: Int,
    vararg operations: DesignOperation,
  ): DesignCommand =
    DesignCommand(
      designId = "design",
      operationId = operationId,
      actorId = "actor-a",
      clientId = "browser-a",
      baseRevision = baseRevision,
      operations = operations.toList(),
    )

  private fun assertNoDesignMutation(before: CollaborationState, after: CollaborationState) {
    assertEquals(before, after.copy(rejectedOperations = before.rejectedOperations))
  }

  private fun document(): UiBuilderDocument {
    val child = UiBuilderNode(id = "child", componentId = "text")
    val a =
      UiBuilderNode(
        id = "a",
        componentId = "column",
        properties = JsonObject(mapOf("text" to typed("string", JsonPrimitive("original")))),
        slots = mapOf("content" to listOf("child")),
      )
    val b = UiBuilderNode(id = "b", componentId = "button")
    val container =
      UiBuilderNode(
        id = "container",
        componentId = "column",
        slots = mapOf("items" to listOf("a")),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Collaboration",
      revision = 4,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("container", "b"),
      nodes = mapOf("container" to container, "a" to a, "child" to child, "b" to b),
    )
  }

  private fun documentWithSiblings(): UiBuilderDocument {
    val base = document()
    val container = base.nodes.getValue("container")
    return base.copy(
      nodes =
        base.nodes +
          mapOf(
            "anchor" to UiBuilderNode(id = "anchor", componentId = "button"),
            "tail" to UiBuilderNode(id = "tail", componentId = "button"),
            "container" to
              container.copy(slots = container.slots + ("items" to listOf("anchor", "a", "tail"))),
          )
    )
  }

  private fun typed(type: String, value: JsonPrimitive): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun catalogPropertyValidator(): CapabilityPropertyWriteValidator {
    fun component(id: String, vararg properties: Pair<String, String>) =
      ComponentCapability(
        componentId = id,
        displayName = id,
        role = "Container",
        properties =
          properties.map { (name, type) ->
            PropertyCapability(name = name, jsonType = JsonPrimitive(type))
          },
        wasm =
          WasmCapability(
            platformSupported = JsonPrimitive(true),
            adapterStatus = WasmAdapterStatus.SUPPORTED,
          ),
      )
    val catalog =
      CapabilityCatalog(
        schema = "compose-ui-builder-capabilities/v1",
        benchmark =
          CapabilityBenchmark(
            id = "collaboration-test",
            sourceRevision = "test",
            catalogSystemId = "test-catalog",
            catalogRevision = "1",
            nativeRuntimeId = "test-runtime",
          ),
        components =
          listOf(
            component("column", "text" to "string"),
            component("button", "enabled" to "boolean"),
            component("text"),
          ),
      )
    return CapabilityPropertyWriteValidator(CapabilityValidator(catalog))
  }
}
