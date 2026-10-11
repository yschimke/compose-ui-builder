package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * The canvas adapters the Wear catalog declares, for tests that compose `UiBuilderSurface`
 * themselves (and so must provide `LocalUiBuilderCanvasAdapters`, e.g. `frame/round-screen` for the
 * screen root). Read from the golden so tests follow the catalog.
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
