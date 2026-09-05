package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The modifier chain, as something a client can write.
 *
 * [DesignOperation.SetModifiers] is the reducer half of `SetModifiersMutationV1`: whole-chain,
 * node-granular, and compensable on its own lane. Padding a container used to mean deleting it and
 * building it again, because `SetProperty` reaches `properties` and nothing reached this.
 */
class SetModifiersOperationTest {
  @Test
  fun `a chain is written whole and read back`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("pad", 4, DesignOperation.SetModifiers("a", chain(padding(12)))),
      )

    val accepted = assertIs<CommandOutcome.Accepted>(application.outcome)
    assertEquals(5, accepted.committedRevision)
    assertEquals(chain(padding(12)), application.state.document.nodes.getValue("a").modifiers)
  }

  @Test
  fun `an empty chain is a value rather than an absence`() {
    val padded =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("pad", 4, DesignOperation.SetModifiers("a", chain(padding(12)))),
      )
    val cleared =
      CollaborationReducer.apply(
        padded.state,
        command("clear", 5, DesignOperation.SetModifiers("a", JsonArray(emptyList()))),
      )

    assertIs<CommandOutcome.Accepted>(cleared.outcome)
    assertEquals(
      JsonArray(emptyList()),
      cleared.state.document.nodes.getValue("a").modifiers,
    )
  }

  @Test
  fun `a modifier this renderer cannot apply is refused rather than committed`() {
    val initial = CollaborationState(document())
    val application =
      CollaborationReducer.apply(
        initial,
        command(
          "unknown",
          4,
          DesignOperation.SetModifiers("a", chain(buildJsonObject { put("type", "shimmer") })),
        ),
      )

    val rejected = assertIs<CommandOutcome.Rejected>(application.outcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("a", rejected.nodeId)
    assertEquals("modifiers[0]", rejected.field)
    assertEquals(initial.document, application.state.document)
  }

  @Test
  fun `a size naming neither dimension is refused for the same reason`() {
    val application =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command(
          "sizeless",
          4,
          DesignOperation.SetModifiers("a", chain(buildJsonObject { put("type", "size") })),
        ),
      )

    assertEquals(
      RejectionCode.INVALID_PROPERTY,
      assertIs<CommandOutcome.Rejected>(application.outcome).code,
    )
  }

  @Test
  fun `undo puts the previous chain back and redo writes it again`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("pad", 4, DesignOperation.SetModifiers("a", chain(padding(12)))),
      )
    val second =
      CollaborationReducer.apply(
        first.state,
        command("fill", 5, DesignOperation.SetModifiers("a", chain(fillMaxWidth()))),
      )

    val undone =
      CollaborationReducer.undo(
        second.state,
        UndoCommand("design", "undo-fill", "actor-a", "browser-a", 6, "fill"),
      )
    assertIs<CommandOutcome.Accepted>(undone.outcome)
    assertEquals(chain(padding(12)), undone.state.document.nodes.getValue("a").modifiers)

    val redone =
      CollaborationReducer.redo(
        undone.state,
        RedoCommand("design", "redo-fill", "actor-a", "browser-a", 7, "undo-fill"),
      )
    assertIs<CommandOutcome.Accepted>(redone.outcome)
    assertEquals(chain(fillMaxWidth()), redone.state.document.nodes.getValue("a").modifiers)
  }

  @Test
  fun `a chain written since the base revision is committed with a stale-write conflict`() {
    val first =
      CollaborationReducer.apply(
        CollaborationState(document()),
        command("pad", 4, DesignOperation.SetModifiers("a", chain(padding(12)))),
      )
    val stale =
      CollaborationReducer.apply(
        first.state,
        command("fill", 4, DesignOperation.SetModifiers("a", chain(fillMaxWidth()))),
      )

    assertIs<CommandOutcome.Accepted>(stale.outcome)
    val conflict = stale.state.acceptedCommands.getValue("fill").conflicts.single()
    assertEquals(ConflictCode.STALE_PROPERTY_WRITE, conflict.code)
    assertEquals("a", conflict.nodeId)
    assertEquals("modifiers", conflict.field)
  }

  private fun chain(vararg modifiers: JsonObject): JsonArray = buildJsonArray {
    modifiers.forEach { add(it) }
  }

  private fun padding(dp: Int): JsonObject = buildJsonObject {
    put("type", "padding")
    put("startDp", dp)
    put("topDp", dp)
    put("endDp", dp)
    put("bottomDp", dp)
  }

  private fun fillMaxWidth(): JsonObject = buildJsonObject { put("type", "fillMaxWidth") }

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

  private fun document(): UiBuilderDocument =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Modifiers",
      revision = 4,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("a"),
      nodes = mapOf("a" to UiBuilderNode("a", "column")),
    )
}
