package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WearWidgetCodeExporterTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  @Test
  fun `hello generates the sample's own shape`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(helloWidgetUiBuilderDocument("hello", pin, environment))
        )
        .source

    write("HelloWidget.kt", source)
    // The host's container appears nowhere: on-device the launcher draws it.
    assertTrue("WidgetContainer" !in source, source)
    assertTrue("widget-container" !in source, source)
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
    assertTrue(
      "WearWidgetDocument(background = WearWidgetBrush.color(colorScheme.primary))" in source,
      source,
    )
    assertTrue("fun HelloWidgetContent()" in source, source)
    assertTrue("SquircleSmallWidgetPreviewParams::class" in source, source)
  }

  @Test
  fun `weather generates its literal colours and its column`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(weatherWidgetUiBuilderDocument("weather", pin, environment))
        )
        .source

    write("WeatherWidget.kt", source)
    assertTrue("WearWidgetBrush.color(Color(0xFF2196F3).rc)" in source, source)
    assertTrue("RemoteColumn(" in source, source)
    assertTrue("SquircleLargeWidgetPreviewParams::class" in source, source)
  }

  /**
   * A gradient in the background slot becomes the `WearWidgetBrush` chain the container takes.
   *
   * `RemoteContentEmitter` has written this since the slot existed, but nothing could author it:
   * the reviewed `remote-m3` subset carried no component with a `DrawLayer` trait, so the slot was
   * unfillable from the palette and from any document the catalog validator would accept
   * (yschimke/compose-preview-server#428). `shape/linear-gradient` is in that subset now, and this
   * is the export end of it.
   */
  @Test
  fun `a linear gradient in the background slot is written as a brush chain`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val gradient =
      UiBuilderNode(
        id = "sky",
        componentId = "shape/linear-gradient",
        properties =
          JsonObject(
            mapOf(
              "startColor" to literal("color", "#FF2196F3"),
              "endColor" to literal("color", "#FF0D47A1"),
              "direction" to literal("enum", "leftToRight"),
            )
          ),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              gradient.id to gradient,
              scaffold.id to
                scaffold.copy(slots = scaffold.slots + ("background" to listOf(gradient.id))),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    write("GradientWidget.kt", source)
    assertTrue(
      "WearWidgetBrush.color(Color(0xFF2196F3).rc).horizontalGradient(" in source,
      source,
    )
    assertTrue("Color(0xFF2196F3).rc, Color(0xFF0D47A1).rc" in source, source)
    assertTrue("import androidx.glance.wear.horizontalGradient" in source, source)
  }

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  /** A screen is the Compose exporter's job, and saying so beats emitting something plausible. */
  @Test
  fun `a design that is not a widget is refused by name`() {
    val blank = blankUiBuilderDocument("screen", pin, environment)

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(blank))

    assertEquals(1, refused.reasons.size)
    assertTrue("layout/scaffold" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * The Code pane routes a widget design here rather than to the Compose gate.
   *
   * Without the branch a widget shows the gate's refusal — "no component record for remote-m3" —
   * which is true and useless: the design has generated code, just not that generator's.
   */
  @Test
  fun `the editor's code pane generates the widget, not a compose refusal`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)

    val generated = reducer.generatedCode(helloWidgetUiBuilderDocument("hello", pin, environment))

    val source = assertIs<EditorGeneratedCode.Source>(generated).kotlin
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-widget-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }
}
