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
import ee.schimke.composeai.uibuilder.canvas.LocalWffCanvasExport
import ee.schimke.composeai.uibuilder.canvas.LocalWffPreviewClock
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.WffAnimation
import ee.schimke.composeai.uibuilder.canvas.WffPreviewClockState
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import ee.schimke.composeai.uibuilder.canvas.float
import ee.schimke.composeai.uibuilder.canvas.wffAnimations
import ee.schimke.composeai.uibuilder.canvas.wffTransformed
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap

/**
 * A `<Transform>`'s `<Animation>` on the canvas: played against the preview clock in an editor
 * (Live or scrubbed), and drawn at its settled end wherever time does not run — an export, a
 * `fixedTime`, a host's settled document, ambient.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WffAnimationCanvasTest {
  /**
   * A white square in a group that fades in from nothing over [duration] s, ten frames a second.
   */
  private fun xml(duration: Int = 1) =
    """
    <WatchFace width="450" height="450" clipShape="NONE">
      <Scene backgroundColor="#ff000000">
        <Group name="pulse" x="0" y="0" width="450" height="450" alpha="0">
          <Transform target="alpha" value="255">
            <Animation duration="$duration" interpolation="LINEAR" fps="10"/>
          </Transform>
          <PartDraw x="0" y="0" width="450" height="450">
            <Rectangle x="0" y="0" width="450" height="450"><Fill color="#ffffffff"/></Rectangle>
          </PartDraw>
        </Group>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private fun imported(fixedTime: String? = null, duration: Int = 1): UiBuilderDocument {
    val document =
      (WatchFaceXmlImporter.import(xml(duration)) as WatchFaceXmlImporter.Result.Imported).document
    return if (fixedTime == null) document
    else
      document.copy(
        environment = JsonObject(document.environment + ("fixedTime" to JsonPrimitive(fixedTime))),
      )
  }

  private fun UiBuilderDocument.group(): UiBuilderNode =
    nodes.values.single { it.componentId == WatchFaceXmlExporter.GROUP }

  @Test
  fun `a transform with an animation plays its change from the value before it`() {
    val document = imported()
    assertEquals(listOf(WffAnimation(1.0, fps = 10)), document.wffAnimations())
    val group = document.group()
    fun alphaAt(seconds: Double?) =
      wffTransformed(document, group, WffPreviewContext().playingAt(seconds)).float("alpha")
    assertEquals(255f, alphaAt(null), "settled: at the transform's value")
    assertEquals(0f, alphaAt(0.0))
    assertEquals(127.5f, alphaAt(0.5))
    assertEquals(255f, alphaAt(1.0))
    assertEquals(255f, alphaAt(42.0), "played once, then it stays")
  }

  @Test
  fun `a transform held raw plays the animation it holds`() {
    val document = imported()
    val transform =
      document.nodes.values.single { it.componentId == WatchFaceXmlExporter.TRANSFORM }
    val animation =
      document.nodes.getValue(transform.slots.getValue("content").single()).let { raw ->
        (raw.properties["xml"] as JsonObject)["value"].let { (it as JsonPrimitive).content }
      }
    val rawXml = "<Transform target=\"alpha\" value=\"255\">$animation</Transform>"
    val raw =
      UiBuilderNode(
        id = transform.id,
        componentId = WatchFaceXmlExporter.RAW,
        properties =
          JsonObject(
            mapOf(
              "xml" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(rawXml)),
                ),
            ),
          ),
        modifiers = JsonArray(emptyList()),
        slots = emptyMap(),
      )
    val legacy = document.copy(nodes = document.nodes + (raw.id to raw))
    val group = legacy.group()
    assertEquals(
      127.5f,
      wffTransformed(legacy, group, WffPreviewContext().playingAt(0.5)).float("alpha"),
    )
    assertEquals(255f, wffTransformed(legacy, group, WffPreviewContext()).float("alpha"))
  }

  private fun centre(
    document: UiBuilderDocument,
    clock: WffPreviewClockState?,
    export: Boolean = false,
  ): Int {
    val side = 100
    val scene =
      ImageComposeScene(side, side, Density(1f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(
            LocalUiBuilderCatalogPlatform provides "watchface",
            LocalWffPreviewClock provides clock,
            LocalWffCanvasExport provides export,
          ) {
            Box(Modifier.size(side.dp)) { UiBuilderSurface(document) }
          }
        }
      }
    try {
      scene.render(0L)
      val bitmap = Bitmap().apply { allocN32Pixels(side, side) }
      check(scene.render(16_000_000L).readPixels(bitmap))
      // The red channel of the white square over black: its alpha.
      return (bitmap.getColor(side / 2, side / 2) shr 16) and 0xff
    } finally {
      scene.close()
    }
  }

  private fun scrubbedTo(seconds: Double) =
    WffPreviewClockState().apply { scrubbed = WffScrubTime(10, 8, seconds) }

  @Test
  fun `a scrubbed editor clock plays the animation through its minute`() {
    val document = imported()
    assertEquals(0, centre(document, scrubbedTo(0.0)))
    val half = centre(document, scrubbedTo(0.5))
    assertTrue(half in 120..135, "half-way through the fade: $half")
    assertEquals(255, centre(document, scrubbedTo(5.0)))
    // Scrubbing is a preview: a pinned `fixedTime` still scrubs in the editor.
    assertEquals(0, centre(imported("2024-05-16T10:08:32Z"), scrubbedTo(0.0)))
  }

  @Test
  fun `without a running clock the face is drawn settled`() {
    // A host's settled document with no editor clock, a fixed time under the Live clock, and an
    // export: each draws the end of the fade.
    assertEquals(255, centre(imported(), clock = null))
    assertEquals(255, centre(imported("2024-05-16T10:08:32Z"), WffPreviewClockState()))
    assertEquals(255, centre(imported(), scrubbedTo(0.0), export = true))
  }

  @Test
  fun `a live editor clock starts the animation when the face is shown`() {
    // The first frames on a Live clock are early in the fade (a minute long, so a slow first
    // frame cannot finish it), not its end.
    assertTrue(centre(imported(duration = 60), WffPreviewClockState()) < 128)
  }

  @Test
  fun `a group's scale is drawn, and is where an animated scale starts`() {
    val ring =
      """
      <WatchFace width="450" height="450" clipShape="NONE">
        <Scene backgroundColor="#ff000000">
          <Group name="ring" x="0" y="0" width="450" height="450" scaleX="0.6" scaleY="0.5">
            <Transform target="scaleX" value="1"><Animation duration="2" repeat="-1" fps="0"/></Transform>
          </Group>
        </Scene>
      </WatchFace>
      """
        .trimIndent()
    val document =
      (WatchFaceXmlImporter.import(ring) as WatchFaceXmlImporter.Result.Imported).document
    val group = document.group()
    fun at(seconds: Double?) =
      ee.schimke.composeai.uibuilder.canvas
        .wffNodeIn(document, group, WffPreviewContext().playingAt(seconds))
        .let { it.float("scaleX") to it.float("scaleY") }
    assertEquals(0.6f to 0.5f, at(0.0))
    assertEquals(0.8f to 0.5f, at(1.0))
    assertEquals(1f to 0.5f, at(null))
  }
}
