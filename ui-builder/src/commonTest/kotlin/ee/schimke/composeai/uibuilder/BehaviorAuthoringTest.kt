package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.local.*
import kotlin.test.*
import kotlinx.serialization.json.*

class BehaviorAuthoringTest {
  private fun obj(source: String) = Json.parseToJsonElement(source).jsonObject

  private val flag =
    obj(
      """{"type":"value","valueType":"bool","nullable":false,"initialValue":false,"persistence":"preview"}"""
    )
  private val actions =
    Json.parseToJsonElement("""[{"type":"toggle","variable":"expanded"}]""").jsonArray

  private fun document() =
    UiBuilderDocument(
      "compose-ui-builder-document/v1-candidate",
      "design",
      "Test",
      0,
      obj("{}"),
      obj("{}"),
      obj("{}"),
      listOf("button"),
      mapOf("button" to UiBuilderNode("button", "m3/button")),
    )

  private fun command(id: String, revision: Int, vararg operations: DesignOperation) =
    DesignCommand("design", id, "actor", "browser", revision, operations.toList())

  private fun apply(
    state: CollaborationState,
    id: String,
    vararg operations: DesignOperation,
  ): CollaborationState {
    val result =
      CollaborationReducer.apply(state, command(id, state.document.revision, *operations))
    assertIs<CommandOutcome.Accepted>(result.outcome)
    return result.state
  }

  @Test
  fun `declarations and ordered event bindings use the released wire in both directions`() {
    val operations =
      listOf(
        DesignOperation.SetStateVariable("expanded", flag),
        DesignOperation.SetEventBinding("button", "click", actions),
        DesignOperation.RemoveStateVariable("expanded"),
      )
    val wire =
      EditorSubmission.Batch(command("edit", 0, *operations.toTypedArray()))
        .toProtocolSubmission("actor", "browser", 0)
    val local = assertIs<LocalSubmissionMapping.Mapped>(wire.toLocalSubmission())
    val roundTrip = assertIs<LocalSubmissionRecordV1.Batch>(local.record).command.operations
    assertEquals(operations, roundTrip)
  }

  @Test
  fun `declare bind clear remove and undo redo retain exact state`() {
    var state =
      apply(
        CollaborationState(document()),
        "declare",
        DesignOperation.SetStateVariable("expanded", flag),
      )
    state = apply(state, "bind", DesignOperation.SetEventBinding("button", "click", actions))
    val wired = state.document
    state =
      apply(
        state,
        "clear",
        DesignOperation.SetEventBinding("button", "click", JsonArray(emptyList())),
      )
    assertTrue(state.document.nodes.getValue("button").eventBindings.isEmpty())
    state = apply(state, "remove", DesignOperation.RemoveStateVariable("expanded"))
    val undoRemove =
      CollaborationReducer.undo(
        state,
        UndoCommand("design", "undo-remove", "actor", "browser", 4, "remove"),
      )
    assertIs<CommandOutcome.Accepted>(undoRemove.outcome)
    val undoClear =
      CollaborationReducer.undo(
        undoRemove.state,
        UndoCommand("design", "undo-clear", "actor", "browser", 5, "clear"),
      )
    assertIs<CommandOutcome.Accepted>(undoClear.outcome)
    assertEquals(wired.copy(revision = 6), undoClear.state.document)
    val redoClear =
      CollaborationReducer.redo(
        undoClear.state,
        RedoCommand("design", "redo-clear", "actor", "browser", 6, "undo-clear"),
      )
    assertIs<CommandOutcome.Accepted>(redoClear.outcome)
    val redoRemove =
      CollaborationReducer.redo(
        redoClear.state,
        RedoCommand("design", "redo-remove", "actor", "browser", 7, "undo-remove"),
      )
    assertIs<CommandOutcome.Accepted>(redoRemove.outcome)
    assertTrue(redoRemove.state.document.stateVariables.isEmpty())
  }

