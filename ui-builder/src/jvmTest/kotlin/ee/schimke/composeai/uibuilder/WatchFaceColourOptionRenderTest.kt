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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap

/**
 * A fill that names a face colour option (held in `wffExtras` after a chip commit) still draws: the
 * option's colour from the configuration's default option, not nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WatchFaceColourOptionRenderTest {
  private fun document(fill: String) =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},"environment":{"widthDp":225,"heightDp":225,"density":2.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled","networkAccess":false,"exportDevices":["id:wearos_large_round"]},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","properties":{
            "width":{"type":"int","value":450},"height":{"type":"int","value":450},
            "clipShape":{"type":"string","value":"NONE"}},
            "slots":{"content":["dot"]}},
          "dot":{"id":"dot","componentId":"wff/part-draw","properties":{
            "shape":{"type":"string","value":"rectangle"},
            "x":{"type":"int","value":100},"y":{"type":"int","value":100},
            "width":{"type":"int","value":100},"height":{"type":"int","value":100},
            $fill}},
          "cfg":{"id":"cfg","componentId":"wff/color-configuration","properties":{
            "id":{"type":"string","value":"theme"},
            "defaultValue":{"type":"string","value":"warm"}},
            "slots":{"options":["warm","cool"]}},
          "warm":{"id":"warm","componentId":"wff/color-option","properties":{
            "id":{"type":"string","value":"warm"},
            "colors":{"type":"string","value":"#ffff0000 #ff00ff00"}}},
          "cool":{"id":"cool","componentId":"wff/color-option","properties":{
            "id":{"type":"string","value":"cool"},
            "colors":{"type":"string","value":"#ff0000ff #ffffff00"}}}}}""",
    )

  /** The pixel at the dot's centre, 1 px per WFF pixel. */
  private fun centre(document: UiBuilderDocument): Int {
    val scene =
      ImageComposeScene(450, 450, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(225.dp)) { UiBuilderSurface(document) }
          }
        }
      }
    try {
      scene.render(0L)
      val bitmap = Bitmap().apply { allocN32Pixels(450, 450) }
      check(scene.render(16_000_000L).readPixels(bitmap))
      return bitmap.getColor(150, 150)
    } finally {
      scene.close()
    }
  }

  private val green = 0xFF00FF00.toInt()
  private val red = 0xFFFF0000.toInt()

  @Test
  fun `a fill held as a face colour option draws that option's colour`() {
    val held =
      """"wffExtras":{"type":"string","value":"{\"fillColor\":\"[CONFIGURATION.theme.1]\"}"}"""
    assertEquals(green, centre(document(held)))
  }

  @Test
  fun `a literal fill still draws, and an unresolvable reference draws nothing rather than failing`() {
    assertEquals(red, centre(document(""""fillColor":{"type":"color","value":"#ffff0000"}""")))
    val missing =
      """"wffExtras":{"type":"string","value":"{\"fillColor\":\"[CONFIGURATION.nope.0]\"}"}"""
    val pixel = centre(document(missing))
    assert(pixel != green && pixel != red)
  }
}
