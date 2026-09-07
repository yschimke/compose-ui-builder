package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the generated file has to be true of *as a file*, checked on a design that is larger than
 * either shipped template.
 *
 * The two sample widgets are one text and two texts, which is not enough to exercise the emitter's
 * own budgets: nothing they generate runs long, and neither declares a gradient. This design — a
 * nested column and row on a literal vertical gradient — was built to find out, and found three
 * things the samples could not: an import for the gradient direction the file does not use, a
 * `WearWidgetDocument(...)` line 34 columns over the budget, and a container call pushed two
 * columns over by the ` {` the emitter appends after measuring.
 *
 * Kept as a test rather than a template because it answers a generator question, not a "what does a
 * widget look like" one — the four templates in `remote-m3` are the answer to that.
 */
class WearWidgetGeneratedSourceQualityTest {
  @Test
  fun `an interesting large widget generates a file that keeps the emitter's own budgets`() {
    val source = generate()

    val long = source.lines().filter { it.length > MAX_LINE }
    assertTrue(long.isEmpty(), "over $MAX_LINE columns:\n${long.joinToString("\n")}")
  }

  /** The direction the design does not use is not imported; the one it does, is. */
  @Test
  fun `a vertical gradient imports the vertical factory alone`() {
    val source = generate()

    assertTrue("import androidx.glance.wear.verticalGradient" in source, source)
    assertFalse("import androidx.glance.wear.horizontalGradient" in source, source)
  }

  /**
   * A widget's canvas is its `WearWidgetParams`, so a `device` on the preview describes a screen
   * the widget never occupies — and a renderer honouring it draws the design at phone size rather
   * than at the 216×124dp frame it was authored in.
   */
  @Test
  fun `the generated preview names no device`() {
    val source = generate()

    assertTrue("@Preview(name = \"Squircle Preview\")" in source, source)
    assertFalse("device =" in source, source)
    // The provider is still the fan-out: it yields every footprint the Large container ships.
    assertTrue("SquircleLargeWidgetPreviewParams::class" in source, source)
  }

  /**
   * The generated source, also written out beside the two samples' — that file is the fixture
   * `:samples:wear-widget` (yschimke/compose-ai-tools) compiles and renders, which is the half of
   * "the generator works" no test on this side of the repository split can answer.
   */
  private fun generate(document: UiBuilderDocument = activitySummaryDocument()): String {
    val result = WearWidgetCodeExporter.export(document, PACKAGE_NAME)
    val source =
      when (result) {
        is WearWidgetCodeExporter.Result.Emitted -> result.source
        is WearWidgetCodeExporter.Result.Refused ->
          throw AssertionError("refused: ${result.reasons}")
      }
    val directory = Path.of("build", "generated-widget-source")
    Files.createDirectories(directory)
    // Named from the document, so a second design written through here cannot overwrite the
    // activity-summary file the samples are compared against.
    val name = if (document.id == "activity-summary") "ActivitySummaryWidget" else document.id
    Files.writeString(directory.resolve("$name.kt"), source)
    return source
  }

  /**
   * A `#RRGGBB` colour is emitted opaque, because `Color` reads its argument as ARGB.
   *
   * Six digits spliced straight through produced `Color(0x1DB954)` — alpha `0x00`, which compiles
   * and draws nothing (yschimke/compose-preview-server#516). Every colour in the design above is
   * eight digits, which is why nothing here caught it; the validator asks authors for `#RRGGBB`, so
   * the documented spelling was the one that broke.
   *
   * Both widths are pinned: padding six, and leaving eight alone rather than double-prefixing it.
   */
  @Test
  fun `a six-digit colour is emitted opaque and an eight-digit one is left alone`() {
    val source = generate(colourDocument())

    assertTrue("Color(0xFF1DB954)" in source, source)
    assertTrue("Color(0xFFFFFFFF)" in source, source)
    assertTrue("Color(0x80123456)" in source, source)
    // The transparent forms the splice used to produce.
    assertFalse("Color(0x1DB954)" in source, source)
    assertFalse("Color(0xFFFF80123456)" in source, source)
  }