  @Test
  fun `removing or narrowing live state is refused atomically`() {
    val state =
      apply(
        CollaborationState(document()),
        "wire",
        DesignOperation.SetStateVariable("expanded", flag),
        DesignOperation.SetEventBinding("button", "click", actions),
      )
    listOf(
        DesignOperation.RemoveStateVariable("expanded"),
        DesignOperation.SetStateVariable(
          "expanded",
          obj(
            """{"type":"text","valueType":"string","nullable":false,"initialValue":"no","persistence":"preview"}"""
          ),
        ),
      )
      .forEachIndexed { i, operation ->
        val result = CollaborationReducer.apply(state, command("bad-$i", 1, operation))
        assertIs<CommandOutcome.Rejected>(result.outcome)
        assertEquals(state.document, result.state.document)
      }
  }

  @Test
  fun `state removal cannot strand a binding nested in a modifier`() {
    val node =
      document()
        .nodes
        .getValue("button")
        .copy(
          modifiers =
            Json.parseToJsonElement(
                """[{"type":"alpha","value":{"type":"state","variable":"expanded"}}]"""
              )
              .jsonArray
        )
    val initial =
      CollaborationState(
        document()
          .copy(
            stateVariables = JsonObject(mapOf("expanded" to flag)),
            nodes = mapOf("button" to node),
          )
      )
    val result =
      CollaborationReducer.apply(
        initial,
        command("remove", 0, DesignOperation.RemoveStateVariable("expanded")),
      )
    assertEquals("modifiers", assertIs<CommandOutcome.Rejected>(result.outcome).field)
  }

  @Test
  fun `collaborator overwrite prevents undo and equal values still count as a write`() {
    val first =
      apply(
        CollaborationState(document()),
        "one",
        DesignOperation.SetStateVariable("expanded", flag),
      )
    val second =
      CollaborationReducer.apply(
        first,
        command("two", 0, DesignOperation.SetStateVariable("expanded", flag))
          .copy(actorId = "other"),
      )
    assertEquals(1, assertIs<CommandOutcome.Accepted>(second.outcome).conflicts.size)
    val undo =
      CollaborationReducer.undo(
        second.state,
        UndoCommand("design", "undo", "actor", "browser", 2, "one"),
      )
    assertEquals(
      RejectionCode.UNSAFE_COMPENSATION,
      assertIs<CommandOutcome.Rejected>(undo.outcome).code,
    )
  }

  @Test
  fun `undo declaration fails if another edit has since bound it`() {
    var state =
      apply(
        CollaborationState(document()),
        "declare",
        DesignOperation.SetStateVariable("expanded", flag),
      )
    state = apply(state, "bind", DesignOperation.SetEventBinding("button", "click", actions))
    val undo =
      CollaborationReducer.undo(
        state,
        UndoCommand("design", "undo", "actor", "browser", 2, "declare"),
      )
    assertEquals(
      RejectionCode.INVALID_DOCUMENT,
      assertIs<CommandOutcome.Rejected>(undo.outcome).code,
    )
    assertEquals(state.document, undo.state.document)
  }

  @Test
  fun `ordered actions read previous writes instead of a stale snapshot`() {
    val twice = JsonArray(actions + actions)
    assertEquals(
      listOf("expanded" to "true", "expanded" to "false"),
      uiBuilderStateWrites(twice, mapOf("expanded" to "false")),
    )
  }

  @Test
  fun `declaration edits update preview defaults without resetting other interactions`() {
    val before = JsonObject(mapOf("expanded" to flag, "unchanged" to flag, "removed" to flag))
    val changed = JsonObject(flag + ("initialValue" to JsonPrimitive(true)))
    val after = JsonObject(mapOf("expanded" to changed, "unchanged" to flag, "added" to flag))
    val preview =
      mutableMapOf<String, String?>(
        "expanded" to "false",
        "unchanged" to "true",
        "removed" to "true",
      )
    reconcilePreviewState(preview, before, after)
    assertEquals(
      mapOf<String, String?>("expanded" to "true", "unchanged" to "true", "added" to "false"),
      preview,
    )
  }
}
