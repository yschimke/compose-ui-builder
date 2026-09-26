package ee.schimke.composeai.uibuilder.renderer

import ee.schimke.composeai.uibuilder.ProvideUiBuilderFonts
import ee.schimke.composeai.uibuilder.UiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.browserFontRegistry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.registerWearDeviceFonts
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.startCatalogRenderer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

fun main() {
  val actionController = UiBuilderSemanticActionController()
  // The runtime frame is its own page with its own copy of the vendored fonts, so it loads the
  // family its document names itself; the editor's registry is in another document.
  val fonts = browserFontRegistry()
  // Wear's face before the runtime starts: see `registerWearDeviceFonts`.
  MainScope().launch {
    fonts.registerWearDeviceFonts()
    startRenderer(actionController, fonts)
  }
}

private fun startRenderer(
  actionController: UiBuilderSemanticActionController,
  fonts: UiBuilderFontRegistry,
) {
  startCatalogRenderer(actionController) { document, surface, renderSessionId, onInspectionSnapshot
    ->
    ProvideUiBuilderFonts(fonts) {
      UiBuilderSurface(
        document = document,
        renderSurface = surface,
        editorOverlay = false,
        runtimeActionController = actionController,
        renderSessionId = renderSessionId,
        onInspectionSnapshot = onInspectionSnapshot,
      )
    }
  }
}
