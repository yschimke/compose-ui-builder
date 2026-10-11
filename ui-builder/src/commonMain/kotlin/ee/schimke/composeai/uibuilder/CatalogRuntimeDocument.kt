package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize

/**
 * [this] design as a catalog's renderer runtime is sent it for the editor canvas.
 * `remote-m3/widget-container-adaptive` is a builder template the runtime doesn't ship, so it is
 * resolved via [AdaptiveWearWidget.resolve], as the preview panes, native render and code export
 * already do.
 *
 * Resolved at Large (the editing size, where every slot shows). Node ids survive resolution, so
 * canvas clicks still select the authored nodes.
 */
internal fun UiBuilderDocument.forCatalogRuntime(): UiBuilderDocument =
  if (AdaptiveWearWidget.isAdaptive(this)) {
    AdaptiveWearWidget.resolve(this, WearWidgetScaffoldSize.Large)
  } else {
    this
  }
