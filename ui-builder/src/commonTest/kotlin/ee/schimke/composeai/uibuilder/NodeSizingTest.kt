package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorAxis
import ee.schimke.composeai.uibuilder.editor.EditorLayoutScope
import ee.schimke.composeai.uibuilder.editor.EditorSizing
import ee.schimke.composeai.uibuilder.editor.ResizeHandle
import ee.schimke.composeai.uibuilder.editor.nodeSizing
import ee.schimke.composeai.uibuilder.editor.resizePreview
import ee.schimke.composeai.uibuilder.editor.resizeToggle
import ee.schimke.composeai.uibuilder.editor.resizedModifierChain
import ee.schimke.composeai.uibuilder.editor.sizingOf
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * What a resize writes: the modifiers that decide the axis come out, at most one goes back where
 * the first one was, and nothing else in the chain moves.
 */
class NodeSizingTest {
  private val everything =
    setOf(
      "width",
      "height",
      "size",
      "fillMaxWidth",
      "fillMaxHeight",
      "fillMaxSize",
      "weight",
      "padding",
    )

  private fun chain(source: String): List<JsonElement> = Json.parseToJsonElement(source).jsonArray

  private fun resize(
    source: String,
    axis: EditorAxis,
    sizing: EditorSizing,
    scope: EditorLayoutScope? = null,
    declared: Set<String> = everything,
  ): String? =
    resizedModifierChain(chain(source), axis, sizing, declared, scope)?.let {
      JsonArray(it).toString()
    }

  @Test
  fun `a fixed width goes first when there was none`() {
    assertEquals(
      """[{"type":"width","widthDp":120},{"type":"padding","startDp":8,"topDp":0,"endDp":8,"bottomDp":0}]""",
      resize(
        """[{"type":"padding","startDp":8,"topDp":0,"endDp":8,"bottomDp":0}]""",
        EditorAxis.Width,
        EditorSizing.Fixed(120f),
      ),
    )
  }

  @Test
  fun `an existing width is replaced in place and padding before it is taken off the outer size`() {
    assertEquals(
      """[{"type":"padding","startDp":8,"topDp":0,"endDp":8,"bottomDp":0},{"type":"width","widthDp":104},{"type":"alpha","alpha":0.5}]""",
      resize(
        """[{"type":"padding","startDp":8,"topDp":0,"endDp":8,"bottomDp":0},{"type":"width","widthDp":50},{"type":"alpha","alpha":0.5}]""",
        EditorAxis.Width,
        EditorSizing.Fixed(120f),
      ),
    )
  }

  @Test
  fun `fill replaces a fixed width, and hug takes it away`() {
    assertEquals(
      """[{"type":"fillMaxWidth"}]""",
      resize("""[{"type":"width","widthDp":50}]""", EditorAxis.Width, EditorSizing.Fill),
    )
    assertEquals(
      "[]",
      resize("""[{"type":"fillMaxWidth"}]""", EditorAxis.Width, EditorSizing.Hug),
    )
  }

  @Test
  fun `fill on a row's main axis is a weight`() {
    assertEquals(
      """[{"type":"weight","weight":1}]""",
      resize("[]", EditorAxis.Width, EditorSizing.Fill, EditorLayoutScope.Row),
    )
    // Across the row it is still a plain fill.
    assertEquals(
      """[{"type":"fillMaxHeight"}]""",
      resize("[]", EditorAxis.Height, EditorSizing.Fill, EditorLayoutScope.Row),
    )
    // And hugging the main axis takes the weight off.
    assertEquals(
      "[]",
      resize(
        """[{"type":"weight","weight":2}]""",
        EditorAxis.Width,
        EditorSizing.Hug,
        EditorLayoutScope.Row,
      ),
    )
  }

  @Test
  fun `a modifier that decides both axes keeps its other half`() {
    assertEquals(
      """[{"type":"width","widthDp":80},{"type":"fillMaxHeight"}]""",
      resize("""[{"type":"fillMaxSize"}]""", EditorAxis.Width, EditorSizing.Fixed(80f)),
    )
    assertEquals(
      """[{"type":"fillMaxHeight"},{"type":"width","widthDp":40}]""",
      resize(
        """[{"type":"size","widthDp":40,"heightDp":40}]""",
        EditorAxis.Height,
        EditorSizing.Fill,
      ),
    )
  }

  @Test
  fun `a sizing the catalog does not declare is refused rather than written`() {
    assertNull(
      resize("[]", EditorAxis.Width, EditorSizing.Fixed(80f), declared = setOf("fillMaxWidth"))
    )
    assertNull(resize("[]", EditorAxis.Width, EditorSizing.Fill, declared = setOf("width")))
  }

  @Test
  fun `the last modifier on an axis is how it is read`() {
    val read = chain("""[{"type":"fillMaxWidth"},{"type":"width","widthDp":64}]""")
    assertEquals(EditorSizing.Fixed(64f), sizingOf(read, EditorAxis.Width, null))
    assertEquals(EditorSizing.Hug, sizingOf(read, EditorAxis.Height, null))
  }

  private val bounds = UiBuilderPixelBounds(100f, 100f, 50f, 20f)
  private val parent = UiBuilderPixelBounds(80f, 80f, 200f, 200f)

  @Test
  fun `dragging the end edge writes whole dp, and near the parent's edge snaps to fill`() {
    val sizing = nodeSizing("n", emptyList(), everything, null)
    val fixed = resizePreview(ResizeHandle.End, sizing, bounds, parent, 31f, 0f, 2f, 12f)
    assertEquals(EditorSizing.Fixed(41f), fixed.width)
    assertNull(fixed.height)
    // The parent ends at 280; the edge dragged to 270 is inside the snap band.
    val snapped = resizePreview(ResizeHandle.End, sizing, bounds, parent, 120f, 0f, 2f, 12f)
    assertEquals(EditorSizing.Fill, snapped.width)
    assertEquals(180f, snapped.widthPx)
  }

  @Test
  fun `the corner moves both axes`() {
    val sizing = nodeSizing("n", emptyList(), everything, null)
    val both = resizePreview(ResizeHandle.Corner, sizing, bounds, parent, 10f, 10f, 1f, 12f)
    assertEquals(EditorSizing.Fixed(60f), both.width)
    assertEquals(EditorSizing.Fixed(30f), both.height)
  }

  @Test
  fun `a double click flips an axis between fill and hug`() {
    val hugging = nodeSizing("n", emptyList(), everything, null)
    assertEquals(EditorSizing.Fill to null, resizeToggle(ResizeHandle.End, hugging))
    val filling = nodeSizing("n", chain("""[{"type":"fillMaxWidth"}]"""), everything, null)
    assertEquals(EditorSizing.Hug to null, resizeToggle(ResizeHandle.End, filling))
    assertEquals(
      EditorSizing.Fill to EditorSizing.Fill,
      resizeToggle(ResizeHandle.Corner, filling),
    )
  }
}
