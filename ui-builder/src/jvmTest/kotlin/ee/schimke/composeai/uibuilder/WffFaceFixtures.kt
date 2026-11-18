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
import java.io.File
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A 450×450 watch face built from typed `wff/` nodes for the fidelity tests: [nodes] is a JSON map
 * body (`"id":{…},…`) and [content] the scene's children. The face is 227dp at density 2, as the
 * templates are, so one face pixel is ≈1.009 canvas pixels.
 */
internal fun wffFace(content: List<String>, nodes: String, clipShape: String = "NONE") =
  Json.decodeFromString(
    UiBuilderDocument.serializer(),
    """{"schema":"compose-ui-builder-document/v1-candidate","id":"face","title":"Face",
      "revision":0,"catalogPin":{"systemId":"wff-watchface","catalogRevision":"synthetic","capabilityDigest":"synthetic","nativeRuntimeId":"candidate"},"environment":{"widthDp":227,"heightDp":227,"density":2.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T10:08:32Z","animations":"settled","networkAccess":false,"exportDevices":["id:wearos_large_round"]},
      "stateVariables":{},"roots":["scene"],"nodes":{
        "scene":{"id":"scene","componentId":"wff/scene","properties":{
          "width":{"type":"int","value":450},"height":{"type":"int","value":450},
          "clipShape":{"type":"string","value":"$clipShape"},
          "backgroundColor":{"type":"color","value":"#ff000000"}},
          "slots":{"content":[${content.joinToString { "\"$it\"" }}]}},
        $nodes}}""",
  )

/** A typed property, `"name":{"type":…,"value":…}`, with a string or number [value]. */
internal fun wffProp(name: String, value: Any): String {
  val type =
    when (value) {
      is Int -> "int"
      is Number -> "float"
      else -> if (value.toString().startsWith("#")) "color" else "string"
    }
  val encoded = if (value is Number) value.toString() else Json.encodeToString(value.toString())
  return """"$name":{"type":"$type","value":$encoded}"""
}

/** One node, `"id":{…}`, with [props] and [slots]. */
internal fun wffNode(
  id: String,
  componentId: String,
  props: Map<String, Any> = emptyMap(),
  slots: Map<String, List<String>> = emptyMap(),
): String =
  """"$id":{"id":"$id","componentId":"$componentId","properties":{${
    props.entries.joinToString(",") { wffProp(it.key, it.value) }
  }},"slots":{${
    slots.entries.joinToString(",") { (slot, ids) ->
      "\"$slot\":[${ids.joinToString(",") { "\"$it\"" }}]"
    }
  }}}"""

/** [document] drawn by the editor canvas at its environment's size and density. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderWffFace(document: UiBuilderDocument, name: String? = null): Bitmap {
  val side = 227
  val density = 2f
  val px = (side * density).toInt()
  val scene =
    ImageComposeScene(px, px, Density(density)) {
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
      File("build/watchface-renders").apply {
        mkdirs()
        File(this, "$name.png")
          .writeBytes(
            checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes,
          )
      }
    return bitmap
  } finally {
    scene.close()
  }
}

/** The ARGB channels of one pixel. */
internal fun Bitmap.argb(x: Int, y: Int): List<Int> {
  val c = getColor(x, y)
  return listOf(c ushr 24 and 0xff, c ushr 16 and 0xff, c ushr 8 and 0xff, c and 0xff)
}
