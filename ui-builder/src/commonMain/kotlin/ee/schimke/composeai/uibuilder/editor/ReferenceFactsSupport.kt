package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.reference.ReferenceFacts
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.facts

/**
 * [image] measured against the frame this design is edited in, using the default host shape since
 * the reducer has no canvas. Never null; nullable for callers without a picture.
 */
internal fun UiBuilderEditorState.referenceFacts(image: ReferenceImage): ReferenceFacts? {
  val (widthDp, heightDp) = document.canvasFrameDp(WearWidgetHostShape.Default)
  return image.facts(widthDp, heightDp, document.screenEnvironmentSettings().density.toFloat())
}
