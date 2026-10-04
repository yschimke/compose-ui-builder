package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.LocalReduceMotion
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderInlineDialogs
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.frameGeometry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preview the same seed as Create, pinned to the catalog the host actually serves. */
internal fun UiBuilderNewDesignCatalog.withTemplatePreviews(
  catalog: CapabilityCatalog,
  fixture: JsonObject,
): UiBuilderNewDesignCatalog =
  copy(
    previewCatalog = catalog,
    templates =
      templates.map { template ->
        template.copy(
          previewDocument =
            UiBuilderNewDesignSeed.document(
              designId = "template-$systemId-${template.id}",
              catalogSystemId = systemId,
              templateId = template.id,
              catalogRevision = catalog.benchmark.catalogRevision,
              nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
              fixture = fixture,
            )
        )
      },
  )

/**
 * A still miniature in the same frame as saved-design thumbnails, with no template interactions.
 */
@Composable
internal fun TemplateThumbnail(
  template: UiBuilderNewDesignTemplate,
  catalog: CapabilityCatalog?,
  onSelect: () -> Unit,
) {
  val document = template.previewDocument ?: return
  val (width, height) = remember(document) { document.canvasFrameDp(WearWidgetHostShape.Default) }
  val scale = minOf(80f / width, 104f / height)
  val hostDensity = LocalDensity.current.density
  // Lay out in the host's dp before shrinking. A phone/watch seed's display density would
  // otherwise squeeze its content into a smaller logical screen inside this host-sized frame.
  val rendered =
    remember(document, hostDensity) {
      document.copy(
        environment = JsonObject(document.environment + ("density" to JsonPrimitive(hostDensity)))
      )
    }
  DesignThumbnailFrame(
    Modifier.requiredSize(80.dp, 104.dp).testTag("template-preview-${template.id}")
  ) {
    Box(
      Modifier.wrapContentSize(Alignment.Center, unbounded = true)
        .requiredSize(width.dp, height.dp)
        .graphicsLayer {
          transformOrigin = TransformOrigin.Center
          scaleX = scale
          scaleY = scale
        }
        .clearAndSetSemantics {}
    ) {
      CompositionLocalProvider(
        LocalReduceMotion provides true,
        LocalUiBuilderInlineDialogs provides true,
        LocalUiBuilderCatalogPlatform provides (catalog?.platform?.wireValue ?: "mobile"),
        LocalUiBuilderFrameGeometry provides
          (catalog?.frameGeometry ?: UiBuilderFrameGeometry.None),
      ) {
        UiBuilderSurface(
          document = rendered,
          editorOverlay = false,
          canvasAdapterIds = catalog?.canvasAdapterIds ?: emptyMap(),
          canvasAdapterMappings = catalog?.canvasAdapterMappings ?: emptyMap(),
          catalogComponentIds = catalog?.componentsById?.keys ?: emptySet(),
        )
      }
    }
    // Keep taps on a component inside the picture selecting the template, not running its actions.
    Box(Modifier.matchParentSize().clickable(onClick = onSelect).clearAndSetSemantics {})
  }
}