  /** One text per colour spelling the validator admits, on the Large container. */
  private fun colourDocument(): UiBuilderDocument {
    val nodes =
      listOf(
        UiBuilderNode(
          id = "wear-widget-large",
          componentId = WearWidgetScaffoldSize.Large.componentId,
          slots = mapOf("content" to listOf("stack")),
        ),
        UiBuilderNode(
          id = "stack",
          componentId = "layout/column",
          modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
          slots = mapOf("children" to listOf("six", "white", "eight")),
        ),
        text("six", "Six", size = 12, color = "#1DB954"),
        text("white", "White", size = 12, color = "#FFFFFF"),
        text("eight", "Eight", size = 12, color = "#80123456"),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "colour-widths",
      title = "Colour widths",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("wear-widget-large"),
      nodes = nodes.associateBy(UiBuilderNode::id),
    )
  }

  private fun activitySummaryDocument(): UiBuilderDocument {
    val nodes =
      listOf(
        UiBuilderNode(
          id = "wear-widget-large",
          componentId = WearWidgetScaffoldSize.Large.componentId,
          slots = mapOf("content" to listOf("root-box"), "background" to listOf("bg-gradient")),
        ),
        UiBuilderNode(
          id = "bg-gradient",
          componentId = "shape/linear-gradient",
          properties =
            JsonObject(
              mapOf(
                "startColor" to literal("color", "#FF00363D"),
                "endColor" to literal("color", "#FF004F58"),
                "direction" to literal("enum", "topToBottom"),
              )
            ),
        ),
        UiBuilderNode(
          id = "root-box",
          componentId = "layout/box",
          modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
          slots = mapOf("children" to listOf("stack")),
        ),
        UiBuilderNode(
          id = "stack",
          componentId = "layout/column",
          properties =
            JsonObject(
              mapOf(
                "verticalSpacingDp" to literal("float", 2),
                "alignment" to literal("enum", "center"),
              )
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = mapOf("children" to listOf("day", "reading-row", "goal")),
        ),
        text("day", "TUESDAY", size = 12, color = "#FF9BE7F0", fullWidth = true),
        UiBuilderNode(
          id = "reading-row",
          componentId = "layout/row",
          properties = JsonObject(mapOf("horizontalSpacingDp" to literal("float", 6))),
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = mapOf("children" to listOf("steps", "unit")),
        ),
        text("steps", "8,412", size = 32, colorToken = "onSurface"),
        text("unit", "steps", size = 13, color = "#FF9BE7F0"),
        text("goal", "68% of daily goal", size = 11, color = "#FFB8C8CB", fullWidth = true),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "activity-summary",
      title = "Activity summary · ${WearWidgetScaffoldSize.Large.label}",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("wear-widget-large"),
      nodes = nodes.associateBy(UiBuilderNode::id),
    )
  }

  private fun text(
    id: String,
    value: String,
    size: Int,
    color: String? = null,
    colorToken: String? = null,
    fullWidth: Boolean = false,
  ): UiBuilderNode =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        JsonObject(
          buildMap {
            put("text", literal("string", value))
            put("fontSizeSp", literal("float", size))
            color?.let { put("color", literal("color", it)) }
            colorToken?.let { put("color", literal("colorToken", it)) }
            if (fullWidth) {
              put("textAlign", literal("enum", "center"))
              put("maxLines", literal("int", 1))
            }
          }
        ),
      modifiers =
        if (fullWidth) JsonArray(listOf(modifier("fillMaxWidth"))) else JsonArray(emptyList()),
    )

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun literal(type: String, value: Int): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun modifier(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))

  private companion object {
    /** ktfmt's default, which the emitter says it writes to. */
    const val MAX_LINE = 100

    /** The sample module the written file is compiled and rendered in. */
    const val PACKAGE_NAME = "com.example.wearwidget"
  }
}
