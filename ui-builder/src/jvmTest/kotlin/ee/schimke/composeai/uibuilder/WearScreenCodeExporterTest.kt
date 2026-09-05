package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class WearScreenCodeExporterTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  /**
   * The whole point of the stand-in, in one assertion set.
   *
   * The canvas draws a stadium with a plain Column in it. What has to come out the other end is the
   * real thing: `ScreenScaffold` around a `TransformingLazyColumn`, with the row transformation on
   * every item. If the generated source ever stops carrying `transformedHeight` and
   * `SurfaceTransformation`, the fake container has stopped being a stand-in and become a lie.
   */
  @Test
  fun `the screen generates a real ScreenScaffold over a TransformingLazyColumn`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    write("ActivityScreen.kt", source)
    assertTrue("ScreenScaffold(scrollState = listState" in source, source)
    assertTrue("TransformingLazyColumn(" in source, source)
    assertTrue("contentPadding = contentPadding," in source, source)
    assertTrue("Modifier.transformedHeight(this, spec)" in source, source)
    assertTrue("transformation = SurfaceTransformation(spec)" in source, source)
    assertTrue("val spec = rememberTransformationSpec()" in source, source)
    // The stand-in is emitted, not erased — the opposite of the widget container.
    assertTrue("screen-scaffold" !in source, source)
    assertTrue("transforming-lazy-column" !in source, source)
  }

  /** The status strip is `AppScaffold`'s, not `ScreenScaffold`'s, and it is frozen. */
  @Test
  fun `a declared timeText generates the AppScaffold that owns it`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    assertTrue(
      "AppScaffold(timeText = { TimeText { timeTextCurvedText(\"10:10\") } })" in source,
      source,
    )
  }

  /** Every round size, because where a Wear list wraps is the question a single render dodges. */
  @Test
  fun `the generated preview fans out across the round devices`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    assertTrue("@WearPreviewDevices" in source, source)
    assertTrue("fun ActivityScreenPreview() = ActivityScreen()" in source, source)
  }

  /** A widget is the other generator's job, and saying so beats emitting something plausible. */
  @Test
  fun `a design that is not a wear screen is refused by name`() {
    val widget = helloWidgetUiBuilderDocument("hello", pin, environment)

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(widget))

    assertEquals(1, refused.reasons.size)
    assertTrue(
      "remote-m3/widget-container-small" in refused.reasons.single(),
      refused.reasons.single(),
    )
  }

  /**
   * A borrowed component with no Wear counterpart is named, not approximated.
   *
   * `wear-m3` lends its content components from `m3-catalog` while it has none of its own, and the
   * canvas will happily draw all of them. Only some map to Wear Compose Material 3, and a generator
   * that guessed at the rest would emit Kotlin that does not compile — which is worse than a
   * refusal that says which node to replace.
   */
  @Test
  fun `a borrowed component with no Wear counterpart is refused by node`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-list" to list.copy(slots = mapOf("items" to listOf("chip")))) +
            ("chip" to UiBuilderNode(id = "chip", componentId = "m3/filter-chip"))
      )

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(document))

    assertTrue("m3/filter-chip" in refused.reasons.single(), refused.reasons.single())
    assertTrue("chip" in refused.reasons.single(), refused.reasons.single())
  }

  /** The Code pane routes a Wear screen here rather than to the Compose gate's record refusal. */
  @Test
  fun `the editor's code pane generates the screen, not a compose refusal`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)

    val generated = reducer.generatedCode(wearScreenUiBuilderDocument("activity", pin, environment))

    val source = assertIs<EditorGeneratedCode.Source>(generated).kotlin
    assertTrue("TransformingLazyColumn(" in source, source)
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-wear-screen-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }
}
