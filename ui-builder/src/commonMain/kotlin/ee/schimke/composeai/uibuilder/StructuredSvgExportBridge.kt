package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog

enum class StructuredSvgRecorderKind {
  JVM_SKIA_SVG_CANVAS,
  WASM_CANVASKIT,
}

/** Immutable identity of the saved snapshot that an asynchronous export worker must execute. */
data class SavedDocumentRevisionPin(
  val designId: String,
  val revision: Int,
  val documentContentSha256: String,
  val catalogPinCanonicalJson: String,
  val environmentCanonicalJson: String,
) {
  companion object {
    fun from(document: UiBuilderDocument) =
      SavedDocumentRevisionPin(
        designId = document.id,
        revision = document.revision,
        documentContentSha256 = sha256Hex(canonicalDocument(document)),
        catalogPinCanonicalJson = canonicalJson(document.catalogPin),
        environmentCanonicalJson = canonicalJson(document.environment),
      )
  }
}

data class SavedDocumentSvgExportJob(
  val pin: SavedDocumentRevisionPin,
  val documentSnapshot: UiBuilderDocument,
  val recorderKind: StructuredSvgRecorderKind,
)

data class StructuredSvgRecording(
  val svg: String,
  val producer: String,
  val rasterRecords: List<StructuredSvgRasterRecord> = emptyList(),
  /** Reproducible only with the same Compose, Skiko, font, OS, and architecture runtime. */
  val determinismScope: String = SAME_RUNTIME_DETERMINISM_SCOPE,
)

data class StructuredSvgRasterRecord(
  val nodeId: String,
  val imageOrdinal: Int,
  val reason: String,
)

const val SAME_RUNTIME_DETERMINISM_SCOPE = "same-compose-skiko-font-os-architecture-runtime"

interface StructuredSvgSceneRecorder {
  val kind: StructuredSvgRecorderKind

  fun record(document: UiBuilderDocument): StructuredSvgRecording
}

sealed interface SavedDocumentSvgExportResult {
  data class Ok(
    val svg: String,
    val provenance: DocumentExportProvenance,
    val producer: String,
  ) : SavedDocumentSvgExportResult

  data class Rejected(val blockers: List<DocumentSvgExportBlocker>) : SavedDocumentSvgExportResult

  data class Failed(val code: String, val message: String) : SavedDocumentSvgExportResult
}

/**
 * Executes a revision-pinned saved-document job through a structured scene recorder. This boundary
 * accepts no PNG/screenshot product, and validates the recorder output before returning bytes to a
 * render-host or MCP caller.
 */
