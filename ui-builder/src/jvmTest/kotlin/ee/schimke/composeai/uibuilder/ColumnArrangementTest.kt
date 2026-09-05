package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap

/**
 * `verticalArrangement` and `horizontalAlignment` on a Column, and `horizontalArrangement` on a
 * Row.
 *
 * All three were declared by the catalog and read by nobody: the renderer used only the spacing and
 * the Compose exporter emitted only `Arrangement.spacedBy` of it. A design asking for
 * `spaceBetween` got `Top` on the canvas *and* in the generated Kotlin, so the two agreed — by both
 * being wrong.
 *
 * Tested through pixels rather than through the arrangement object, because the object is what the
 * old code also produced; where the children land is the thing that was broken.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ColumnArrangementTest {
  private val reference =
    UiBuilderReducer.replay(
        kotlinx.serialization.json.Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json")).readText()
          )
          .let { it as JsonObject }
      )
      .document

  @Test
  fun `spaceBetween pushes the first and last child to the ends`() {
    val pixels = render("layout/column", "verticalArrangement" to "spaceBetween")

    assertTrue(pixels.isMarked(x = DOT / 2, y = DOT / 2), "first child at the top")
    assertTrue(pixels.isMarked(x = DOT / 2, y = SIZE - DOT / 2), "last child at the bottom")
    assertTrue(!pixels.isMarked(x = DOT / 2, y = SIZE / 2), "nothing left stacked in the middle")
  }

  @Test
  fun `the default is still top, so designs authored before this are unchanged`() {
    val pixels = render("layout/column")

    assertTrue(pixels.isMarked(x = DOT / 2, y = DOT / 2), "first child at the top")
    assertTrue(!pixels.isMarked(x = DOT / 2, y = SIZE - DOT / 2), "nothing pushed to the bottom")
  }

  @Test
  fun `horizontalAlignment moves the children across the axis`() {
    val start = render("layout/column")
    val end = render("layout/column", "horizontalAlignment" to "end")

    assertTrue(start.isMarked(x = DOT / 2, y = DOT / 2), "start-aligned child on the left")
    assertTrue(!start.isMarked(x = SIZE - DOT / 2, y = DOT / 2), "and not on the right")
    assertTrue(end.isMarked(x = SIZE - DOT / 2, y = DOT / 2), "end-aligned child on the right")
    assertTrue(!end.isMarked(x = DOT / 2, y = DOT / 2), "and not on the left")
  }

  @Test
  fun `a row distributes on its own axis the same way`() {
    val pixels = render("layout/row", "horizontalArrangement" to "spaceBetween")

    assertTrue(pixels.isMarked(x = DOT / 2, y = SIZE / 2), "first child at the start")
    assertTrue(pixels.isMarked(x = SIZE - DOT / 2, y = SIZE / 2), "last child at the end")
  }

  /**
   * The generated Kotlin has to say what the canvas draws, which is the half that was diverging.
   */
  @Test
  fun `the exporter emits the same arrangement the renderer resolves`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    val source =
      CapabilityComposeCodeExporter.export(
          document(
            "layout/column",
            "verticalArrangement" to "spaceBetween",
            "horizontalAlignment" to "end",
            childComponentId = "m3/text",
            pinned = true,
          ),
          catalog,
        )
        .requireSource()

    assertTrue("verticalArrangement = Arrangement.SpaceBetween" in source, source)
    assertTrue("horizontalAlignment = Alignment.End" in source, source)
  }

  /** A default-valued Column keeps the shorter form it has always generated. */
  @Test
  fun `the exporter leaves an untouched column exactly as it was`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )

    val source =
      CapabilityComposeCodeExporter.export(
          document("layout/column", childComponentId = "m3/text", pinned = true),
          catalog,
        )
        .requireSource()

    assertTrue("verticalArrangement = Arrangement.spacedBy(0.dp)" in source, source)
    assertTrue("horizontalAlignment" !in source, source)
  }

  private fun document(
    componentId: String,
    vararg properties: Pair<String, String>,
    childComponentId: String = "shape/colour-dot",
    pinned: Boolean = false,
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "arrangement",
      title = "Arrangement",
      revision = 0,
      // The export validates both; the render does not read either.
      catalogPin = if (pinned) reference.catalogPin else JsonObject(emptyMap()),
      environment = if (pinned) reference.environment else JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("container"),
      nodes =
        mapOf(
          "container" to
            UiBuilderNode(
              id = "container",
              componentId = componentId,
              properties =
                JsonObject(
                  properties.associate { (name, value) ->
                    name to
                      JsonObject(
                        mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive(value))
                      )
                  }
                ),
              modifiers =
                JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
              slots = mapOf("children" to listOf("first", "last")),
            ),
          "first" to marker("first", childComponentId),
          "last" to marker("last", childComponentId),
        ),
    )

  /** An 8dp white dot: small enough that "where is it?" has one answer per point probed. */
  private fun marker(id: String, componentId: String) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties =
        if (componentId == "m3/text")
          JsonObject(
            mapOf(
              "text" to
                JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(id)))
            )
          )
        else
          JsonObject(
            mapOf(
              "color" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FFFFFFFF"))
                ),
              "diameterDp" to
                JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(8))),
            )
          ),
      modifiers = JsonArray(emptyList()),
      slots = emptyMap(),
    )

  private fun render(componentId: String, vararg properties: Pair<String, String>): Bitmap {
    val image =
      renderComposeScene(SIZE, SIZE, Density(1f)) {
        UiBuilderSurface(document(componentId, *properties))
      }
    return Bitmap().apply { allocN32Pixels(SIZE, SIZE) }.also(image::readPixels)
  }

  /**
   * White markers on the surface's own (dark) background, so a bright pixel is a marker.
   *
   * Probed rather than measured because the arrangement object is exactly what the broken code also
   * produced; where the children land is the thing that was wrong.
   */
  private fun Bitmap.isMarked(x: Int, y: Int): Boolean {
    val colour = getColor(x, y)
    return ((colour shr 16 and 0xFF) + (colour shr 8 and 0xFF) + (colour and 0xFF)) > 600
  }

  private companion object {
    const val SIZE = 64

    /** The marker diameter, so a probe can name a dot's centre rather than a magic offset. */
    const val DOT = 8
  }
}
