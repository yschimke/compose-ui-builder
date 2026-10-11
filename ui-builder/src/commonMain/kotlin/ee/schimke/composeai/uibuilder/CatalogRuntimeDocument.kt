package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot

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

/**
 * [this] runtime inspection with only what [authored] — the design as the editor holds it — names.
 *
 * [forCatalogRuntime] sends the runtime an adaptive widget resolved, which adds layout nodes the
 * designer never placed (`wear-widget-adaptive-large`, its `-text` column). The runtime inspects
 * what it drew, so those came back with bounds, and the canvas picks the smallest bounds under a
 * tap: a click in the empty part of the text column selected a node the editor does not have, which
 * it refused, leaving the previous layer selected; a drag from there did nothing. Their slots go
 * too, and so does any slot the authored node does not declare (the resolved container's
 * `content`), so a drop never targets a parent the document lacks.
 */
internal fun UiBuilderInspectionSnapshot.authoredIn(
  authored: UiBuilderDocument
): UiBuilderInspectionSnapshot =
  copy(
    nodes = nodes.filter { it.nodeId in authored.nodes },
    slots =
      slots.filter { slot ->
        authored.nodes[slot.parentNodeId]?.slots?.containsKey(slot.slotName) == true
      },
  )
