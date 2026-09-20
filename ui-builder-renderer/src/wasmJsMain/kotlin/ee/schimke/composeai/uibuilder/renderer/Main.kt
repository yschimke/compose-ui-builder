package ee.schimke.composeai.uibuilder.renderer

import ee.schimke.composeai.uibuilder.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.UiBuilderSurface
import ee.schimke.composeai.uibuilder.startCatalogRenderer

fun main() {
  val actionController = UiBuilderSemanticActionController()
  startCatalogRenderer(actionController) { document, renderSessionId, onInspectionSnapshot ->
    UiBuilderSurface(
      document = document,
      editorOverlay = false,
      runtimeActionController = actionController,
      renderSessionId = renderSessionId,
      onInspectionSnapshot = onInspectionSnapshot,
    )
  }
}
