package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.HostLogic
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.HostLogicLanguageV1
import ee.schimke.composeai.uibuilder.protocol.HostLogicV1
import ee.schimke.composeai.uibuilder.protocol.RemoveHostLogicMutationV1
import ee.schimke.composeai.uibuilder.protocol.SetHostLogicMutationV1
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Host logic is one document value: set, removed and undone like any other edit. */
class HostLogicEditorTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
      )
      .document

  @Test
  fun `host logic is set, removed and undone`() {
    val initial = reducer.initial(document)
    val set =
      reducer.reduce(initial, UiBuilderEditorEvent.SetHostLogic("export function init() {}"))
    assertIs<CommandOutcome.Accepted>(set.lastOutcome)
    assertEquals(HostLogic.encode("export function init() {}"), set.document.hostLogic)

    val removed = reducer.reduce(set, UiBuilderEditorEvent.SetHostLogic(null))
    assertNull(removed.document.hostLogic)

    val undone = reducer.reduce(removed, UiBuilderEditorEvent.Undo)
    assertEquals(HostLogic.encode("export function init() {}"), undone.document.hostLogic)
  }

  @Test
  fun `a program no engine could be handed is refused`() {
    val refused =
      reducer.reduce(
        reducer.initial(document),
        UiBuilderEditorEvent.SetHostLogic("x".repeat(HostLogic.MAX_SOURCE_LENGTH + 1)),
      )
    assertContains(assertIs<CommandOutcome.Rejected>(refused.lastOutcome).message, "64 KiB")
    assertNull(refused.document.hostLogic)
  }

  @Test
  fun `host logic reaches the protocol, and survives the document's round trip`() {
    fun mutation(operation: DesignOperation) =
      assertIs<DesignCommandV1>(
          EditorSubmission.Batch(DesignCommand("d", "o", "a", "c", 1, listOf(operation)))
            .toProtocolSubmission(actorId = "a", clientId = "c", authoritativeRevision = 1),
        )
        .operations
        .single()

    val logic = HostLogicV1.Builder(HostLogicLanguageV1.JAVASCRIPT, "export function init() {}")
    assertEquals(
      SetHostLogicMutationV1.Builder(logic.build()).build(),
      mutation(DesignOperation.SetHostLogic(HostLogic.encode("export function init() {}"))),
    )
    assertEquals(RemoveHostLogicMutationV1, mutation(DesignOperation.RemoveHostLogic))

    val withLogic = document.copy(hostLogic = HostLogic.encode("export function init() {}"))
    assertEquals(logic.build(), withLogic.toDesignDocumentV1().hostLogic)
    assertEquals(
      withLogic.hostLogic,
      withLogic.toDesignDocumentV1().toUiBuilderDocument().hostLogic,
    )
    // A design without host logic says nothing about it, so its document hash is unchanged.
    assertNull(document.toDesignDocumentV1().hostLogic)
  }

  @Test
  fun `a host input survives the wire document, so the editor can read back what it stored`() {
    val withInput =
      document.copy(
        stateVariables =
          JsonObject(
            document.stateVariables +
              ("calc.display" to
                Json.parseToJsonElement(
                    """{"type":"text","valueType":"string","nullable":false,"initialValue":"0",
                      "persistence":"host"}""",
                  )
                  .jsonObject),
          ),
        hostLogic = HostLogic.encode("export function init() {} export function onAction() {}"),
      )
    val wire = withInput.toDesignDocumentV1()
    assertEquals(withInput.stateVariables, wire.toUiBuilderDocument().stateVariables)
    assertEquals(
      mapOf("calc.display" to HostLogic.hostInputs(withInput).getValue("calc.display")),
      HostLogic.hostInputs(wire.toUiBuilderDocument()),
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
