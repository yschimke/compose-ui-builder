package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The extent pane measures a design at its content height rather than at its frame's.
 *
 * The editor draws a long screen twice — once unrolled at the extent, which is the surface edits
 * land on, and once at the frame beside it. The extent is measured with an unbounded height, and
 * that is the whole difficulty: Compose refuses to measure a `LazyColumn` against infinity, and
 * `Scaffold` is a `SubcomposeLayout` that refuses it too. [LocalUiBuilderUnrolled] swaps both for
 * layouts that wrap their content, and these tests pin that they actually do.
 *
 * Measured rather than probed: "how tall did this get" is exactly the question, and a pixel probe
 * would answer it only for the resolution it happened to be rendered at.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CanvasExtentTest {

  @Test
  fun `an unrolled lazy column grows past the frame to its content height`() {
    val height = measureUnbounded { UiBuilderSurface(listDocument(root = "list"), unrolled = true) }

    assertTrue(
      height >= ITEMS * ITEM_DP,
      "expected the extent to hold all $ITEMS items ($ITEMS x $ITEM_DP dp), measured $height",
    )
  }

  /**
   * A screen is a `Scaffold`, so this is the case that matters: the unrolled path has to get past
   * the subcompose layout as well as past the list inside it.
   */
  @Test
  fun `an unrolled scaffold grows past the frame, top bar included`() {
    val height = measureUnbounded {
      UiBuilderSurface(listDocument(root = "screen"), unrolled = true)
    }

    assertTrue(
      height >= ITEMS * ITEM_DP,
      "expected the scaffold's extent to hold all $ITEMS items, measured $height",
    )
  }

  /**
   * The unrolled column drops the weight it hands a nested list at the frame. A weighted child of a
   * column measured against infinity gets no space at all, so leaving it in place would have
   * measured tall and drawn empty — the failure that looks most like success.
   */
  @Test
  fun `an unrolled column holds a list that would have been weighted`() {
    val height = measureUnbounded {
      UiBuilderSurface(listDocument(root = "column"), unrolled = true)
    }

    assertTrue(
      height >= ITEMS * ITEM_DP,
      "expected the column's extent to hold all $ITEMS items, measured $height",
    )
  }

  /** The frame pane is unchanged: the real composition still clips to the device it draws. */
  @Test
  fun `the same design at its frame stays the frame's height`() {
    val height = measureBounded { UiBuilderSurface(listDocument(root = "screen")) }

    assertTrue(height <= FRAME_DP, "expected the frame to stay $FRAME_DP dp, measured $height")
  }

  /** Measures [content] with no height limit, the way the extent pane draws it. */
  private fun measureUnbounded(content: @Composable () -> Unit): Int {
    var measured = 0
    renderComposeScene(FRAME_DP, FRAME_DP, Density(1f)) {
      Box(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
          .requiredWidth(FRAME_DP.dp)
          .onSizeChanged { measured = it.height }
      ) {
        content()
      }
    }
    return measured
  }

  private fun measureBounded(content: @Composable () -> Unit): Int {
    var measured = 0
    renderComposeScene(FRAME_DP, FRAME_DP, Density(1f)) {
      Box(Modifier.onSizeChanged { measured = it.height }) { content() }
    }
    return measured
  }

  /**
   * A list of [ITEMS] dots under one of three roots: the list alone, the list in a plain column, or
   * the list in a scaffold with a top bar — the three shapes the extent has to survive.
   */
  private fun listDocument(root: String): UiBuilderDocument {
    val items = (0 until ITEMS).map { "item-$it" }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "extent-$root",
      title = "Extent",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(root),
      nodes =
        buildMap {
          put(
            "list",
            UiBuilderNode(
              id = "list",
              componentId = "layout/lazy-column",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("items" to items),
            ),
          )
          put(
            "column",
            UiBuilderNode(
              id = "column",
              componentId = "layout/column",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("children" to listOf("list")),
            ),
          )
          put(
            "screen",
            UiBuilderNode(
              id = "screen",
              componentId = "layout/scaffold",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("topBar" to listOf("bar"), "content" to listOf("list")),
            ),
          )
          put(
            "bar",
            UiBuilderNode(
              id = "bar",
              componentId = "shape/colour-dot",
              properties = dot(),
              modifiers = JsonArray(emptyList()),
              slots = emptyMap(),
            ),
          )
          items.forEach {
            put(
              it,
              UiBuilderNode(
                id = it,
                componentId = "shape/colour-dot",
                properties = dot(),
                modifiers = JsonArray(emptyList()),
                slots = emptyMap(),
              ),
            )
          }
        },
    )
  }

  private fun dot() =
    JsonObject(
      mapOf(
        "color" to
          JsonObject(
            mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FFFFFFFF"))
          ),
        "diameterDp" to
          JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(ITEM_DP))),
      )
    )

  private companion object {
    /** Comfortably taller than the frame once stacked, so "did it grow?" has one answer. */
    const val ITEMS = 20
    const val ITEM_DP = 40
    const val FRAME_DP = 200
  }
}
