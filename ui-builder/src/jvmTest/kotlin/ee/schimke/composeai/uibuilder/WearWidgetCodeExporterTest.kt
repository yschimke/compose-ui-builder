package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

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
