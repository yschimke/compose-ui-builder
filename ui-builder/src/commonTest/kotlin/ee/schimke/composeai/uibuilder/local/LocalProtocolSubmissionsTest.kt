package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The wire spelling, and back again.
 *
 * The local session's whole claim is that the editor talks to it in exactly the shape it talks to
 * the server, so the operations that come out of the round trip have to be the operations that went
 * in. Anything that survives one direction and not the other would be an edit that behaves
 * differently offline, which is the one thing this mode must not do.
 */
class LocalProtocolSubmissionsTest {
  private fun roundTrip(operations: List<DesignOperation>): List<DesignOperation> {
    val command =
      DesignCommand(
        designId = "d",
        operationId = "op-1",
        actorId = "tester",
        clientId = "client",
        baseRevision = 3,
        operations = operations,
      )
    val wire =
      EditorSubmission.Batch(command)
        .toProtocolSubmission(actorId = "tester", clientId = "client", authoritativeRevision = 3)
    val mapped = wire.toLocalSubmission()
    assertIs<LocalSubmissionMapping.Mapped>(mapped)
    val batch = mapped.record
    assertIs<LocalSubmissionRecordV1.Batch>(batch)
    assertEquals(command.copy(operations = batch.command.operations), batch.command)
    return batch.command.operations
  }

  @Test
  fun `structural operations survive the round trip unchanged`() {
    val operations =
      listOf(
        DesignOperation.InsertNode(
          node = UiBuilderNode(id = "text-1", componentId = "m3/text"),
          parent = ParentSlot("root", "content"),
          afterNodeId = "text-0",
        ),
        DesignOperation.MoveNode("text-1", ParentSlot("root", "content"), afterNodeId = null),
        DesignOperation.DeleteNode("text-0"),
        DesignOperation.RestoreNode("text-0"),
      )

    assertEquals(operations, roundTrip(operations))
  }

  @Test
  fun `a property write, its removal and a modifier chain survive the round trip`() {
    val operations =
      listOf(
        DesignOperation.SetProperty(
          nodeId = "text-1",
          property = "text",
          value = Json.parseToJsonElement("""{"type":"string","value":"Hello"}""").jsonObject,
        ),
        // #480's explicit removal, which used to be a null `setProperty` on the wire.
        DesignOperation.RemoveNodeProperty("text-1", "style"),
        DesignOperation.SetModifiers(
          nodeId = "text-1",
          modifiers =
            buildJsonArray {
              add(
                Json.parseToJsonElement(
                  """{"type":"padding","startDp":8,"topDp":8,"endDp":8,"bottomDp":8}"""
                )
              )
            },
        ),
      )

    assertEquals(operations, roundTrip(operations))
  }

  @Test
  fun `environment writes survive as the field-granular operations the reducer versions`() {
    val operations =
      listOf(
        DesignOperation.SetEnvironment("widthDp", JsonPrimitive(412)),
        DesignOperation.SetEnvironment("theme", JsonPrimitive("dark")),
        DesignOperation.SetEnvironment("layoutDirection", JsonPrimitive("rtl")),
        DesignOperation.SetEnvironment("fontScale", JsonPrimitive(1.5)),
        DesignOperation.SetEnvironment(
          "exportDevices",
          buildJsonArray { add(JsonPrimitive("pixel-8")) },
        ),
        // The empty set is the document's own default, and the wire says it with a reset rather
        // than an empty list — so this is the one value whose spelling changes on the way out.
        DesignOperation.SetEnvironment("exportDevices", buildJsonArray {}),
      )

    assertEquals(operations, roundTrip(operations))
  }

  @Test
  fun `undo and redo carry their target across`() {
    val undo =
      EditorSubmission.Undo(
          ee.schimke.composeai.uibuilder.UndoCommand(
            designId = "d",
            operationId = "undo-1",
            actorId = "tester",
            clientId = "client",
            baseRevision = 4,
            targetOperationId = "op-1",
          )
        )
        .toProtocolSubmission("tester", "client", 4)
        .toLocalSubmission()

    assertIs<LocalSubmissionMapping.Mapped>(undo)
    val record = undo.record
    assertIs<LocalSubmissionRecordV1.Undo>(record)
    assertEquals("op-1", record.command.targetOperationId)
    assertEquals(4, record.command.baseRevision)
  }
}