fun executeSavedDocumentSvgExport(
  job: SavedDocumentSvgExportJob,
  catalog: CapabilityCatalog,
  recorder: StructuredSvgSceneRecorder,
): SavedDocumentSvgExportResult {
  val document = job.documentSnapshot
  val actualPin = SavedDocumentRevisionPin.from(document)
  if (actualPin != job.pin) {
    return SavedDocumentSvgExportResult.Rejected(
      listOf(
        DocumentSvgExportBlocker(
          code = "SAVED_REVISION_PIN_MISMATCH",
          message = "job identity does not match its immutable document snapshot",
        )
      )
    )
  }
  if (recorder.kind != job.recorderKind) {
    return SavedDocumentSvgExportResult.Rejected(
      listOf(
        DocumentSvgExportBlocker(
          code = "SVG_RECORDER_KIND_MISMATCH",
          message = "job requests ${job.recorderKind} but recorder provides ${recorder.kind}",
        )
      )
    )
  }
  val bridge =
    when (job.recorderKind) {
      StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS ->
        DocumentSvgExecutionBridge.JVM_SKIA_SCENE_RECORDING
      StructuredSvgRecorderKind.WASM_CANVASKIT -> DocumentSvgExecutionBridge.WASM_SCENE_RECORDING
    }
  val readiness = inspectDocumentSvgExport(document, catalog, bridge)
  if (!readiness.ready) {
    val blockers =
      readiness.blockers +
        readiness.unverifiedNodeIds.map { nodeId ->
          val node = document.nodes[nodeId]
          DocumentSvgExportBlocker(
            code = "SVG_CAPABILITY_UNVERIFIED",
            message = "node has not passed structured SVG verification",
            nodeId = nodeId,
            componentId = node?.componentId,
          )
        }
    return SavedDocumentSvgExportResult.Rejected(blockers.distinct())
  }

  val environmentBlockers = validateRecorderEnvironment(document, job.recorderKind)
  if (environmentBlockers.isNotEmpty()) {
    return SavedDocumentSvgExportResult.Rejected(environmentBlockers)
  }
  val recording =
    try {
      recorder.record(document)
    } catch (failure: Throwable) {
      return SavedDocumentSvgExportResult.Failed(
        code = "SVG_RECORDER_FAILED",
        message = failure.message ?: "structured SVG recorder failed",
      )
    }
  val parsedRecording = parseStrictSvg(recording.svg)
  if (parsedRecording.blockers.isNotEmpty()) {
    return SavedDocumentSvgExportResult.Rejected(parsedRecording.blockers)
  }
  val parsed = checkNotNull(parsedRecording.document)
  val rasterBlockers =
    validateRasterRecords(
      parsed = parsed,
      records = recording.rasterRecords,
      declaredNodeIds = readiness.declaredRasterFallbackNodeIds.sorted(),
    )
  if (rasterBlockers.isNotEmpty()) return SavedDocumentSvgExportResult.Rejected(rasterBlockers)
  val metadata =
    SvgExportMetadata(
      designId = document.id,
      revision = document.revision,
      documentContentSha256 = job.pin.documentContentSha256,
      catalogPinCanonicalJson = job.pin.catalogPinCanonicalJson,
      environmentCanonicalJson = job.pin.environmentCanonicalJson,
      rasterFallbackNodeIds = readiness.declaredRasterFallbackNodeIds.sorted(),
      producer = recording.producer,
      determinismScope = recording.determinismScope,
    )
  val annotated = annotateRasterFallbacks(recording.svg, parsed, recording.rasterRecords)
  val withMetadata = injectMetadata(annotated, metadata)
  val outputBlockers = validateStructuredSvg(withMetadata, metadata)
  if (outputBlockers.isNotEmpty()) return SavedDocumentSvgExportResult.Rejected(outputBlockers)
  return SavedDocumentSvgExportResult.Ok(
    svg = withMetadata,
    provenance = readiness.request.provenance,
    producer = recording.producer,
  )
}

private data class SvgExportMetadata(
  val designId: String,
  val revision: Int,
  val documentContentSha256: String,
  val catalogPinCanonicalJson: String,
  val environmentCanonicalJson: String,
  val rasterFallbackNodeIds: List<String>,
  val producer: String,
  val determinismScope: String,
) {
  fun canonicalText(): String = buildString {
    append("designId=").append(designId).append(';')
    append("revision=").append(revision).append(';')
    append("documentContentSha256=").append(documentContentSha256).append(';')
    append("catalogPin=").append(catalogPinCanonicalJson).append(';')
    append("environment=").append(environmentCanonicalJson).append(';')
    append("rasterFallbackNodeIds=").append(rasterFallbackNodeIds.joinToString(",")).append(';')
    append("producer=").append(producer).append(';')
    append("determinismScope=").append(determinismScope)
  }
}

private fun injectMetadata(svg: String, metadata: SvgExportMetadata): String {
  val parsed = parseStrictSvg(svg).document ?: return svg
  val rootEnd = parsed.root.startTagEnd
  val element =
    "<metadata id=\"compose-ui-builder-export\">${metadata.canonicalText().escapeXml()}</metadata>"
  return svg.substring(0, rootEnd + 1) + element + svg.substring(rootEnd + 1)
}

