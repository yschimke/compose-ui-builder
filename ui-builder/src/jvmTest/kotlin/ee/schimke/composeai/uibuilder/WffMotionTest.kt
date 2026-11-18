package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.float
import ee.schimke.composeai.uibuilder.canvas.wffReadsSubSecond
import ee.schimke.composeai.uibuilder.canvas.wffTransformed
import ee.schimke.composeai.uibuilder.canvas.withWffAmbient
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap

/**
 * The preview moves as the watch does: a `<Gyro>` follows a gently swaying wrist on a live canvas,
 * and the switch between interactive and ambient fades across rather than cutting.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WffMotionTest {
  /** A white dot in a group a gyro moves along x by ten times the tilt; [fixed] freezes time. */
  private fun face(fixed: String?, gyro: Boolean = true): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},
        "environment":{"widthDp":227,"heightDp":227,"density":2.0${if (fixed != null) ",\"fixedTime\":\"$fixed\"" else ""},"animations":"settled"},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","properties":{"previewTime":{"type":"string","value":"10:08:32"}},"slots":{"content":["group"]}},
          "group":{"id":"group","componentId":"wff/group","properties":{},"slots":{"content":[${if (gyro) "\"tilt\"," else ""}"dot"]}},
          "tilt":{"id":"tilt","componentId":"wff/gyro","properties":{"x":{"type":"string","value":"[ACCELEROMETER_ANGLE_X] * 10"}},"slots":{}},
          "dot":{"id":"dot","componentId":"wff/part-draw","properties":{"shape":{"type":"string","value":"ellipse"},"x":{"type":"int","value":200},"y":{"type":"int","value":200},"width":{"type":"int","value":50},"height":{"type":"int","value":50},"fillColor":{"type":"color","value":"#ffffffff"}},"slots":{}},
          "ambient":{"id":"ambient","componentId":"wff/variant","properties":{"mode":{"type":"string","value":"AMBIENT"},"target":{"type":"string","value":"alpha"},"value":{"type":"string","value":"0"}},"slots":{}}}}""",
    )

  @Test
  fun `a gyro offsets its part by its expressions of the tilt`() {
    val document = face(null)
    val group = document.nodes.getValue("group")
    val flat = wffTransformed(document, group, WffPreviewContext())
    assertEquals(0f, flat.float("x"))
    val tilted = wffTransformed(document, group, WffPreviewContext().withTilt(4.0, 0.0))
    assertEquals(40f, tilted.float("x"))
  }

  @Test
  fun `the wrist sways with time, and holds still in ambient`() {
    val a = WffPreviewContext(second = 10, fraction = 0.0).swaying()
    val b = WffPreviewContext(second = 11, fraction = 0.25).swaying()
    assertNotEquals(a.tiltX, b.tiltX)
    assertTrue(kotlin.math.abs(a.tiltX) <= 12.0 && kotlin.math.abs(a.tiltY) <= 8.0)
    val still = WffPreviewContext(second = 11, fraction = 0.25, ambient = true).swaying()
    assertEquals(0.0 to 0.0, still.tiltX to still.tiltY)
    // A face with a gyro is redrawn every frame, so the sway is smooth.
    assertTrue(face(null).wffReadsSubSecond())
    val noGyro = face(null, gyro = false)
    assertTrue(!noGyro.copy(nodes = noGyro.nodes - "tilt").wffReadsSubSecond())
  }

  private fun frames(
    document: () -> UiBuilderDocument,
    count: Int,
    step: (Int) -> Unit = {},
    gapMillis: Long = 0,
    nanosPerFrame: Long = 16_000_000L,
  ): List<Bitmap> {
    val side = 227
    val px = side * 2
    val scene =
      ImageComposeScene(px, px, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(side.dp)) { UiBuilderSurface(document()) }
          }
        }
      }
    try {
      scene.render(0L)
      return (0 until count).map { frame ->
        step(frame)
        if (frame > 0 && gapMillis > 0) Thread.sleep(gapMillis)
        val bitmap = Bitmap().apply { allocN32Pixels(px, px) }
        check(scene.render((frame + 1) * nanosPerFrame).readPixels(bitmap))
        bitmap
      }
    } finally {
      scene.close()
    }
  }

  /** The leftmost white column on the dot's row: where the dot is. */
  private fun Bitmap.dotLeft(): Int {
    val y = height / 2
    return (0 until width).first { getColor(it, y) == 0xFFFFFFFF.toInt() }
  }

  @Test
  fun `a live face's gyro moves its part from frame to frame`() {
    val live = frames({ face(null) }, count = 2, gapMillis = 400)
    assertNotEquals(live[0].dotLeft(), live[1].dotLeft(), "the gyro part held still")
    val frozen = frames({ face("2024-05-16T10:08:32Z") }, count = 2, gapMillis = 400)
    assertEquals(frozen[0].dotLeft(), frozen[1].dotLeft(), "a frozen face swayed")
  }

  @Test
  fun `switching to ambient fades across rather than cutting`() {
    // The dot hidden in ambient: its variant takes the alpha to 0.
    val base = face("2024-05-16T10:08:32Z", gyro = false)
    val dimmed =
      base.copy(
        nodes =
          base.nodes +
            ("dot" to
              base.nodes.getValue("dot").let {
                it.copy(slots = mapOf("variants" to listOf("ambient")))
              }),
      )
    val ambient = mutableStateOf(false)
    val centre = { b: Bitmap -> b.getColor(b.width / 2, b.height / 2) }
    val shots =
      frames(
        { dimmed.withWffAmbient(ambient.value) },
        count = 16,
        step = { if (it == 1) ambient.value = true },
        nanosPerFrame = 50_000_000L,
      )
    assertEquals(0xFFFFFFFF.toInt(), centre(shots[0]), "interactive draws the dot")
    // Some frame of the 400ms switch is neither the dot nor black: the dot fading out.
    val reds = shots.map { (centre(it) shr 16) and 0xFF }
    assertTrue(reds.any { it in 20..235 }, "no frame mid-fade: $reds")
    assertEquals(0, reds.last(), "the switch ends in ambient: $reds")
    assertEquals(
      0xFF000000.toInt(),
      frames({ dimmed.withWffAmbient(true) }, count = 1).single().let(centre),
    )
  }
}
