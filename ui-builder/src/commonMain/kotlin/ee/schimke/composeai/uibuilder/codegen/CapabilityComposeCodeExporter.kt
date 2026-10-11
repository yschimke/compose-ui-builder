package ee.schimke.composeai.uibuilder.codegen

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.RemoteScopes
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ComposeExportSeverity {
  WARNING,
  ERROR,
}

data class ComposeExportDiagnostic(
  val code: String,
  val severity: ComposeExportSeverity,
  val message: String,
  val nodeId: String? = null,
  val componentId: String? = null,
)

data class DocumentExportProvenance(
  val designId: String,
  val designRevision: Int,
  val documentSchema: String,
  val catalogSystemId: String,
  val catalogRevision: String,
  val capabilityDigest: String,
  val nativeRuntimeId: String,
  val viewportWidthDp: Float?,
  val viewportHeightDp: Float?,
  val density: Float?,
  val theme: String,
  val environmentCanonicalJson: String,
  val declaredFallbacks: List<String>,
  val exporterVersion: String,
  val assetAdapterId: String? = null,
  /** The document's canonical location, when the document records one. */
  val home: String? = null,
)

/** Explicit, caller-owned mapping from catalog asset keys to editable Compose artwork. */
data class ComposeAssetAdapter(
  val id: String,
  val bindings: Map<String, ComposeAssetBinding>,
  val renderer: ComposeAssetRenderer? = null,
)

data class ComposeAssetRenderer(val symbol: String, val importName: String)

data class ComposeAssetBinding(
  val paletteArgb: List<String> = emptyList(),
  val sourceIdentity: String? = null,
)

data class ComposeExportResult(
  val source: String?,
  val provenance: DocumentExportProvenance,
  val diagnostics: List<ComposeExportDiagnostic>,
) {
  val successful: Boolean
    get() = source != null && diagnostics.none { it.severity == ComposeExportSeverity.ERROR }

  fun requireSource(): String {
    check(successful) {
      diagnostics.joinToString("; ") { diagnostic ->
        listOfNotNull(diagnostic.code, diagnostic.nodeId, diagnostic.message).joinToString(": ")
      }
    }
    return checkNotNull(source)
  }
}

/**
 * Capability-gated recursive Compose source projection. The catalog decides whether a node has an
 * exportable Kotlin identity and supplies its symbol provenance; the emitter owns call syntax (the
 * format has no typed call template yet). A missing emitter is an error, never a painted or bitmap
 * fallback.
 */
object CapabilityComposeCodeExporter {
  const val EXPORTER_VERSION = "compose-ui-builder-code/v1-spike"

  fun export(
    document: UiBuilderDocument,
    catalog: CapabilityCatalog,
    assetAdapter: ComposeAssetAdapter? = null,
  ): ComposeExportResult {
    val diagnostics = diagnose(document, catalog, assetAdapter).toMutableList()
    val compatibilityFallbacks =
      document.nodes.values
        .filter { it.componentId == "m3/horizontal-floating-toolbar" }
        .map { "component-adapter:${it.id}:${it.componentId}" }
    val provenance =
      document.exportProvenance(
        EXPORTER_VERSION,
        declaredFallbacks =
          document.unboundAssetKeys(assetAdapter).map { "asset-placeholder:$it" } +
            compatibilityFallbacks,
        assetAdapterId = assetAdapter?.id,
      )
    if (diagnostics.any { it.severity == ComposeExportSeverity.ERROR }) {
      return ComposeExportResult(null, provenance, diagnostics)
    }
    val emitter = ComposeEmitter(document, catalog, diagnostics, assetAdapter)
    val source = emitter.emit()
    return ComposeExportResult(source, provenance, diagnostics)
  }

