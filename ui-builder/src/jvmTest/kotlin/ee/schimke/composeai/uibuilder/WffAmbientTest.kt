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
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffValue
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.isWatchFace
import ee.schimke.composeai.uibuilder.canvas.isWffAmbient
import ee.schimke.composeai.uibuilder.canvas.wffNodeIn
import ee.schimke.composeai.uibuilder.canvas.wffReadsSubSecond
import ee.schimke.composeai.uibuilder.canvas.wffVisibleIn
import ee.schimke.composeai.uibuilder.canvas.withWffAmbient
import ee.schimke.composeai.uibuilder.editor.UiBuilderVariantPane
import ee.schimke.composeai.uibuilder.editor.mobileTabLabel
import ee.schimke.composeai.uibuilder.editor.withWffAmbientTwins
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiExpressions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A watch face's Preview: the real clock unless the environment fixes the time, and Interactive and
 * Ambient side by side, the ambient one applying the `<Variant mode="AMBIENT">` overrides on black.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WffAmbientTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private fun template(name: String): UiBuilderDocument =
    Json.decodeFromString(UiBuilderDocument.serializer(), File(designs, "$name.json").readText())

  private fun UiBuilderDocument.fixedAt(time: String?): UiBuilderDocument =
    copy(
      environment =
        JsonObject(
          if (time == null) environment - "fixedTime"
          else environment + ("fixedTime" to JsonPrimitive(time)),
        ),
    )

  private val analog = template("analog-watch-face")
  private val context = WffPreviewContext.of("10:08:32", "2024-05-16T10:08:32Z")

  @Test
  fun `a clock reading becomes the preview time with its fraction of a second`() {
    val clock = UiExpressions.Clock.at(1_715_854_112_500L) // 2024-05-16T10:08:32.5Z
    val live = WffPreviewContext.of(clock)
    assertEquals(listOf(10, 8, 32), listOf(live.hour, live.minute, live.second))
    assertEquals(0.5, live.fraction)
    assertEquals(
      32.5,
      (evaluateWffExpression("[SECOND_MILLISECOND]", live) as WffValue.Num).value,
    )
    assertEquals(listOf(2024, 5, 16), listOf(live.year, live.month, live.day))
  }

  @Test
  fun `a fixed time with no preview time is frozen at its own time of day`() {
    val frozen = WffPreviewContext.of("", "2024-05-16T07:41:09Z")
    assertEquals(listOf(7, 41, 9), listOf(frozen.hour, frozen.minute, frozen.second))
  }

  @Test
  fun `only a face that reads SECOND_MILLISECOND is drawn every frame`() {
    assertTrue(analog.wffReadsSubSecond(), "the template's sweeping second hand")
    val ticking =
      analog.copy(
        nodes =
          analog.nodes.mapValues { (_, node) ->
            if (node.componentId != "wff/raw") node
            else
              node.copy(
                properties =
                  JsonObject(
                    node.properties.mapValues { (_, value) ->
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "value" to
                            JsonPrimitive(
                              ((value as JsonObject)["value"] as JsonPrimitive)
                                .content
                                .replace("SECOND_MILLISECOND", "SECOND"),
                            ),
                        ),
                      )
                    },
                  ),
              )
          },
      )
    assertFalse(ticking.wffReadsSubSecond())
  }

  @Test
  fun `ambient applies the variant and hides the second hand, interactive leaves it`() {
    val variant = analog.nodes.getValue("second-hand-ambient")
    val owner =
      analog.nodes.values.single { node -> node.slots.values.any { "second-hand-ambient" in it } }
    assertEquals(
      "alpha",
      (variant.properties["target"] as JsonObject)["value"].toString().trim('"'),
    )

    val interactive = wffNodeIn(analog, owner, context)
    assertTrue(wffVisibleIn(interactive, context), "interactive keeps the second hand")

    val ambient = context.inMode(true)
    val dimmed = wffNodeIn(analog, owner, ambient)
    assertFalse(
      wffVisibleIn(dimmed, ambient),
      "ambient hides it: alpha ${dimmed.properties["alpha"]}",
    )
    // Only the targeted property changed.
    assertEquals(owner.properties - "alpha", dimmed.properties - "alpha")
  }

  @Test
  fun `a variant's value is evaluated and an unknown mode is ignored`() {
    val ambient = context.inMode(true)
    val owner =
      analog.nodes.values.single { node -> node.slots.values.any { "second-hand-ambient" in it } }
    val doc =
      analog.copy(
        nodes =
          analog.nodes +
            ("second-hand-ambient" to
              analog.nodes.getValue("second-hand-ambient").let { variant ->
                variant.copy(
                  properties =
                    JsonObject(
                      variant.properties +
                        ("value" to
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("string"),
                              "value" to JsonPrimitive("[HOUR_0_11] * 10"),
                            ),
                          )),
                    ),
                )
              }),
      )
    val shown = wffNodeIn(doc, owner, ambient)
    assertEquals(
      "100.0",
      ((shown.properties["alpha"] as JsonObject)["value"] as JsonPrimitive).content,
    )

    val other =
      doc.nodes.getValue("second-hand-ambient").let {
        it.copy(
          properties =
            JsonObject(
              it.properties +
                ("mode" to
                  JsonObject(
                    mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("OTHER")),
                  )),
            ),
        )
      }
    val untouched =
      wffNodeIn(doc.copy(nodes = doc.nodes + ("second-hand-ambient" to other)), owner, ambient)
    assertEquals(owner, untouched)
  }

  @Test
  fun `the ambient mode is carried by the document copy and nothing else changes`() {
    assertFalse(analog.isWffAmbient())
    val ambient = analog.withWffAmbient(true)
    assertTrue(ambient.isWffAmbient())
    assertEquals(analog.nodes, ambient.nodes)
    assertEquals(analog, ambient.withWffAmbient(false))
    assertTrue(analog.isWatchFace())
  }

  @Test
  fun `a watch face's preview is every frame twice, interactive then ambient`() {
    val pane =
      UiBuilderVariantPane(
        id = "variant-device-id:wearos_large_round",
        label = "Wear OS Large Round · 227×227dp · 2×",
        widthDp = 227f,
        heightDp = 227f,
        document = analog,
      )
    val twins = listOf(pane).withWffAmbientTwins(analog)
    assertEquals(
      listOf(
        "variant-device-id:wearos_large_round",
        "variant-device-id:wearos_large_round-ambient",
      ),
      twins.map { it.id },
    )
    assertEquals(listOf(false, true), twins.map { it.document.isWffAmbient() })
    assertTrue(twins[0].label.endsWith("Interactive"), twins[0].label)
    assertTrue(twins[1].label.endsWith("Ambient"), twins[1].label)
    assertEquals(
      listOf("Wear OS Large Round", "Wear OS Large Round · Ambient"),
      twins.map { it.mobileTabLabel() },
    )
    // Not a watch face: nothing to twin.
    val other = analog.copy(roots = emptyList())
    assertEquals(listOf(pane), listOf(pane).withWffAmbientTwins(other))
  }

  // ---- Rendering --------------------------------------------------------------------------

  private val surround = Color(0xFF3D4A5C)

  private fun render(
    document: UiBuilderDocument,
    name: String? = null,
    frames: Int = 1,
    gapMillis: Long = 0,
  ): List<Bitmap> {
    val side = 227
    val px = side * 2
    val scene =
      ImageComposeScene(px, px, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(side.dp).background(surround)) { UiBuilderSurface(document) }
          }
        }
      }
    try {
      scene.render(0L)
      return (0 until frames).map { frame ->
        if (frame > 0) Thread.sleep(gapMillis)
        val bitmap = Bitmap().apply { allocN32Pixels(px, px) }
        check(scene.render((frame + 1) * 16_000_000L).readPixels(bitmap))
        if (name != null) {
          File("build/watchface-renders")
            .apply { mkdirs() }
            .let {
              File(it, if (frames > 1) "$name-$frame.png" else "$name.png")
                .writeBytes(
                  checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG))
                    .bytes,
                )
            }
        }
        bitmap
      }
    } finally {
      scene.close()
    }
  }

  private fun Bitmap.differingPixels(other: Bitmap): Int {
    var count = 0
    for (y in 0 until height) for (x in 0 until width) if (getColor(x, y) != other.getColor(x, y))
      count++
    return count
  }

  @Test
  fun `ambient leaves out the second hand that interactive draws`() {
    val fixed = analog.fixedAt("2024-05-16T10:08:32Z")
    val interactive = render(fixed, "analog-interactive").single()
    val ambient = render(fixed.withWffAmbient(true), "analog-ambient").single()
    val centre = interactive.width / 2
    // The second hand at 32s points to the lower left of the centre.
    var handPixels = 0
    for (y in centre + 40 until centre + 200) for (x in centre - 80 until centre + 10) {
      if (interactive.getColor(x, y) != ambient.getColor(x, y)) handPixels++
    }
    assertTrue(handPixels > 50, "ambient still draws the second hand: $handPixels pixels differ")
  }

  @Test
  fun `the digital template's ambient leaves out its ring`() {
    val fixed = template("digital-watch-face").fixedAt("2024-05-16T10:08:32Z")
    val interactive = render(fixed, "digital-interactive").single()
    val ambient = render(fixed.withWffAmbient(true), "digital-ambient").single()
    // The ring's stroke, at the top of the face, 15px of 450 in from its edge.
    val x = interactive.width / 2
    val y = (interactive.height * 18f / 450f).toInt()
    assertNotEquals(0xFF000000.toInt(), interactive.getColor(x, y), "interactive draws the ring")
    assertEquals(0xFF000000.toInt(), ambient.getColor(x, y), "ambient still draws the ring")
  }

  @Test
  fun `a clock hand is drawn at its ambient alpha`() {
    val face =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
          "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},
          "environment":{"widthDp":227,"heightDp":227,"density":2.0,"fixedTime":"2024-05-16T00:00:00Z","animations":"settled"},
          "stateVariables":{},"roots":["scene"],"nodes":{
            "scene":{"id":"scene","componentId":"wff/scene","properties":{"previewTime":{"type":"string","value":"00:00:00"}},"slots":{"content":["clock"]}},
            "clock":{"id":"clock","componentId":"wff/analog-clock","properties":{},"slots":{"hands":["hand"]}},
            "hand":{"id":"hand","componentId":"wff/clock-hand","properties":{"type":{"type":"string","value":"hour"},"resource":{"type":"string","value":"hand"},"x":{"type":"int","value":215},"y":{"type":"int","value":40},"width":{"type":"int","value":20},"height":{"type":"int","value":185},"tintColor":{"type":"color","value":"#ffffffff"}},"slots":{"variants":["dim"]}},
            "dim":{"id":"dim","componentId":"wff/variant","properties":{"mode":{"type":"string","value":"AMBIENT"},"target":{"type":"string","value":"alpha"},"value":{"type":"string","value":"128"}},"slots":{}}}}""",
      )
    // A point on the hand, straight up from the centre.
    val x = 227
    val y = 227 - 100
    val interactive = render(face).single().getColor(x, y)
    val ambient = render(face.withWffAmbient(true)).single().getColor(x, y)
    assertEquals(0xFFFFFFFF.toInt(), interactive)
    val red = (ambient shr 16) and 0xFF
    assertTrue(
      red in 120..136,
      "ambient hand at alpha 128 over black: ${Integer.toHexString(ambient)}",
    )
  }

  @Test
  fun `ambient draws on black whatever the face's background is`() {
    val red =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
          "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},
          "environment":{"widthDp":227,"heightDp":227,"density":2.0,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled"},
          "stateVariables":{},"roots":["scene"],"nodes":{
            "scene":{"id":"scene","componentId":"wff/scene","properties":{"backgroundColor":{"type":"string","value":"#ffcc0000"}},"slots":{}}}}""",
      )
    val inside = 227
    assertEquals(0xFFCC0000.toInt(), render(red).single().getColor(inside, inside))
    assertEquals(
      0xFF000000.toInt(),
      render(red.withWffAmbient(true)).single().getColor(inside, inside),
    )
  }

  @Test
  fun `with no fixed time the face follows the real clock`() {
    // Two frames more than a second apart: a frozen face draws the same picture twice, a live
    // one has moved its second hand.
    val live = render(analog.fixedAt(null), frames = 2, gapMillis = 1_300)
    assertNotEquals(0, live[0].differingPixels(live[1]), "a face with no fixedTime held still")

    val frozen = render(analog.fixedAt("2024-05-16T10:08:32Z"), frames = 2, gapMillis = 1_300)
    assertEquals(0, frozen[0].differingPixels(frozen[1]), "a face with a fixedTime moved")
  }
}
