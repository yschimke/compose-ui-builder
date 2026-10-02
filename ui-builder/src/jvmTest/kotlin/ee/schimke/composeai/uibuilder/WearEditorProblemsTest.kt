package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Issues panel on a `wear-m3` design, judged by the generator that actually writes it.
 *
 * Reported from the deployed builder: a Wear screen holding one `wear-m3/button` showed "1 blocking
 * root cause · Missing code capability · No Kotlin symbol/import mapping exists" against the
 * button, while the Code pane beside it showed the screen's Kotlin. `wear-m3` leaves every
 * component's `code` null on purpose — `WearScreenCodeExporter` writes these designs, not
 * `CapabilityComposeCodeExporter` — so that diagnostic was the wrong exporter describing itself.
 */
class WearEditorProblemsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  private val seed =
    UiBuilderNewDesignSeed.document(
      designId = "wear-problems",
      catalogSystemId = "wear-m3",
      templateId = "wear-screen",
      catalogRevision = "wear-screen-scaffold-v1",
      nativeRuntimeId = "candidate",
      fixture =
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
    )

  /** The scaffold over a list over one button: the shape the New design chooser starts from. */
  private val screen =
    seed.copy(
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to
            UiBuilderNode(
              id = "screen",
              componentId = WearScreenCodeExporter.SCAFFOLD,
              slots = mapOf("content" to listOf("list")),
            ),
          "list" to
            UiBuilderNode(
              id = "list",
              componentId = "wear-m3/transforming-lazy-column",
              slots = mapOf("items" to listOf("cell-0")),
            ),
        ) + button("cell-0"),
    )

  @Test
  fun `a wear screen the wear generator writes reports no missing Kotlin symbol`() {
    // The pane and the panel must agree: this generates, so nothing blocks it.
    val code = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(screen))
    assertTrue("Button(" in code.kotlin, code.kotlin)

    val reported = reducer.problems(screen)

    assertEquals(emptyList(), reported.filter { it.blocking }, "$reported")
  }

  @Test
  fun `a wear screen its generator refuses is still reported, in the generator's words`() {
    // The panel is not silenced for Wear: a scaffold holding two content bodies is something
    // `WearScreenCodeExporter` refuses, and that refusal is about the design.
    val unlisted =
      screen.copy(
        nodes =
          screen.nodes +
            button("cell-1") +
            ("screen" to
              screen.nodes
                .getValue("screen")
                .copy(slots = mapOf("content" to listOf("list", "cell-1"))))
      )
    val refused = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(unlisted))

    val reported = reducer.problems(unlisted)

    assertTrue(
      reported.any { it.code == "COMPOSE_EXPORT_REFUSED" && it.message in refused.reasons },
      "$reported",
    )
    assertTrue(
      reported.none { it.code == "MISSING_CODE_CAPABILITY" },
      "the refusal is the Wear generator's, not the capability exporter's: $reported",
    )
  }

  @Test
  fun `the capability diagnostics no generator asks about are still reported on a wear screen`() {
    val drifted =
      screen.copy(
        catalogPin =
          JsonObject(screen.catalogPin + ("nativeRuntimeId" to JsonPrimitive("some-other-runtime")))
      )

    assertTrue(
      reducer.problems(drifted).any { it.code == "CATALOG_PIN_MISMATCH" && it.blocking },
      "${reducer.problems(drifted)}",
    )
  }

  /**
   * A bare `wear-m3/button` as the design's only root is neither a Wear screen nor a widget, so
   * neither Wear emitter writes it and the export refuses. That refusal is real and the panel keeps
   * it — but as its cause, which is the root, and with what to do about it. It used to be the
   * record-driven generator's "no component `wear-m3/button` in this catalog" once per component,
   * beside the capability exporter's "No Kotlin symbol/import mapping exists" on the node: three
   * symptoms, none of them saying to wrap the button in a screen.
   */
  @Test
  fun `a bare wear button at the root is refused by the real export, and the panel says why`() {
    val bare = seed.copy(roots = listOf("cell-0"), nodes = button("cell-0"))
    val refused = assertIs<EditorGeneratedCode.Refused>(reducer.generatedCode(bare))
    val cause =
      "the root is `wear-m3/button`, but a Wear design exports only as a Wear screen or a Wear " +
        "widget: put its content inside a `wear-m3/screen-scaffold` (or start from the " +
        "\"wear-screen\" template), or make the root a Wear widget container"
    assertEquals(listOf(cause), refused.reasons, "the Code pane says the same thing")

    val reported = reducer.problems(bare)

    assertEquals(
      listOf("COMPOSE_EXPORT_REFUSED" to cause),
      reported.filter { it.blocking }.map { it.code to it.message },
      "one root cause, in the export's words, and not the capability exporter's: $reported",
    )
  }

  private fun button(id: String) =
    mapOf(
      id to
        UiBuilderNode(
          id = id,
          componentId = "wear-m3/button",
          slots = mapOf("content" to listOf("$id-label")),
        ),
      "$id-label" to
        UiBuilderNode(
          id = "$id-label",
          componentId = "wear-m3/text",
          properties =
            JsonObject(
              mapOf(
                "text" to
                  JsonObject(
                    mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Start"))
                  )
              )
            ),
        ),
    )

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
