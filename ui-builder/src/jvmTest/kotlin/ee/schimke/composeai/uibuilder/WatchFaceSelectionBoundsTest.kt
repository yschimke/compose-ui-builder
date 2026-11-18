package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A rotated part's reported box wraps the part as drawn. The canvas rotates a group with a graphics
 * layer, so its children's measured bounds must be mapped through that rotation: the selection
 * overlay and hit testing read them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WatchFaceSelectionBoundsTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private fun snapshotOf(name: String, side: Int): UiBuilderInspectionSnapshot {
    // Frozen at the template's preview time: with no `fixedTime` a face follows the real clock.
    val document =
      Json.decodeFromString(UiBuilderDocument.serializer(), File(designs, "$name.json").readText())
        .let {
          it.copy(
            environment =
              JsonObject(it.environment + ("fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z"))),
          )
        }
    var snapshot: UiBuilderInspectionSnapshot? = null
    val px = side * 2
    val scene =
      ImageComposeScene(px, px, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(side.dp)) {
              UiBuilderSurface(document, onInspectionSnapshot = { snapshot = it })
            }
          }
        }
      }
    try {
      scene.render(0L)
      scene.render(16_000_000L)
    } finally {
      scene.close()
    }
    return checkNotNull(snapshot)
  }

  @Test
  fun `the minute hand's box wraps the hand as it is turned`() {
    val side = 225 // dp; at 2 px per dp the 450-wide face is 450 px, one px per WFF pixel
    val snapshot = snapshotOf("analog-watch-face", side)
    val bounds = checkNotNull(snapshot.nodes.first { it.nodeId == "minute-hand-body" }.bounds)
    // 10:08:32 preview time, `[MINUTE] * 6 + [SECOND] * 0.1`; one px per WFF pixel.
    val k = 1f
    val radians = Math.toRadians(8 * 6 + 32 * 0.1)
    val cx = 225f
    val cy = 225f
    val corners =
      listOf(215f to 50f, 235f to 50f, 215f to 242f, 235f to 242f).map { (x, y) ->
        val dx = x - cx
        val dy = y - cy
        (cx + dx * cos(radians) - dy * sin(radians)).toFloat() * k to
          (cy + dx * sin(radians) + dy * cos(radians)).toFloat() * k
      }
    val left = corners.minOf { it.first }
    val right = corners.maxOf { it.first }
    val top = corners.minOf { it.second }
    val bottom = corners.maxOf { it.second }
    fun near(a: Float, b: Float) = abs(a - b) < 3f
    assertTrue(
      near(bounds.x, left) &&
        near(bounds.y, top) &&
        near(bounds.x + bounds.width, right) &&
        near(bounds.y + bounds.height, bottom),
      "expected ~($left, $top, $right, $bottom) but the hand reported $bounds",
    )
  }
}
