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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CollaborationConvergenceTest {
  private val propertyValidator = capabilityPropertyValidator()

  @Test
  fun `two browsers and MCP converge across insert permutations retries and full replay`() {
    val browserA =
      command(
        operationId = "a-browser-a-insert",
        actorId = "actor-a",
        clientId = "browser-a",
        baseRevision = 4,
        DesignOperation.InsertNode(
          UiBuilderNode("insert-browser", "button"),
          ParentSlot("container", "items"),
          "a",
        ),
      )
    val browserB =
      command(
        operationId = "b-browser-b-insert",
        actorId = "actor-b",
        clientId = "browser-b",
        baseRevision = 4,
        DesignOperation.InsertNode(
          UiBuilderNode("insert-browser-b", "button"),
          ParentSlot("container", "items"),
          "a",
        ),
      )
    val mcp =
      command(
        operationId = "c-mcp-insert",
        actorId = "agent-c",
        clientId = "mcp-c",
        baseRevision = 4,
        DesignOperation.InsertNode(
          UiBuilderNode("insert-mcp", "button"),
          ParentSlot("container", "items"),
          "a",
        ),
      )

    val commands = listOf(browserA, browserB, mcp)
    val states = commands.permutations().map { applyAll(document(), it) }
    val expected = listOf("a", "insert-browser", "insert-browser-b", "insert-mcp")
    states.forEach { state ->
      assertEquals(expected, state.document.nodes.getValue("container").slots["items"])
      assertEquals(states.first().document, state.document)
      assertEquals(states.first().positions, state.positions)
    }

    var retried = states.first()
    commands.reversed().forEach { command ->
      val retry = CollaborationReducer.apply(retried, command)
      assertTrue(assertIs<CommandOutcome.Accepted>(retry.outcome).idempotentReplay)
      assertSame(retried, retry.state)
      retried = retry.state
    }

    val replayedFromInitialSnapshot = applyAll(document(), commands)
    assertEquals(
      canonicalDocument(states.first().document),
      canonicalDocument(replayedFromInitialSnapshot.document),
    )

    val nested =
      CollaborationReducer.apply(
        states.first(),
        command(
          "nested-insert",
          "actor-a",
          "browser-a",
          7,
          DesignOperation.InsertNode(
            UiBuilderNode("nested", "button"),
            ParentSlot("container", "items"),
            "insert-browser",
          ),
        ),
      )
    assertEquals(
      listOf("a", "insert-browser", "nested", "insert-browser-b", "insert-mcp"),
      nested.state.document.nodes.getValue("container").slots["items"],
    )
  }

  @Test
  fun `position identities remain distinct when operation and node ids contain separators`() {
    val first =
      command(
        "x:0",
        "actor-a",
        "browser-a",
        4,
        DesignOperation.InsertNode(
          UiBuilderNode("z", "button"),
          ParentSlot("container", "items"),
          "a",
        ),
      )
    val second =
      command(
        "x",
        "actor-b",
        "browser-b",
        4,
        DesignOperation.InsertNode(
          UiBuilderNode("0:z", "button"),
          ParentSlot("container", "items"),
          "a",
        ),
      )
    val concurrent = applyAll(document(), listOf(first, second))
    assertTrue(concurrent.positions.getValue("z").key != concurrent.positions.getValue("0:z").key)

    val insertedBetween =
      CollaborationReducer.apply(
        concurrent,
        command(
          "between",
          "actor-c",
          "mcp-c",
          6,
          DesignOperation.InsertNode(
            UiBuilderNode("nested", "button"),
            ParentSlot("container", "items"),
            "0:z",
          ),
        ),
      )

    assertEquals(
      listOf("a", "0:z", "nested", "z"),
      insertedBetween.state.document.nodes.getValue("container").slots["items"],
    )
  }

  @Test
  fun `stale moves resolve in server order and report displacement`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(documentWithSiblings()),
        command(
          "browser-move",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.MoveNode("a", parent = null, afterNodeId = "b"),
        ),
      )
    val stale =
      CollaborationReducer.apply(
        first.state,
        command(
          "mcp-move",
          "agent-b",
          "mcp-b",
          4,
          DesignOperation.MoveNode("a", ParentSlot("container", "items"), "tail"),
        ),
      )

    val outcome = assertIs<CommandOutcome.Accepted>(stale.outcome)
    assertEquals(listOf(ConflictNotice(ConflictCode.STALE_MOVE, "a", null, 5)), outcome.conflicts)
    assertEquals(
      listOf("anchor", "tail", "a"),
      stale.state.document.nodes.getValue("container").slots["items"],
    )
  }

  @Test
  fun `later operation in an atomic batch may anchor to an earlier insert`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "insert-chain",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("first-in-batch", "button"),
            ParentSlot("container", "items"),
            "a",
          ),
          DesignOperation.InsertNode(
            UiBuilderNode("second-in-batch", "button"),
            ParentSlot("container", "items"),
            "first-in-batch",
          ),
        ),
      )

    assertIs<CommandOutcome.Accepted>(application.outcome)
    assertEquals(
      listOf("a", "first-in-batch", "second-in-batch"),
      application.state.document.nodes.getValue("container").slots["items"],
    )
  }

  @Test
  fun `insert rejects existing child references instead of creating a second parent`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "invalid-subtree-insert",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("second-parent", "column", slots = mapOf("content" to listOf("a")))
          ),
        ),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_LOCATION, rejected.code)
    assertEquals(0, rejected.operationIndex)
    assertEquals("second-parent", rejected.nodeId)
    assertNoDesignMutation(initial, application.state)
  }

  @Test
  fun `a rejected operation id replays its rejection after the target becomes valid`() {
    val initiallyRejected =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "delayed-insert",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("delayed", "button"),
            ParentSlot("container", "items"),
            "future-anchor",
          ),
        ),
      )
    val originalOutcome = assertIs<CommandOutcome.Rejected>(initiallyRejected.outcome)
    assertEquals(RejectionCode.INVALID_LOCATION, originalOutcome.code)

    val anchorInserted =
      CollaborationReducer.apply(
        initiallyRejected.state,
        command(
          "insert-anchor",
          "actor-b",
          "browser-b",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("future-anchor", "button"),
            ParentSlot("container", "items"),
            "a",
          ),
        ),
      )
    assertIs<CommandOutcome.Accepted>(anchorInserted.outcome)

    val retry =
      CollaborationReducer.apply(
        anchorInserted.state,
        command(
          "delayed-insert",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("delayed", "button"),
            ParentSlot("container", "items"),
            "future-anchor",
          ),
        ),
      )

    assertEquals(originalOutcome, retry.outcome)
    assertSame(anchorInserted.state, retry.state)
    assertEquals(null, retry.state.document.nodes["delayed"])
  }

  @Test
  fun `stale delete preserves Wave 1 strict revision behavior`() {
    val inserted =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "insert",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.InsertNode(
            UiBuilderNode("new", "button"),
            ParentSlot("container", "items"),
            "a",
          ),
        ),
      )
    val staleDelete =
      CollaborationReducer.apply(
        inserted.state,
        command("delete", "actor-b", "browser-b", 4, DesignOperation.DeleteNode("a")),
      )

    assertEquals(
      RejectionCode.REVISION_MISMATCH,
      assertIs<CommandOutcome.Rejected>(staleDelete.outcome).code,
    )
    assertNoDesignMutation(inserted.state, staleDelete.state)
  }

  @Test
  fun `actor scoped scalar undo and redo append revisions and are idempotent`() {
    val write =
      command(
        "write",
        "actor-a",
        "browser-a",
        4,
        DesignOperation.SetProperty("a", "text", typed("string", "one")),
      )
    val written =
      CollaborationReducer.apply(CollaborationState(document()), write, propertyValidator)

    val wrongActor =
      CollaborationReducer.undo(
        written.state,
        undo("undo-wrong", "actor-b", "browser-b", 5, "write"),
      )
    assertEquals(
      RejectionCode.ACTOR_MISMATCH,
      assertIs<CommandOutcome.Rejected>(wrongActor.outcome).code,
    )

    val undoCommand = undo("undo", "actor-a", "browser-a", 5, "write")
    val undone = CollaborationReducer.undo(written.state, undoCommand)
    assertEquals(6, assertIs<CommandOutcome.Accepted>(undone.outcome).committedRevision)
    assertEquals(typed("string", "original"), undone.state.nodeProperty("a", "text"))
    val undoRetry = CollaborationReducer.undo(undone.state, undoCommand)
    assertTrue(assertIs<CommandOutcome.Accepted>(undoRetry.outcome).idempotentReplay)
    assertSame(undone.state, undoRetry.state)

    val redoCommand = redo("redo", "actor-a", "browser-a", 6, "undo")
    val redone = CollaborationReducer.redo(undone.state, redoCommand)
    assertEquals(7, assertIs<CommandOutcome.Accepted>(redone.outcome).committedRevision)
    assertEquals(typed("string", "one"), redone.state.nodeProperty("a", "text"))
    val redoRetry = CollaborationReducer.redo(redone.state, redoCommand)
    assertTrue(assertIs<CommandOutcome.Accepted>(redoRetry.outcome).idempotentReplay)
    assertSame(redone.state, redoRetry.state)
  }

  @Test
  fun `undo rejects when another client changed the same scalar`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "first-write",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", "one")),
        ),
        propertyValidator,
      )
    val later =
      CollaborationReducer.apply(
        first.state,
        command(
          "later-write",
          "agent-b",
          "mcp-b",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", "two")),
        ),
        propertyValidator,
      )
    val unsafe =
      CollaborationReducer.undo(
        later.state,
        undo("unsafe-undo", "actor-a", "browser-a", 6, "first-write"),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(unsafe.outcome)
    assertEquals(RejectionCode.UNSAFE_COMPENSATION, rejected.code)
    assertEquals("a", rejected.nodeId)
    assertEquals("text", rejected.field)
    assertNoDesignMutation(later.state, unsafe.state)
  }

  @Test
  fun `undo rejects intervening writes even when the scalar returned to the target value`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "first-write",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", "one")),
        ),
        propertyValidator,
      )
    val changed =
      CollaborationReducer.apply(
        first.state,
        command(
          "intervening-write",
          "actor-b",
          "browser-b",
          5,
          DesignOperation.SetProperty("a", "text", typed("string", "two")),
        ),
        propertyValidator,
      )
    val restoredValue =
      CollaborationReducer.apply(
        changed.state,
        command(
          "intervening-restore",
          "actor-b",
          "browser-b",
          6,
          DesignOperation.SetProperty("a", "text", typed("string", "one")),
        ),
        propertyValidator,
      )

    val unsafe =
      CollaborationReducer.undo(
        restoredValue.state,
        undo("unsafe-undo", "actor-a", "browser-a", 7, "first-write"),
      )

    assertEquals(
      RejectionCode.UNSAFE_COMPENSATION,
      assertIs<CommandOutcome.Rejected>(unsafe.outcome).code,
    )
    assertNoDesignMutation(restoredValue.state, unsafe.state)
  }

  @Test
  fun `redo rejects intervening writes even when the scalar returned to the undone value`() {
    val written =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "write",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", "one")),
        ),
        propertyValidator,
      )
    val undone =
      CollaborationReducer.undo(
        written.state,
        undo("undo", "actor-a", "browser-a", 5, "write"),
      )
    val changed =
      CollaborationReducer.apply(
        undone.state,
        command(
          "intervening-write",
          "actor-b",
          "browser-b",
          6,
          DesignOperation.SetProperty("a", "text", typed("string", "two")),
        ),
        propertyValidator,
      )
    val restoredValue =
      CollaborationReducer.apply(
        changed.state,
        command(
          "intervening-restore",
          "actor-b",
          "browser-b",
          7,
          DesignOperation.SetProperty("a", "text", typed("string", "original")),
        ),
        propertyValidator,
      )

    val unsafe =
      CollaborationReducer.redo(
        restoredValue.state,
        redo("unsafe-redo", "actor-a", "browser-a", 8, "undo"),
      )

    assertEquals(
      RejectionCode.UNSAFE_COMPENSATION,
      assertIs<CommandOutcome.Rejected>(unsafe.outcome).code,
    )
    assertNoDesignMutation(restoredValue.state, unsafe.state)
  }

  @Test
  fun `scalar batch compensation restores the value before its first write`() {
    val batch =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "two-writes",
          "actor-a",
          "browser-a",
          4,
          DesignOperation.SetProperty("a", "text", typed("string", "one")),
          DesignOperation.SetProperty("a", "text", typed("string", "two")),
        ),
        propertyValidator,
      )
    val undone =
      CollaborationReducer.undo(
        batch.state,
        undo("undo-two", "actor-a", "browser-a", 5, "two-writes"),
      )

    assertIs<CommandOutcome.Accepted>(undone.outcome)
    assertEquals(typed("string", "original"), undone.state.nodeProperty("a", "text"))
  }

  private fun applyAll(
    initial: UiBuilderDocument,
    commands: List<DesignCommand>,
  ): CollaborationState {
    var state = CollaborationState(initial)
    commands.forEach { command ->
      val applied = CollaborationReducer.apply(state, command, propertyValidator)
      assertIs<CommandOutcome.Accepted>(applied.outcome)
      state = applied.state
    }
    return state
  }

  private fun <T> List<T>.permutations(): List<List<T>> =
    if (size <= 1) listOf(this)
    else
      indices.flatMap { index ->
        val head = this[index]
        (take(index) + drop(index + 1)).permutations().map { tail -> listOf(head) + tail }
      }

  private fun command(
    operationId: String,
    actorId: String,
    clientId: String,
    baseRevision: Int,
    vararg operations: DesignOperation,
  ) = DesignCommand("design", operationId, actorId, clientId, baseRevision, operations.toList())

  private fun undo(
    operationId: String,
    actorId: String,
    clientId: String,
    baseRevision: Int,
    targetOperationId: String,
  ) = UndoCommand("design", operationId, actorId, clientId, baseRevision, targetOperationId)

  private fun redo(
    operationId: String,
    actorId: String,
    clientId: String,
    baseRevision: Int,
    targetUndoOperationId: String,
  ) = RedoCommand("design", operationId, actorId, clientId, baseRevision, targetUndoOperationId)

  private fun typed(type: String, value: String): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun CollaborationState.nodeProperty(nodeId: String, property: String) =
    document.nodes.getValue(nodeId).properties[property]

  private fun assertNoDesignMutation(before: CollaborationState, after: CollaborationState) {
    assertEquals(before, after.copy(rejectedOperations = before.rejectedOperations))
  }

  private fun document(): UiBuilderDocument {
    val child = UiBuilderNode("child", "text")
    val a =
      UiBuilderNode(
        "a",
        "column",
        properties = JsonObject(mapOf("text" to typed("string", "original"))),
        slots = mapOf("content" to listOf("child")),
      )
    val b = UiBuilderNode("b", "button")
    val container = UiBuilderNode("container", "column", slots = mapOf("items" to listOf("a")))
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Convergence",
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
            "anchor" to UiBuilderNode("anchor", "button"),
            "tail" to UiBuilderNode("tail", "button"),
            "container" to container.copy(slots = mapOf("items" to listOf("anchor", "a", "tail"))),
          )
    )
  }

  private fun capabilityPropertyValidator(): CapabilityPropertyWriteValidator {
    fun component(id: String, vararg properties: Pair<String, String>) =
      ComponentCapability(
        componentId = id,
        displayName = id,
        role = "Container",
        properties =
          properties.map { (name, type) ->
            PropertyCapability(name = name, jsonType = JsonPrimitive(type))
          },
        wasm = WasmCapability(JsonPrimitive(true), WasmAdapterStatus.SUPPORTED),
      )
    return CapabilityPropertyWriteValidator(
      CapabilityValidator(
        CapabilityCatalog(
          schema = "compose-ui-builder-capabilities/v1",
          benchmark = CapabilityBenchmark("test", "test", "test", "1", "runtime"),
          components =
            listOf(
              component("column", "text" to "string"),
              component("button"),
              component("text"),
            ),
        )
      )
    )
  }
}
