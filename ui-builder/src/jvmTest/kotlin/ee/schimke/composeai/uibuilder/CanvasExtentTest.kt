package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.CanvasExtentLayout
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderUnrolled
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The extent pane measures a design at its content height rather than at its frame's. It is
 * measured with unbounded height, which `LazyColumn` and `Scaffold` (a `SubcomposeLayout`) refuse;
 * [LocalUiBuilderUnrolled] swaps both for wrapping layouts, and these tests measure that they wrap.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CanvasExtentTest {

  @Test
  fun `an unrolled lazy column grows past the frame to its content height`() {
    val height = measureUnbounded {
      WearCatalogAdapters { UiBuilderSurface(listDocument(root = "list"), unrolled = true) }
    }

    assertTrue(
      height >= ITEMS * ITEM_DP,
      "expected the extent to hold all $ITEMS items ($ITEMS x $ITEM_DP dp), measured $height",
    )
  }

  /**
   * A screen is a `Scaffold`, so the unrolled path must get past the subcompose layout and the list
   * inside it.
   */
  @Test
  fun `an unrolled scaffold grows past the frame, top bar included`() {
    val height = measureUnbounded {
      WearCatalogAdapters { UiBuilderSurface(listDocument(root = "screen"), unrolled = true) }
    }

    assertTrue(
      height >= ITEMS * ITEM_DP,
      "expected the scaffold's extent to hold all $ITEMS items, measured $height",
    )
  }

  /**
   * The unrolled column drops the weight it hands a nested list: a weighted child measured against
   * infinity gets no space and would draw empty.
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

  /**
   * A Wear screen's `TransformingLazyColumn` reports infinite height against infinity (`Size(w x
   * 2147483647) is out of range`, a blank editor), so the extent draws it as a Column; this
   *             measures that the Column wraps.
   */
  @Test
  fun `an unrolled wear transforming list grows past the frame`() {
    val height = measureUnbounded {
      WearCatalogAdapters { UiBuilderSurface(wearScreenDocument(), unrolled = true) }
    }

    assertTrue(
      height in ITEMS * ITEM_DP until 100_000,
      "expected the Wear extent to hold all $ITEMS items at a finite height, measured $height",
    )
  }

  /**
   * The extent is the end of the scroll, so the edge button is drawn at its full `EdgeButtonSize`;
   * `EdgeButton` takes its height from constraints and would otherwise collapse to a pill.
   */
  @Test
  fun `an unrolled wear screen draws its edge button expanded`() {
    val without = measureUnbounded {
      WearCatalogAdapters { UiBuilderSurface(wearScreenDocument(), unrolled = true) }
    }
    val with = measureUnbounded {
      WearCatalogAdapters {
        UiBuilderSurface(wearScreenDocument(edgeButton = true), unrolled = true)
      }
    }

    assertTrue(
      with - without >= EDGE_BUTTON_SMALL_DP,
      "expected the edge button to add its full $EDGE_BUTTON_SMALL_DP dp, added ${with - without}",
    )
  }

  /**
   * A theme font loading after the first frame can make text taller; the extent must re-measure
   * rather than stay pinned to its first probe.
   */
  @Test
  fun `the extent re-probes when a font loads`() {
    val fonts = mutableStateMapOf<String, FontFamily>()
    var measured = 0
    val scene = ImageComposeScene(FRAME_DP, FRAME_DP, Density(1f))
    try {
      scene.setContent {
        CompositionLocalProvider(LocalUiBuilderFontFamilies provides fonts) {
          Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
              .requiredWidth(FRAME_DP.dp)
              .requiredHeightIn(min = FRAME_DP.dp)
          ) {
            CanvasExtentLayout(Modifier.fillMaxSize().onSizeChanged { measured = it.height }) {
              // Stands in for text that wraps to more lines in the loaded face.
              val tall = LocalUiBuilderFontFamilies.current.isNotEmpty()
              Box(Modifier.fillMaxWidth().height((if (tall) 3 * FRAME_DP else FRAME_DP).dp))
            }
          }
        }
      }
      scene.render()
      assertEquals(FRAME_DP, measured)

      fonts["Space Mono"] = FontFamily.Monospace
      scene.render()
      scene.render()
      assertEquals(
        3 * FRAME_DP,
        measured,
        "expected the extent to follow the content the font grew",
      )
    } finally {
      scene.close()
    }
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
   * A list of [ITEMS] dots under one of three roots (list alone, in a column, in a scaffold with a
   * top bar): the shapes the extent must survive.
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

  /**
   * A Wear screen over a `TransformingLazyColumn` of [ITEMS] dots: a new `wear-m3` design, reduced
   * to what the extent question needs.
   */
  private fun wearScreenDocument(edgeButton: Boolean = false): UiBuilderDocument {
    val items = (0 until ITEMS).map { "item-$it" }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "extent-wear",
      title = "Extent wear",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(FRAME_DP),
            "heightDp" to JsonPrimitive(FRAME_DP),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("wear-screen"),
      nodes =
        buildMap {
          put(
            "wear-screen",
            UiBuilderNode(
              id = "wear-screen",
              componentId = "wear-m3/screen-scaffold",
              properties =
                JsonObject(
                  mapOf(
                    "timeText" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "value" to JsonPrimitive("10:10"),
                        )
                      ),
                    "scrollIndicator" to
                      JsonObject(
                        mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(true))
                      ),
                  )
                ),
              modifiers = JsonArray(emptyList()),
              slots =
                mapOf(
                  "content" to listOf("wear-list"),
                  "edgeButton" to if (edgeButton) listOf("edge-button") else emptyList(),
                ),
            ),
          )
          put(
            "edge-button",
            UiBuilderNode(
              id = "edge-button",
              componentId = "wear-m3/edge-button",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("content" to emptyList()),
            ),
          )
          put(
            "wear-list",
            UiBuilderNode(
              id = "wear-list",
              componentId = "wear-m3/transforming-lazy-column",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("items" to items),
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

  private companion object {
    /** Comfortably taller than the frame once stacked, so "did it grow?" has one answer. */
    const val ITEMS = 20
    const val ITEM_DP = 40
    const val FRAME_DP = 200

    /** `EdgeButtonSize.Small`'s height, the default size, before its vertical padding. */
    const val EDGE_BUTTON_SMALL_DP = 56
  }
}
