package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser

/**
 * The canvas adapters the Wear catalog declares, for a test that composes the surface itself.
 *
 * The editor provides `LocalUiBuilderCanvasAdapters` from the served catalog, so a design's screen
 * root is drawn by whatever adapter its catalog names — `frame/round-screen` for `wear-m3`. A test
 * that calls `UiBuilderSurface` directly is the host, and a host that declares nothing gets
 * nothing: the frame is not drawn, because nothing said which drawing frames it.
 *
 * Read from the golden rather than restated, because the point of the adapter field is that the
 * catalog owns that fact: a test that wrote `"wear-m3/screen-scaffold" to "frame/round-screen"`
 * itself would keep passing after the catalog changed its mind, which is the failure the field
 * exists to remove.
 */
private val wearCatalog by lazy {
  CapabilityCatalogParser.parse(
    checkNotNull(UiBuilderDocument::class.java.getResource("/wear-m3-capabilities-v1.json")) {
        "missing the wear capability golden on the test resources path"
      }
      .readText()
  )
}

internal val wearCatalogAdapters: Map<String, String> by lazy { wearCatalog.canvasAdapterIds }

/** [content] with the Wear catalog's adapters declared, the way the editor declares them. */
@Composable
internal fun WearCatalogAdapters(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalUiBuilderCanvasAdapters provides wearCatalogAdapters,
    LocalUiBuilderFrameGeometry provides wearCatalog.frameGeometry,
    LocalUiBuilderCatalogPlatform provides wearCatalog.platform.wireValue,
    content = content,
  )
}
