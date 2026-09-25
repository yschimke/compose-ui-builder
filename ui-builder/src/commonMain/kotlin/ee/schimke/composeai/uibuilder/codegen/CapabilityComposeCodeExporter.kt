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
 * Capability-gated recursive Compose source projection.
 *
 * The capability catalog decides whether a node has an exportable Kotlin identity and supplies its
 * symbol provenance. The emitter still owns call syntax because the candidate capability format
 * does not yet carry a typed call template. A missing emitter is an error, never a generic painted
 * or bitmap fallback.
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
   * Everything this exporter would refuse the document for, without generating a line of source.
   *
   * Split out of [export] so the editor's Issues panel can read the gate that actually refuses
   * rather than restate it. Document-level validity and the Compose projection are two different
   * questions: a design can satisfy every structural rule and still hold a component this exporter
   * has no emitter for, and a panel that reported only the first said "nothing is blocking an
   * export" right up until the export refused.
   *
   * The document-level pass short-circuits the per-node one, as it did inside [export]: a broken
   * graph makes every node-level answer unreliable, and the structural problems are what to fix
   * first anyway.
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
    // A component's signature is derived before a line is generated, so what the emitter cannot
    // print as an expression is refused by name here rather than exported as the component's own
    // default — the difference between a screen that will not build and one that quietly draws the
    // wrong thing.
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

    // Which nodes are written in the Remote Compose vocabulary rather than this one. A node inside
    // remote content is not this exporter's to judge: `layout/column` there becomes `RemoteColumn`,
    // its modifiers are `RemoteModifier`s, and every question below — is there a typed call
    // emitter,
    // does the catalog allow this modifier on this component — is being asked about the wrong
    // language. The host that opened the scope is reported once, immediately below, and the subtree
    // it covers is `RemoteContentEmitter`'s to accept or refuse.
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
        // A placement is a document construct rather than a catalog component: it is not on any
        // catalog's palette, it has no properties or slots of its own, and where it may sit is the
        // question of what its component's body root is — the capability the body already has.
        // Declaring a synthetic entry in the m3 catalog would put a tile on the palette that draws
        // nothing until a component exists, and would say `design/…` is part of Material 3.
        node.componentId == DESIGN_COMPONENT_INSTANCE_ID -> Unit
        capability == null ->
          diagnostics += node.error("UNKNOWN_COMPONENT", "No catalog capability exists")
        // Named before the two generic refusals below, because both of them would be true of it and
        // neither would be useful. "No typed call emitter exists for RemoteDocumentPlayer" tells a
        // designer that a component is missing an implementation; what is actually true is that
        // this subtree is a different language, whose delivery — captured at build time, fetched,
        // played by whichever host the app already has — the design does not decide.
        node.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID ->
          diagnostics +=
            node.error(
              "REMOTE_CONTENT_NOT_COMPOSE",
              "Remote Compose content is not part of a Compose screen's source: generate its " +
                "@RemoteComposable body separately and play the captured document at a call site " +
                "whose display info and density behaviour are your application's to choose",
            )
        // Reachable only by dropping one outside remote content, which the slot rules allow — a
        // generic container accepts `AnyContent`, and whether a subtree is remote is ancestry
        // rather than anything a slot can say (see [RemoteScopes]). So it is caught here instead.
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
        // `layout/supporting-pane-scaffold` used to warn here that a "two-pane helper does not
        // prove adaptive posture or motion parity". It no longer emits a helper that could fail to:
        // the generated screen calls the real `SupportingPaneScaffold` through the directive the
        // window computes, which is the thing the warning was asking for.
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
 * The asset keys no adapter binds, which are both a provenance fallback and a warning.
 *
 * The key is read as a primitive *if it is one*. `UiBuilderNode.string` goes through
 * `jsonPrimitive`, which throws on an array or an object — and a malformed `assetKey` is precisely
 * what `INVALID_PROPERTY_TYPE` already reports, so decoding it eagerly turned a diagnostic into a
 * crash. The editor asks for these diagnostics on every document now, which is what made a latent
 * throw in the export path a way to take the whole editor down.
 */
private fun UiBuilderDocument.unboundAssetKeys(assetAdapter: ComposeAssetAdapter?): List<String> =
  nodes.values
    .filter { it.componentId == "asset/image" }
    .mapNotNull { (it.obj("assetKey")["value"] as? JsonPrimitive)?.contentOrNull }
    .filter { it !in assetAdapter?.bindings.orEmpty() }
    .distinct()
    .sorted()
