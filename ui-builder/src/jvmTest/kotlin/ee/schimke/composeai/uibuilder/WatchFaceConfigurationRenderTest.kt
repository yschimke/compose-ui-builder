package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import ee.schimke.composeai.uibuilder.canvas.wffConfigurations
import ee.schimke.composeai.uibuilder.canvas.withWffConfigurationSelection
import ee.schimke.composeai.uibuilder.editor.WffConfigurationPicker
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A face's user configurations on the canvas: a `[CONFIGURATION.<id>.<n>]` colour draws the
 * selected option's colour, a boolean or list container draws only the selected option's parts, and
 * the preview's choice changes the picture without touching the design.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WatchFaceConfigurationRenderTest {
  private val designs: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/wff-watchface/designs") }
      .first { it.isDirectory }

  private val face =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <UserConfigurations>
        <ColorConfiguration id="theme" displayName="Theme" defaultValue="red">
          <ColorOption id="red" displayName="Red" colors="#FF0000"/>
          <ColorOption id="blue" displayName="Blue" colors="#0000FF"/>
        </ColorConfiguration>
        <BooleanConfiguration id="dot" displayName="Dot" defaultValue="TRUE"/>
        <ListConfiguration id="layout" displayName="Layout" defaultValue="left">
          <ListOption id="left" displayName="Left"/>
          <ListOption id="right" displayName="Right"/>
        </ListConfiguration>
      </UserConfigurations>
      <Scene backgroundColor="#ff000000">
        <PartDraw x="0" y="0" width="450" height="225">
          <Rectangle x="0" y="0" width="450" height="225"><Fill color="[CONFIGURATION.theme.0]"/></Rectangle>
        </PartDraw>
        <BooleanConfiguration id="dot">
          <BooleanOption id="TRUE">
            <PartDraw x="150" y="300" width="150" height="150">
              <Rectangle x="0" y="0" width="150" height="150"><Fill color="#ffffffff"/></Rectangle>
            </PartDraw>
          </BooleanOption>
          <BooleanOption id="FALSE"><PartDraw x="0" y="0" width="10" height="10"><Rectangle x="0" y="0" width="10" height="10"><Fill color="#ff000000"/></Rectangle></PartDraw></BooleanOption>
        </BooleanConfiguration>
        <ListConfiguration id="layout">
          <ListOption id="left">
            <PartDraw x="0" y="230" width="100" height="100">
              <Rectangle x="0" y="0" width="100" height="100"><Fill color="#ff00ff00"/></Rectangle>
            </PartDraw>
          </ListOption>
          <ListOption id="right">
            <PartDraw x="350" y="230" width="100" height="100">
              <Rectangle x="0" y="0" width="100" height="100"><Fill color="#ff00ff00"/></Rectangle>
            </PartDraw>
          </ListOption>
        </ListConfiguration>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private val document: UiBuilderDocument =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
        WatchFaceXmlImporter.import(face, WatchFaceXmlImporter.Options(wffVersion = 5)),
      )
      .document

  private fun render(document: UiBuilderDocument, name: String, picker: UiBuilderDocument? = null) =
    ImageComposeScene(454, if (picker == null) 454 else 560, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Column(Modifier.background(Color(0xFF3D4A5C))) {
              if (picker != null)
                Box(Modifier.size(227.dp, 53.dp).background(Color(0xFF22262E))) {
                  WffConfigurationPicker(
                    document = picker,
                    selection = emptyMap(),
                    onSelect = { _, _ -> },
                    onReset = {},
                  )
                }
              Box(Modifier.size(227.dp)) { UiBuilderSurface(document) }
            }
          }
        }
      }
      .let { scene ->
        try {
          scene.render(0L)
          val bitmap = Bitmap().apply { allocN32Pixels(454, if (picker == null) 454 else 560) }
          check(scene.render(16_000_000L).readPixels(bitmap))
          File("build/watchface-renders").apply {
            mkdirs()
            File(this, "$name.png")
              .writeBytes(
                checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG))
                  .bytes,
              )
          }
          bitmap
        } finally {
          scene.close()
        }
      }

  /** The colour at a point of the face, in face pixels (450 to the picture's 454 less margins). */
  private fun Bitmap.at(faceX: Int, faceY: Int, top: Int = 0): Int =
    getColor((faceX * 454f / 450f).toInt().coerceIn(0, 453), top + (faceY * 454f / 450f).toInt())

  private fun UiBuilderDocument.chosen(vararg picks: Pair<String, String>) =
    withWffConfigurationSelection(mapOf(*picks))

  @Test
  fun `a colour bound to a configuration draws the selected option's colour`() {
    val red = render(document, "config-theme-default-red")
    val blue = render(document.chosen("theme" to "blue"), "config-theme-blue")
    assertNotEquals(red.at(225, 100), blue.at(225, 100))
    assertEquals(0xFFFF0000.toInt(), red.at(225, 100))
    assertEquals(0xFF0000FF.toInt(), blue.at(225, 100))
  }

  @Test
  fun `a boolean container draws its parts only while its option is selected`() {
    val on = render(document, "config-boolean-on")
    val off = render(document.chosen("dot" to "FALSE"), "config-boolean-off")
    assertEquals(0xFFFFFFFF.toInt(), on.at(225, 375))
    assertNotEquals(0xFFFFFFFF.toInt(), off.at(225, 375))
  }

  @Test
  fun `a list container draws the parts of the selected option`() {
    val left = render(document, "config-list-left")
    val right = render(document.chosen("layout" to "right"), "config-list-right")
    assertEquals(0xFF00FF00.toInt(), left.at(50, 280))
    assertNotEquals(0xFF00FF00.toInt(), right.at(50, 280))
    assertEquals(0xFF00FF00.toInt(), right.at(400, 280))
  }

  @Test
  fun `the preview's choice is a copy for drawing and the design keeps its default`() {
    val picked = document.chosen("theme" to "blue", "dot" to "FALSE")
    assertNotEquals(document, picked)
    val before =
      assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document)).xml
    assertTrue("""defaultValue="red"""" in before, before)
    assertTrue("""defaultValue="TRUE"""" in before, before)
    assertEquals(listOf("theme", "dot", "layout"), document.wffConfigurations().map { it.id })
    // An unknown option is ignored rather than drawn as nothing.
    assertEquals(document, document.chosen("theme" to "green"))
  }

  @Test
  fun `the analog template's colour theme recolours its ticks and second hand`() {
    val template =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        File(designs, "analog-watch-face.json").readText(),
      )
    val ice = render(template, "analog-theme-ice", picker = template)
    val ember = render(template.chosen("theme" to "ember"), "analog-theme-ember", picker = template)
    val moss = render(template.chosen("theme" to "moss"), "analog-theme-moss", picker = template)
    assertNotEquals(ice.getColor(227, 150), ember.getColor(227, 150))
    assertNotEquals(ember.getColor(227, 150), moss.getColor(227, 150))
  }
}
