package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.editor.ConstrainedFramePane
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The two watch face templates drawn by the canvas: cut to the round screen, with the dial, the
 * hands and the complication where the design puts them. The pictures are kept under `build/` for a
 * pull request to show.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WatchFaceRenderTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private fun template(name: String): UiBuilderDocument =
    Json.decodeFromString(UiBuilderDocument.serializer(), File(designs, "$name.json").readText())

  private val surround = Color(0xFF3D4A5C)

  /** The face at 227dp, 2px to the dp, on a lighter surround so the screen's edge shows. */
  private fun render(name: String, pane: Boolean = false): Bitmap {
    val side = 227
    val density = 2f
    val px = (side * density).toInt()
    val scene =
      ImageComposeScene(px, px, Density(density)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(side.dp).background(surround)) {
              // The preview pane's frame, which has a surface of its own under the face.
              if (pane) ConstrainedFramePane(template(name), side.toFloat(), side.toFloat(), 1f, 1f)
              else UiBuilderSurface(template(name))
            }
          }
        }
      }
    try {
      scene.render(0L)
      val bitmap = Bitmap().apply { allocN32Pixels(px, px) }
      check(scene.render(16_000_000L).readPixels(bitmap))
      File("build/watchface-renders").apply {
        mkdirs()
        File(this, if (pane) "$name-preview.png" else "$name.png")
          .writeBytes(
            checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes,
          )
      }
      return bitmap
    } finally {
      scene.close()
    }
  }

  @Test
  fun `both templates are cut to the round screen`() {
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val bitmap = render(name)
      val last = bitmap.width - 1
      for ((x, y) in listOf(0 to 0, last to 0, 0 to last, last to last)) {
        assertEquals(surround.toArgbInt(), bitmap.getColor(x, y), "$name corner ($x, $y)")
      }
      assertNotEquals(surround.toArgbInt(), bitmap.getColor(last / 2, 4), "$name top edge")
    }
  }

  @Test
  fun `the preview pane's frame is round too`() {
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val bitmap = render(name, pane = true)
      assertEquals(surround.toArgbInt(), bitmap.getColor(0, 0), "$name preview corner")
      assertNotEquals(surround.toArgbInt(), bitmap.getColor(bitmap.width / 2, 8), "$name preview")
    }
  }

  private fun Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or
      ((red * 255).toInt() shl 16) or
      ((green * 255).toInt() shl 8) or
      (blue * 255).toInt()
}
