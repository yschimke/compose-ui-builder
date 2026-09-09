package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The smallest design the reducer will accept edits to, plus the commands the tests make of it.
 *
 * Deliberately not the Jetcaster fixture: these tests are about storage, replay and compaction, and
 * a fixture large enough to be realistic would make the compaction thresholds they assert on
 * arbitrary rather than legible.
 */
internal object LocalDesignFixtures {
  const val DESIGN_ID: String = "local-fixture"
  const val CATALOG_SYSTEM_ID: String = "m3-catalog"

  fun document(designId: String = DESIGN_ID): UiBuilderDocument =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = designId,
      title = "Local fixture",
      revision = 0,
      catalogPin =
        buildJsonObject {
          put("systemId", JsonPrimitive(CATALOG_SYSTEM_ID))
          put("catalogRevision", JsonPrimitive("test"))
          put("capabilityDigest", JsonPrimitive("test"))
          put("nativeRuntimeId", JsonPrimitive("test"))
        },
      // Every field `DesignEnvironmentV1` requires: the fixture has to survive the projection into
      // the released document shape, which is what a snapshot carries.
      environment =
        buildJsonObject {
          put("widthDp", JsonPrimitive(360))
          put("heightDp", JsonPrimitive(800))
          put("density", JsonPrimitive(2.0))
          put("theme", JsonPrimitive("light"))
          put("locale", JsonPrimitive("en-US"))
          put("fontScale", JsonPrimitive(1.0))
          put("layoutDirection", JsonPrimitive("ltr"))
        },
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("root"),
      nodes = mapOf("root" to UiBuilderNode(id = "root", componentId = "m3/column")),
    )

  /** Deleting a node the design does not have, which the reducer refuses rather than converges. */
  fun deleteMissing(operationId: String, baseRevision: Int): LocalSubmissionRecordV1.Batch =
    LocalSubmissionRecordV1.Batch(
      DesignCommand(
        designId = DESIGN_ID,
        operationId = operationId,
        actorId = "tester",
        clientId = "test-client",
        baseRevision = baseRevision,
        operations = listOf(DesignOperation.DeleteNode("nothing-here")),
      )
    )

  /** One text node appended to the root column, at [baseRevision]. */
  fun insert(
    nodeId: String,
    operationId: String = "op-$nodeId",
    baseRevision: Int,
    afterNodeId: String? = null,
  ): LocalSubmissionRecordV1.Batch =
    LocalSubmissionRecordV1.Batch(
      DesignCommand(
        designId = DESIGN_ID,
        operationId = operationId,
        actorId = "tester",
        clientId = "test-client",
        baseRevision = baseRevision,
        operations =
          listOf(
            DesignOperation.InsertNode(
              node = UiBuilderNode(id = nodeId, componentId = "m3/text"),
              parent = ParentSlot("root", "content"),
              afterNodeId = afterNodeId,
            )
          ),
      )
    )
}
