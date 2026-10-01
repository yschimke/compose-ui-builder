package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.reference.ReferenceFacts
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.facts

/**
 * [image] measured against the frame this design is edited in, as the reducer sees it — the default
 * host shape, since the reducer has no canvas. Null never; kept nullable for callers that have no
 * picture.
 */
internal fun UiBuilderEditorState.referenceFacts(image: ReferenceImage): ReferenceFacts? {
  val (widthDp, heightDp) = document.canvasFrameDp(WearWidgetHostShape.Default)
  return image.facts(widthDp, heightDp, document.screenEnvironmentSettings().density.toFloat())
}
