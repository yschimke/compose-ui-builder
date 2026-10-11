package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
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
 * The code pane: the Kotlin an export would write, beside the canvas that produced it, driven by
 * the same [ScreenExportGate] as the server's export. The key case is an edit the source then
 * follows (a fixed snapshot would pass against a constant).
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
  fun `a Remote catalog shows source for a plain layout root`() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
    val remoteCatalog =
      catalog.copy(
        statusSemantics =
          JsonObject(catalog.statusSemantics + ("platform" to JsonPrimitive("remote-compose")))
      )
    val document = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString<UiBuilderDocument>(
        java.io
          .File(
            System.getProperty("uiBuilderProjectDir"),
            "../docs/design/evidence/ui-builder-live-document-preview/sample.uid",
          )
          .readText()
      )
    val code =
      assertIs<EditorGeneratedCode.Source>(
        UiBuilderEditorReducer(remoteCatalog).generatedCode(document)
      )
    assertTrue("RemoteStateLayout" in code.kotlin, code.kotlin)
    assertTrue(".clickable(valueChange(page, 20.ri))" in code.kotlin, code.kotlin)
    assertFalse("WearWidget" in code.kotlin, code.kotlin)
  }

  @Test
  fun `the existing code pane uses production bound callback factories`() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
    val remoteCatalog =
      catalog.copy(
        statusSemantics =
          JsonObject(catalog.statusSemantics + ("platform" to JsonPrimitive("remote-compose")))
      )
    val document = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString<UiBuilderDocument>(
        java.io
          .File(
            System.getProperty("uiBuilderProjectDir"),
            "../experiments/remote-state-selection/bound-actions.uid",
          )
          .readText()
      )
    val code =
      assertIs<EditorGeneratedCode.Source>(
          UiBuilderEditorReducer(remoteCatalog).generatedCode(document)
        )
        .kotlin
    val expected =
      assertIs<RecordFreeExport.Generated.Emitted>(
          RecordFreeExport.generate(document, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
        )
        .source
    assertEquals(expected, code)
    assertTrue("valueChange(page, actionValue0.ri)" in code)
    assertTrue("clickable(capture0(argument1))" in code)
  }

  @Test
  fun `a new design shows the Kotlin its export would write`() {
    val code = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(blank()))

    // The export's own package, so shadowing cases agree with the generator.
    assertTrue(code.kotlin.contains("package ${ScreenExportGate.PACKAGE_NAME}"), code.kotlin)
    assertTrue(code.kotlin.contains("Scaffold("), code.kotlin)
    // The body sits below the bars: Scaffold hands its content the padding they occupy, and the
    // export spends it on the content first, as the canvas does.
    assertTrue(code.kotlin.contains("{ contentPadding ->"), code.kotlin)
    assertTrue(
      code.kotlin.contains("Box(modifier = Modifier.padding(contentPadding)) {"),
      code.kotlin,
    )
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
    // The pane prints the export's projection (`Box`'s `content`, as a trailing lambda), not the
    // catalog's slot name `children`.
    assertTrue(
      after.kotlin.contains("Box(modifier = Modifier.padding(contentPadding)) {"),
      after.kotlin,
    )
    assertFalse(after.kotlin.contains("children ="), after.kotlin)
  }

  /** `increment` is refused by name until the shared generator can lower it. */
  @Test
  fun `a counter button names the missing increment lowering in the screen export`() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
    val counter =
      blankUiBuilderDocument(
        designId = "counter",
        catalogPin = jetcaster.catalogPin,
        environment = jetcaster.environment,
        state =
          listOf(
            ee.schimke.composeai.uibuilder.export.NewDesignState(
              "count",
              ee.schimke.composeai.uibuilder.export.NewDesignStateType.Number,
              JsonPrimitive(3),
            )
          ),
      )
    val initial = reducer.initial(counter, "screen-content")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/button"))
    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponentWithAction(
          "m3/button",
          target,
          ee.schimke.composeai.uibuilder.editor.EditorStateAction.Increment("count", "2"),
        ),
      )

    val code = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(inserted.document))
    assertTrue(code.reasons.any { "increments `count`" in it }, code.reasons.toString())
  }

  @Test
  fun `a design the export refuses shows the reasons where the source would be`() {
    // The flagship fixture doesn't export (enum values without Kotlin members, a text callback
    // needing its event value, an adaptive grid spec); the pane shows the reasons rather than going
    // blank.
    val code = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(jetcaster))

    assertTrue(code.reasons.isNotEmpty())
    assertTrue(
      code.reasons.any { it.contains("eventBindings.valueChange") && it.contains("callback") },
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
