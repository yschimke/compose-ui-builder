package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGCanvas

/**
 * JVM execution bridge from the same native Compose renderer to Skia's structured SVG canvas.
 * `convertTextToPaths=false` asks Skia to retain text where its backend can represent it.
 *
 * This remains a feasibility spike, not the production render-host bridge. Its deterministic output
 * claim is scoped to the same Compose/Skiko/font/OS/architecture runtime, and it supplies no raster
 * node correlations because generic Skia image elements cannot be attributed to document nodes.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
object JvmSkiaStructuredSvgRecorder : StructuredSvgSceneRecorder {
  override val kind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS

  override fun record(document: UiBuilderDocument): StructuredSvgRecording {
    val widthDp = document.environmentNumber("widthDp")
    val heightDp = document.environmentNumber("heightDp")
    val density = document.environmentNumber("density")
    val fontScale = document.environmentNumber("fontScale")
    val widthPx = (widthDp * density).roundToInt()
    val heightPx = (heightDp * density).roundToInt()
    val layoutDirection =
      if (document.environmentText("layoutDirection") == "rtl") LayoutDirection.Rtl
      else LayoutDirection.Ltr
    val output = DynamicMemoryWStream()
    val skiaCanvas =
      SVGCanvas.make(
        Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()),
        output,
        convertTextToPaths = false,
        prettyXML = true,
      )
    val scene =
      CanvasLayersComposeScene(
        density = Density(density, fontScale),
        layoutDirection = layoutDirection,
        size = IntSize(widthPx, heightPx),
        coroutineContext = EmptyCoroutineContext,
        invalidate = {},
      )
    return try {
      try {
        scene.setContent { UiBuilderSurface(document = document, editorOverlay = false) }
        scene.render(skiaCanvas.asComposeCanvas(), document.fixedFrameNanos())
      } finally {
        try {
          scene.close()
        } finally {
          skiaCanvas.close()
        }
      }
      val bytes = ByteArray(output.bytesWritten())
      check(output.read(bytes, 0, bytes.size)) { "Skia SVG stream could not be read" }
      StructuredSvgRecording(
        svg = bytes.decodeToString().canonicalizeSkiaResourceIds(),
        producer = "skia-svg-canvas/0.144.6",
        rasterRecords = emptyList(),
        determinismScope = SAME_RUNTIME_DETERMINISM_SCOPE,
      )
    } finally {
      output.close()
    }
  }
}

private fun UiBuilderDocument.environmentNumber(name: String): Float =
  requireNotNull(
    (environment[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
  ) {
    "validated environment.$name is missing"
  }

private fun UiBuilderDocument.environmentText(name: String): String =
  (environment[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private fun UiBuilderDocument.fixedFrameNanos(): Long {
  // The pinned fixture is settled; a fixed non-zero frame avoids wall-clock output variance. The
  // ISO timestamp remains in export metadata even though ComposeScene consumes monotonic nanos.
  return 1_000_000_000L
}

/**
 * Skia allocates SVG resource ids from a process-global counter; normalize declarations and actual
 * fragment references per document without rewriting unrelated `#` text or color values.
 */
private fun String.canonicalizeSkiaResourceIds(): String {
  val ids = linkedMapOf<String, String>()
  Regex("id=\"([^\"]+)\"").findAll(this).forEach { match ->
    ids.getOrPut(match.groupValues[1]) { "builder_${ids.size}" }
  }
  if (ids.isEmpty()) return this
  val withDeclarations =
    Regex("id=\"([^\"]+)\"").replace(this) { match ->
      "id=\"${ids.getValue(match.groupValues[1])}\""
    }
  val withUrlReferences =
    Regex("url\\(\\s*#([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)").replace(withDeclarations) { match ->
      ids[match.groupValues[1]]?.let { "url(#$it)" } ?: match.value
    }
  return Regex("((?:xlink:)?href\\s*=\\s*[\"'])#([^\"']+)([\"'])").replace(withUrlReferences) {
    match ->
    ids[match.groupValues[2]]?.let { "${match.groupValues[1]}#$it${match.groupValues[3]}" }
      ?: match.value
  }
}