  /**
   * Everything this exporter would refuse the document for, without generating source, so the
   * Issues panel reads the real gate. The document-level pass short-circuits the per-node one: a
   * broken graph makes node-level answers unreliable.
   */
  fun diagnose(
    document: UiBuilderDocument,
    catalog: CapabilityCatalog,
    assetAdapter: ComposeAssetAdapter? = null,
  ): List<ComposeExportDiagnostic> {
    val diagnostics =
      validateDocumentForExport(document, catalog).mapTo(mutableListOf()) { issue ->
        ComposeExportDiagnostic(
          code = issue.code,
          severity = ComposeExportSeverity.ERROR,
          message = issue.message,
          nodeId = issue.nodeId,
          componentId = issue.componentId,
        )
      }
    if (assetAdapter != null && assetAdapter.id.isBlank()) {
      diagnostics +=
        ComposeExportDiagnostic(
          code = "INVALID_ASSET_ADAPTER",
          severity = ComposeExportSeverity.ERROR,
          message = "asset adapter id must be non-blank for provenance",
        )
    }
    if (assetAdapter?.renderer?.let { it.symbol.isBlank() || it.importName.isBlank() } == true) {
      diagnostics +=
        ComposeExportDiagnostic(
          code = "INVALID_ASSET_RENDERER",
          severity = ComposeExportSeverity.ERROR,
          message = "asset renderer symbol and import must be non-blank",
        )
    }
    val unboundAssetKeys = document.unboundAssetKeys(assetAdapter)
    // Signatures are derived first, so unprintable bindings are refused by name rather than
    // exported as defaults.
    val (signatures, componentRefusals) = document.componentSignatures()
    val (loops, loopRefusals) =
      document.loopSignatures(
        signatures.values.mapTo(mutableSetOf()) { it.functionName },
        signatures,
      )
    (componentRefusals +
        loopRefusals +
        document.placementRefusals(signatures, document.scopedNodeIds(signatures)) +
        document.loopPlacementRefusals() +
        document.strayBindingRefusals(signatures))
      .forEach { refusal ->
        diagnostics +=
          ComposeExportDiagnostic(
            code = refusal.code,
            severity = ComposeExportSeverity.ERROR,
            message = refusal.message,
            nodeId = refusal.nodeId,
            componentId = refusal.nodeId?.let { document.nodes[it]?.componentId },
          )
      }
    if (diagnostics.isNotEmpty()) {
      return diagnostics
    }

    // Nodes inside remote content are in the Remote Compose vocabulary (`RemoteColumn`,
    // `RemoteModifier`), so they're `RemoteContentEmitter`'s to judge; the scope host is reported
    // once below.
    val remoteScopes = RemoteScopes.of(document)

    document.nodes.values.sortedBy(UiBuilderNode::id).forEach { node ->
      if (remoteScopes.isRemote(node.id)) return@forEach
      val capability = catalog.componentsById[node.componentId]
      when {
        SHOW_BY_STATE in node.properties ->
          diagnostics +=
            node.error(
              "STATE_SELECTION_GENERATOR_REQUIRED",
              "Show by state requires the shared state-selection generator",
            )
        // A placement is a document construct, not a catalog component; where it may sit is its
        // body root's question.
        node.componentId == DESIGN_COMPONENT_INSTANCE_ID -> Unit
        capability == null ->
          diagnostics += node.error("UNKNOWN_COMPONENT", "No catalog capability exists")
        // Named before the generic refusals: the subtree is a different language whose delivery the
        // design doesn't decide.
        node.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID ->
          diagnostics +=
            node.error(
              "REMOTE_CONTENT_NOT_COMPOSE",
              "Remote Compose content is not part of a Compose screen's source: generate its " +
                "@RemoteComposable body separately and play the captured document at a call site " +
                "whose display info and density behaviour are your application's to choose",
            )
        // Only reachable by dropping one outside remote content (slots can't express ancestry; see
        // [RemoteScopes]).
        node.componentId == REMOTE_COMPOSE_CUSTOM_COMPONENT_ID ->
          diagnostics +=
            node.error(
              "CUSTOM_COMPONENT_OUTSIDE_REMOTE_CONTENT",
              "A custom component is a Remote Compose operation naming a host renderer, so it " +
                "only means something inside remote content; put it under a " +
                "$REMOTE_COMPOSE_INLINE_COMPONENT_ID node, or use its children directly here",
            )
        capability.code == null ->
          diagnostics +=
            node.error("MISSING_CODE_CAPABILITY", "No Kotlin symbol/import mapping exists")
        node.componentId !in EMITTER_IDS ->
          diagnostics +=
            node.error(
              "UNSUPPORTED_CODE_COMPONENT",
              "No typed call emitter exists for ${capability.code.symbol}",
            )
      }
      if (node.componentId == "asset/image") {
        val binding = assetAdapter?.bindings?.get(node.string("assetKey"))
        if (
          binding != null &&
            assetAdapter.renderer == null &&
            (binding.paletteArgb.isEmpty() ||
              binding.paletteArgb.any { !it.matches(Regex("[0-9a-fA-F]{8}")) })
        ) {
          diagnostics +=
            node.error(
              "INVALID_ASSET_BINDING",
              "adapter '${assetAdapter.id}' must provide one or more eight-digit ARGB colors",
            )
        }
      }
      node.modifiers.forEachIndexed { index, element ->
        val modifier = element as? JsonObject
        val type = modifier?.optionalString("type")
        when {
          type == null ->
            diagnostics +=
              node.error(
                "MALFORMED_MODIFIER",
                "Modifier at index $index must be an object with a string type",
              )
          type !in SUPPORTED_MODIFIERS ->
            diagnostics +=
              node.error(
                "UNSUPPORTED_CODE_MODIFIER",
                "No Compose emitter exists for modifier $type",
              )
          capability != null && type !in capability.modifierCapabilities ->
            diagnostics +=
              node.error(
                "UNDECLARED_COMPONENT_MODIFIER",
                "Catalog does not allow modifier $type on ${node.componentId}",
              )
        }
      }
      diagnostics += node.fieldCoverageDiagnostics()
      when (node.componentId) {
        "m3/horizontal-floating-toolbar" ->
          diagnostics +=
            node.warning(
              "FLOATING_TOOLBAR_COMPATIBILITY_HELPER",
              "the pinned Material 3 dependency does not provide HorizontalFloatingToolbar; " +
                "generated code uses the adapter declared in export provenance",
            )
        "asset/image" ->
          if (node.string("assetKey") in unboundAssetKeys) {
            diagnostics +=
              node.warning(
                "ASSET_BINDING_REQUIRED",
                "asset '${node.string("assetKey")}' has no binding in adapter '${assetAdapter?.id ?: "none"}'; generated code uses the visible placeholder declared in provenance",
              )
          }
      }
    }

    return diagnostics
  }
}

/**
 * The asset keys no adapter binds: both a provenance fallback and a warning. Read as a primitive
 * only if it is one, since a malformed `assetKey` is already an `INVALID_PROPERTY_TYPE` diagnostic
 * and must not crash the editor.
 */
private fun UiBuilderDocument.unboundAssetKeys(assetAdapter: ComposeAssetAdapter?): List<String> =
  nodes.values
    .filter { it.componentId == "asset/image" }
    .mapNotNull { (it.obj("assetKey")["value"] as? JsonPrimitive)?.contentOrNull }
    .filter { it !in assetAdapter?.bindings.orEmpty() }
    .distinct()
    .sorted()
