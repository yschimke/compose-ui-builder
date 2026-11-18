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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap

/**
 * A part outside the round screen: drawn and measured on the editing canvas, cut on the preview.
 * The measured box is what selection and hit testing read.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WatchFaceEditingCanvasTest {
  private val document =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
        "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},"environment":{"widthDp":225,"heightDp":225,"density":2.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled","networkAccess":false,"exportDevices":["id:wearos_large_round"]},
        "stateVariables":{},"roots":["scene"],"nodes":{
          "scene":{"id":"scene","componentId":"wff/scene","properties":{
            "width":{"type":"int","value":450},"height":{"type":"int","value":450}},
            "slots":{"content":["corner"]}},
          "corner":{"id":"corner","componentId":"wff/part-draw","properties":{
            "shape":{"type":"string","value":"rectangle"},
            "x":{"type":"int","value":400},"y":{"type":"int","value":400},
            "width":{"type":"int","value":40},"height":{"type":"int","value":40},
            "fillColor":{"type":"color","value":"#ffff0000"}}}}}""",
    )

  private fun render(editing: Boolean): Pair<Int, UiBuilderInspectionSnapshot?> {
    var snapshot: UiBuilderInspectionSnapshot? = null
    val scene =
      ImageComposeScene(450, 450, Density(2f)) {
        MaterialTheme(darkColorScheme()) {
          CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
            Box(Modifier.size(225.dp)) {
              UiBuilderSurface(
                document,
                editorOverlay = editing,
                onInspectionSnapshot = { snapshot = it },
              )
            }
          }
        }
      }
    try {
      scene.render(0L)
      val bitmap = Bitmap().apply { allocN32Pixels(450, 450) }
      check(scene.render(16_000_000L).readPixels(bitmap))
      return bitmap.getColor(420, 420) to snapshot
    } finally {
      scene.close()
    }
  }

  @Test
  fun `the editing canvas draws and measures a part outside the circle, the preview cuts it`() {
    val red = 0xFFFF0000.toInt()
    val (editingPixel, snapshot) = render(editing = true)
    assertEquals(red, editingPixel)
    val bounds = checkNotNull(snapshot?.nodes?.first { it.nodeId == "corner" }?.bounds)
    assertEquals(400f, bounds.x, 2f)
    assertNotEquals(red, render(editing = false).first)
  }
}
