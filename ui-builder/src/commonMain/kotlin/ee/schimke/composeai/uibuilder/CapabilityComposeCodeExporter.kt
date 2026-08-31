package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
)

data class ComposeAssetBinding(val paletteArgb: List<String>)

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
    val unboundAssetKeys =
      document.nodes.values
        .filter { it.componentId == "asset/image" }
        .map { it.string("assetKey") }
        .filter { it !in assetAdapter?.bindings.orEmpty() }
        .distinct()
        .sorted()
    val provenance =
      document.exportProvenance(
        EXPORTER_VERSION,
        declaredFallbacks = unboundAssetKeys.map { "asset-placeholder:$it" },
        assetAdapterId = assetAdapter?.id,
      )
    if (diagnostics.isNotEmpty()) {
      return ComposeExportResult(null, provenance, diagnostics)
    }

    document.nodes.values.sortedBy(UiBuilderNode::id).forEach { node ->
      val capability = catalog.componentsById[node.componentId]
      when {
        capability == null ->
          diagnostics += node.error("UNKNOWN_COMPONENT", "No catalog capability exists")
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
        "layout/supporting-pane-scaffold" ->
          diagnostics +=
            node.warning(
              "ADAPTIVE_COMPATIBILITY_HELPER",
              "two-pane helper does not prove adaptive posture or motion parity",
            )
        "layout/horizontal-carousel" ->
          diagnostics +=
            node.warning(
              "CAROUSEL_COMPATIBILITY_HELPER",
              "row helper preserves order and sizing but not Material carousel masking",
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

    if (diagnostics.any { it.severity == ComposeExportSeverity.ERROR }) {
      return ComposeExportResult(null, provenance, diagnostics)
    }

    val emitter = ComposeEmitter(document, catalog, diagnostics, assetAdapter)
    val source = emitter.emit()
    return ComposeExportResult(source, provenance, diagnostics)
  }
}

private class ComposeEmitter(
  private val document: UiBuilderDocument,
  private val catalog: CapabilityCatalog,
  private val diagnostics: MutableList<ComposeExportDiagnostic>,
  private val assetAdapter: ComposeAssetAdapter?,
) {
  private val out = StringBuilder()

  fun emit(): String {
    val functionName = document.exportFunctionName()
    appendLine("@file:OptIn(ExperimentalMaterial3Api::class)")
    appendLine()
    appendLine("package generated.uibuilder")
    appendLine()
    GENERATED_IMPORTS.forEach { importName -> appendLine("import $importName") }
    appendLine()
    appendLine(
      "// Generated from design ${document.id.escapeComment()} revision ${document.revision}."
    )
    appendLine(
      "// Catalog ${document.catalogString("systemId").escapeComment()}@${document.catalogString("catalogRevision").escapeComment()}; capability ${document.catalogString("capabilityDigest").escapeComment()}."
    )
    diagnostics
      .filter { it.severity == ComposeExportSeverity.WARNING }
      .forEach { diagnostic ->
        appendLine(
          "// TODO[${diagnostic.code.escapeComment()}] node=${diagnostic.nodeId?.escapeComment() ?: "document"}: ${diagnostic.message.escapeComment()}"
        )
      }
    appendLine("@Composable")
    appendLine("fun $functionName() {")
    emitState(1)
    document.roots.singleOrNull()?.let { rootId -> emitNode(rootId, 1) }
    appendLine("}")
    appendLine()
    emitCompatibilityHelpers()
    return out.toString().trimEnd() + "\n"
  }

  private fun emitState(level: Int) {
    document.stateVariables.entries
      .sortedBy { entry -> entry.key }
      .forEach { (name, declarationElement) ->
        val declaration = declarationElement as? JsonObject ?: JsonObject(emptyMap())
        val initial = declaration["initialValue"].kotlinLiteral()
        line(level, "var ${name.identifier()} by remember { mutableStateOf($initial) }")
      }
  }

  private fun emitNode(nodeId: String, level: Int) {
    val node = document.nodes.getValue(nodeId)
    val capability = catalog.componentsById.getValue(node.componentId)
    line(
      level,
      "// node:${node.id.escapeComment()} component:${node.componentId.escapeComment()} symbol:${capability.code?.symbol?.escapeComment()}",
    )
    line(level, "// typed-properties:${node.properties.toString().escapeComment()}")
    when (node.componentId) {
      "layout/supporting-pane-scaffold" -> emitSupportingPane(node, level)
      "layout/scaffold" -> emitScaffold(node, level)
      "layout/box" -> emitSimpleContainer(node, level, "Box", "children")
      "layout/column" -> emitColumn(node, level)
      "layout/row" -> emitRow(node, level)
      "layout/lazy-row" -> emitLazy(node, level, "LazyRow", "items")
      "layout/lazy-column" -> emitLazy(node, level, "LazyColumn", "items")
      "layout/lazy-grid" -> emitGrid(node, level)
      "layout/horizontal-carousel" -> emitCarousel(node, level)
      "m3/search-bar" -> emitSimpleContainer(node, level, "BuilderSearchBar", "inputField")
      "m3/search-input-field" -> emitSearchInput(node, level)
      "m3/snackbar-host" ->
        line(level, "BuilderSnackbarHost(visible = ${node.boolExpression("visible")})")
      "m3/surface" -> emitSurface(node, level)
      "m3/card" -> emitCard(node, level)
      "m3/filter-chip" -> emitFilterChip(node, level)
      "m3/icon-button" -> emitSimpleContainer(node, level, "IconButton", "content", "onClick = {}")
      "m3/button" -> emitButton(node, level)
      "m3/horizontal-floating-toolbar" ->
        emitSimpleContainer(node, level, "BuilderHorizontalFloatingToolbar", "content")
      "m3/horizontal-divider" ->
        line(
          level,
          "HorizontalDivider(color = ${node.colorExpression("color")}, ${node.modifierArgument()})",
        )
      "m3/text" -> emitText(node, level)
      "m3/icon" -> emitIcon(node, level)
      "asset/image" -> emitImage(node, level)
      "shape/linear-gradient" -> emitGradient(node, level, radial = false)
      "shape/radial-gradient" -> emitGradient(node, level, radial = true)
      // Confetti baseline components remain supported by the generic projection.
      "m3/center-aligned-top-app-bar" -> emitTopAppBar(node, level)
      "m3/primary-tab-row" ->
        emitSimpleContainer(node, level, "PrimaryTabRow", "tabs", "selectedTabIndex = 0")
      "m3/tab" ->
        emitSimpleContainer(
          node,
          level,
          "Tab",
          "text",
          "selected = ${node.boolExpression("selected")}, onClick = {}",
        )
      "m3/list-item" -> emitListItem(node, level)
      "shape/colour-dot" ->
        line(
          level,
          "Box(${node.modifierExpression()}.size(${node.number("diameterDp", 8f).dpLiteral()}).background(${node.colorExpression("color")}))",
        )
      else -> error("validated emitter dispatch drifted for ${node.componentId}")
    }
  }

  private fun emitSupportingPane(node: UiBuilderNode, level: Int) {
    line(level, "BuilderSupportingPaneScaffold(")
    line(level + 1, "modifier = ${node.modifierExpression()},")
    line(level + 1, "mainPaneWidth = ${node.number("mainPanePreferredWidthDp", 744f).dpLiteral()},")
    line(level + 1, "paneSpacing = ${node.number("paneSpacingDp", 24f).dpLiteral()},")
    line(level + 1, "mainPane = {")
    node.slot("mainPane").forEach { emitNode(it, level + 2) }
    line(level + 1, "},")
    line(level + 1, "supportingPane = {")
    node.slot("supportingPane").forEach { emitNode(it, level + 2) }
    line(level + 1, "},")
    line(level, ")")
  }

  private fun emitScaffold(node: UiBuilderNode, level: Int) {
    line(level, "Scaffold(")
    line(level + 1, "modifier = ${node.modifierExpression()},")
    line(level + 1, "containerColor = ${node.colorExpression("containerColor")},")
    listOf("topBar", "snackbarHost").forEach { slot ->
      line(level + 1, "$slot = {")
      node.slot(slot).forEach { emitNode(it, level + 2) }
      line(level + 1, "},")
    }
    line(level, ") { contentPadding ->")
    line(level + 1, "Box(Modifier.padding(contentPadding)) {")
    node.slot("content").forEach { emitNode(it, level + 2) }
    line(level + 1, "}")
    line(level, "}")
  }

  private fun emitSimpleContainer(
    node: UiBuilderNode,
    level: Int,
    symbol: String,
    slot: String,
    arguments: String? = null,
  ) {
    val prefix = arguments?.let { "$it, " }.orEmpty()
    line(level, "$symbol(${prefix}${node.modifierArgument()}) {")
    node.slot(slot).forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitColumn(node: UiBuilderNode, level: Int) {
    line(
      level,
      "Column(${node.modifierArgument()}, verticalArrangement = Arrangement.spacedBy(${node.number("verticalSpacingDp").dpLiteral()})) {",
    )
    node.slot("children").forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitRow(node: UiBuilderNode, level: Int) {
    val verticalAlignment =
      when (node.string("verticalAlignment")) {
        "top" -> "Alignment.Top"
        "bottom" -> "Alignment.Bottom"
        else -> "Alignment.CenterVertically"
      }
    line(
      level,
      "Row(${node.modifierArgument()}, horizontalArrangement = Arrangement.spacedBy(${node.number("horizontalSpacingDp").dpLiteral()}), verticalAlignment = $verticalAlignment) {",
    )
    node.slot("children").forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitLazy(node: UiBuilderNode, level: Int, symbol: String, slot: String) {
    val contentPadding = node.obj("contentPadding").paddingValuesExpression()
    val arrangement =
      if (symbol == "LazyRow") {
        "horizontalArrangement = Arrangement.spacedBy(${node.number("horizontalSpacingDp").dpLiteral()}), "
      } else {
        "verticalArrangement = Arrangement.spacedBy(${node.number("verticalSpacingDp").dpLiteral()}), "
      }
    line(
      level,
      "$symbol(contentPadding = $contentPadding, $arrangement${node.modifierArgument()}) {",
    )
    node.slot(slot).forEach { id ->
      line(level + 1, "item(key = \"${id.escape()}\") {")
      emitNode(id, level + 2)
      line(level + 1, "}")
    }
    line(level, "}")
  }

  private fun emitGrid(node: UiBuilderNode, level: Int) {
    val minimum = node.obj("columns").number("minimumCellWidthDp", 362f)
    line(
      level,
      "LazyVerticalGrid(columns = GridCells.Adaptive(${minimum.dpLiteral()}), contentPadding = ${node.obj("contentPadding").paddingValuesExpression()}, ${node.modifierArgument()}) {",
    )
    node.slot("items").forEach { id ->
      val full = document.nodes.getValue(id).string("span") == "full"
      val span = if (full) ", span = { GridItemSpan(maxLineSpan) }" else ""
      line(level + 1, "item(key = \"${id.escape()}\"$span) {")
      emitNode(id, level + 2)
      line(level + 1, "}")
    }
    line(level, "}")
  }

  private fun emitCarousel(node: UiBuilderNode, level: Int) {
    line(
      level,
      "BuilderHorizontalCarousel(itemWidth = ${node.number("itemWidthDp", 128f).dpLiteral()}, spacing = ${node.number("itemSpacingDp").dpLiteral()}, contentPaddingStart = ${node.number("contentPaddingStartDp").dpLiteral()}) {",
    )
    node.slot("items").forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitSearchInput(node: UiBuilderNode, level: Int) {
    val variable = node.obj("value").optionalString("variable")?.identifier() ?: "searchQuery"
    line(level, "BuilderSearchInputField(")
    line(level + 1, "value = $variable,")
    line(level + 1, "onValueChange = { $variable = it },")
    listOf("leadingIcon", "placeholder", "trailingIcon").forEach { slot ->
      line(level + 1, "$slot = {")
      node.slot(slot).forEach { emitNode(it, level + 2) }
      line(level + 1, "},")
    }
    line(level, ")")
  }

  private fun emitFilterChip(node: UiBuilderNode, level: Int) {
    line(level, "FilterChip(")
    line(level + 1, "selected = ${node.boolExpression("selected")},")
    line(level + 1, "onClick = { ${node.actionExpression("click")} },")
    line(level + 1, "enabled = ${node.boolValue("enabled", true)},")
    line(level + 1, "shape = RoundedCornerShape(16.dp),")
    line(level + 1, "label = {")
    node.slot("label").forEach { emitNode(it, level + 2) }
    line(level + 1, "},")
    if (node.slot("leadingIcon").isNotEmpty()) {
      line(level + 1, "leadingIcon = {")
      node.slot("leadingIcon").forEach { emitNode(it, level + 2) }
      line(level + 1, "},")
    }
    line(level, ")")
  }

  private fun emitText(node: UiBuilderNode, level: Int) {
    line(
      level,
      "Text(text = \"${node.string("text").escape()}\", style = MaterialTheme.typography.${node.string("style").ifEmpty { "bodyMedium" }.identifier()}, color = ${node.colorExpression("color")}, maxLines = ${node.integer("maxLines", Int.MAX_VALUE)}, overflow = TextOverflow.Ellipsis, ${node.modifierArgument()})",
    )
  }

  private fun emitIcon(node: UiBuilderNode, level: Int) {
    line(
      level,
      "Icon(imageVector = builderIcon(\"${node.string("iconKey").escape()}\"), contentDescription = ${node.string("contentDescription").nullableStringLiteral()}, tint = ${node.colorExpression("color")}, ${node.modifierArgument()})",
    )
  }

  private fun emitImage(node: UiBuilderNode, level: Int) {
    line(
      level,
      "BuilderAssetImage(assetKey = \"${node.string("assetKey").escape()}\", contentDescription = ${node.string("contentDescription").nullableStringLiteral()}, ${node.modifierArgument()})",
    )
  }

  private fun emitGradient(node: UiBuilderNode, level: Int, radial: Boolean) {
    if (radial) {
      line(
        level,
        "BuilderRadialGradient(${node.modifierExpression()}, ${node.colorExpression("innerColor")}, ${node.number("innerAlpha", 1f).floatLiteral()}, ${node.colorExpression("outerColor")})",
      )
    } else {
      line(
        level,
        "Box(${node.modifierExpression()}.background(Brush.verticalGradient(listOf(${node.colorExpression("startColor")}, ${node.colorExpression("endColor")}))))",
      )
    }
  }

  private fun emitTopAppBar(node: UiBuilderNode, level: Int) {
    line(level, "CenterAlignedTopAppBar(title = {")
    node.slot("title").forEach { emitNode(it, level + 1) }
    line(level, "}, ${node.modifierArgument()})")
  }

  private fun emitListItem(node: UiBuilderNode, level: Int) {
    line(level, "ListItem(")
    listOf("headline", "supporting", "trailing").forEach { slot ->
      line(level + 1, "${slot}Content = {")
      node.slot(slot).forEach { emitNode(it, level + 2) }
      line(level + 1, "},")
    }
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  private fun emitSurface(node: UiBuilderNode, level: Int) {
    line(
      level,
      "Surface(${node.modifierArgument()}, color = ${node.colorExpression("containerColor")}) {",
    )
    node.slot("content").forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitCard(node: UiBuilderNode, level: Int) {
    line(
      level,
      "Card(${node.modifierArgument()}, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ${node.colorExpression("containerColor")})) {",
    )
    line(level + 1, "Box(Modifier.fillMaxSize()) {")
    node.slot("content").forEach { emitNode(it, level + 2) }
    line(level + 1, "}")
    line(level, "}")
  }

  private fun emitButton(node: UiBuilderNode, level: Int) {
    val symbol = node.buttonSymbol()
    val colors =
      if (node.string("style") == "fab") {
        "containerColor = ${node.colorExpression("containerColor")}, contentColor = ${node.colorExpression("contentColor")}, "
      } else {
        ""
      }
    line(
      level,
      "$symbol(onClick = { ${node.actionExpression("click")} }, $colors${node.modifierArgument()}) {",
    )
    node.slot("content").forEach { emitNode(it, level + 1) }
    line(level, "}")
  }

  private fun emitCompatibilityHelpers() {
    appendLine(
      "// Compatibility helpers are explicit export diagnostics, not claims of API parity."
    )
    appendLine(
      "@Composable private fun BuilderSupportingPaneScaffold(modifier: Modifier, mainPaneWidth: Dp, paneSpacing: Dp, mainPane: @Composable () -> Unit, supportingPane: @Composable () -> Unit) { BoxWithConstraints(modifier) { if (maxWidth >= 1280.dp) {"
    )
    appendLine(
      "  Row(Modifier.fillMaxSize()) { Box(Modifier.width(mainPaneWidth).fillMaxHeight()) { mainPane() }; Spacer(Modifier.width(paneSpacing)); Box(Modifier.weight(1f).fillMaxHeight()) { supportingPane() } } } else { mainPane() } }"
    )
    appendLine("}")
    appendLine(
      "@Composable private fun BuilderHorizontalCarousel(itemWidth: Dp, spacing: Dp, contentPaddingStart: Dp, content: @Composable RowScope.() -> Unit) { Row(Modifier.padding(start = contentPaddingStart), horizontalArrangement = Arrangement.spacedBy(spacing), content = content) }"
    )
    appendLine(
      "@Composable private fun BuilderSearchBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) { Surface(modifier.height(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Box(Modifier.fillMaxSize()) { content() } } }"
    )
    appendLine(
      "@Composable private fun BuilderSearchInputField(value: String, onValueChange: (String) -> Unit, leadingIcon: @Composable () -> Unit, placeholder: @Composable () -> Unit, trailingIcon: @Composable () -> Unit) { Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { leadingIcon(); BasicTextField(value, onValueChange, Modifier.weight(1f), decorationBox = { inner -> if (value.isEmpty()) placeholder(); inner() }); trailingIcon() } }"
    )
    appendLine(
      "@Composable private fun BuilderSnackbarHost(visible: Boolean) { if (visible) Snackbar { Text(\"Snackbar\") } }"
    )
    appendLine(
      "@Composable private fun BuilderHorizontalFloatingToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) { Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 8.dp) { Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content) } }"
    )
    appendLine(
      "@Composable private fun BuilderRadialGradient(modifier: Modifier, innerColor: Color, innerAlpha: Float, outerColor: Color) { Box(modifier.drawBehind { drawRect(Brush.radialGradient(listOf(innerColor.copy(alpha = innerAlpha), outerColor), center = Offset.Zero, radius = size.maxDimension * .82f)) }) }"
    )
    appendLine(
      "// Asset adapter: ${assetAdapter?.id?.escapeComment() ?: "none (visible placeholder)"}."
    )
    appendLine(
      "@Composable private fun BuilderAssetImage(assetKey: String, contentDescription: String?, modifier: Modifier = Modifier) { val semanticModifier = if (contentDescription == null) modifier else modifier.semantics { this.contentDescription = contentDescription }; Canvas(semanticModifier) { val palette = when (assetKey) {"
    )
    assetAdapter
      ?.bindings
      ?.entries
      ?.sortedBy { it.key }
      ?.forEach { (assetKey, binding) ->
        val colors = binding.paletteArgb.joinToString { "Color(0x${it.removePrefix("0x")})" }
        appendLine("  \"${assetKey.escape()}\" -> listOf($colors)")
      }
    appendLine(
      "  else -> listOf(Color(0xFFFF00FF), Color(0xFF202020), Color(0xFFFF00FF)) } ; drawRect(Brush.linearGradient(palette, Offset.Zero, Offset(size.width, size.height))); drawCircle(Color.White.copy(alpha = .18f), size.minDimension * .34f, Offset(size.width * .76f, size.height * .24f)); drawCircle(Color.Black.copy(alpha = .18f), size.minDimension * .22f, Offset(size.width * .22f, size.height * .72f)); val path = Path().apply { moveTo(size.width * .19f, size.height * .32f); lineTo(size.width * .48f, size.height * .18f); lineTo(size.width * .82f, size.height * .58f); lineTo(size.width * .48f, size.height * .78f); close() }; drawPath(path, Color.White.copy(alpha = .27f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .30f, size.height * .39f), Size(size.width * .10f, size.height * .28f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .47f, size.height * .30f), Size(size.width * .10f, size.height * .38f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .64f, size.height * .43f), Size(size.width * .10f, size.height * .24f)); drawCircle(Color.White.copy(alpha = .72f), size.minDimension * .28f, style = Stroke(size.minDimension * .035f)) } }"
    )
    appendLine(
      "private fun builderIcon(key: String): ImageVector = when (key) { \"search\" -> Icons.Default.Search; \"accountCircle\" -> Icons.Default.AccountCircle; \"check\" -> Icons.Default.Check; \"checkCircle\" -> Icons.Default.CheckCircle; \"addCircle\" -> Icons.Default.AddCircle; \"playCircle\" -> Icons.Default.PlayCircle; \"playlistAdd\" -> Icons.Default.PlaylistAdd; \"moreVert\" -> Icons.Default.MoreVert; \"videoLibrary\" -> Icons.Default.VideoLibrary; else -> Icons.Default.Category }"
    )
  }

  private fun appendLine(value: String = "") {
    out.append(value).append('\n')
  }

  private fun line(level: Int, value: String) = appendLine("  ".repeat(level) + value)
}

private fun UiBuilderNode.slot(name: String): List<String> = slots[name].orEmpty()

private fun UiBuilderNode.obj(name: String): JsonObject =
  properties[name]?.let { it as? JsonObject } ?: JsonObject(emptyMap())

private fun UiBuilderNode.string(name: String): String =
  obj(name)["value"]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun UiBuilderNode.number(name: String, fallback: Float = 0f): Float =
  obj(name)["value"]?.jsonPrimitive?.floatOrNull ?: fallback

private fun JsonObject.number(name: String, fallback: Float = 0f): Float =
  this[name]?.jsonPrimitive?.floatOrNull ?: fallback

private fun UiBuilderNode.integer(name: String, fallback: Int = 0): Int =
  obj(name)["value"]?.jsonPrimitive?.intOrNull ?: fallback

private fun UiBuilderNode.boolValue(name: String, fallback: Boolean = false): Boolean =
  obj(name)["value"]?.jsonPrimitive?.booleanOrNull ?: fallback

private fun UiBuilderNode.boolExpression(name: String): String {
  val value = obj(name)
  return if (value.optionalString("type") == "stateEquals") {
    val variable = value.optionalString("variable")?.identifier() ?: "missingState"
    "$variable == ${value["value"].kotlinLiteral()}"
  } else {
    (value["value"]?.jsonPrimitive?.booleanOrNull ?: false).toString()
  }
}

private fun UiBuilderNode.actionExpression(event: String): String {
  val action = (eventBindings[event] as? JsonArray)?.firstOrNull()?.jsonObject ?: return "Unit"
  val variable = action.optionalString("variable")?.identifier() ?: return "Unit"
  val value = action["value"].kotlinLiteral()
  return when (action.optionalString("type")) {
    "select",
    "setText" -> "$variable = $value"
    "selectOrClear" -> "$variable = if ($variable == $value) null else $value"
    else -> "TODO(\"Unsupported action ${action.optionalString("type")?.escape()}\")"
  }
}

private fun UiBuilderNode.buttonSymbol(): String =
  when (string("style")) {
    "text" -> "TextButton"
    "filledTonal" -> "FilledTonalButton"
    "fab" -> "FloatingActionButton"
    else -> "Button"
  }

private fun UiBuilderNode.modifierArgument(): String = "modifier = ${modifierExpression()}"

private fun UiBuilderNode.modifierExpression(): String {
  var expression = "Modifier"
  modifiers.forEachIndexed { index, element ->
    val modifier = element as? JsonObject ?: error("malformed modifier at $id[$index]")
    expression +=
      when (val type = modifier.optionalString("type")) {
        "fillMaxSize" -> ".fillMaxSize()"
        "fillMaxWidth" -> ".fillMaxWidth()"
        "matchParentSize" -> ".matchParentSize()"
        "padding" ->
          ".padding(start = ${modifier.number("startDp").dpLiteral()}, top = ${modifier.number("topDp").dpLiteral()}, end = ${modifier.number("endDp").dpLiteral()}, bottom = ${modifier.number("bottomDp").dpLiteral()})"
        "size" ->
          ".size(width = ${modifier.number("widthDp").dpLiteral()}, height = ${modifier.number("heightDp").dpLiteral()})"
        "clip" ->
          ".clip(RoundedCornerShape(${shapeDp(modifier.optionalString("shape")).dpLiteral()}))"
        else -> error("unsupported modifier $type on $id")
      }
  }
  if ("weight" in properties) expression += ".weight(${number("weight", 1f).floatLiteral()})"
  expression +=
    when (string("alignment")) {
      "topStart" -> ".align(Alignment.TopStart)"
      "topEnd" -> ".align(Alignment.TopEnd)"
      "bottomStart" -> ".align(Alignment.BottomStart)"
      "bottomCenter" -> ".align(Alignment.BottomCenter)"
      "bottomEnd" -> ".align(Alignment.BottomEnd)"
      "center" -> ".align(Alignment.Center)"
      "centerEnd" -> ".align(Alignment.CenterEnd)"
      else -> ""
    }
  if ("sizeDp" in properties) expression += ".size(${number("sizeDp").dpLiteral()})"
  return expression
}

private fun JsonObject.paddingValuesExpression(): String =
  "PaddingValues(start = ${number("startDp").dpLiteral()}, top = ${number("topDp").dpLiteral()}, end = ${number("endDp").dpLiteral()}, bottom = ${number("bottomDp").dpLiteral()})"

private fun UiBuilderNode.colorExpression(name: String): String {
  val value = string(name)
  if (value.startsWith("#")) return "Color(0x${value.removePrefix("#").uppercase()})"
  return when (value) {
    "primary" -> "MaterialTheme.colorScheme.primary"
    "tertiary" -> "MaterialTheme.colorScheme.tertiary"
    "onTertiary" -> "MaterialTheme.colorScheme.onTertiary"
    "onSurface" -> "MaterialTheme.colorScheme.onSurface"
    "onSurfaceVariant" -> "MaterialTheme.colorScheme.onSurfaceVariant"
    "surface" -> "MaterialTheme.colorScheme.surface"
    "surfaceContainer" -> "MaterialTheme.colorScheme.surfaceContainer"
    "surfaceContainerLow" -> "MaterialTheme.colorScheme.surfaceContainerLow"
    "surfaceContainerHighest" -> "MaterialTheme.colorScheme.surfaceContainerHighest"
    "background" -> "MaterialTheme.colorScheme.background"
    "outlineVariant" -> "MaterialTheme.colorScheme.outlineVariant"
    "transparent" -> "Color.Transparent"
    else -> "Color.Unspecified"
  }
}

private fun UiBuilderDocument.exportFunctionName(): String =
  title.identifier().replaceFirstChar { it.uppercase() }.ifEmpty { "GeneratedScreen" }

internal fun UiBuilderDocument.exportProvenance(
  exporterVersion: String,
  declaredFallbacks: List<String> = emptyList(),
  assetAdapterId: String? = null,
) =
  DocumentExportProvenance(
    designId = id,
    designRevision = revision,
    documentSchema = schema,
    catalogSystemId = catalogString("systemId"),
    catalogRevision = catalogString("catalogRevision"),
    capabilityDigest = catalogString("capabilityDigest"),
    nativeRuntimeId = catalogString("nativeRuntimeId"),
    viewportWidthDp = environment.floatValue("widthDp"),
    viewportHeightDp = environment.floatValue("heightDp"),
    density = environment.floatValue("density"),
    theme =
      (environment["theme"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf(kotlinx.serialization.json.JsonPrimitive::isString)
        ?.content
        .orEmpty(),
    environmentCanonicalJson = canonicalJson(environment),
    declaredFallbacks = declaredFallbacks,
    exporterVersion = exporterVersion,
    assetAdapterId = assetAdapterId,
  )

private fun UiBuilderDocument.catalogString(name: String): String =
  (catalogPin[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

private fun UiBuilderNode.error(code: String, message: String) =
  ComposeExportDiagnostic(code, ComposeExportSeverity.ERROR, message, id, componentId)

private fun UiBuilderNode.warning(code: String, message: String) =
  ComposeExportDiagnostic(code, ComposeExportSeverity.WARNING, message, id, componentId)

private data class HandledFields(
  val properties: Set<String> = emptySet(),
  val slots: Set<String> = emptySet(),
  val events: Set<String> = emptySet(),
)

private fun UiBuilderNode.fieldCoverageDiagnostics(): List<ComposeExportDiagnostic> {
  val handled = HANDLED_FIELDS[componentId] ?: return emptyList()
  val diagnostics = mutableListOf<ComposeExportDiagnostic>()
  (properties.keys - handled.properties).sorted().forEach { field ->
    diagnostics +=
      warning("UNEMITTED_PROPERTY", "property '$field' is preserved in provenance but not emitted")
  }
  (slots.keys - handled.slots).sorted().forEach { field ->
    diagnostics += warning("UNEMITTED_SLOT", "slot '$field' is not emitted")
  }
  (eventBindings.keys - handled.events).sorted().forEach { field ->
    diagnostics += warning("UNEMITTED_EVENT", "event '$field' is not emitted")
  }
  handled.events.intersect(eventBindings.keys).sorted().forEach { event ->
    val actions = eventBindings[event] as? JsonArray
    if (actions == null || actions.isEmpty()) {
      diagnostics += warning("MALFORMED_EVENT", "event '$event' has no action array")
    } else {
      if (actions.size > 1) {
        diagnostics +=
          warning("PARTIAL_EVENT", "event '$event' emits only its first of ${actions.size} actions")
      }
      val action = actions.firstOrNull() as? JsonObject
      val type = action?.optionalString("type")
      if (type !in SUPPORTED_ACTIONS) {
        diagnostics +=
          warning("UNSUPPORTED_EVENT_ACTION", "event '$event' action '$type' emits TODO")
      }
    }
  }
  return diagnostics
}

private fun JsonObject.floatValue(name: String): Float? =
  (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull

private fun JsonElement?.kotlinLiteral(): String =
  when (this) {
    null,
    JsonNull -> "null"
    is kotlinx.serialization.json.JsonPrimitive ->
      when {
        isString -> "\"${content.escape()}\""
        else -> content
      }
    else -> "\"${toString().escape()}\""
  }

private fun String?.nullableStringLiteral(): String =
  if (isNullOrEmpty()) "null" else "\"${escape()}\""

private fun Float.dpLiteral(): String = if (this % 1f == 0f) "${toInt()}.dp" else "${this}f.dp"

private fun Float.floatLiteral(): String = if (this % 1f == 0f) "${toInt()}f" else "${this}f"

private fun shapeDp(value: String?): Float =
  when (value) {
    "large" -> 16f
    "medium" -> 12f
    "small" -> 8f
    else -> value?.toFloatOrNull() ?: 0f
  }

private fun String.identifier(): String {
  val words = split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
  val joined =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar { it.lowercase() }
        else word.replaceFirstChar { it.uppercase() }
      }
      .joinToString("")
  val candidate = joined.ifEmpty { "generatedValue" }
  return if (candidate.first().isDigit()) "generated$candidate" else candidate
}

private fun String.escape(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun String.escapeComment(): String = replace("\n", " ").replace("\r", " ")

private val EMITTER_IDS =
  setOf(
    "asset/image",
    "layout/box",
    "layout/column",
    "layout/horizontal-carousel",
    "layout/lazy-column",
    "layout/lazy-grid",
    "layout/lazy-row",
    "layout/row",
    "layout/scaffold",
    "layout/supporting-pane-scaffold",
    "m3/button",
    "m3/card",
    "m3/center-aligned-top-app-bar",
    "m3/filter-chip",
    "m3/horizontal-divider",
    "m3/horizontal-floating-toolbar",
    "m3/icon",
    "m3/icon-button",
    "m3/list-item",
    "m3/primary-tab-row",
    "m3/search-bar",
    "m3/search-input-field",
    "m3/snackbar-host",
    "m3/surface",
    "m3/tab",
    "m3/text",
    "shape/colour-dot",
    "shape/linear-gradient",
    "shape/radial-gradient",
  )

private val SUPPORTED_MODIFIERS =
  setOf("clip", "fillMaxSize", "fillMaxWidth", "matchParentSize", "padding", "size")

private val SUPPORTED_ACTIONS = setOf("select", "selectOrClear", "setText")

private val HANDLED_FIELDS =
  mapOf(
    "asset/image" to HandledFields(setOf("assetKey", "contentDescription")),
    "layout/box" to HandledFields(slots = setOf("children")),
    "layout/column" to HandledFields(setOf("verticalSpacingDp", "weight"), setOf("children")),
    "layout/horizontal-carousel" to
      HandledFields(
        setOf("itemWidthDp", "itemSpacingDp", "contentPaddingStartDp", "span"),
        setOf("items"),
      ),
    "layout/lazy-column" to
      HandledFields(setOf("contentPadding", "verticalSpacingDp"), setOf("items")),
    "layout/lazy-grid" to HandledFields(setOf("columns", "contentPadding"), setOf("items")),
    "layout/lazy-row" to
      HandledFields(setOf("contentPadding", "horizontalSpacingDp", "span"), setOf("items")),
    "layout/row" to
      HandledFields(setOf("horizontalSpacingDp", "verticalAlignment"), setOf("children")),
    "layout/scaffold" to
      HandledFields(setOf("containerColor"), setOf("topBar", "snackbarHost", "content")),
    "layout/supporting-pane-scaffold" to
      HandledFields(
        setOf("mainPanePreferredWidthDp", "paneSpacingDp"),
        setOf("mainPane", "supportingPane"),
      ),
    "m3/button" to
      HandledFields(
        setOf("style", "containerColor", "contentColor"),
        setOf("content"),
        setOf("click"),
      ),
    "m3/card" to HandledFields(setOf("containerColor", "shape"), setOf("content")),
    "m3/center-aligned-top-app-bar" to HandledFields(slots = setOf("title")),
    "m3/filter-chip" to
      HandledFields(
        setOf("selected", "enabled", "shape"),
        setOf("label", "leadingIcon"),
        setOf("click"),
      ),
    "m3/horizontal-divider" to HandledFields(setOf("color")),
    "m3/horizontal-floating-toolbar" to
      HandledFields(setOf("alignment", "containerColor", "expanded"), setOf("content")),
    "m3/icon" to HandledFields(setOf("iconKey", "contentDescription", "color", "sizeDp")),
    "m3/icon-button" to HandledFields(setOf("alignment", "sizeDp"), slots = setOf("content")),
    "m3/list-item" to HandledFields(slots = setOf("headline", "supporting", "trailing")),
    "m3/primary-tab-row" to HandledFields(slots = setOf("tabs")),
    "m3/search-bar" to HandledFields(slots = setOf("inputField")),
    "m3/search-input-field" to
      HandledFields(
        setOf("value"),
        setOf("placeholder", "leadingIcon", "trailingIcon"),
        setOf("valueChange"),
      ),
    "m3/snackbar-host" to HandledFields(setOf("visible")),
    "m3/surface" to HandledFields(setOf("containerColor"), setOf("content")),
    "m3/tab" to HandledFields(setOf("selected"), setOf("text")),
    "m3/text" to
      HandledFields(setOf("text", "style", "color", "maxLines", "overflow", "alignment", "weight")),
    "shape/colour-dot" to HandledFields(setOf("color", "diameterDp")),
    "shape/linear-gradient" to HandledFields(setOf("startColor", "endColor", "direction")),
    "shape/radial-gradient" to
      HandledFields(setOf("innerColor", "outerColor", "innerAlpha", "center")),
  )

private val GENERATED_IMPORTS =
  listOf(
      "androidx.compose.foundation.background",
      "androidx.compose.foundation.Canvas",
      "androidx.compose.foundation.layout.*",
      "androidx.compose.foundation.lazy.*",
      "androidx.compose.foundation.lazy.grid.*",
      "androidx.compose.foundation.shape.CircleShape",
      "androidx.compose.foundation.shape.RoundedCornerShape",
      "androidx.compose.foundation.text.BasicTextField",
      "androidx.compose.material.icons.Icons",
      "androidx.compose.material.icons.filled.*",
      "androidx.compose.material3.*",
      "androidx.compose.runtime.*",
      "androidx.compose.ui.Alignment",
      "androidx.compose.ui.Modifier",
      "androidx.compose.ui.draw.clip",
      "androidx.compose.ui.draw.drawBehind",
      "androidx.compose.ui.geometry.Offset",
      "androidx.compose.ui.geometry.Size",
      "androidx.compose.ui.graphics.Brush",
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.graphics.Path",
      "androidx.compose.ui.graphics.drawscope.Stroke",
      "androidx.compose.ui.graphics.vector.ImageVector",
      "androidx.compose.ui.semantics.contentDescription",
      "androidx.compose.ui.semantics.semantics",
      "androidx.compose.ui.text.style.TextOverflow",
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.unit.dp",
    )
    .sorted()
