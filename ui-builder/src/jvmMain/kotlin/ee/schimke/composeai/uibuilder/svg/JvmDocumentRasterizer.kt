package ee.schimke.composeai.uibuilder.svg

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderNativeOnly
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.frameGeometry
import ee.schimke.composeai.uibuilder.nativeOnlyComponentIds
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/**
 * Draws a design to PNG in this process, the way [JvmSkiaStructuredSvgRecorder] draws it to SVG.
 *
 * The same surface, environment and fixed frame as the SVG recorder, onto a raster instead of an
 * SVG canvas, so a PNG and an SVG exported from one document agree about layout. It is for hosts
 * that have no server to render through — the desktop app and the IntelliJ plugin. The server's PNG
 * route stays the one a shared link points at.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
object JvmDocumentRasterizer {
  /**
   * The document's screen at its own size, density and font scale, PNG-encoded.
   *
   * [catalog] is what the editor's canvas is drawn with. A scene inherits no composition locals, so
   * without it a Wear or Remote Compose design would rasterize as the default catalog — another
   * platform, another frame — and the PNG would not be the design on screen.
   */
  fun renderPng(document: UiBuilderDocument, catalog: CapabilityCatalog? = null): ByteArray {
    val density = document.environmentNumber("density")
    val widthPx = (document.environmentNumber("widthDp") * density).roundToInt()
    val heightPx = (document.environmentNumber("heightDp") * density).roundToInt()
    val layoutDirection =
      if (document.environmentText("layoutDirection") == "rtl") LayoutDirection.Rtl
      else LayoutDirection.Ltr
    val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
    // One frame driven by hand from this thread, as the SVG recorder does; see its comment on why
    // the recomposer takes `Unconfined`.
    val recomposer = FrameRecomposer(Dispatchers.Unconfined)
    val scene =
      CanvasLayersComposeScene(
        recomposer,
        density = Density(density, document.environmentNumber("fontScale")),
        layoutDirection = layoutDirection,
        size = IntSize(widthPx, heightPx),
      )
    return try {
      scene.setContent {
        if (catalog == null) {
          UiBuilderSurface(document = document, editorOverlay = false)
        } else {
          // The catalog-derived half of what the editor provides around its canvas.
          CompositionLocalProvider(
            LocalUiBuilderNativeOnly provides catalog.nativeOnlyComponentIds,
            LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
            LocalUiBuilderCanvasAdapters provides catalog.canvasAdapterIds,
            LocalUiBuilderCanvasAdapterMappings provides catalog.canvasAdapterMappings,
            LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
            LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
          ) {
            UiBuilderSurface(document = document, editorOverlay = false)
          }
        }
      }
      recomposer.performFrame(document.fixedFrameNanos())
      scene.measureAndLayout()
      scene.draw(surface.canvas.asComposeCanvas())
      surface.makeImageSnapshot().use { image ->
        checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Skia could not encode PNG" }
          .bytes
      }
    } finally {
      try {
        scene.close()
      } finally {
        try {
          recomposer.close()
        } finally {
          surface.close()
        }
      }
    }
  }
}
