package ee.schimke.composeai.uibuilder.renderer

import ee.schimke.composeai.uibuilder.ProvideUiBuilderFonts
import ee.schimke.composeai.uibuilder.browserFontRegistry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.startCatalogRenderer

fun main() {
  val actionController = UiBuilderSemanticActionController()
  // The runtime frame is its own page with its own copy of the vendored fonts, so it loads the
  // family its document names itself; the editor's registry is in another document.
  val fonts = browserFontRegistry()
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