private fun validateStructuredSvg(
  svg: String,
  metadata: SvgExportMetadata,
): List<DocumentSvgExportBlocker> {
  val parsedResult = parseStrictSvg(svg)
  val blockers = parsedResult.blockers.toMutableList()
  val parsed = parsedResult.document ?: return blockers
  val structuralElementCount = parsed.elements.count { it.name in STRUCTURAL_SVG_ELEMENTS }
  if (structuralElementCount == 0) {
    blockers +=
      svgOutputBlocker(
        "SVG_NOT_STRUCTURED",
        "output contains no groups, text, paths, or vector geometry",
      )
  }
  val images = parsed.images
  if (images.size == 1 && structuralElementCount == 0) {
    blockers +=
      svgOutputBlocker("FULL_SCREEN_RASTER_WRAPPER", "a single image is not structured SVG")
  }
  if (images.size != metadata.rasterFallbackNodeIds.size) {
    blockers +=
      svgOutputBlocker(
        "RASTER_FALLBACK_COUNT_MISMATCH",
        "SVG contains ${images.size} image elements but metadata declares ${metadata.rasterFallbackNodeIds.size} raster fallback nodes",
      )
  }
  images.forEachIndexed { index, image ->
    val href = image.attributes["href"] ?: image.attributes["xlink:href"]
    if (href?.startsWith("data:image/", ignoreCase = true) != true) {
      blockers +=
        svgOutputBlocker(
          "RASTER_FALLBACK_NOT_EMBEDDED",
          "every declared image fallback must be a self-contained data URI",
        )
    }
    val expectedNode = metadata.rasterFallbackNodeIds.getOrNull(index)
    if (expectedNode != null && image.attributes["data-compose-node-id"] != expectedNode) {
      blockers +=
        svgOutputBlocker(
          "RASTER_FALLBACK_NODE_METADATA_MISSING",
          "image fallback $index is not bound to declared node $expectedNode",
        )
    }
  }
  if (
    parsed.elements.none {
      it.name == "metadata" && it.attributes["id"] == "compose-ui-builder-export"
    }
  ) {
    blockers += svgOutputBlocker("SVG_METADATA_MISSING", "revision/fallback metadata is absent")
  }
  return blockers
}

private fun validateRecorderEnvironment(
  document: UiBuilderDocument,
  recorderKind: StructuredSvgRecorderKind,
): List<DocumentSvgExportBlocker> {
  if (recorderKind != StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS) return emptyList()
  val expectedText =
    mapOf(
      "locale" to "en-US",
      "windowPosture" to "flat",
      "animations" to "settled",
      "layoutDirection" to setOf("ltr", "rtl"),
    )
  val blockers = mutableListOf<DocumentSvgExportBlocker>()
  val fontScale = document.environmentPrimitive("fontScale")?.toFloatOrNull()
  if (fontScale == null || !fontScale.isFinite() || fontScale <= 0f) {
    blockers +=
      svgOutputBlocker(
        "UNSUPPORTED_SVG_ENVIRONMENT",
        "JVM scene recording requires a positive finite environment.fontScale",
      )
  }
  expectedText.forEach { (field, accepted) ->
    val actual = document.environmentPrimitive(field)
    val supported = if (accepted is Set<*>) actual in accepted else actual == accepted
    if (!supported) {
      blockers +=
        svgOutputBlocker(
          "UNSUPPORTED_SVG_ENVIRONMENT",
          "JVM scene recording does not support environment.$field='$actual'",
        )
    }
  }
  val fixedTime = document.environmentPrimitive("fixedTime")
  if (fixedTime != "2024-05-16T12:00:00Z") {
    blockers +=
      svgOutputBlocker(
        "UNSUPPORTED_SVG_ENVIRONMENT",
        "the spike supports fixedTime=2024-05-16T12:00:00Z only; no app-clock injection exists",
      )
  }
  mapOf("dynamicColor" to "false", "networkAccess" to "false", "browserZoomPercent" to "100")
    .forEach { (field, expected) ->
      val actual = document.environmentPrimitive(field)
      if (actual != expected) {
        blockers +=
          svgOutputBlocker(
            "UNSUPPORTED_SVG_ENVIRONMENT",
            "JVM scene recording supports environment.$field=$expected only",
          )
      }
    }
  return blockers
}

private fun UiBuilderDocument.environmentPrimitive(name: String): String? =
  (environment[name] as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun svgOutputBlocker(code: String, message: String) =
  DocumentSvgExportBlocker(code = code, message = message)

private fun String.escapeXml(): String =
  replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
