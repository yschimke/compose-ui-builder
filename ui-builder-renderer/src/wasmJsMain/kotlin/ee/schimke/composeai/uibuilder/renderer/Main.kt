package ee.schimke.composeai.uibuilder.renderer

import ee.schimke.composeai.uibuilder.UiBuilderSurface
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.startCatalogRenderer

fun main() {
  val actionController = UiBuilderSemanticActionController()
  startCatalogRenderer(actionController) { document, surface, renderSessionId, onInspectionSnapshot
    ->
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
