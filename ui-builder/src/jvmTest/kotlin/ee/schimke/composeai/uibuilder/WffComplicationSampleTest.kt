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
import ee.schimke.composeai.uibuilder.canvas.LocalWffComplicationPicks
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.WffComplicationPick
import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffSampleImage
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.wffSlotSample
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A complication slot drawn with sample data per provider and type: the values a provider sends,
 * the slot's own `<Complication type>` evaluated against them so an arc reaches its value, a tidy
 * default for a type the slot has no content for, and the editor's pick over the slot's default.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WffComplicationSampleTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private fun template(name: String): UiBuilderDocument =
    Json.decodeFromString(UiBuilderDocument.serializer(), File(designs, "$name.json").readText())
      .fixed()

  private fun UiBuilderDocument.fixed() =
    copy(
      environment =
        JsonObject(environment + ("fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z"))),
    )

  @Test
  fun `each provider sends believable values for its types`() {
    val battery = WffComplicationSample.forProvider("WATCH_BATTERY", "RANGED_VALUE")
    assertEquals(
      listOf(85.0, 0.0, 100.0),
      listOf(battery.rangedValue, battery.rangedMin, battery.rangedMax),
    )
    assertEquals("85%", battery.text)
    assertEquals(WffSampleImage.BATTERY, battery.image)
    assertEquals(0.85, battery.progress)

    val steps = WffComplicationSample.forProvider("STEP_COUNT", "GOAL_PROGRESS")
    assertEquals(6482.0 to 10000.0, steps.goalValue to steps.goalTarget)
    assertNull(steps.rangedValue)
    assertEquals("6,482", steps.text)

    assertEquals("72", WffComplicationSample.forProvider("HEART_RATE").text)
    assertEquals("06:42", WffComplicationSample.forProvider("SUNRISE_SUNSET").text)
    assertEquals(
      "Stand-up 10:30",
      WffComplicationSample.forProvider("NEXT_EVENT", "LONG_TEXT").text,
    )
    assertEquals(
      "10:30" to "Stand-up",
      WffComplicationSample.forProvider("NEXT_EVENT").let { it.text to it.title },
    )

    // A text type carries no number; NO_DATA and EMPTY carry nothing but the dash.
    assertNull(WffComplicationSample.forProvider("WATCH_BATTERY", "SHORT_TEXT").rangedValue)
    assertEquals("--", WffComplicationSample.forProvider("WATCH_BATTERY", "NO_DATA").line)
    assertEquals("", WffComplicationSample.forProvider("WATCH_BATTERY", "EMPTY").line)
    assertEquals(
      4,
      WffComplicationSample.forProvider("STEP_COUNT", "WEIGHTED_ELEMENTS").weights.size,
    )
    assertEquals(WffSampleImage.PHOTO, WffComplicationSample.forProvider("", "PHOTO_IMAGE").image)
    assertEquals(
      WffSampleImage.HEART,
      WffComplicationSample.forProvider("HEART_RATE", "SMALL_IMAGE").image,
    )
  }

  @Test
  fun `the complication sources evaluate against the sample`() {
    val context =
      WffPreviewContext()
        .withComplication(WffComplicationSample.forProvider("WATCH_BATTERY", "RANGED_VALUE"))
    fun eval(expression: String) = evaluateWffExpression(expression, context)?.asNumber()
    assertEquals(85.0, eval("[COMPLICATION.RANGED_VALUE_VALUE]"))
    assertEquals(
      306.0,
      eval("[COMPLICATION.RANGED_VALUE_VALUE] / [COMPLICATION.RANGED_VALUE_MAX] * 360"),
    )
    assertEquals(0.0, eval("[COMPLICATION.RANGED_VALUE_MIN]"))
    val goal =
      WffPreviewContext()
        .withComplication(WffComplicationSample.forProvider("STEP_COUNT", "GOAL_PROGRESS"))
    assertEquals(
      10000.0,
      evaluateWffExpression("[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]", goal)?.asNumber(),
    )
  }

  @Test
  fun `the editor's pick wins over the slot default, within the slot's types`() {
    val types = listOf("SHORT_TEXT", "RANGED_VALUE")
    fun sample(pick: WffComplicationPick?) =
      wffSlotSample(
        "slot",
        "WATCH_BATTERY",
        "SHORT_TEXT",
        types,
        if (pick == null) emptyMap() else mapOf("slot" to pick),
      )
    assertEquals("SHORT_TEXT", sample(null).type)
    assertEquals("RANGED_VALUE", sample(WffComplicationPick(type = "RANGED_VALUE")).type)
    // A type the slot does not support is not offered; NO_DATA and EMPTY always are.
    assertEquals("SHORT_TEXT", sample(WffComplicationPick(type = "PHOTO_IMAGE")).type)
    assertEquals("EMPTY", sample(WffComplicationPick(type = "EMPTY")).type)
    val moved = sample(WffComplicationPick(type = "RANGED_VALUE", value = 40.0))
    assertEquals(40.0, moved.rangedValue)
    assertEquals("40%", moved.text)
    assertEquals("72", sample(WffComplicationPick(provider = "HEART_RATE")).text)
  }

  /** A face whose ranged-value slot draws an arc as far round as the value. */
  private val arcFace: UiBuilderDocument by lazy {
    val xml =
      """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <Scene backgroundColor="#ff000000">
          <ComplicationSlot slotId="0" supportedTypes="RANGED_VALUE SHORT_TEXT" x="75" y="75" width="300" height="300">
            <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="RANGED_VALUE"/>
            <BoundingOval x="0" y="0" width="300" height="300"/>
            <Complication type="RANGED_VALUE">
              <PartDraw x="0" y="0" width="300" height="300">
                <Arc centerX="150" centerY="150" width="280" height="280" startAngle="0" endAngle="[COMPLICATION.RANGED_VALUE_VALUE] / [COMPLICATION.RANGED_VALUE_MAX] * 360">
                  <Stroke color="#ff4fc3f7" thickness="20"/>
                </Arc>
              </PartDraw>
              <PartText x="0" y="110" width="300" height="80">
                <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="56" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
              </PartText>
            </Complication>
          </ComplicationSlot>
        </Scene>
      </WatchFace>
      """
        .trimIndent()
    assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml))
      .document
      .fixed()
  }

  private val surround = Color(0xFF3D4A5C)

  private fun render(
    document: UiBuilderDocument,
    name: String? = null,
    picks: Map<String, WffComplicationPick> = emptyMap(),
  ): Bitmap {
    val side = 227
    val px = side * 2
    val scene =
      ImageComposeScene(px, px, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(
            LocalUiBuilderCatalogPlatform provides "watchface",
            LocalWffComplicationPicks provides picks,
          ) {
            Box(Modifier.size(side.dp).background(surround)) { UiBuilderSurface(document) }
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

  /** The colour on the arc's circle at [degrees] clockwise from 12 o'clock. */
  private fun Bitmap.onArc(degrees: Double): Int {
    val centre = width / 2.0
    val radius = width * 130 / 450.0
    val a = Math.toRadians(degrees)
    return getColor((centre + radius * sin(a)).toInt(), (centre - radius * cos(a)).toInt())
  }

  private val accent = 0xFF4FC3F7.toInt()

  @Test
  fun `an arc reaches as far round as the ranged value`() {
    val slotId =
      arcFace.nodes.values.single { it.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT }.id
    val full = render(arcFace, "ranged-arc-85")
    // 85% is 306 degrees: past nine o'clock, short of eleven.
    assertEquals(accent, full.onArc(90.0))
    assertEquals(accent, full.onArc(270.0))
    assertNotEquals(accent, full.onArc(330.0))
    val half = render(arcFace, "ranged-arc-40", mapOf(slotId to WffComplicationPick(value = 40.0)))
    assertEquals(accent, half.onArc(90.0))
    assertNotEquals(accent, half.onArc(270.0))
  }

  @Test
  fun `a slot with no content for the picked type draws a tidy default`() {
    val analog = template("analog-watch-face")
    val slot =
      analog.nodes.values.single { it.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT }
    val text = render(analog, "analog-complication-short-text")
    val ranged =
      render(
        analog,
        "analog-complication-ranged",
        mapOf(slot.id to WffComplicationPick(type = "RANGED_VALUE")),
      )
    val empty = render(analog, picks = mapOf(slot.id to WffComplicationPick(type = "EMPTY")))
    fun differ(a: Bitmap, b: Bitmap): Int {
      var count = 0
      for (y in 0 until a.height) for (x in 0 until a.width) if (
        a.getColor(x, y) != b.getColor(x, y)
      )
        count++
      return count
    }
    assertTrue(differ(text, ranged) > 200, "the ranged default draws a ring, not the text chip")
    // The ring's accent is drawn somewhere in the slot.
    val k = text.width / 450f
    var accents = 0
    for (y in (288 * k).toInt() until (348 * k).toInt()) for (x in
      (155 * k).toInt() until (295 * k).toInt()) if (ranged.getColor(x, y) == accent) accents++
    assertTrue(accents > 20, "no accent ring in the slot: $accents")
    assertTrue(differ(text, empty) > 100, "EMPTY still draws the chip")
    render(template("digital-watch-face"), "digital-complication-short-text")
  }
}
