package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize

/**
 * [this] design as a catalog's renderer runtime is sent it for the editor canvas.
 *
 * A runtime draws the components its catalog ships, and `remote-m3/widget-container-adaptive` is
 * not one of them: it is the builder's template, which [AdaptiveWearWidget.resolve] turns into the
 * fixed Small or Large container every surface already draws. The preview panes, the native render
 * and the code export all resolve it first; the runtime canvas sent it as authored, so remote-m3's
 * runtime — which knows `widget-container-small` and `-large` only — drew an empty frame where the
 * headline, supporting line and action should be.
 *
 * Resolved at Large, the size the editor edits an adaptive widget at (every slot shows there, and
 * the in-process canvas draws the same arrangement). Every node the designer placed keeps its id
 * through the resolution, so a click on the canvas still selects the node they placed.
 */
internal fun UiBuilderDocument.forCatalogRuntime(): UiBuilderDocument =
  if (AdaptiveWearWidget.isAdaptive(this)) {
    AdaptiveWearWidget.resolve(this, WearWidgetScaffoldSize.Large)
  } else {
    this
  }
