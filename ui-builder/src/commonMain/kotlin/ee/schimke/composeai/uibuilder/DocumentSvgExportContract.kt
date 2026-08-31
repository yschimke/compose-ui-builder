package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.SvgCapability
import kotlinx.serialization.json.JsonObject

enum class DocumentSvgExecutionBridge {
  GENERATED_COMPOSE_WRAPPER,
  WASM_SCENE_RECORDING,
}

/** Immutable input to a future render-host job; this spike does not implement either bridge. */
data class DocumentSvgExportRequest(
  val document: UiBuilderDocument,
  val provenance: DocumentExportProvenance,
  val executionBridge: DocumentSvgExecutionBridge,
)

data class DocumentSvgExportBlocker(
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val componentId: String? = null,
)

data class DocumentSvgExportReadiness(
  val request: DocumentSvgExportRequest,
  val blockers: List<DocumentSvgExportBlocker>,
  val declaredRasterFallbackNodeIds: List<String>,
  val unverifiedNodeIds: List<String>,
) {
  val ready: Boolean
    get() = blockers.isEmpty() && unverifiedNodeIds.isEmpty()
}

private enum class ClosedSvgStatus {
  VERIFIED,
  UNVERIFIED,
  RASTER_FALLBACK_REQUIRED,
  UNSUPPORTED,
}

private enum class ClosedSvgFallback {
  NONE,
  EMBEDDED_RASTER,
}

/** Produces a fail-closed stop/go decision and deliberately emits no SVG bytes. */
fun inspectDocumentSvgExport(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  executionBridge: DocumentSvgExecutionBridge,
): DocumentSvgExportReadiness {
  val blockers =
    validateDocumentForExport(document, catalog).mapTo(mutableListOf()) { issue ->
      DocumentSvgExportBlocker(issue.code, issue.message, issue.nodeId, issue.componentId)
    }
  val rasterFallbacks = mutableListOf<String>()
  val unverified = mutableListOf<String>()

  document.nodes.values.sortedBy(UiBuilderNode::id).forEach { node ->
    val svg = catalog.componentsById[node.componentId]?.svg
    if (svg == null) {
      blockers += node.svgBlocker("MISSING_SVG_CAPABILITY", "No SVG capability is declared")
      return@forEach
    }
    val status = svg.closedStatus()
    val fallback = svg.closedFallback()
    if (status == null) {
      blockers += node.svgBlocker("UNKNOWN_SVG_STATUS", "Unknown SVG status '${svg.status}'")
    }
    if (fallback == null) {
      blockers += node.svgBlocker("UNKNOWN_SVG_FALLBACK", "Unknown SVG fallback '${svg.fallback}'")
    }
    when (status) {
      ClosedSvgStatus.UNVERIFIED -> unverified += node.id
      ClosedSvgStatus.UNSUPPORTED ->
        blockers +=
          node.svgBlocker(
            "SVG_CAPABILITY_BLOCKS_EXPORT",
            svg.notes ?: "unsupported SVG capability",
          )
      ClosedSvgStatus.RASTER_FALLBACK_REQUIRED -> {
        if (fallback != ClosedSvgFallback.EMBEDDED_RASTER) {
          blockers +=
            node.svgBlocker(
              "INVALID_SVG_FALLBACK",
              "raster-fallback-required must declare embedded-raster",
            )
        } else if (node.isFullScreenRaster(document, catalog)) {
          blockers +=
            node.svgBlocker(
              "FULL_SCREEN_RASTER_FALLBACK",
              "root, scaffold, and fillMaxSize nodes cannot flatten the design to a raster",
            )
        } else {
          rasterFallbacks += node.id
        }
      }
      ClosedSvgStatus.VERIFIED ->
        if (svg.blocksExport) {
          blockers +=
            node.svgBlocker("SVG_CAPABILITY_BLOCKS_EXPORT", "verified capability blocks export")
        }
      null -> Unit
    }
    if (svg.blocksExport && status != ClosedSvgStatus.UNSUPPORTED && status != null) {
      blockers +=
        node.svgBlocker(
          "SVG_CAPABILITY_BLOCKS_EXPORT",
          svg.notes ?: "${svg.status} explicitly blocks export",
        )
    }
  }

  val provenance =
    document.exportProvenance(
      exporterVersion = "compose-ui-builder-svg-bridge/v1-spike",
      declaredFallbacks = rasterFallbacks.sorted().map { "$it:embedded-raster" },
    )
  return DocumentSvgExportReadiness(
    request = DocumentSvgExportRequest(document, provenance, executionBridge),
    blockers = blockers.distinct(),
    declaredRasterFallbackNodeIds = rasterFallbacks,
    unverifiedNodeIds = unverified,
  )
}

private fun SvgCapability.closedStatus(): ClosedSvgStatus? =
  when (status) {
    "verified" -> ClosedSvgStatus.VERIFIED
    "unverified" -> ClosedSvgStatus.UNVERIFIED
    "raster-fallback-required" -> ClosedSvgStatus.RASTER_FALLBACK_REQUIRED
    "unsupported" -> ClosedSvgStatus.UNSUPPORTED
    else -> null
  }

private fun SvgCapability.closedFallback(): ClosedSvgFallback? =
  when (fallback) {
    "none" -> ClosedSvgFallback.NONE
    "embedded-raster" -> ClosedSvgFallback.EMBEDDED_RASTER
    else -> null
  }

private fun UiBuilderNode.svgBlocker(code: String, message: String) =
  DocumentSvgExportBlocker(code, message, id, componentId)

private fun UiBuilderNode.isFullScreenRaster(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): Boolean =
  id in document.roots ||
    catalog.componentsById[componentId]?.role == "Scaffold" ||
    modifiers.any { modifier -> (modifier as? JsonObject)?.optionalString("type") == "fillMaxSize" }
