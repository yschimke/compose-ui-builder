package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Issues panel on a `wear-m3` design, judged by the generator that actually writes it
 * (`WearScreenCodeExporter`). `wear-m3` leaves every component's `code` null on purpose, so
 * `CapabilityComposeCodeExporter`'s "No Kotlin symbol/import mapping exists" must not be reported.
 */
class WearEditorProblemsTest {
  private val catalog = SyntheticCatalogs.wear
  private val reducer = UiBuilderEditorReducer(catalog)

  private val seed =
    publishedTestSeed(
      systemId = "wear-m3",
      templateId = "wear-screen",
      designId = "wear-problems",
      catalogRevision = "wear-screen-scaffold-v1",
      nativeRuntimeId = "candidate",
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
   * A bare `wear-m3/button` as the only root is neither a Wear screen nor a widget, so the export
   * really refuses; the panel reports it once, at the root cause, saying to wrap it in a screen.
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
