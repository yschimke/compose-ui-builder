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
  }

  /**
   * One preview per shape, not one per footprint.
   *
   * `@PreviewParameter` unrolls a preview per value the provider yields — for the Large squircle a
   * constrained 182×112dp beside the 216×124dp the design is authored against — and a scaffold does
   * not need both to show what it looks like. The provider is still where the numbers come from, so
   * each preview keeps the shipped spec rather than inventing a frame; only the fan-out goes.
   * Picked by width, so the choice does not rest on the order a provider happens to yield.
   */
  @Test
  fun `each generated preview is one, at the container's own footprint`() {
    val source = generate()

    assertFalse("@PreviewParameter" in source, source)
    assertFalse("import androidx.compose.ui.tooling.preview.PreviewParameter" in source, source)
    assertTrue(
      "SquircleLargeWidgetPreviewParams().values.maxBy { it.widthDp }" in source,
      source,
    )
    assertTrue(
      "RectangularLargeWidgetPreviewParams().values.maxBy { it.widthDp }" in source,
      source,
    )
  }

  /**
   * The rectangular frame is generated beside the squircle, imported and all.
   *
   * The squircle is the host default and the frame the builder's canvas draws, so it is what a
   * designer compares against the design; the rectangular render is the one recommended as the
   * widget picker editor's image (yschimke/compose-preview-server#587), and a designer who has to
   * hand-write a second `@Preview` to see it will not see it. Both come from the shipped
   * size-specific providers, so neither invents a frame.
   */
  @Test
  fun `the file previews the widget in both host container shapes`() {
    val source = generate()

    assertTrue("@Preview(name = \"Squircle Preview\")" in source, source)
    assertTrue("fun ActivitySummaryWidgetSquirclePreview() =" in source, source)
    assertTrue("@Preview(name = \"Rectangular Preview\")" in source, source)
    assertTrue("fun ActivitySummaryWidgetRectangularPreview() =" in source, source)
    assertTrue(
      "import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams" in source,
      source,
    )
    assertTrue(
      "import androidx.glance.wear.tooling.preview.RectangularLargeWidgetPreviewParams" in source,
      source,
    )
    // The all-sizes providers stay out: a design is authored at one container size, and previewing
    // a Large design at the Small footprint would show a frame nobody drew.
    assertFalse("AllWidgetPreviewParams" in source, source)
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

  /**
   * A row's own alignment reaches the generated `RemoteRow`, centre included.
   *
   * The canvas has always read `verticalAlignment` off the node and defaults a row to
   * `CenterVertically`; the emitter read only the children's `alignVertical` modifiers, so a row
   * generated no alignment and the widget drew top-aligned while the canvas drew it centred —
   * silently, with no diagnostic (yschimke/compose-preview-server#518).
   *
   * The centre is written explicitly because `RemoteRow`'s own default is `Top`: emitting nothing
   * is what kept the two lanes disagreeing.
   */
  @Test
  fun `a row carries the alignment the canvas gives it`() {
    val source = generate(rowAlignmentDocument())

    assertTrue("verticalAlignment = RemoteAlignment.CenterVertically" in source, source)
  }

  /** A row that asks for `top` matches RemoteRow's own default, so it writes nothing. */
  @Test
  fun `a top-aligned row writes no alignment argument`() {
    val source = generate(rowAlignmentDocument(alignment = "top"))

    assertFalse("verticalAlignment" in source, source)
  }

  /** A row on the Large container, with only the node's own alignment to go on. */
  private fun rowAlignmentDocument(alignment: String? = null): UiBuilderDocument {
    val nodes =
      listOf(
        UiBuilderNode(
          id = "wear-widget-large",
          componentId = WearWidgetScaffoldSize.Large.componentId,
          slots = mapOf("content" to listOf("bar")),
        ),
        UiBuilderNode(
          id = "bar",
          componentId = "layout/row",
          properties =
            JsonObject(
              buildMap { alignment?.let { put("verticalAlignment", literal("enum", it)) } }
            ),
          modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
          slots = mapOf("children" to listOf("label")),
        ),
        text("label", "Nightcall", size = 14),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "row-alignment",
      title = "Row alignment",
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
