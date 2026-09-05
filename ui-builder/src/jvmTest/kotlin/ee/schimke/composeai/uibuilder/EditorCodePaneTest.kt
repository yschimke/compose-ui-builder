package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The code pane: the Kotlin an export would write, beside the canvas that produced it.
 *
 * ## What this is really asserting
 *
 * That the builder's proposition — a design *is* code — is visible while designing. Before this the
 * only way to read the source a design produced was to run an export and open the artifact, and the
 * pane that could have shown it lived in the other repository's builder, driven by a second
 * generator. This drives the **same** [ScreenExportGate] the server's export runs, so the pane
 * cannot show Kotlin the export would not write.
 *
 * The interesting case is the one that changes: a document is edited and the source follows. A test
 * that only asserted a snapshot of a fixed document would pass against a pane wired to a constant.
 */
class EditorCodePaneTest {

  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** The document a new design starts from: a scaffold holding a box, both backed by the record. */
  private fun blank(): UiBuilderDocument =
    blankUiBuilderDocument(
      designId = "code-pane",
      catalogPin = jetcaster.catalogPin,
      environment = jetcaster.environment,
    )

  @Test
  fun `a new design shows the Kotlin its export would write`() {
    val code = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(blank()))

    // The package the export emits into, not one this pane picked: a pane showing a different
    // package than the export uses would disagree on exactly the shadowing cases the generator
    // refuses.
    assertTrue(code.kotlin.contains("package ${ScreenExportGate.PACKAGE_NAME}"), code.kotlin)
    assertTrue(code.kotlin.contains("Scaffold("), code.kotlin)
    assertTrue(code.kotlin.contains("Box("), code.kotlin)
  }

  @Test
  fun `the source follows the document rather than a snapshot of it`() {
    val before = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(blank()))
    assertFalse(before.kotlin.contains("Text("), before.kotlin)

    val box = blank().nodes.getValue("screen-content")
    val withText =
      blank().let { document ->
        document.copy(
          nodes =
            document.nodes +
              mapOf(
                box.id to box.copy(slots = mapOf("children" to listOf("headline"))),
                "headline" to
                  UiBuilderNode(
                    id = "headline",
                    componentId = "m3/text",
                    // The wire shape a property carries: a typed value, not a bare primitive.
                    properties =
                      JsonObject(
                        mapOf(
                          "text" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("string"),
                                "value" to JsonPrimitive("Discover"),
                              )
                            )
                        )
                      ),
                  ),
              )
        )
      }

    val after = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(withText))
    assertTrue(after.kotlin.contains("""Text(text = "Discover""""), after.kotlin)
    // The catalog spells the slot `children`; `Box` names the parameter `content`. The pane reads
    // the export's own projection, so it prints what compiles rather than what the catalog says.
    assertTrue(after.kotlin.contains("content = {"), after.kotlin)
    assertFalse(after.kotlin.contains("children ="), after.kotlin)
  }

  @Test
  fun `a design the export refuses shows the reasons where the source would be`() {
    // The flagship fixture does not export — enum values with no Kotlin member, a state read
    // needing a `remember` preamble, an adaptive grid specification. A pane that went blank here
    // would hide the actionable half of the answer behind a different tab.
    val code = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(jetcaster))

    assertTrue(code.reasons.isNotEmpty())
    assertTrue(
      code.reasons.any { it.contains("state variable `searchQuery`") },
      code.reasons.toString(),
    )
    // The same answer the problems panel gives, because it is the same call.
    assertEquals(
      code.reasons.toSet(),
      reducer
        .problems(jetcaster)
        .filter { it.code == "COMPOSE_EXPORT_REFUSED" }
        .map { it.message }
        .toSet(),
    )
  }

  @Test
  fun `the pane toggles and survives the document changing underneath it`() {
    val opened = reducer.reduce(reducer.initial(blank()), UiBuilderEditorEvent.ToggleCodePane)
    assertTrue(opened.codePaneVisible)

    // A remote edit rebuilds the state around the new document. A pane that closed itself every
    // time a collaborator typed would be unusable in exactly the session it is most wanted.
    val rebuilt = reducer.reconciled(opened, jetcaster)
    assertTrue(rebuilt.codePaneVisible)

    assertFalse(reducer.reduce(opened, UiBuilderEditorEvent.ToggleCodePane).codePaneVisible)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
