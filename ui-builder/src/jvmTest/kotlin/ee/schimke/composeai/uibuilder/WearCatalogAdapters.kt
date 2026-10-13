package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry

/**
 * The canvas adapters the Wear catalog declares, for tests that compose `UiBuilderSurface`
 * themselves (and so must provide `LocalUiBuilderCanvasAdapters`, e.g. `frame/round-screen` for the
 * screen root). Read from the captured published catalog so tests follow delivery.
 */
private val wearCatalog by lazy { publishedTestCatalog("wear-m3") }

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
