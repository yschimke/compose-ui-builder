package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.wffConfigurations
import ee.schimke.composeai.uibuilder.editor.CANVAS_ZOOM_CONTROLS_TAG
import ee.schimke.composeai.uibuilder.editor.CanvasBottomChrome
import ee.schimke.composeai.uibuilder.editor.CanvasZoomControls
import ee.schimke.composeai.uibuilder.editor.WFF_CONFIGURATION_PICKER_TAG
import ee.schimke.composeai.uibuilder.editor.WffConfigurationPicker
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

/**
 * A watch face's configuration picker shares the editing canvas's bottom edge with the zoom bar:
 * beside it when there is room, in a row of its own above it when not, and never on top of it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class WffConfigurationLaneTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  /** Six configurations, the shape of a sectored face: a layout, four sector styles, a theme. */
  private val sectored: UiBuilderDocument = run {
    val sectorStyles =
      listOf("heart" to "Heart", "battery" to "Battery", "weather" to "Weather", "steps" to "Steps")
    val sectors =
      (1..4).joinToString("\n") { n ->
        val options =
          sectorStyles.joinToString("") { (id, name) ->
            """<ListOption id="$id" displayName="$name"/>"""
          }
        """<ListConfiguration id="sector$n" displayName="Sector $n style" """ +
          """defaultValue="${sectorStyles[(n - 1) % 4].first}">$options</ListConfiguration>"""
      }
    val face =
      """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <UserConfigurations>
          <ListConfiguration id="layout" displayName="Layout" defaultValue="four">
            <ListOption id="four" displayName="Four · quarters"/>
            <ListOption id="two" displayName="Two · halves"/>
          </ListConfiguration>
          $sectors
          <ColorConfiguration id="theme" displayName="Colour theme" defaultValue="ember">
            <ColorOption id="ember" displayName="Ember" colors="#FFFF5722 #FFFFC107"/>
            <ColorOption id="ice" displayName="Ice" colors="#FF4FC3F7 #FFE1F5FE"/>
          </ColorConfiguration>
        </UserConfigurations>
        <Scene backgroundColor="#ff101418">
          <PartDraw x="0" y="0" width="450" height="450">
            <Ellipse x="25" y="25" width="400" height="400"><Fill color="[CONFIGURATION.theme.0]"/></Ellipse>
          </PartDraw>
        </Scene>
      </WatchFace>
      """
        .trimIndent()
    assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(face, WatchFaceXmlImporter.Options(wffVersion = 5)),
      )
      .document
  }

  private val analog: UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      File(designs, "analog-watch-face.json").readText(),
    )

  /** The canvas's bottom edge as the editor composes it, over a stand-in workspace. */
  @Composable
  private fun Chrome(document: UiBuilderDocument, modifier: Modifier = Modifier) {
    CanvasBottomChrome(
      lane = {
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          tonalElevation = 3.dp,
        ) {
          WffConfigurationPicker(document, emptyMap(), { _, _ -> }, {})
        }
      },
      controls = {
        CanvasZoomControls(scale = 3.8f, fitting = true, onZoomChanged = {}, deviceView = false)
      },
      modifier = modifier.fillMaxWidth().padding(12.dp),
    )
  }

  private fun layout(document: UiBuilderDocument, widthDp: Int): Pair<DpRect, DpRect> {
    var bounds: Pair<DpRect, DpRect>? = null
    runDesktopComposeUiTest(width = widthDp, height = 400) {
      setContent {
        MaterialTheme(darkColorScheme()) {
          Box(Modifier.size(widthDp.dp, 400.dp)) {
            Chrome(document, Modifier.align(Alignment.BottomEnd))
          }
        }
      }
      val picker = onNodeWithTag(WFF_CONFIGURATION_PICKER_TAG)
      val zoom = onNodeWithTag(CANVAS_ZOOM_CONTROLS_TAG)
      val pickerBounds = picker.getBoundsInRoot()
      bounds = pickerBounds to zoom.getBoundsInRoot()
      // Cut off or not, every configuration is still in the row: past the edge it scrolls.
      val range =
        picker.fetchSemanticsNode().config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
      val clipped = pickerBounds.right > (widthDp - 12).dp || pickerBounds.left < 12.dp
      assertFalse(clipped, "the picker stays inside the canvas: $pickerBounds at ${widthDp}dp")
      if (range != null && range.maxValue() > 0f) {
        assertTrue(widthDp < 700, "only a narrow canvas needs to scroll the picker")
      }
    }
    return checkNotNull(bounds)
  }

  private fun DpRect.intersects(other: DpRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

  @Test
  fun `the configuration picker never sits on the zoom bar, narrow or wide`() {
    assertEquals(6, sectored.wffConfigurations().size)
    assertTrue(analog.wffConfigurations().isNotEmpty())
    for (document in listOf(sectored, analog)) {
      for (width in listOf(360, 1280)) {
        val (picker, zoom) = layout(document, width)
        assertFalse(
          picker.intersects(zoom),
          "${document.id} at ${width}dp: picker $picker overlaps zoom bar $zoom",
        )
      }
    }
  }

  @Test
  fun `a picker that fits rides beside the zoom bar, one that does not gets a row above it`() {
    val (analogPicker, analogZoom) = layout(analog, 1280)
    // One colour theme fits beside the bar, on its line.
    assertTrue(analogPicker.bottom > analogZoom.top && analogPicker.top < analogZoom.bottom)
    val (sectoredPicker, sectoredZoom) = layout(sectored, 360)
    // Six configurations on a phone-width canvas stack above it.
    assertTrue(sectoredPicker.bottom <= sectoredZoom.top)
  }

  /**
   * Evidence for the review: the canvas's bottom edge over the face, as it was (the picker floated
   * 36dp up, centred, under nothing) and as it is now, written to `build/wff-configuration-lane`.
   */
  @Test
  fun `renders the before and after evidence`() {
    for ((name, document) in listOf("sectored" to sectored, "analog" to analog)) {
      for ((label, width) in listOf("narrow" to 360, "wide" to 900)) {
        render(document, width, before = true, "$name-$label-before")
        render(document, width, before = false, "$name-$label-after")
      }
    }
  }

  private fun render(document: UiBuilderDocument, widthDp: Int, before: Boolean, name: String) {
    val heightDp = 420
    val density = 2f
    val scene =
      ImageComposeScene(
        (widthDp * density).toInt(),
        (heightDp * density).toInt(),
        Density(density),
      ) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1F26))) {
              Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(300.dp)) {
                UiBuilderSurface(document)
              }
              if (before) {
                // The arrangement this replaces, as #1270 placed it.
                CanvasZoomControls(
                  scale = 3.8f,
                  fitting = true,
                  onZoomChanged = {},
                  deviceView = false,
                  modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
                Surface(
                  Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
                  shape = MaterialTheme.shapes.medium,
                  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                  WffConfigurationPicker(document, emptyMap(), { _, _ -> }, {})
                }
              } else {
                Chrome(document, Modifier.align(Alignment.BottomEnd))
              }
            }
          }
        }
      }
    try {
      scene.render(0L)
      val image = scene.render(16_000_000L)
      File("build/wff-configuration-lane").apply {
        mkdirs()
        File(this, "$name.png")
          .writeBytes(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
      }
    } finally {
      scene.close()
    }
  }
}
