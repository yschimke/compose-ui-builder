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
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.float
import ee.schimke.composeai.uibuilder.canvas.wffTransformed
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A `wff/transform` turns its part on the canvas: the "Rotate with" preset's expression evaluated
 * at the preview time, the same as a `<Transform>` held as raw XML.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WffTransformCanvasTest {
  /** A white bar from the centre straight up, in a group the [transform] node drives. */
  private fun face(transform: String, time: String = "03:00:00"): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},
        "environment":{"widthDp":227,"heightDp":227,"density":2.0,"fixedTime":"2024-05-16T${time}Z","animations":"settled"},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","properties":{"backgroundColor":{"type":"string","value":"#ff000000"},"previewTime":{"type":"string","value":"$time"}},"slots":{"content":["hand"]}},
          "hand":{"id":"hand","componentId":"wff/group","properties":{"pivotX":{"type":"float","value":0.5},"pivotY":{"type":"float","value":0.5}},"slots":{"content":["turn","bar"]}},
          "turn":$transform,
          "bar":{"id":"bar","componentId":"wff/part-draw","properties":{"shape":{"type":"string","value":"rectangle"},"x":{"type":"int","value":215},"y":{"type":"int","value":40},"width":{"type":"int","value":20},"height":{"type":"int","value":185},"fillColor":{"type":"color","value":"#ffffffff"}},"slots":{}}}}""",
    )

  private fun typed(vararg properties: Pair<String, String>) =
    """{"id":"turn","componentId":"wff/transform","properties":{${
      properties.joinToString(",") { (k, v) -> "\"$k\":{\"type\":\"string\",\"value\":\"$v\"}" }
    }},"slots":{}}"""

  private fun raw(xml: String) =
    """{"id":"turn","componentId":"wff/raw","properties":{"xml":{"type":"string","value":${Json.encodeToString(String.serializer(), xml)}}},"slots":{}}"""

  private fun render(document: UiBuilderDocument, name: String? = null): Bitmap {
    val side = 227
    val px = side * 2
    val scene =
      ImageComposeScene(px, px, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(side.dp)) { UiBuilderSurface(document) }
          }
        }
      }
    try {
      scene.render(0L)
      val bitmap = Bitmap().apply { allocN32Pixels(px, px) }
      check(scene.render(16_000_000L).readPixels(bitmap))
      if (name != null)
        File("build/watchface-renders")
          .apply { mkdirs() }
          .let {
            File(it, "$name.png")
              .writeBytes(
                checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG))
                  .bytes,
              )
          }
      return bitmap
    } finally {
      scene.close()
    }
  }

  private val white = 0xFFFFFFFF.toInt()

  /** Whether the bar covers the point 120px of the face from the centre in [direction]. */
  private fun Bitmap.barAt(direction: String): Boolean {
    val centre = width / 2
    val reach = width * 120 / 450
    val (x, y) =
      when (direction) {
        "up" -> centre to centre - reach
        "right" -> centre + reach to centre
        "down" -> centre to centre + reach
        else -> centre - reach to centre
      }
    return getColor(x, y) == white
  }

  @Test
  fun `the hour preset turns the hand to three o'clock`() {
    val bitmap = render(face(typed("rotateWith" to "hour")), "transform-hour-0300")
    assertEquals(
      listOf(false, true, false, false),
      listOf("up", "right", "down", "left").map { bitmap.barAt(it) },
    )
  }

  @Test
  fun `each preset reads its own clock`() {
    // Each at a time its hand stands on a quarter: six o'clock, quarter past, 45 seconds.
    assertEquals(true, render(face(typed("rotateWith" to "hour"), "06:00:00")).barAt("down"))
    assertEquals(true, render(face(typed("rotateWith" to "minute"), "06:15:00")).barAt("right"))
    assertEquals(
      true,
      render(face(typed("rotateWith" to "second-ticking"), "06:15:45")).barAt("left"),
    )
    assertEquals(
      true,
      render(face(typed("rotateWith" to "second-sweep"), "06:15:45")).barAt("left"),
    )
    // Not a quarter: 06:15 puts the hour hand 7.5 degrees past six, off the straight-down point.
    assertEquals(false, render(face(typed("rotateWith" to "hour"), "06:15:00")).barAt("down"))
  }

  @Test
  fun `a custom value, BY mode and a raw transform draw as the typed node does`() {
    val custom = render(face(typed("target" to "angle", "value" to "[HOUR_0_11] * 30")))
    val preset = render(face(typed("rotateWith" to "hour")))
    assertEquals(0, custom.differing(preset))
    val raw =
      render(face(raw("<Transform target=\"angle\" value=\"([HOUR_0_11]+[MINUTE]/60)*30\"/>")))
    assertEquals(0, raw.differing(preset))
    // BY adds to the group's own angle (0 here); TO sets it.
    val by = render(face(typed("target" to "angle", "value" to "180", "mode" to "BY")))
    assertEquals(true, by.barAt("down"))
  }

  @Test
  fun `a gyro changes nothing on a flat watch`() {
    val gyro =
      """{"id":"turn","componentId":"wff/gyro","properties":{"angle":{"type":"string","value":"[ACCELEROMETER_ANGLE_X] * 3"}},"slots":{}}"""
    assertEquals(true, render(face(gyro)).barAt("up"))
  }

  @Test
  fun `a transform of another target moves the part`() {
    val document = face(typed("target" to "alpha", "value" to "[SECOND] * 2"), "03:00:30")
    val moved =
      wffTransformed(
        document,
        document.nodes.getValue("hand"),
        WffPreviewContext.of("03:00:30", ""),
      )
    assertEquals(60f, moved.float("alpha"))
    assertNotEquals(white, render(document).getColor(227, 227 - 120), "drawn at alpha 60 of 255")
  }

  private fun Bitmap.differing(other: Bitmap): Int {
    var count = 0
    for (y in 0 until height) for (x in 0 until width) if (getColor(x, y) != other.getColor(x, y))
      count++
    return count
  }
}
