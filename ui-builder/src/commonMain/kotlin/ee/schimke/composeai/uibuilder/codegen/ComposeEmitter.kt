package ee.schimke.composeai.uibuilder.codegen

import ee.schimke.composeai.uibuilder.canvas.DEFAULT_PICKED_HOUR
import ee.schimke.composeai.uibuilder.canvas.DEFAULT_PICKED_MINUTE
import ee.schimke.composeai.uibuilder.canvas.DEFAULT_SELECTED_DATE
import ee.schimke.composeai.uibuilder.canvas.DIALOG_CORNER_DP
import ee.schimke.composeai.uibuilder.canvas.DIALOG_TONAL_ELEVATION_DP
import ee.schimke.composeai.uibuilder.canvas.cardStyle
import ee.schimke.composeai.uibuilder.canvas.iconButtonStyle
import ee.schimke.composeai.uibuilder.canvas.isoDateToEpochMillis
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.editor.themeHost
import ee.schimke.composeai.uibuilder.export.AndroidGoogleFonts
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.KOTLIN_HARD_KEYWORDS
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.TwoWayStateBinding
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.canonicalJson
import ee.schimke.composeai.uibuilder.export.cardContentFill
import ee.schimke.composeai.uibuilder.export.description
import ee.schimke.composeai.uibuilder.export.exportedStateIdentifier
import ee.schimke.composeai.uibuilder.export.optionalString
import ee.schimke.composeai.uibuilder.renderer.sdk.GoogleMaterialIcon
import ee.schimke.composeai.uibuilder.renderer.sdk.googleMaterialIcon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ComposeEmitter(
  private val document: UiBuilderDocument,
  private val catalog: CapabilityCatalog,
  private val diagnostics: MutableList<ComposeExportDiagnostic>,
  private val assetAdapter: ComposeAssetAdapter?,
) {
  private val out = StringBuilder()

  /** The typefaces the design's theme host names, declared once at the foot of the file. */
  private val googleFonts = AndroidGoogleFonts(indent = "  ")

  /** Only vectors this document uses reach its generated source and downstream binary. */
  private val usedGoogleMaterialIcons: List<GoogleMaterialIcon> by lazy {
    document.nodes.values
      .filter { it.componentId == "m3/icon" }
      .mapNotNull { googleMaterialIcon(it.string("iconKey")) }
      .distinctBy(GoogleMaterialIcon::key)
      .sortedBy(GoogleMaterialIcon::key)
  }

  /** Each component's generated name and parameters, derived once by the same code the gate ran. */
  private val componentSignatures: Map<String, ComponentSignature> =
    document.componentSignatures().first

  /**
   * The component whose body is being emitted, or null inside the screen function. Bound properties
   * print their parameter only inside the declaring body; the gate refuses bindings that would
   * reach the screen.
   */
  private var emittingComponent: ComponentSignature? = null

  /** Each loop's generated row type, derived once by the same code the gate ran. */
  private val loopSignatures: Map<String, LoopSignature> by lazy {
    document
      .loopSignatures(
        componentSignatures.values.mapTo(mutableSetOf()) { it.functionName },
        componentSignatures,
      )
      .first
  }

  /**
   * The row being drawn inside a loop's template: the lambda's parameter and its signature. A
   * nested loop replaces it rather than stacking, since a template's bindings read only its own
   * row.
   */
  private var emittingRow: Pair<String, LoopSignature>? = null

  /**
   * Emits the body first, then the header, so imports reflect what was actually emitted (e.g. no
   * Adaptive import for an unplaced supporting-pane component).
   */
  fun emit(): String {
    val functionName = document.exportFunctionName()
    val navigates =
      document.nodes.values.any { node ->
        node.eventBindings.values.any { encoded ->
          (encoded as? JsonArray).orEmpty().any { action ->
            (action as? JsonObject)?.optionalString("type") == "navigatePage"
          }
        }
      }
    appendLine("@Composable")
    appendLine(
      if (navigates) "fun $functionName(onNavigatePage: (String) -> Unit = {}) {"
      else "fun $functionName() {"
    )
    emitState(1)
    document.roots.forEach { rootId -> emitNode(rootId, 1) }
    appendLine("}")
    appendLine()
    emitComponentFunctions()
    emitRowClasses()
    emitCompatibilityHelpers()
    googleFonts.declarations.forEach { declaration ->
      appendLine(declaration)
      appendLine()
    }
    val body = out.toString()

    out.clear()
    // `FlowRow` is `@ExperimentalLayoutApi`; opt in only when one was written, since an unused
    // opt-in is a warning. The import is already covered by [GENERATED_IMPORTS].
    val optIns =
      listOf("ExperimentalMaterial3Api") +
        if (emittedFlowLayout) listOf("ExperimentalLayoutApi") else emptyList()
    appendLine("@file:OptIn(${optIns.joinToString { "$it::class" }})")
    appendLine()
    appendLine("package generated.uibuilder")
    appendLine()
    (GENERATED_IMPORTS +
        adaptiveImports() +
        (if (emittedNavigationSuite)
          listOf(
            "androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem",
            "androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold",
          )
        else emptyList()) +
        additionalTextImports() +
        googleFonts.imports +
        listOfNotNull(assetAdapter?.renderer?.importName))
      .distinct()
      .sorted()
      .forEach { importName -> appendLine("import $importName") }
    appendLine()
    appendLine(
      "// Generated from design ${document.id.escapeComment()} revision ${document.revision}."
    )
    document.home?.let { home ->
      appendLine("// Canonical home: ${home.description().escapeComment()}.")
    }
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
    return (out.toString() + body).trimEnd() + "\n"
  }

  /**
   * Every declared variable and the Kotlin type `emitState` writes for it. Handlers read this to
   * refuse values the type cannot hold.
   */
  private val stateKotlinTypes: Map<String, String> by lazy {
    document.stateVariables.keys.associateWith { name -> stateDeclaration(name).kotlinType }
  }

  private fun stateDeclaration(name: String): StateDeclaration =
    StateDeclaration.of(document.stateVariables[name] as? JsonObject)

  /**
   * Every variable, with its Kotlin type written explicitly from `valueType`/`nullable`. Inference
   * from `initialValue` breaks for `null` (infers `Nothing?`) and for string `"true"`.
   */
  /**
   * One `private @Composable fun` per component the design actually places, after the screen
   * function. Parameters are in sorted key order so re-exports are byte-identical.
   */
  private fun emitComponentFunctions() {
    val placed =
      document.nodes.values
        .filter { it.componentId == DESIGN_COMPONENT_INSTANCE_ID }
        .mapNotNull { it.component?.primitiveString("componentKey") }
        .toSet()
    componentSignatures.entries
      .filter { it.key in placed }
      .sortedBy { it.key }
      .forEach { (key, signature) ->
        val parameters =
          (signature.parameters.map { (name, kind) -> "${name.identifier()}: ${kind.kotlinType}" } +
              "modifier: Modifier = Modifier")
            .joinToString()
        appendLine("// component:${key.escapeComment()} root:${signature.root.escapeComment()}")
        appendLine("@Composable")
        appendLine("private fun ${signature.functionName}($parameters) {")
        // `Box(modifier)`: placement modifiers belong to the placement, not the body, matching how
        // the canvas draws it.
        line(1, "Box(modifier) {")
        emittingComponent = signature
        emitNode(signature.root, 2)
        emittingComponent = null
        line(1, "}")
        appendLine("}")
        appendLine()
      }
  }

  /**
   * The rows a loop draws, as the list the generated `forEach` walks, in a `Column` with the
   * canvas's spacing. `kotlin.collections.listOf` is qualified in case a state variable shadows
   * `listOf`.
   */
  private fun emitLoop(node: UiBuilderNode, level: Int) {
    val loop = loopSignatures[node.id] ?: return
    val parameter = rowParameterName(loop)
    line(
      level,
      "Column(${node.modifierArgument()}, verticalArrangement = Arrangement.spacedBy(${node.number("verticalSpacingDp").dpLiteral()})) {",
    )
    val rows =
      loop.rows.joinToString { row ->
        val arguments =
          loop.properties.joinToString { (key, kind) ->
            val value = row[key] as? JsonObject ?: return@joinToString ""
            "${key.identifier()} = ${kind.argument(value)}"
          }
        "${loop.className}($arguments)"
      }
    // An empty list gives Kotlin nothing to infer `row`'s type from, so it is written explicitly.
    val elementType = if (loop.rows.isEmpty()) "<${loop.className}>" else ""
    line(level + 1, "kotlin.collections.listOf$elementType($rows).forEach { $parameter ->")
    val outer = emittingRow
    emittingRow = parameter to loop
    emitNode(loop.template, level + 2)
    emittingRow = outer
    line(level + 1, "}")
    line(level, "}")
  }

  /**
   * A name for the row the lambda binds that nothing else in scope already means: `row`, unless a
   * state variable claims it.
   */
  private fun rowParameterName(loop: LoopSignature): String {
    val taken = document.stateVariables.keys.mapTo(mutableSetOf()) { it.identifier() }
    taken += loop.properties.map { it.first.identifier() }
    if ("row" !in taken) return "row"
    var index = 2
    while ("row$index" in taken) index++
    return "row$index"
  }

  /** One `private data class` per loop, after the screen and the component functions. */
  private fun emitRowClasses() {
    loopSignatures.entries
      .sortedBy { it.key }
      .forEach { (loopId, loop) ->
        val properties =
          loop.properties.joinToString { (key, kind) ->
            "val ${key.identifier()}: ${kind.kotlinType}"
          }
        appendLine("// loop:${loopId.escapeComment()} rows:${loop.rows.size}")
        // A loop whose template reads nothing is legitimate — the same cell n times — and
        // `data class ()` is not a declaration Kotlin accepts, so it becomes a plain class.
        if (properties.isEmpty()) appendLine("private class ${loop.className}")
        else appendLine("private data class ${loop.className}($properties)")
        appendLine()
      }
  }

  /**
   * The call a placement becomes: every parameter the body reads, then the placement's modifier.
   */
  private fun emitPlacement(node: UiBuilderNode, level: Int) {
    val key = node.component?.primitiveString("componentKey").orEmpty()
    val signature = componentSignatures[key] ?: return
    val arguments = node.component?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
    val passed =
      signature.parameters.joinToString("") { (parameter, kind) ->
        val value = arguments[parameter] as? JsonObject ?: return@joinToString ""
        // Inside a loop template a placement may pass the row rather than its own value.
        val expression = node.boundArgument(parameter) ?: kind.argument(value)
        "${parameter.identifier()} = $expression, "
      }
    line(level, "${signature.functionName}($passed${node.modifierArgument()})")
  }

  /**
   * A colour property, as a parameter reference where the body reads one (`shade` instead of
   * `Color(0xFF39D353)`); otherwise the literal.
   */
  private fun UiBuilderNode.boundColorExpression(name: String): String =
    boundParameter(name) ?: colorExpression(name)

  /** A text property, quoted as a literal or written as the parameter the body reads. */
  private fun UiBuilderNode.boundStringExpression(name: String): String =
    boundParameter(name) ?: "\"${string(name).escape()}\""

  /** The row reference a placement's argument reads, or null when it passes a value. */
  private fun UiBuilderNode.boundArgument(argument: String): String? {
    val key = argumentBindingKey(argument) ?: return null
    emittingRow?.let { (parameter, loop) ->
      if (loop.properties.any { it.first == key }) return "$parameter.${key.identifier()}"
    }
    val signature = emittingComponent ?: return null
    return key
      .takeIf { candidate -> signature.parameters.any { it.first == candidate } }
      ?.identifier()
  }

  private fun UiBuilderNode.boundParameter(property: String): String? {
    val key = bindingKey(property) ?: return null
    emittingRow?.let { (parameter, loop) ->
      if (loop.properties.any { it.first == key }) return "$parameter.${key.identifier()}"
    }
    val signature = emittingComponent ?: return null
    return key
      .takeIf { candidate -> signature.parameters.any { it.first == candidate } }
      ?.identifier()
  }

  private fun emitState(level: Int) {
    document.stateVariables.entries
      .sortedBy { entry -> entry.key }
      .forEach { (name, declarationElement) ->
        val declaration = StateDeclaration.of(declarationElement as? JsonObject)
        val initial = declaration.initialValue.literalAs(declaration.kotlinType)
        line(
          level,
          "var ${name.identifier()}: ${declaration.kotlinType} by remember { " +
            "mutableStateOf($initial) }",
        )
      }
  }

  private fun emitNode(nodeId: String, level: Int) {
    val node = document.nodes.getValue(nodeId)
    // Null for a placement, which no catalog declares — the located comment then names the
    // component it places instead of a catalog symbol it does not have.
    val capability = catalog.componentsById[node.componentId]
    val stableIdentity = node.string("stableKey").ifEmpty { node.string("scrollStateKey") }
    val bodyLevel = if (stableIdentity.isEmpty()) level else level + 1
    if (stableIdentity.isNotEmpty()) line(level, "key(\"${stableIdentity.escape()}\") {")
    line(
      bodyLevel,
      "// node:${node.id.escapeComment()} component:${node.componentId.escapeComment()} symbol:${capability?.code?.symbol?.escapeComment() ?: node.component?.primitiveString("componentKey")?.escapeComment()}",
    )
    line(bodyLevel, "// typed-properties:${canonicalJson(node.properties).escapeComment()}")
    when (node.componentId) {
      "layout/supporting-pane-scaffold",
      "layout/list-detail-pane-scaffold" -> emitSupportingPane(node, bodyLevel)
      "layout/scaffold" -> emitScaffold(node, bodyLevel)
      "layout/box" ->
        emitSimpleContainer(node, bodyLevel, "Box", "children", node.boxContentAlignmentArgument())
      "layout/column" -> emitColumn(node, bodyLevel)
      "layout/row" -> emitRow(node, bodyLevel)
      "layout/flow-row" -> emitFlowRow(node, bodyLevel)
      "layout/lazy-row" -> emitLazy(node, bodyLevel, "LazyRow", "items")
      "layout/lazy-column" -> emitLazy(node, bodyLevel, "LazyColumn", "items")
      "layout/lazy-grid" -> emitGrid(node, bodyLevel)
      "layout/horizontal-carousel" -> emitCarousel(node, bodyLevel)
      "m3/search-bar" -> emitSearchBar(node, bodyLevel)
      "m3/search-input-field" -> emitSearchInput(node, bodyLevel)
      "m3/snackbar-host" ->
        line(
          bodyLevel,
          "BuilderSnackbarHost(visible = ${node.boolExpression("visible", stateKotlinTypes)})",
        )
      "m3/surface" -> emitSurface(node, bodyLevel)
      "m3/card" -> emitCard(node, bodyLevel)
      "m3/checkbox" -> emitToggle(node, bodyLevel, "Checkbox")
      "m3/radio-button" ->
        line(
          bodyLevel,
          "RadioButton(selected = ${node.boolExpression("selected", stateKotlinTypes)}, onClick = ${node.actionLambda("click", stateKotlinTypes)}, enabled = ${node.boolValue("enabled", true)}, ${node.modifierArgument()})",
        )
      "m3/text-field" -> emitTextField(node, bodyLevel)
      "m3/slider" -> emitSlider(node, bodyLevel)
      "m3/progress-indicator" -> emitProgress(node, bodyLevel)
      "m3/switch" -> emitToggle(node, bodyLevel, "Switch")
      "m3/dialog" -> emitDialog(node, bodyLevel)
      "m3/date-picker" -> emitDatePicker(node, bodyLevel)
      "m3/time-picker" -> emitTimePicker(node, bodyLevel)
      "m3/filter-chip" -> emitFilterChip(node, bodyLevel)
      "m3/icon-button" -> emitIconButton(node, bodyLevel)
      "m3/button" -> emitButton(node, bodyLevel)
      "m3/horizontal-floating-toolbar" -> emitToolbar(node, bodyLevel)
      "m3/horizontal-divider" ->
        line(
          bodyLevel,
          "HorizontalDivider(${node.dimensionArgument("thickness", "thicknessDp")}color = ${node.colorExpression("color")}, ${node.modifierArgument()})",
        )
      "m3/text" -> emitText(node, bodyLevel)
      "m3/icon" -> emitIcon(node, bodyLevel)
      "asset/image" -> emitImage(node, bodyLevel)
      "shape/linear-gradient" -> emitGradient(node, bodyLevel, radial = false)
      "shape/radial-gradient" -> emitGradient(node, bodyLevel, radial = true)
      // Confetti baseline components remain supported by the generic projection.
      "m3/center-aligned-top-app-bar" -> emitTopAppBar(node, bodyLevel)
      // The design's own index, not zero. The renderer has always read `selectedIndex`, so a tab
      // row with the second tab selected drew that way and generated a screen with the first.
      "m3/primary-tab-row" ->
        emitSimpleContainer(
          node,
          bodyLevel,
          "PrimaryTabRow",
          "tabs",
          "selectedTabIndex = ${node.integerExpression("selectedIndex", stateKotlinTypes)}",
        )
      "m3/primary-scrollable-tab-row" ->
        emitSimpleContainer(
          node,
          bodyLevel,
          "PrimaryScrollableTabRow",
          "tabs",
          "selectedTabIndex = ${node.integerExpression("selectedIndex", stateKotlinTypes)}",
        )
      // Emit the click binding so the exported tab row's selection can move.
      "m3/tab" ->
        emitSimpleContainer(
          node,
          bodyLevel,
          "Tab",
          "text",
          "selected = ${node.boolExpression("selected", stateKotlinTypes)}, onClick = ${node.actionLambda("click", stateKotlinTypes)}",
        )
      "m3/navigation-suite-scaffold" -> emitNavigationSuite(node, bodyLevel)
      // An item's click is its selection, exactly as a tab's is: the binding the renderer
      // dispatches is the one the generated item runs.
      "m3/navigation-suite-item" -> emitNavigationSuiteItem(node, bodyLevel)
      DESIGN_COMPONENT_INSTANCE_ID -> emitPlacement(node, bodyLevel)
      FOR_EACH_COMPONENT_ID -> emitLoop(node, bodyLevel)
      "m3/list-item" -> emitListItem(node, bodyLevel)
      "shape/colour-dot" ->
        line(
          bodyLevel,
          "Box(${node.modifierExpression()}.size(${node.number("diameterDp", 8f).dpLiteral()}).background(${node.colorExpression("color")}))",
        )
      else -> error("validated emitter dispatch drifted for ${node.componentId}")
    }
    if (stableIdentity.isNotEmpty()) line(level, "}")
  }

  /**
   * Whether `kotlin` (used by folded `kotlin.repeat`) still means the package. A design state or
   * renderer import named `kotlin` would capture it; such designs don't fold.
   */
  private val foldsRepeatedSiblings: Boolean by lazy {
    document.stateVariables.keys.none { it.identifier() == "kotlin" } &&
      assetAdapter?.renderer?.importName?.importedSimpleName() != "kotlin"
  }

  /**
   * A slot's children, with runs of identical siblings written as one `repeat`. [foldSignature]
   * decides what is interchangeable.
   *
   * Only in non-lazy containers (the carousel included): lazy children carry per-item keys a fold
   * would have to invent. [emitOne] lets a per-child wrapper fold with its child.
   */
  private fun emitChildren(
    children: List<String>,
    level: Int,
    emitOne: (String, Int) -> Unit = ::emitNode,
  ) {
    // Short lists skip signature computation; this matters for deep single-child chains, where each
    // level would otherwise serialise its whole subtree.
    if (children.size < MINIMUM_FOLDED_RUN) {
      children.forEach { emitOne(it, level) }
      return
    }
    var index = 0
    while (index < children.size) {
      val signature = if (foldsRepeatedSiblings) foldSignature(children[index]) else null
      var end = index + 1
      if (signature != null) {
        while (end < children.size && foldSignature(children[end]) == signature) end++
      }
      val run = end - index
      if (run < MINIMUM_FOLDED_RUN) {
        children.subList(index, end).forEach { emitOne(it, level) }
      } else {
        val folded = children.subList(index, end)
        line(level, "// repeated:$run nodes:${folded.joinToString(",").escapeComment()}")
        line(level, "kotlin.repeat($run) { _ ->")
        emitOne(folded.first(), level + 1)
        line(level, "}")
      }
      index = end
    }
  }

  /**
   * Memoised [foldSignature] answers, nulls included; signatures nest, so unmemoised walks are
   * quadratic in depth.
   */
  private val foldSignatures = mutableMapOf<String, String?>()

  /**
   * What makes two sibling subtrees the same drawing (equal signatures generate identical Kotlin),
   * or null for one that may not fold: a subtree carrying `stableKey` or `scrollStateKey` at any
   * depth, since [emitNode] spends those on a `key(…)` wrapper.
   */
  private fun foldSignature(nodeId: String, ancestors: Set<String> = emptySet()): String? {
    if (nodeId in foldSignatures) return foldSignatures.getValue(nodeId)
    val signature = computeFoldSignature(nodeId, ancestors)
    // Not cached under a cycle: that answer depends on the path, not the node. `GRAPH_CYCLE` is
    // refused before emission, so this is only a guard.
    if (ancestors.isEmpty() || signature != null) foldSignatures[nodeId] = signature
    return signature
  }

  private fun computeFoldSignature(nodeId: String, ancestors: Set<String>): String? {
    // The export gate refuses `GRAPH_CYCLE` before any of this runs; the guard is here so that a
    // future caller cannot turn a malformed document into a stack overflow inside the emitter.
    if (nodeId in ancestors) return null
    val node = document.nodes[nodeId] ?: return null
    if (node.string("stableKey").isNotEmpty() || node.string("scrollStateKey").isNotEmpty()) {
      return null
    }
    val slots = StringBuilder()
    node.slots.entries
      .sortedBy { it.key }
      .forEach { (slot, children) ->
        slots.append(slot).append("=[")
        children.forEach { child ->
          slots.append(foldSignature(child, ancestors + nodeId) ?: return null).append(",")
        }
        slots.append("],")
      }
    return listOf(
        node.componentId,
        canonicalJson(node.properties),
        canonicalJson(node.modifiers),
        canonicalJson(node.eventBindings),
        // Placement arguments are part of the signature, so placements differing only in passed
        // values never fold together.
        canonicalJson(node.component ?: JsonObject(emptyMap())),
        slots.toString(),
      )
      .joinToString("|")
  }

  /**
   * The real `SupportingPaneScaffold`, through the same directive the canvas uses. `layoutMode`
   * becomes the [PaneScaffoldDirective]; preferred widths become `PaneScaffoldScope.preferredWidth`
   * and spacing `horizontalPartitionSpacerSize`. Unstated widths are `null`.
   */
  private fun emitSupportingPane(node: UiBuilderNode, level: Int) {
    emittedSupportingPaneScaffold = true
    val listDetail = node.componentId == "layout/list-detail-pane-scaffold"
    line(level, "BuilderSupportingPaneScaffold(")
    line(level + 1, "listDetail = $listDetail,")
    line(
      level + 1,
      "activePaneIndex = ${if ("activePaneIndex" in node.properties) node.integerExpression("activePaneIndex", stateKotlinTypes) else "null"},",
    )
    line(
      level + 1,
      "activePane = ${node.string("activePane").ifBlank { "list" }.nullableStringLiteral()},",
    )
    line(
      level + 1,
      "paneSizing = ${node.string("paneSizing").ifBlank { "preferred" }.nullableStringLiteral()},",
    )
    line(level + 1, "fixedPaneWidth = ${node.dpArgument("fixedPaneWidthDp")},")
    line(level + 1, "splitFraction = ${node.number("splitFraction", .5f)}f,")
    line(level + 1, "modifier = ${node.modifierExpression()},")
    line(level + 1, "singlePane = ${node.string("layoutMode") == "singlePane"},")
    line(
      level + 1,
      "mainPaneVisible = ${node.boolValue(if (listDetail) "listPaneVisible" else "mainPaneVisible", true)},",
    )
    line(
      level + 1,
      "supportingPaneVisible = ${node.boolValue(if (listDetail) "detailPaneVisible" else "supportingPaneVisible", true)},",
    )
    line(
      level + 1,
      "mainPaneWidth = ${node.dpArgument(if (listDetail) "listPanePreferredWidthDp" else "mainPanePreferredWidthDp")},",
    )
    line(
      level + 1,
      "supportingPaneWidth = ${node.dpArgument(if (listDetail) "detailPanePreferredWidthDp" else "supportingPanePreferredWidthDp")},",
    )
    line(level + 1, "paneSpacing = ${node.dpArgument("paneSpacingDp")},")
    line(level + 1, "mainPane = {")
    emitChildren(node.slot(if (listDetail) "listPane" else "mainPane"), level + 2)
    line(level + 1, "},")
    line(level + 1, "supportingPane = {")
    emitChildren(node.slot(if (listDetail) "detailPane" else "supportingPane"), level + 2)
    line(level + 1, "},")
    if (listDetail && node.slot("extraPane").isNotEmpty()) {
      line(level + 1, "extraPaneWidth = ${node.dpArgument("extraPanePreferredWidthDp")},")
      line(level + 1, "extraPane = {")
      emitChildren(node.slot("extraPane"), level + 2)
      line(level + 1, "},")
    }
    line(level, ")")
  }

  /**
   * `NavigationSuiteScaffold` with its default navigation type (rail on tablet, bar on phone), as
   * the canvas draws per frame. The primary action is written only when the design has one.
   */
  private fun emitNavigationSuite(node: UiBuilderNode, level: Int) {
    emittedNavigationSuite = true
    line(level, "NavigationSuiteScaffold(")
    line(level + 1, "navigationItems = {")
    emitChildren(node.slot("navigationItems"), level + 2)
    line(level + 1, "},")
    line(level + 1, "modifier = ${node.modifierExpression()},")
    val primaryAction = node.slot("primaryAction")
    if (primaryAction.isNotEmpty()) {
      line(level + 1, "primaryActionContent = {")
      emitChildren(primaryAction, level + 2)
      line(level + 1, "},")
    }
    line(level, ") {")
    emitChildren(node.slot("content"), level + 1)
    line(level, "}")
  }

  private fun emitNavigationSuiteItem(node: UiBuilderNode, level: Int) {
    emittedNavigationSuite = true
    line(level, "NavigationSuiteItem(")
    line(level + 1, "selected = ${node.boolExpression("selected", stateKotlinTypes)},")
    line(level + 1, "onClick = ${node.actionLambda("click", stateKotlinTypes)},")
    line(level + 1, "icon = {")
    emitChildren(node.slot("icon"), level + 2)
    line(level + 1, "},")
    // `label` is nullable with no default in Material, so an item without one still names it.
    val label = node.slot("label")
    if (label.isNotEmpty()) {
      line(level + 1, "label = {")
      emitChildren(label, level + 2)
      line(level + 1, "},")
    } else {
      line(level + 1, "label = null,")
    }
    line(level + 1, "modifier = ${node.modifierExpression()},")
    line(level, ")")
  }

  private fun emitScaffold(node: UiBuilderNode, level: Int) {
    line(level, "Scaffold(")
    line(level + 1, "modifier = ${node.modifierExpression()},")
    line(level + 1, "containerColor = ${node.colorExpression("containerColor")},")
    listOf("topBar", "snackbarHost").forEach { slot ->
      line(level + 1, "$slot = {")
      emitChildren(node.slot(slot), level + 2)
      line(level + 1, "},")
    }
    line(level, ") { contentPadding ->")
    line(level + 1, "Box(Modifier.padding(contentPadding)) {")
    emitChildren(node.slot("content"), level + 2)
    if ("loading" in node.properties) {
      line(
        level + 2,
        "if (${node.boolValue("loading")}) LinearProgressIndicator(Modifier.fillMaxWidth())",
      )
    }
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
    emitChildren(node.slot(slot), level + 1)
    line(level, "}")
  }

  private fun emitColumn(node: UiBuilderNode, level: Int) {
    // Omitted at Compose's default, so designs that never set it generate unchanged Kotlin.
    val horizontalAlignment =
      when (node.string("horizontalAlignment")) {
        "center" -> ", horizontalAlignment = Alignment.CenterHorizontally"
        "end" -> ", horizontalAlignment = Alignment.End"
        else -> ""
      }
    line(
      level,
      "Column(${node.modifierArgument()}, verticalArrangement = ${node.verticalArrangementExpression()}$horizontalAlignment) {",
    )
    emitChildren(node.slot("children"), level + 1)
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
      "Row(${node.modifierArgument()}, horizontalArrangement = ${node.horizontalArrangementExpression()}, verticalAlignment = $verticalAlignment) {",
    )
    emitChildren(node.slot("children"), level + 1)
    line(level, "}")
  }

  /**
   * A `FlowRow`: its main axis uses the row arrangement and its cross axis the column arrangement.
   * `maxItemsInEachRow` is written only when the design set one.
   */
  private fun emitFlowRow(node: UiBuilderNode, level: Int) {
    emittedFlowLayout = true
    val maximum = node.number("maxItemsInEachRow").toInt().takeIf { it > 0 }
    line(
      level,
      "FlowRow(${node.modifierArgument()}, horizontalArrangement = ${node.horizontalArrangementExpression()}, verticalArrangement = ${node.verticalArrangementExpression()}" +
        (maximum?.let { ", maxItemsInEachRow = $it" } ?: "") +
        ") {",
    )
    emitChildren(node.slot("children"), level + 1)
    line(level, "}")
  }

  /** Set by [emitFlowRow], so nothing but an emitted call turns the experimental opt-in on. */
  private var emittedFlowLayout = false

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
      val itemKey = document.nodes.getValue(id).string("stableKey").ifEmpty { id }
      line(level + 1, "item(key = \"${itemKey.escape()}\") {")
      emitNode(id, level + 2)
      line(level + 1, "}")
    }
    line(level, "}")
  }

  private fun emitGrid(node: UiBuilderNode, level: Int) {
    val minimum = node.obj("columns").number("minimumCellWidthDp", 362f)
    line(
      level,
      "LazyVerticalGrid(columns = GridCells.Adaptive(${minimum.dpLiteral()}), contentPadding = ${node.obj("contentPadding").paddingValuesExpression()}, verticalArrangement = Arrangement.spacedBy(${node.number("verticalSpacingDp").dpLiteral()}), horizontalArrangement = Arrangement.spacedBy(${node.number("horizontalSpacingDp").dpLiteral()}), ${node.modifierArgument()}) {",
    )
    node.slot("items").forEach { id ->
      val full = document.nodes.getValue(id).string("span") == "full"
      val itemKey = document.nodes.getValue(id).string("stableKey").ifEmpty { id }
      val span = if (full) ", span = { GridItemSpan(maxLineSpan) }" else ""
      line(level + 1, "item(key = \"${itemKey.escape()}\"$span) {")
      emitNode(id, level + 2)
      line(level + 1, "}")
    }
    line(level, "}")
  }

  private fun emitCarousel(node: UiBuilderNode, level: Int) {
    val items = node.slot("items")
    line(level, "val carouselState = rememberCarouselState { ${items.size} }")
    line(
      level,
      "HorizontalUncontainedCarousel(state = carouselState, itemWidth = ${node.number("itemWidthDp", 128f).dpLiteral()}, itemSpacing = ${node.number("itemSpacingDp").dpLiteral()}, contentPadding = PaddingValues(start = ${node.number("contentPaddingStartDp").dpLiteral()}), ${node.modifierArgument()}) { index ->",
    )
    line(level + 1, "when (index) {")
    items.forEachIndexed { index, id ->
      line(level + 2, "$index -> {")
      emitNode(id, level + 3)
      line(level + 2, "}")
    }
    line(level + 2, "else -> Unit")
    line(level + 1, "}")
    line(level, "}")
  }

  private fun emitSearchBar(node: UiBuilderNode, level: Int) {
    line(level, "SearchBar(")
    line(level + 1, "inputField = {")
    emitChildren(node.slot("inputField"), level + 2)
    line(level + 1, "},")
    line(level + 1, "expanded = ${node.boolValue("expanded")},")
    line(level + 1, "onExpandedChange = {},")
    line(level + 1, "tonalElevation = ${node.number("tonalElevationDp").dpLiteral()},")
    // Only when authored: absent, Material's own input-field shape is the right default, and the
    // renderer draws the same one.
    if ("shapeDp" in node.properties) {
      line(level + 1, "shape = RoundedCornerShape(${node.number("shapeDp").dpLiteral()}),")
    }
    line(level + 1, "${node.modifierArgument()},")
    line(level, ") {")
    emitChildren(node.slot("expandedContent"), level + 1)
    line(level, "}")
  }

  private fun emitSearchInput(node: UiBuilderNode, level: Int) {
    val value = node.obj("value")
    line(level, "SearchBarDefaults.InputField(")
    if (value.optionalString("type") == "state") {
      val name = value.optionalString("variable")?.identifier() ?: "searchQuery"
      line(level + 1, "query = $name,")
      line(level + 1, "onQueryChange = { $name = it },")
    } else {
      // A literal query: the field shows it, and typing has nowhere to go.
      line(level + 1, "query = \"${node.string("value").escape()}\",")
      line(level + 1, "onQueryChange = {},")
    }
    line(level + 1, "onSearch = {},")
    line(level + 1, "expanded = false,")
    line(level + 1, "onExpandedChange = {},")
    line(level + 1, "enabled = ${node.boolValue("enabled", true)},")
    listOf("leadingIcon", "placeholder", "trailingIcon").forEach { slot ->
      line(level + 1, "$slot = {")
      emitChildren(node.slot(slot), level + 2)
      line(level + 1, "},")
    }
    line(level, ")")
  }

  private fun emitFilterChip(node: UiBuilderNode, level: Int) {
    line(level, "FilterChip(")
    line(level + 1, "selected = ${node.boolExpression("selected", stateKotlinTypes)},")
    line(
      level + 1,
      "onClick = ${node.actionLambda("click", stateKotlinTypes)},",
    )
    line(level + 1, "enabled = ${node.boolValue("enabled", true)},")
    line(
      level + 1,
      "shape = RoundedCornerShape(${shapeDp(node.string("shape").ifEmpty { "large" }).dpLiteral()}),",
    )
    line(level + 1, "label = {")
    emitChildren(node.slot("label"), level + 2)
    line(level + 1, "},")
    if (node.slot("leadingIcon").isNotEmpty()) {
      line(level + 1, "leadingIcon = {")
      emitChildren(node.slot("leadingIcon"), level + 2)
      line(level + 1, "},")
    }
    line(level, ")")
  }

  private fun emitText(node: UiBuilderNode, level: Int) {
    val font = FontSettings.composeArguments(document, node.id)
    val featureCopy = font.styleCopy.orEmpty()
    val optionalArguments = buildList {
      font.fontFamily?.let { add("fontFamily = $it") }
      if ("fontStyle" in node.properties) add("fontStyle = ${node.fontStyleExpression()}")
      if ("fontSizeSp" in node.properties)
        add("fontSize = ${node.optionalSpExpression("fontSizeSp")}")
      if ("lineHeightSp" in node.properties)
        add("lineHeight = ${node.optionalSpExpression("lineHeightSp")}")
      if ("letterSpacingSp" in node.properties)
        add("letterSpacing = ${node.optionalSpExpression("letterSpacingSp")}")
      if ("textDecoration" in node.properties)
        add("textDecoration = ${node.textDecorationExpression()}")
      if ("minLines" in node.properties) add("minLines = ${node.integer("minLines", 1)}")
      if ("softWrap" in node.properties) add("softWrap = ${node.boolValue("softWrap", true)}")
      if ("textAlign" in node.properties) add("textAlign = ${node.textAlignExpression()}")
    }
      .joinToString(separator = "") { "$it, " }
    line(
      level,
      "Text(text = ${node.boundStringExpression("text")}, style = MaterialTheme.typography.${node.string("style").ifEmpty { "bodyMedium" }.identifier()}$featureCopy, color = ${node.boundColorExpression("color")}, fontWeight = ${font.fontWeight ?: if (font.overridesWeight) "null" else node.fontWeightExpression()}, ${optionalArguments}maxLines = ${node.integer("maxLines", Int.MAX_VALUE)}, overflow = ${node.textOverflowExpression()}, ${node.modifierArgument()})",
    )
  }

  /**
   * Material 3 Adaptive's imports, only for a design that uses it. Adaptive is a separate artifact,
   * so an unused import breaks compilation in projects without it. `emitAdaptiveHelper` uses the
   * same predicate.
   */
  private fun adaptiveImports(): List<String> =
    if (!emittedSupportingPaneScaffold) emptyList()
    else
      listOf(
        "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
        "androidx.compose.material3.adaptive.WindowAdaptiveInfo",
        "androidx.compose.material3.adaptive.currentWindowAdaptiveInfo",
        "androidx.compose.material3.adaptive.layout.PaneAdaptedValue",
        "androidx.compose.material3.adaptive.layout.PaneScaffoldScope",
        "androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold",
        "androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults",
        "androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole",
        "androidx.compose.material3.adaptive.layout.PaneExpansionAnchor",
        "androidx.compose.material3.adaptive.layout.rememberPaneExpansionState",
        "androidx.compose.material3.adaptive.layout.SupportingPaneScaffold",
        "androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldDefaults",
        "androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole",
        "androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem",
        "androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue",
        "androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective",
        "androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue",
        "androidx.window.core.layout.WindowSizeClass",
      )

  /** Set by [emitSupportingPane], so nothing but an emitted call can turn the adaptive code on. */
  private var emittedSupportingPaneScaffold = false

  /**
   * Set by the navigation-suite emitters: `material3-adaptive-navigation-suite` is a separate
   * artifact, so its import must only appear when used.
   */
  private var emittedNavigationSuite = false

  private fun additionalTextImports(): List<String> {
    val propertyNames = document.nodes.values.flatMap { it.properties.keys }.toSet()
    return buildList {
      if ("fontStyle" in propertyNames) add("androidx.compose.ui.text.font.FontStyle")
      if ("textAlign" in propertyNames) add("androidx.compose.ui.text.style.TextAlign")
      if ("textDecoration" in propertyNames) add("androidx.compose.ui.text.style.TextDecoration")
      document.nodes.values
        .filter { it.componentId == "m3/text" }
        .forEach { addAll(FontSettings.composeArguments(document, it.id).imports) }
      if (propertyNames.any { it in setOf("fontSizeSp", "lineHeightSp", "letterSpacingSp") }) {
        add("androidx.compose.ui.unit.TextUnit")
        add("androidx.compose.ui.unit.sp")
      }
    }
  }

  private fun emitIcon(node: UiBuilderNode, level: Int) {
    val tint =
      if (node.string("color").isEmpty()) "LocalContentColor.current"
      else node.colorExpression("color")
    line(
      level,
      "Icon(imageVector = builderIcon(\"${node.string("iconKey").escape()}\"), contentDescription = ${node.string("contentDescription").nullableStringLiteral()}, tint = $tint, ${node.modifierArgument()})",
    )
  }

  private fun emitIconButton(node: UiBuilderNode, level: Int) {
    val selectedBackground =
      if ("selected" in node.properties) {
        ".background(Color.Black.copy(alpha = .46f), CircleShape)"
      } else {
        ""
      }
    val function =
      when (node.iconButtonStyle()) {
        "filled" -> "FilledIconButton"
        "tonal" -> "FilledTonalIconButton"
        "outlined" -> "OutlinedIconButton"
        else -> "IconButton"
      }
    line(
      level,
      "$function(onClick = {}, modifier = ${node.modifierExpression()}$selectedBackground) {",
    )
    emitChildren(node.slot("content"), level + 1)
    line(level, "}")
  }

  private fun emitImage(node: UiBuilderNode, level: Int) {
    line(
      level,
      "BuilderAssetImage(assetKey = \"${node.string("assetKey").escape()}\", contentDescription = ${node.string("contentDescription").nullableStringLiteral()}, contentScale = \"${node.string("contentScale").escape()}\", ${node.modifierArgument()})",
    )
  }

  private fun emitGradient(node: UiBuilderNode, level: Int, radial: Boolean) {
    if (radial) {
      line(
        level,
        "BuilderRadialGradient(${node.modifierExpression()}, ${node.colorExpression("innerColor")}, ${node.number("innerAlpha", 1f).floatLiteral()}, ${node.colorExpression("outerColor")}, ${node.gradientCenterExpression()})",
      )
    } else {
      line(
        level,
        "Box(${node.modifierExpression()}.background(${node.linearGradientExpression()}))",
      )
    }
  }

  /**
   * `CenterAlignedTopAppBar`, with the canvas's two colours and the scroll behavior the canvas
   * cannot draw (it needs a nested-scroll connection).
   */
  private fun emitTopAppBar(node: UiBuilderNode, level: Int) {
    line(level, "CenterAlignedTopAppBar(")
    line(level + 1, "title = {")
    emitChildren(node.slot("title"), level + 2)
    line(level + 1, "},")
    line(
      level + 1,
      "colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = ${node.colorExpression("containerColor")}, scrolledContainerColor = ${node.colorExpression("scrolledContainerColor")}),",
    )
    node.scrollBehaviorExpression()?.let { line(level + 1, "scrollBehavior = $it,") }
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  private fun emitListItem(node: UiBuilderNode, level: Int) {
    line(level, "ListItem(")
    listOf("headline", "supporting", "trailing").forEach { slot ->
      line(level + 1, "${slot}Content = {")
      emitChildren(node.slot(slot), level + 2)
      line(level + 1, "},")
    }
    // The leading accent bar the canvas draws, via `drawBehind` since `ListItem` has no such
    // parameter.
    val accent = node.string("startAccentColor")
    val modifier =
      if (accent.isEmpty()) node.modifierExpression()
      else
        "${node.modifierExpression()}.drawBehind { drawRect(${node.colorExpression("startAccentColor")}, size = Size(3.dp.toPx(), size.height)) }"
    line(level + 1, "modifier = $modifier,")
    line(level, ")")
  }

  private fun emitSurface(node: UiBuilderNode, level: Int) {
    // The theme host wraps its content in a `MaterialTheme` with its typefaces, as the canvas does.
    // See [ThemeTypefaces].
    val host = document.themeHost()?.id == node.id
    val roles =
      if (host) ThemeTypefaces.m3RoleFamilies(ThemeTypefaces.families(node)) else emptyMap()
    // The host's default text role, provided inside the theme so it is the themed role. See
    // [ThemeTextStyle].
    val textRole = if (host) ThemeTextStyle.role(node)?.let(ThemeTextStyle::m3Role) else null
    var inner = level
    if (roles.isNotEmpty()) {
      line(level, "MaterialTheme(")
      googleFonts.typography("MaterialTheme.typography", roles, depth = level + 1).forEach {
        appendLine(it)
      }
      line(level, ") {")
      inner++
    }
    if (textRole != null) line(inner++, "ProvideTextStyle(MaterialTheme.typography.$textRole) {")
    line(
      inner,
      "Surface(${node.modifierArgument()}, shape = ${node.shapeExpression()}, color = ${node.boundColorExpression("containerColor")}, tonalElevation = ${node.number("tonalElevationDp").dpLiteral()}) {",
    )
    emitChildren(node.slot("content"), inner + 1)
    line(inner, "}")
    while (inner > level) line(--inner, "}")
  }

  private fun emitCard(node: UiBuilderNode, level: Int) {
    line(
      level,
      "${node.cardCall()}(${node.modifierArgument()}, shape = RoundedCornerShape(${shapeDp(node.string("shape").ifEmpty { "large" }).dpLiteral()}), elevation = ${node.cardElevation()}, colors = builderCardColors(${node.boundColorExpression("containerColor")})) {",
    )
    // The same decision the canvas makes — see [cardContentFill] — so the generated screen wraps
    // an unsized card exactly where the picture did.
    val fill = node.cardContentFill()
    val box =
      when {
        fill.width && fill.height -> "Box(Modifier.fillMaxSize()) {"
        fill.width -> "Box(Modifier.fillMaxWidth()) {"
        fill.height -> "Box(Modifier.fillMaxHeight()) {"
        else -> "Box {"
      }
    line(level + 1, box)
    emitChildren(node.slot("content"), level + 2)
    line(level + 1, "}")
    line(level, "}")
  }

  /** The Material 3 card function for the node's [cardStyle]. */
  private fun UiBuilderNode.cardCall(): String =
    when (cardStyle()) {
      "elevated" -> "ElevatedCard"
      "outlined" -> "OutlinedCard"
      else -> "Card"
    }

  /** The elevation argument for [cardCall]: the style's own defaults unless the design set one. */
  private fun UiBuilderNode.cardElevation(): String {
    val set = "elevationDp" in properties
    val dp = number("elevationDp").dpLiteral()
    return when (cardStyle()) {
      "elevated" ->
        if (set) "CardDefaults.elevatedCardElevation(defaultElevation = $dp)"
        else "CardDefaults.elevatedCardElevation()"
      "outlined" -> "CardDefaults.outlinedCardElevation(defaultElevation = $dp)"
      else -> "CardDefaults.cardElevation(defaultElevation = $dp)"
    }
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
      "$symbol(onClick = ${node.actionLambda("click", stateKotlinTypes)}, $colors${node.modifierArgument()}) {",
    )
    if (node.string("style") == "fab") {
      line(level + 1, "Box(Modifier.padding(horizontal = 16.dp)) {")
      emitChildren(node.slot("content"), level + 2)
      line(level + 1, "}")
    } else {
      emitChildren(node.slot("content"), level + 1)
    }
    line(level, "}")
  }

  private fun emitToolbar(node: UiBuilderNode, level: Int) {
    line(
      level,
      "BuilderHorizontalFloatingToolbar(expanded = ${node.boolValue("expanded", true)}, containerColor = ${node.colorExpression("containerColor")}, contentPadding = ${node.toolbarContentPaddingExpression()}, ${node.modifierArgument()}) {",
    )
    emitChildren(node.slot("content"), level + 1)
    line(level, "}")
  }

  /**
   * `Slider`, writing the variable the design bound it to. `onValueChange` assigns directly; an
   * unbound slider assigns nothing.
   */
  private fun emitSlider(node: UiBuilderNode, level: Int) {
    val variable = node.obj("value").optionalString("variable")?.identifier()
    val from = node.number("valueFrom")
    val to = node.number("valueTo", 1f).coerceAtLeast(from)
    line(level, "Slider(")
    line(level + 1, "value = ${variable ?: node.number("value").floatLiteral()},")
    line(level + 1, "onValueChange = { ${if (variable == null) "Unit" else "$variable = it"} },")
    line(level + 1, "enabled = ${node.boolValue("enabled", true)},")
    line(level + 1, "valueRange = ${from.floatLiteral()}..${to.floatLiteral()},")
    line(level + 1, "steps = ${node.integer("steps")},")
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  /**
   * `LinearProgressIndicator` or `CircularProgressIndicator`. Indeterminate is a different overload
   * (no progress lambda), so the flag picks the call.
   */
  private fun emitProgress(node: UiBuilderNode, level: Int) {
    val symbol =
      if (node.string("variant") == "circular") "CircularProgressIndicator"
      else "LinearProgressIndicator"
    val variable = node.obj("progress").optionalString("variable")?.identifier()
    if (node.boolValue("indeterminate")) {
      line(level, "$symbol(${node.modifierArgument()})")
      return
    }
    val fraction = variable?.let { "$it.toFloat()" } ?: node.number("progress").floatLiteral()
    line(level, "$symbol(progress = { $fraction }, ${node.modifierArgument()})")
  }

  /**
   * `TextField` or `OutlinedTextField` (by `variant`) with its bound state variable.
   * `onValueChange` writes only that variable; an unbound field gets an empty lambda rather than
   * hidden local state.
   */
  private fun emitTextField(node: UiBuilderNode, level: Int) {
    val symbol = if (node.string("variant") == "outlined") "OutlinedTextField" else "TextField"
    val variable = node.obj("value").optionalString("variable")?.identifier()
    line(level, "$symbol(")
    line(level + 1, "value = ${variable ?: "\"${node.string("value").escape()}\""},")
    line(level + 1, "onValueChange = { ${if (variable == null) "Unit" else "$variable = it"} },")
    line(level + 1, "enabled = ${node.boolValue("enabled", true)},")
    line(level + 1, "readOnly = ${node.boolValue("readOnly")},")
    line(level + 1, "isError = ${node.boolValue("isError")},")
    line(level + 1, "singleLine = ${node.boolValue("singleLine", true)},")
    listOf(
        "label" to "label",
        "placeholder" to "placeholder",
        "supportingText" to "supportingText",
        "leadingIcon" to "leadingIcon",
        "trailingIcon" to "trailingIcon",
      )
      .forEach { (slot, parameter) ->
        if (node.slot(slot).isNotEmpty()) {
          line(level + 1, "$parameter = {")
          emitChildren(node.slot(slot), level + 2)
          line(level + 1, "},")
        }
      }
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  /**
   * `Checkbox` and `Switch`, which differ only in the symbol; one emitter keeps the `checked`
   * binding consistent with the renderer.
   */
  private fun emitToggle(node: UiBuilderNode, level: Int, symbol: String) {
    line(
      level,
      "$symbol(checked = ${node.boolExpression("checked", stateKotlinTypes)}, onCheckedChange = ${node.checkedChangeLambda(stateKotlinTypes)}, enabled = ${node.boolValue("enabled", true)}, ${node.modifierArgument()})",
    )
  }

  /** A real modal dialog. Authored buttons carry the design's state actions. */
  private fun emitDialog(node: UiBuilderNode, level: Int) {
    line(level, "AlertDialog(")
    line(level + 1, "onDismissRequest = {},")
    line(level + 1, "containerColor = ${node.colorExpression("containerColor")},")
    line(
      level + 1,
      "tonalElevation = ${node.number("tonalElevationDp", DIALOG_TONAL_ELEVATION_DP).dpLiteral()},",
    )
    line(
      level + 1,
      "shape = RoundedCornerShape(${node.number("shapeDp", DIALOG_CORNER_DP).dpLiteral()}),",
    )
    line(level + 1, "${node.modifierArgument()},")
    line(level + 1, "icon = {")
    emitChildren(node.slot("icon"), level + 2)
    line(level + 1, "},")
    line(level + 1, "title = {")
    emitChildren(node.slot("title"), level + 2)
    line(level + 1, "},")
    line(level + 1, "text = {")
    emitChildren(node.slot("text"), level + 2)
    line(level + 1, "},")
    line(level + 1, "dismissButton = {")
    emitChildren(node.slot("dismissButton"), level + 2)
    line(level + 1, "},")
    line(level + 1, "confirmButton = {")
    emitChildren(node.slot("confirmButton"), level + 2)
    line(level + 1, "},")
    line(level, ")")
  }

  /**
   * `DatePicker` with the state the document pins; `rememberDatePickerState()` with no arguments
   * would open on the current month and drift from the design.
   */
  private fun emitDatePicker(node: UiBuilderNode, level: Int) {
    val millis =
      isoDateToEpochMillis(node.string("selectedDate"))
        ?: isoDateToEpochMillis(DEFAULT_SELECTED_DATE)
    val mode = if (node.string("mode") == "input") "DisplayMode.Input" else "DisplayMode.Picker"
    line(level, "DatePicker(")
    line(
      level + 1,
      "state = rememberDatePickerState(initialSelectedDateMillis = ${millis}L, initialDisplayedMonthMillis = ${millis}L, initialDisplayMode = $mode),",
    )
    line(level + 1, "showModeToggle = ${node.boolValue("showModeToggle")},")
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  /** `TimePicker`, or `TimeInput` for the typed mode, both off one pinned state. */
  private fun emitTimePicker(node: UiBuilderNode, level: Int) {
    val hour = node.integer("hour", DEFAULT_PICKED_HOUR).coerceIn(0, 23)
    val minute = node.integer("minute", DEFAULT_PICKED_MINUTE).coerceIn(0, 59)
    val symbol = if (node.string("mode") == "input") "TimeInput" else "TimePicker"
    line(level, "$symbol(")
    line(
      level + 1,
      "state = rememberTimePickerState(initialHour = $hour, initialMinute = $minute, is24Hour = ${node.boolValue("is24Hour", true)}),",
    )
    line(level + 1, "${node.modifierArgument()},")
    line(level, ")")
  }

  /**
   * The real `SupportingPaneScaffold`, wrapped to take this design's two booleans; emitted only
   * when used (see [adaptiveImports]). The directive comes from the scaffold's own
   * `BoxWithConstraints`, as in the renderer, so a constrained scaffold in a wide window matches
   * the preview; only posture comes from the window.
   */
  private fun emitAdaptiveHelper() {
    if (!emittedSupportingPaneScaffold) return
    appendLine(
      "@OptIn(ExperimentalMaterial3AdaptiveApi::class) @Composable private fun BuilderSupportingPaneScaffold(modifier: Modifier, listDetail: Boolean, activePane: String, activePaneIndex: Int?, paneSizing: String, fixedPaneWidth: Dp?, splitFraction: Float, singlePane: Boolean, mainPaneVisible: Boolean, supportingPaneVisible: Boolean, mainPaneWidth: Dp?, supportingPaneWidth: Dp?, paneSpacing: Dp?, mainPane: @Composable () -> Unit, supportingPane: @Composable () -> Unit, extraPaneWidth: Dp? = null, extraPane: (@Composable () -> Unit)? = null) {"
    )
    appendLine("  val posture = currentWindowAdaptiveInfo().windowPosture")
    appendLine("  BoxWithConstraints(modifier) {")
    appendLine(
      "    val frameDirective = calculatePaneScaffoldDirective(WindowAdaptiveInfo(WindowSizeClass.compute(maxWidth.value, maxHeight.value), posture)); val partitioned = if (singlePane) frameDirective.copy(maxHorizontalPartitions = 1) else frameDirective; val directive = if (paneSpacing != null) partitioned.copy(horizontalPartitionSpacerSize = paneSpacing) else partitioned"
    )
    appendLine(
      "    val anchor = when (paneSizing) { \"fixedStart\" -> PaneExpansionAnchor.Offset.fromStart((fixedPaneWidth ?: 360.dp) + directive.horizontalPartitionSpacerSize / 2); \"fixedEnd\" -> PaneExpansionAnchor.Offset.fromEnd((fixedPaneWidth ?: 360.dp) + directive.horizontalPartitionSpacerSize / 2); \"split\" -> PaneExpansionAnchor.Proportion(splitFraction.coerceIn(.1f, .9f)); else -> null }"
    )
    appendLine(
      "    val expansion = rememberPaneExpansionState(anchors = listOfNotNull(anchor), initialAnchoredIndex = if (anchor == null) -1 else 0)"
    )
    appendLine("    if (listDetail) {")
    appendLine(
      "      val requestedRole = if (activePaneIndex != null) listOf(ListDetailPaneScaffoldRole.List, ListDetailPaneScaffoldRole.Detail, ListDetailPaneScaffoldRole.Extra)[activePaneIndex.coerceIn(0, 2)] else when (activePane) { \"detail\" -> ListDetailPaneScaffoldRole.Detail; \"extra\" -> ListDetailPaneScaffoldRole.Extra; else -> ListDetailPaneScaffoldRole.List }"
    )
    appendLine(
      "      val available = buildList { if (mainPaneVisible) add(ListDetailPaneScaffoldRole.List); if (supportingPaneVisible) add(ListDetailPaneScaffoldRole.Detail); if (extraPane != null) add(ListDetailPaneScaffoldRole.Extra) }"
    )
    appendLine(
      "      val role = requestedRole.takeIf { it in available } ?: available.firstOrNull()"
    )
    appendLine(
      "      val computed = calculateThreePaneScaffoldValue(directive.maxHorizontalPartitions, ListDetailPaneScaffoldDefaults.adaptStrategies(), currentDestination = role?.let { ThreePaneScaffoldDestinationItem<Nothing>(it) })"
    )
    appendLine(
      "      ListDetailPaneScaffold(directive = directive, value = ThreePaneScaffoldValue(primary = if (supportingPaneVisible) computed.primary else PaneAdaptedValue.Hidden, secondary = if (mainPaneVisible) computed.secondary else PaneAdaptedValue.Hidden, tertiary = if (extraPane != null) computed.tertiary else PaneAdaptedValue.Hidden), listPane = { BuilderPane(mainPaneWidth, mainPane) }, detailPane = { BuilderPane(supportingPaneWidth, supportingPane) }, extraPane = extraPane?.let { content -> { BuilderPane(extraPaneWidth, content) } }, paneExpansionState = expansion, modifier = Modifier.fillMaxSize())"
    )
    appendLine("    } else {")
    // A supporting-only design must name the supporting pane as the destination, or the sole
    // partition goes to the primary and masking it afterwards leaves a blank frame.
    appendLine(
      "    val computed = calculateThreePaneScaffoldValue(maxHorizontalPartitions = directive.maxHorizontalPartitions, adaptStrategies = SupportingPaneScaffoldDefaults.adaptStrategies(), currentDestination = if (!mainPaneVisible && supportingPaneVisible) ThreePaneScaffoldDestinationItem<Nothing>(SupportingPaneScaffoldRole.Supporting) else null)"
    )
    appendLine(
      "    SupportingPaneScaffold(directive = directive, value = ThreePaneScaffoldValue(primary = if (mainPaneVisible) computed.primary else PaneAdaptedValue.Hidden, secondary = if (supportingPaneVisible) computed.secondary else PaneAdaptedValue.Hidden, tertiary = PaneAdaptedValue.Hidden), mainPane = { BuilderPane(mainPaneWidth, mainPane) }, supportingPane = { BuilderPane(supportingPaneWidth, supportingPane) }, paneExpansionState = expansion, modifier = Modifier.fillMaxSize())"
    )
    appendLine("    }")
    appendLine("  }")
    appendLine("}")
    // A pane whose width the design did not state is composed exactly as it was before this
    // existed — no wrapper, no parent data — so those designs export byte-identical source.
    appendLine(
      "@OptIn(ExperimentalMaterial3AdaptiveApi::class) @Composable private fun PaneScaffoldScope.BuilderPane(width: Dp?, content: @Composable () -> Unit) { if (width == null) content() else Box(Modifier.preferredWidth(width)) { content() } }"
    )
  }

  private fun emitCompatibilityHelpers() {
    appendLine(
      "// Compatibility helpers are explicit export diagnostics, not claims of API parity."
    )
    appendLine(
      "@Composable private fun builderCardColors(containerColor: Color) = CardDefaults.cardColors(containerColor = containerColor)"
    )
    emitAdaptiveHelper()
    appendLine(
      "@Composable private fun BuilderSnackbarHost(visible: Boolean) { if (visible) Snackbar { Text(\"Snackbar\") } }"
    )
    appendLine(
      "@Composable private fun BuilderHorizontalFloatingToolbar(expanded: Boolean, containerColor: Color, contentPadding: PaddingValues, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) { Surface(modifier.semantics { stateDescription = if (expanded) \"expanded\" else \"collapsed\" }, shape = CircleShape, color = containerColor, tonalElevation = 6.dp, shadowElevation = 8.dp) { Row(Modifier.padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content) } }"
    )
    appendLine(
      "@Composable private fun BuilderRadialGradient(modifier: Modifier, innerColor: Color, innerAlpha: Float, outerColor: Color, centerFraction: Offset) { Box(modifier.drawBehind { drawRect(Brush.radialGradient(listOf(innerColor.copy(alpha = innerAlpha), outerColor), center = Offset(size.width * centerFraction.x, size.height * centerFraction.y), radius = size.maxDimension * .82f)) }) }"
    )
    appendLine(
      "// Asset adapter: ${assetAdapter?.id?.escapeComment() ?: "none (visible placeholder)"}."
    )
    val renderer = assetAdapter?.renderer
    if (renderer != null) {
      appendLine(
        "@Composable private fun BuilderAssetImage(assetKey: String, contentDescription: String?, contentScale: String, modifier: Modifier = Modifier) { check(contentScale == \"crop\" || contentScale.isEmpty()) { \"Unsupported content scale: ${'$'}contentScale\" }; ${renderer.symbol}(assetKey = assetKey, contentDescription = contentDescription, modifier = modifier) }"
      )
      appendLine(builderIconFunction(usedGoogleMaterialIcons))
      return
    }
    appendLine(
      "@Composable private fun BuilderAssetImage(assetKey: String, contentDescription: String?, contentScale: String, modifier: Modifier = Modifier) { val semanticModifier = if (contentDescription == null) modifier else modifier.semantics { this.contentDescription = contentDescription }; Canvas(semanticModifier) { val palette = when (assetKey) {"
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
      "  else -> listOf(Color(0xFFFF00FF), Color(0xFF202020), Color(0xFFFF00FF)) }; check(contentScale == \"crop\" || contentScale.isEmpty()) { \"Unsupported content scale: ${'$'}contentScale\" }; drawRect(Brush.linearGradient(palette, Offset.Zero, Offset(size.width, size.height))); drawCircle(Color.White.copy(alpha = .18f), size.minDimension * .34f, Offset(size.width * .76f, size.height * .24f)); drawCircle(Color.Black.copy(alpha = .18f), size.minDimension * .22f, Offset(size.width * .22f, size.height * .72f)); val path = Path().apply { moveTo(size.width * .19f, size.height * .32f); lineTo(size.width * .48f, size.height * .18f); lineTo(size.width * .82f, size.height * .58f); lineTo(size.width * .48f, size.height * .78f); close() }; drawPath(path, Color.White.copy(alpha = .27f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .30f, size.height * .39f), Size(size.width * .10f, size.height * .28f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .47f, size.height * .30f), Size(size.width * .10f, size.height * .38f)); drawRect(Color.White.copy(alpha = .72f), Offset(size.width * .64f, size.height * .43f), Size(size.width * .10f, size.height * .24f)); drawCircle(Color.White.copy(alpha = .72f), size.minDimension * .28f, style = Stroke(size.minDimension * .035f)) } }"
    )
    appendLine(builderIconFunction(usedGoogleMaterialIcons))
  }

  private fun appendLine(value: String = "") {
    out.append(value).append('\n')
  }

  private fun line(level: Int, value: String) = appendLine("  ".repeat(level) + value)
}

private fun UiBuilderNode.slot(name: String): List<String> = slots[name].orEmpty()

private fun builderIconFunction(icons: List<GoogleMaterialIcon>): String = buildString {
  append("private fun builderIcon(key: String): ImageVector = when (key) { ")
  icons.forEach { icon ->
    append("\"").append(icon.key).append("\" -> ").append(icon.composeExpression).append("; ")
  }
  append("else -> error(\"Unsupported Google Material icon: ${'$'}key\") }")
}

internal fun UiBuilderNode.obj(name: String): JsonObject =
  properties[name]?.let { it as? JsonObject } ?: JsonObject(emptyMap())

internal fun UiBuilderNode.string(name: String): String =
  obj(name)["value"]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun UiBuilderNode.number(name: String, fallback: Float = 0f): Float =
  obj(name)["value"]?.jsonPrimitive?.floatOrNull ?: fallback

private fun JsonObject.number(name: String, fallback: Float = 0f): Float =
  this[name]?.jsonPrimitive?.floatOrNull ?: fallback

private fun UiBuilderNode.integer(name: String, fallback: Int = 0): Int =
  obj(name)["value"]?.jsonPrimitive?.intOrNull ?: fallback

/**
 * An integer property, read from state where the document says so (e.g. `selectedTabIndex`). A
 * declared type other than `Int` is refused with `TODO` rather than coerced.
 */
private fun UiBuilderNode.integerExpression(name: String, stateTypes: Map<String, String>): String {
  val value = obj(name)
  if (value.optionalString("type") != "state") return integer(name).toString()
  val variable = value.optionalString("variable") ?: return "TODO(\"Missing state variable\")"
  val declared =
    stateTypes[variable] ?: return "TODO(\"Undeclared state variable ${variable.escape()}\")"
  return if (declared == "Int") variable.identifier()
  else "TODO(\"State variable ${variable.escape()} is declared $declared, not Int\")"
}

private fun UiBuilderNode.boolValue(name: String, fallback: Boolean = false): Boolean =
  obj(name)["value"]?.jsonPrimitive?.booleanOrNull ?: fallback

/**
 * A Boolean property: a literal, a `stateEquals` comparison, or a flag read whole. With
 * [stateTypes], `Boolean` reads as itself and `Boolean?` as `== true` (matching the canvas);
 * without them `== true` compiles for either.
 */
private fun UiBuilderNode.boolExpression(
  name: String,
  stateTypes: Map<String, String>? = null,
): String {
  val value = obj(name)
  return when (value.optionalString("type")) {
    "stateEquals" -> {
      val variable = value.optionalString("variable")?.identifier() ?: "missingState"
      "$variable == ${value["value"].kotlinLiteral()}"
    }
    "state" -> {
      val stateName = value.optionalString("variable") ?: return "TODO(\"Missing state variable\")"
      val variable = stateName.identifier()
      when (val declared = stateTypes?.get(stateName)) {
        "Boolean" -> variable
        "Boolean?" -> "$variable == true"
        null ->
          if (stateTypes == null) "$variable == true"
          else "TODO(\"Undeclared state variable ${stateName.escape()}\")"
        else -> "TODO(\"State variable ${stateName.escape()} is declared $declared, not Boolean\")"
      }
    }
    else -> (value["value"]?.jsonPrimitive?.booleanOrNull ?: false).toString()
  }
}

/**
 * `onCheckedChange` for a two-way bound control: the reported value written back to the variable
 * `checked` reads, then the `click` actions — or the actions alone when they already write it
 * ([TwoWayStateBinding]), or when `checked` is not a whole read of a declared flag.
 */
private fun UiBuilderNode.checkedChangeLambda(stateTypes: Map<String, String>): String {
  // `checkedChange`, the callback's own name, or a legacy `click` binding (TwoWayStateBinding).
  val event = TwoWayStateBinding.eventFor("checked", eventBindings)
  val actions = actionExpression(event, stateTypes)
  val variable =
    TwoWayStateBinding.writeBackVariable(properties["checked"], eventBindings[event])?.takeIf {
      stateTypes[it]?.removeSuffix("?") == "Boolean"
    } ?: return if (actions.isEmpty()) "{}" else "{ $actions }"
  val writeBack = "${variable.identifier()} = it"
  return if (actions.isEmpty()) "{ $writeBack }" else "{ $writeBack; $actions }"
}

/**
 * One event handler as a lambda literal. An empty handler is `{}`, not `{ Unit }`, to stay
 * warning-free.
 */
private fun UiBuilderNode.actionLambda(event: String, stateTypes: Map<String, String>): String =
  actionExpression(event, stateTypes).let { if (it.isEmpty()) "{}" else "{ $it }" }

/**
 * The body of one event handler: every action in order, as the renderer dispatches them. Checked
 * against [stateTypes] — `toggle` needs a `Boolean`, `selectOrClear` a nullable, and assignments a
 * value the type holds — refusing what would not compile.
 */
private fun UiBuilderNode.actionExpression(event: String, stateTypes: Map<String, String>): String {
  val actions = (eventBindings[event] as? JsonArray).orEmpty()
  // Empty, not `Unit`: [actionLambda] turns this into `{}`.
  if (actions.isEmpty()) return ""
  return actions.joinToString("; ") { element ->
    // Safe cast: a malformed entry yields a TODO instead of throwing out of `export()`.
    val action = element as? JsonObject ?: return@joinToString "TODO(\"Malformed action\")"
    val name = action.optionalString("variable")
    // An undeclared variable would compile to nothing; export validation doesn't read
    // `stateVariables`, so refuse here.
    if (name != null && name !in stateTypes) {
      return@joinToString "TODO(\"Undeclared state variable ${name.escape()}\")"
    }
    val declaredType = name?.let(stateTypes::get)
    val variable = name?.identifier()
    val value = action["value"].kotlinLiteral()
    when (action.optionalString("type")) {
      "navigatePage" ->
        action.optionalString("pageKey")?.takeIf(String::isNotBlank)?.let {
          "onNavigatePage(\"${it.escape()}\")"
        } ?: "TODO(\"navigatePage needs a pageKey\")"
      "select",
      // `set` is the protocol's own name for an assignment and behaves exactly as `select` does.
      "set",
      "setText" ->
        when {
          variable == null -> "Unit"
          // Documents from other clients can carry a value the declared type cannot hold; refuse
          // rather than emit uncompilable source.
          action["value"]?.let(UiExpressions::isComputed) == true -> computedActionTodo(name)
          !declaredType.holds(action["value"]) ->
            "TODO(\"${name.orEmpty().escape()} is $declaredType and cannot hold $value\")"
          else -> "$variable = $value"
        }
      "selectOrClear" ->
        when {
          variable == null -> "Unit"
          declaredType?.endsWith("?") != true ->
            "TODO(\"selectOrClear needs a nullable state variable\")"
          !declaredType.holds(action["value"]) ->
            "TODO(\"${name.orEmpty().escape()} is $declaredType and cannot hold $value\")"
          else -> "$variable = if ($variable == $value) null else $value"
        }
      "toggle" ->
        when {
          variable == null || declaredType?.removeSuffix("?") != "Boolean" ->
            "TODO(\"toggle needs a boolean state variable\")"
          // `!` doesn't apply to `Boolean?`; like the renderer, treat null as not-true.
          declaredType.endsWith("?") -> "$variable = !($variable ?: false)"
          else -> "$variable = !$variable"
        }
      // `x += 1`, spelled for the type the variable is declared as so `Double` gets `1.0`. A
      // nullable number counts from zero, as the canvas does when it holds no value.
      "increment" -> {
        val amount = action["amount"] ?: kotlinx.serialization.json.JsonPrimitive(1)
        val bare = declaredType?.removeSuffix("?")?.takeIf { it == "Int" || it == "Double" }
        when {
          variable == null || bare == null -> "TODO(\"increment needs a number state variable\")"
          UiExpressions.isComputed(amount) -> computedActionTodo(name)
          !bare.holds(amount) ->
            "TODO(\"${name.orEmpty().escape()} is $declaredType and cannot add ${amount.kotlinLiteral()}\")"
          declaredType?.endsWith("?") == true ->
            "$variable = ($variable ?: ${if (bare == "Int") "0" else "0.0"}) + ${amount.literalAs(bare)}"
          else -> "$variable += ${amount.literalAs(bare)}"
        }
      }
      else -> "TODO(\"Unsupported action ${action.optionalString("type")?.escape()}\")"
    }
  }
}

/** A formula an action writes is computed by the Remote Compose player; this lane has none. */
private fun computedActionTodo(name: String?): String =
  "TODO(\"${name.orEmpty().escape()} is written by a formula, which only the Remote Compose " +
    "export computes\")"

private fun UiBuilderNode.buttonSymbol(): String =
  when (string("style")) {
    "text" -> "TextButton"
    "filledTonal" -> "FilledTonalButton"
    "fab" -> "FloatingActionButton"
    else -> "Button"
  }

private fun UiBuilderNode.fontWeightExpression(): String =
  when (string("fontWeight")) {
    "normal" -> "FontWeight.Normal"
    "bold" -> "FontWeight.Bold"
    "semiBold" -> "FontWeight.SemiBold"
    "medium" -> "FontWeight.Medium"
    else -> "null"
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
        "fillMaxHeight" -> ".fillMaxHeight()"
        "width" -> ".width(${modifier.number("widthDp").dpLiteral()})"
        "height" -> ".height(${modifier.number("heightDp").dpLiteral()})"
        "widthIn" -> ".widthIn(${modifier.boundsArguments()})"
        "heightIn" -> ".heightIn(${modifier.boundsArguments()})"
        "aspectRatio" -> ".aspectRatio(${modifier.number("ratio", 1f).floatLiteral()})"
        "wrapContentSize" ->
          ".wrapContentSize(${alignmentExpression(modifier.optionalString("alignment"))})"
        "offset" ->
          ".offset(x = ${modifier.number("xDp").dpLiteral()}, y = ${modifier.number("yDp").dpLiteral()})"
        "zIndex" -> ".zIndex(${modifier.number("zIndex").floatLiteral()})"
        "background" ->
          ".background(${modifier.modifierColorExpression()}${modifier.shapeArgument()})"
        "border" ->
          ".border(${modifier.number("widthDp", 1f).dpLiteral()}, ${modifier.modifierColorExpression()}${modifier.shapeArgument()})"
        "alpha" -> ".alpha(${modifier.number("alpha", 1f).floatLiteral()})"
        "shadow" ->
          ".shadow(${modifier.number("elevationDp").dpLiteral()}${modifier.shapeArgument()})"
        "rotate" -> ".rotate(${modifier.number("degrees").floatLiteral()})"
        "scale" ->
          ".scale(${modifier.number("scaleX", 1f).floatLiteral()}, ${modifier.number("scaleY", 1f).floatLiteral()})"
        // The scroll position belongs to the screen being run, not to the design: a generated
        // screen remembers its own, exactly as a hand-written one would.
        "verticalScroll" -> ".verticalScroll(rememberScrollState())"
        "horizontalScroll" -> ".horizontalScroll(rememberScrollState())"
        "testTag" -> ".testTag(\"${modifier.optionalString("tag").orEmpty().escape()}\")"
        // Scoped: legal only inside the Row, Column or Box this node is a child of, which is what
        // the catalog's parent-slot rules and the reducer's own gate already decide.
        "align" -> ".align(${alignmentExpression(modifier.optionalString("alignment"))})"
        "alignHorizontal" ->
          ".align(${horizontalAlignmentExpression(modifier.optionalString("alignment"))})"
        "alignVertical" ->
          ".align(${verticalAlignmentExpression(modifier.optionalString("alignment"))})"
        "weight" ->
          ".weight(${modifier.number("weight", 1f).floatLiteral()}${modifier.fillArgument()})"
        else -> error("unsupported modifier $type on $id")
      }
  }
  // Legacy alignment/weight properties, printed only when the modifier chain doesn't say the same,
  // so neither is applied twice.
  val scoped = modifierTypes()
  if ("weight" in properties && "weight" !in scoped) {
    expression += ".weight(${number("weight", 1f).floatLiteral()})"
  }
  expression +=
    if (scoped.any { it in SCOPED_ALIGNMENT_MODIFIERS }) ""
    else
      when (val alignment = string("alignment")) {
        "" -> ""
        else -> ".align(${alignmentExpression(alignment)})"
      }
  if ("sizeDp" in properties) expression += ".size(${number("sizeDp").dpLiteral()})"
  val description = string("contentDescription")
  if (description.isNotEmpty()) {
    expression += ".semantics { contentDescription = \"${description.escape()}\" }"
  }
  if ("selected" in properties) {
    expression += ".semantics { selected = ${boolExpression("selected")} }"
  }
  return expression
}

private fun UiBuilderNode.textOverflowExpression(): String =
  when (string("overflow")) {
    "clip" -> "TextOverflow.Clip"
    "visible" -> "TextOverflow.Visible"
    "ellipsis",
    "" -> "TextOverflow.Ellipsis"
    else -> "TextOverflow.Ellipsis"
  }

/**
 * The `Arrangement.Vertical` this Column asks for, mirroring the renderer's
 * `verticalArrangement()`.
 */
private fun UiBuilderNode.verticalArrangementExpression(): String {
  val spacing = number("verticalSpacingDp")
  return when (string("verticalArrangement")) {
    "center" ->
      if (spacing > 0f) "Arrangement.spacedBy(${spacing.dpLiteral()}, Alignment.CenterVertically)"
      else "Arrangement.Center"
    "bottom" ->
      if (spacing > 0f) "Arrangement.spacedBy(${spacing.dpLiteral()}, Alignment.Bottom)"
      else "Arrangement.Bottom"
    "spaceBetween" -> "Arrangement.SpaceBetween"
    "spaceAround" -> "Arrangement.SpaceAround"
    "spaceEvenly" -> "Arrangement.SpaceEvenly"
    // `spacedBy(space)` IS `spacedBy(space, Alignment.Top)`, so the default stays in its shorter
    // form and an untouched design's generated source does not move.
    else -> "Arrangement.spacedBy(${spacing.dpLiteral()})"
  }
}

/** The Row counterpart of [verticalArrangementExpression]. */
private fun UiBuilderNode.horizontalArrangementExpression(): String {
  val spacing = number("horizontalSpacingDp")
  return when (string("horizontalArrangement")) {
    "center" ->
      if (spacing > 0f) "Arrangement.spacedBy(${spacing.dpLiteral()}, Alignment.CenterHorizontally)"
      else "Arrangement.Center"
    "end" ->
      if (spacing > 0f) "Arrangement.spacedBy(${spacing.dpLiteral()}, Alignment.End)"
      else "Arrangement.End"
    "spaceBetween" -> "Arrangement.SpaceBetween"
    "spaceAround" -> "Arrangement.SpaceAround"
    "spaceEvenly" -> "Arrangement.SpaceEvenly"
    else -> "Arrangement.spacedBy(${spacing.dpLiteral()})"
  }
}

/**
 * The `Brush` for a `shape/linear-gradient`, in the same vocabulary [UiBuilderRenderer] draws,
 * including the axis-only `horizontal` the widget templates write.
 */
private fun UiBuilderNode.linearGradientExpression(): String =
  when (string("direction")) {
    "bottomToTop" ->
      "Brush.verticalGradient(listOf(${colorExpression("endColor")}, ${colorExpression("startColor")}))"
    "leftToRight",
    "horizontal" ->
      "Brush.horizontalGradient(listOf(${colorExpression("startColor")}, ${colorExpression("endColor")}))"
    "rightToLeft" ->
      "Brush.horizontalGradient(listOf(${colorExpression("endColor")}, ${colorExpression("startColor")}))"
    else ->
      "Brush.verticalGradient(listOf(${colorExpression("startColor")}, ${colorExpression("endColor")}))"
  }

private fun UiBuilderNode.gradientCenterExpression(): String =
  when (string("center")) {
    "topStart" -> "Offset.Zero"
    "topEnd" -> "Offset(1f, 0f)"
    "bottomStart" -> "Offset(0f, 1f)"
    "bottomEnd" -> "Offset(1f, 1f)"
    else -> "Offset(.5f, .5f)"
  }

private fun JsonObject.paddingValuesExpression(): String =
  "PaddingValues(start = ${number("startDp").dpLiteral()}, top = ${number("topDp").dpLiteral()}, end = ${number("endDp").dpLiteral()}, bottom = ${number("bottomDp").dpLiteral()})"

/**
 * `TopAppBarDefaults.…ScrollBehavior()` for the declared behavior, or null when none is declared
 * (the bar's own default is no behavior).
 */
private fun UiBuilderNode.scrollBehaviorExpression(): String? =
  when (string("scrollBehavior")) {
    "pinned" -> "TopAppBarDefaults.pinnedScrollBehavior()"
    "enterAlways" -> "TopAppBarDefaults.enterAlwaysScrollBehavior()"
    "exitUntilCollapsed" -> "TopAppBarDefaults.exitUntilCollapsedScrollBehavior()"
    else -> null
  }

/** The bounds a `widthIn`/`heightIn` names, printed as only the edges it actually constrains. */
private fun JsonObject.boundsArguments(): String =
  listOfNotNull(
      this["minDp"]?.jsonPrimitive?.floatOrNull?.let { "min = ${it.dpLiteral()}" },
      this["maxDp"]?.jsonPrimitive?.floatOrNull?.let { "max = ${it.dpLiteral()}" },
    )
    .joinToString()

/** A modifier's optional shape, as a trailing argument or nothing at all. */
private fun JsonObject.shapeArgument(): String =
  optionalString("shape")?.let { ", RoundedCornerShape(${shapeDp(it).dpLiteral()})" }.orEmpty()

/**
 * The colour inside a modifier (a `UiValueV1`), resolved like [colorExpression] so tokens cannot
 * diverge.
 */
private fun JsonObject.modifierColorExpression(): String =
  colorExpressionFor((this["color"] as? JsonObject)?.optionalString("value").orEmpty())

/** The two scoped modifiers that replace the `alignment` property, whichever axis they name. */
private val SCOPED_ALIGNMENT_MODIFIERS = setOf("align", "alignHorizontal", "alignVertical")

/**
 * `contentAlignment = …` for a `layout/box`, or null for Compose's default `TopStart` or an
 * unresolved spelling.
 */
private fun UiBuilderNode.boxContentAlignmentArgument(): String? =
  string("contentAlignment")
    .takeIf { it in BOX_CONTENT_ALIGNMENTS && it != "topStart" }
    ?.let { "contentAlignment = ${alignmentExpression(it)}" }

private val BOX_CONTENT_ALIGNMENTS =
  setOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )

private fun UiBuilderNode.modifierTypes(): Set<String> =
  modifiers.mapNotNull { (it as? JsonObject)?.optionalString("type") }.toSet()

/** `fill` is Compose's second argument, and only worth printing when it is not the default. */
private fun JsonObject.fillArgument(): String =
  when (this["fill"]?.jsonPrimitive?.booleanOrNull) {
    null,
    true -> ""
    false -> ", fill = false"
  }

private fun horizontalAlignmentExpression(value: String?): String =
  when (value) {
    "centerHorizontally" -> "Alignment.CenterHorizontally"
    "end" -> "Alignment.End"
    else -> "Alignment.Start"
  }

private fun verticalAlignmentExpression(value: String?): String =
  when (value) {
    "centerVertically" -> "Alignment.CenterVertically"
    "bottom" -> "Alignment.Bottom"
    else -> "Alignment.Top"
  }

private fun alignmentExpression(value: String?): String =
  when (value) {
    "topStart" -> "Alignment.TopStart"
    "topCenter" -> "Alignment.TopCenter"
    "topEnd" -> "Alignment.TopEnd"
    "centerStart" -> "Alignment.CenterStart"
    "centerEnd" -> "Alignment.CenterEnd"
    "bottomStart" -> "Alignment.BottomStart"
    "bottomCenter" -> "Alignment.BottomCenter"
    "bottomEnd" -> "Alignment.BottomEnd"
    else -> "Alignment.Center"
  }

private fun UiBuilderNode.colorExpression(name: String): String = colorExpressionFor(string(name))

private fun colorExpressionFor(value: String): String {
  if (value.startsWith("#")) {
    val digits = value.removePrefix("#").uppercase()
    // `#RRGGBB` is opaque in the document, but `Color(0xRRGGBB)` would be transparent; add the
    // alpha byte.
    return "Color(0x${if (digits.length == 6) "FF$digits" else digits})"
  }
  return when (value) {
    "primary" -> "MaterialTheme.colorScheme.primary"
    "onPrimary" -> "MaterialTheme.colorScheme.onPrimary"
    "secondary" -> "MaterialTheme.colorScheme.secondary"
    "onSecondary" -> "MaterialTheme.colorScheme.onSecondary"
    "tertiary" -> "MaterialTheme.colorScheme.tertiary"
    "onTertiary" -> "MaterialTheme.colorScheme.onTertiary"
    "onSurface" -> "MaterialTheme.colorScheme.onSurface"
    "onSurfaceVariant" -> "MaterialTheme.colorScheme.onSurfaceVariant"
    "surface" -> "MaterialTheme.colorScheme.surface"
    "surfaceContainer" -> "MaterialTheme.colorScheme.surfaceContainer"
    "surfaceContainerLow" -> "MaterialTheme.colorScheme.surfaceContainerLow"
    // The dialog's default container colour, offered by the inspector picker.
    "surfaceContainerHigh" -> "MaterialTheme.colorScheme.surfaceContainerHigh"
    "surfaceContainerHighest" -> "MaterialTheme.colorScheme.surfaceContainerHighest"
    "background" -> "MaterialTheme.colorScheme.background"
    "outlineVariant" -> "MaterialTheme.colorScheme.outlineVariant"
    "transparent" -> "Color.Transparent"
    else -> "Color.Unspecified"
  }
}

private fun UiBuilderNode.optionalSpExpression(name: String): String =
  if (name in properties) "${number(name).floatLiteral()}.sp" else "TextUnit.Unspecified"

private fun UiBuilderNode.fontStyleExpression(): String =
  when (string("fontStyle")) {
    "normal" -> "FontStyle.Normal"
    "italic" -> "FontStyle.Italic"
    else -> "null"
  }

private fun UiBuilderNode.textDecorationExpression(): String =
  when (string("textDecoration")) {
    "underline" -> "TextDecoration.Underline"
    "lineThrough" -> "TextDecoration.LineThrough"
    else -> "null"
  }

private fun UiBuilderNode.textAlignExpression(): String =
  when (string("textAlign")) {
    "center" -> "TextAlign.Center"
    "end" -> "TextAlign.End"
    "justify" -> "TextAlign.Justify"
    else -> "TextAlign.Start"
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
    home = home?.description(),
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

internal fun UiBuilderNode.error(code: String, message: String) =
  ComposeExportDiagnostic(code, ComposeExportSeverity.ERROR, message, id, componentId)

internal fun UiBuilderNode.warning(code: String, message: String) =
  ComposeExportDiagnostic(code, ComposeExportSeverity.WARNING, message, id, componentId)

/**
 * Which component properties this exporter can print as an expression rather than a literal, i.e.
 * what a generated component function can take as a parameter. Also read by
 * [CapabilityComposeCodeExporter.diagnose] to refuse unsupported bindings before generation.
 */
private val BINDABLE_PROPERTIES: Map<String, Map<String, BindingKind>> =
  mapOf(
    "m3/surface" to mapOf("containerColor" to BindingKind.COLOR),
    "m3/card" to mapOf("containerColor" to BindingKind.COLOR),
    "m3/text" to mapOf("text" to BindingKind.STRING, "color" to BindingKind.COLOR),
  )

/**
 * The text properties of [componentId] a component body may read from an argument — what making a
 * component from a subtree promotes to parameters. Read from [BINDABLE_PROPERTIES], so the editor
 * never makes a parameter this exporter would then refuse as `UNSUPPORTED_BINDING`.
 */
internal fun bindableTextProperties(componentId: String): Set<String> =
  BINDABLE_PROPERTIES[componentId].orEmpty().filterValues { it == BindingKind.STRING }.keys

/** Every property of [componentId] a component body may read from an argument, of any kind. */
internal fun bindableProperties(componentId: String): Set<String> =
  BINDABLE_PROPERTIES[componentId].orEmpty().keys

/** What a bound key is, in Kotlin, and how a placement's value is written at the call site. */
internal enum class BindingKind(val kotlinType: String) {
  COLOR("Color") {
    override fun argument(value: JsonObject): String = colorExpressionFor(value.scalar().orEmpty())

    override fun refusalFor(value: JsonObject): String? {
      val scalar = value.scalar() ?: return "holds no value"
      // The same question `colorExpressionFor` answers, asked before it answers it with
      // `Color.Unspecified` — a colour that compiles and paints nothing.
      return if (scalar.startsWith("#") || colorExpressionFor(scalar) != "Color.Unspecified") null
      else "is neither a #RRGGBB colour nor a colour token"
    }
  },
  STRING("String") {
    override fun argument(value: JsonObject): String = "\"${value.scalar().orEmpty().escape()}\""

    override fun refusalFor(value: JsonObject): String? =
      if (value.scalar() == null) "holds no value" else null
  };

  abstract fun argument(value: JsonObject): String

  /** Why this argument cannot be written as this kind, or null when it can. */
  abstract fun refusalFor(value: JsonObject): String?
}

/**
 * The signature this exporter would generate for each component the design defines, and everything
 * it refuses. Computed once and shared by the gate and the emitter.
 */
internal fun UiBuilderDocument.componentSignatures():
  Pair<
    Map<String, ComponentSignature>,
    List<ExportRefusal>,
  > {
  val signatures = linkedMapOf<String, ComponentSignature>()
  val refusals = mutableListOf<ExportRefusal>()
  componentsInDependencyOrder().forEach { (key, declaration) ->
    val component = declaration as? JsonObject ?: return@forEach
    val root = component.primitiveString("root")
    if (root == null || root !in nodes) {
      refusals += ExportRefusal("UNKNOWN_COMPONENT_ROOT", "component $key names no body", null)
      return@forEach
    }
    val parameters = linkedMapOf<String, BindingKind>()
    // `body` is the parameter-binding scope and stops at loop templates; `contained` includes
    // templates, for whole-subtree questions like recursion and state reads.
    val body = bodyNodes(root)
    val contained = subtreeNodes(root)
    // A self-placing component would recurse forever in generated code (the canvas has an ancestor
    // guard), so refuse it.
    if (placesItself(key, contained)) {
      refusals += ExportRefusal("COMPONENT_CYCLE", "component $key places itself", root)
      return@forEach
    }
    contained.forEach { node ->
      // The design record's own refusal: a scaffold is a screen, and a screen inside a component
      // is a claim about the whole frame made from inside one box of it.
      if (node.componentId in SCAFFOLD_COMPONENT_IDS) {
        refusals +=
          ExportRefusal(
            "SCAFFOLD_IN_COMPONENT_BODY",
            "component $key holds ${node.componentId}, which lays out a whole screen",
            node.id,
          )
      }
      // A body may only use its arguments: state reads and event handlers name screen-scoped
      // variables the component function cannot see.
      if (
        node.properties.values.any {
          it is JsonObject && it.optionalString("type") in COMPONENT_STATE_VALUE_TYPES
        }
      ) {
        refusals +=
          ExportRefusal(
            "COMPONENT_BODY_READS_STATE",
            "component $key reads a state variable, which its function cannot see",
            node.id,
          )
      }
      if (node.eventBindings.isNotEmpty()) {
        refusals +=
          ExportRefusal(
            "COMPONENT_BODY_HANDLES_EVENT",
            "component $key handles an event, which writes state its function cannot see",
            node.id,
          )
      }
    }
    body.forEach { node ->
      node.bindingKinds(signatures).forEach { (property, bindingKey, kind) ->
        if (kind == null) {
          refusals +=
            ExportRefusal(
              "UNSUPPORTED_BINDING",
              "property '$property' cannot be written as an expression, so component $key " +
                "cannot take '$bindingKey' as a parameter",
              node.id,
            )
          return@forEach
        }
        // A key that normalises to a hard keyword (`fun Cell(class: Color)`) isn't valid Kotlin and
        // `identifier()` doesn't escape it.
        val identifier = bindingKey.identifier()
        if (identifier in KOTLIN_HARD_KEYWORDS) {
          refusals +=
            ExportRefusal(
              "RESERVED_PARAMETER",
              "component $key reads '$bindingKey', which generates the parameter name " +
                "'$identifier' — a Kotlin keyword",
              node.id,
            )
          return@forEach
        }
        val existing = parameters[bindingKey]
        if (existing != null && existing != kind) {
          refusals +=
            ExportRefusal(
              "CONFLICTING_BINDING",
              "component $key reads '$bindingKey' as both ${existing.kotlinType} and " +
                "${kind.kotlinType}",
              node.id,
            )
          return@forEach
        }
        parameters[bindingKey] = kind
      }
    }
    val functionName = component.primitiveString("name").orEmpty().componentFunctionName(key)
    // Duplicate function names, or a key matching the wrapper's name, would not compile; refuse
    // them here.
    signatures.entries
      .firstOrNull { it.value.functionName == functionName }
      ?.let { existing ->
        refusals +=
          ExportRefusal(
            "COLLIDING_COMPONENT_NAME",
            "components ${existing.key} and $key both generate $functionName",
            root,
          )
        return@forEach
      }
    val sortedParameters = parameters.entries.sortedBy { it.key }.map { it.key to it.value }
    val identifiers = mutableSetOf(RESERVED_COMPONENT_PARAMETER)
    sortedParameters.forEach { (parameterKey, _) ->
      val identifier = parameterKey.identifier()
      if (!identifiers.add(identifier)) {
        refusals +=
          ExportRefusal(
            "COLLIDING_PARAMETER_NAME",
            "component $key reads '$parameterKey', which generates the parameter name " +
              "'$identifier' something else already has",
            root,
          )
      }
    }
    signatures[key] =
      ComponentSignature(
        functionName = functionName,
        root = root,
        // Sorted: the generated file must not reorder its own parameters between exports, and
        // the document's key order is an authoring accident.
        parameters = sortedParameters,
      )
  }
  return signatures to refusals
}

/**
 * The components, ordered so one placed with a bound argument is derived before its placer (the
 * argument takes the placed component's parameter type). Sorted within the order for determinism; a
 * cycle keeps sorted order and is refused.
 */
private fun UiBuilderDocument.componentsInDependencyOrder(): List<Pair<String, JsonObject>> {
  val declared =
    components.entries
      .sortedBy { it.key }
      .mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
  val placed = declared.associate { (key, declaration) ->
    val root = declaration.primitiveString("root")
    key to
      if (root == null || root !in nodes) emptySet()
      else
        ownNodes(root)
          .filter { it.componentId == DESIGN_COMPONENT_INSTANCE_ID }
          .mapNotNullTo(mutableSetOf()) { it.placementKey().takeIf(String::isNotEmpty) }
  }
  val ordered = linkedMapOf<String, JsonObject>()
  val visiting = mutableSetOf<String>()
  fun visit(key: String, declaration: JsonObject) {
    if (key in ordered || !visiting.add(key)) return
    placed[key].orEmpty().sorted().forEach { dependency ->
      declared.firstOrNull { it.first == dependency }?.let { visit(it.first, it.second) }
    }
    visiting -= key
    ordered[key] = declaration
  }
  declared.forEach { (key, declaration) -> visit(key, declaration) }
  return ordered.entries.map { it.key to it.value }
}

/** Everything a placement is refused for: an unknown component, or an argument it does not pass. */
internal fun UiBuilderDocument.placementRefusals(
  signatures: Map<String, ComponentSignature>,
  scoped: Set<String>,
): List<ExportRefusal> =
  nodes.values
    .sortedBy(UiBuilderNode::id)
    .filter { it.componentId == DESIGN_COMPONENT_INSTANCE_ID }
    .flatMap { node ->
      val key = node.component?.primitiveString("componentKey").orEmpty()
      val signature =
        signatures[key]
          ?: return@flatMap listOf(
            ExportRefusal(
              "UNKNOWN_COMPONENT_KEY",
              "placement names no component of this design: ${key.ifEmpty { "(none)" }}",
              node.id,
            )
          )
      val arguments = node.component?.get("arguments")?.let { it as? JsonObject }
      buildList {
        // The emitted call has nowhere to put a click bound to a placement, so refuse rather than
        // drop it.
        if (node.eventBindings.isNotEmpty()) {
          this +=
            ExportRefusal(
              "PLACEMENT_HANDLES_EVENT",
              "a placement of ${signature.functionName} handles an event, which its call cannot " +
                "carry",
              node.id,
            )
        }
        signature.parameters.forEach { (parameter, kind) ->
          val argument = arguments?.get(parameter) as? JsonObject
          // A bound argument is checked only for having a scope to read from; its type is checked
          // where the row or parameter is declared.
          node.argumentBindingKey(parameter)?.let { key ->
            if (node.id !in scoped) {
              this +=
                ExportRefusal(
                  "BINDING_OUTSIDE_COMPONENT",
                  "a placement of ${signature.functionName} reads '$key' for '$parameter', and " +
                    "this node is in no component body or loop template",
                  node.id,
                )
            }
            return@forEach
          }
          if (argument == null) {
            this +=
              ExportRefusal(
                "MISSING_ARGUMENT",
                "placement of ${signature.functionName} passes no '$parameter'",
                node.id,
              )
            return@forEach
          }
          // Checked against the body's kind before formatting, so a wrong shape is refused rather
          // than becoming `Color.Unspecified` or throwing.
          kind.refusalFor(argument)?.let { why ->
            this +=
              ExportRefusal(
                "INVALID_ARGUMENT",
                "placement of ${signature.functionName} passes a '$parameter' that $why",
                node.id,
              )
          }
        }
        // An argument the component doesn't declare would be silently dropped from the call; refuse
        // it explicitly.
        val declared = signature.parameters.map { it.first }.toSet()
        arguments
          .orEmpty()
          .keys
          .sorted()
          .filterNot { it in declared }
          .forEach { argument ->
            this +=
              ExportRefusal(
                "UNKNOWN_ARGUMENT",
                "placement of ${signature.functionName} passes '$argument', which it does not take",
                node.id,
              )
          }
      }
    }

/**
 * Bindings on a node no component body reaches. Outside a body there is no scope to read, so the
 * emitter would print the literal and report success; capability validation can't catch this
 * either.
 */
internal fun UiBuilderDocument.strayBindingRefusals(
  signatures: Map<String, ComponentSignature>
): List<ExportRefusal> {
  val inABody = scopedNodeIds(signatures)
  return nodes.values
    .sortedBy(UiBuilderNode::id)
    .filter { it.id !in inABody }
    .flatMap { node ->
      node.properties.keys.sorted().mapNotNull { property ->
        val key = node.bindingKey(property) ?: return@mapNotNull null
        ExportRefusal(
          "BINDING_OUTSIDE_COMPONENT",
          "property '$property' reads '$key' from a component's arguments, and this node is not " +
            "in a component body",
          node.id,
        )
      }
    }
}

/**
 * The data class and rows this exporter would generate for each loop, and what it refuses. Derived
 * like a component's parameters; rows come from the design, so they are checked here.
 */
internal fun UiBuilderDocument.loopSignatures(
  taken: Set<String>,
  // Not `components`: that would shadow the document's own component map.
  componentSignatures: Map<String, ComponentSignature> = emptyMap(),
): Pair<Map<String, LoopSignature>, List<ExportRefusal>> {
  val signatures = linkedMapOf<String, LoopSignature>()
  val refusals = mutableListOf<ExportRefusal>()
  val names = taken.toMutableSet()
  nodes.values
    .filter { it.componentId == FOR_EACH_COMPONENT_ID }
    .sortedBy(UiBuilderNode::id)
    .forEach { loop ->
      val template = loop.slots["template"].orEmpty().singleOrNull()
      if (template == null || template !in nodes) {
        refusals +=
          ExportRefusal("EMPTY_LOOP_TEMPLATE", "loop ${loop.id} holds no template", loop.id)
        return@forEach
      }
      val rows = loop.loopRows()
      if (rows == null) {
        refusals +=
          ExportRefusal(
            "INVALID_LOOP_DATA",
            "loop ${loop.id} has no `data` list of row dictionaries to draw",
            loop.id,
          )
        return@forEach
      }
      val properties = linkedMapOf<String, BindingKind>()
      val identifiers = mutableSetOf<String>()
      // Identity (`key`) anywhere a row draws, including bodies of placed components at any depth,
      // would tie remembered state to whichever row composed last; [drawnNodes] walks all of it.
      val drawn = drawnNodes(template)
      drawn.forEach { node ->
        val identity = node.identityClaim()
        if (identity.isNotEmpty()) {
          refusals +=
            ExportRefusal(
              "IDENTITY_IN_LOOP_TEMPLATE",
              "loop ${loop.id} repeats a node claiming the identity '$identity', which every row " +
                "would share",
              node.id,
            )
        }
      }
      // Bindings come from the template's own nodes only; a placed body reads its placement's
      // arguments.
      templateNodes(template).forEach { node ->
        node.bindingKinds(componentSignatures).forEach { (property, key, kind) ->
          if (kind == null) {
            refusals +=
              ExportRefusal(
                "UNSUPPORTED_BINDING",
                "property '$property' cannot be written as an expression, so loop ${loop.id} " +
                  "cannot read '$key' from its rows",
                node.id,
              )
            return@forEach
          }
          val existing = properties[key]
          if (existing != null && existing != kind) {
            refusals +=
              ExportRefusal(
                "CONFLICTING_BINDING",
                "loop ${loop.id} reads '$key' as both ${existing.kotlinType} and " +
                  "${kind.kotlinType}",
                node.id,
              )
            return@forEach
          }
          // A key that normalises to a hard keyword can't be escaped by `identifier()`, so it's
          // refused.
          val identifier = key.identifier()
          if (identifier in KOTLIN_HARD_KEYWORDS) {
            refusals +=
              ExportRefusal(
                "RESERVED_ROW_PROPERTY",
                "loop ${loop.id} reads '$key', which generates the property name '$identifier' — " +
                  "a Kotlin keyword",
                node.id,
              )
            return@forEach
          }
          // Two keys normalising to one Kotlin name would duplicate data class properties.
          if (properties[key] == null && !identifiers.add(identifier)) {
            refusals +=
              ExportRefusal(
                "COLLIDING_ROW_PROPERTY",
                "loop ${loop.id} reads '$key', which generates the property name '$identifier' " +
                  "another key already has",
                node.id,
              )
            return@forEach
          }
          properties[key] = kind
        }
      }
      // Every row must answer every key in the shape the template reads.
      rows.forEachIndexed { index, row ->
        properties.forEach { (key, kind) ->
          val value = row[key] as? JsonObject
          if (value == null) {
            refusals +=
              ExportRefusal(
                "MISSING_ROW_VALUE",
                "row $index of loop ${loop.id} has no '$key', which its template reads",
                loop.id,
              )
            return@forEach
          }
          kind.refusalFor(value)?.let { why ->
            refusals +=
              ExportRefusal(
                "INVALID_ROW_VALUE",
                "row $index of loop ${loop.id} has a '$key' that $why",
                loop.id,
              )
          }
        }
      }
      val className = loop.id.rowClassName(names)
      names += className
      signatures[loop.id] =
        LoopSignature(
          className = className,
          template = template,
          // Sorted, for the reason a component's parameters are: the document's key order is an
          // authoring accident and a re-export must not reorder a declaration.
          properties = properties.entries.sortedBy { it.key }.map { it.key to it.value },
          rows = rows,
        )
    }
  return signatures to refusals
}

/** One generated row type: what it is called, the template it fills, and the rows themselves. */
internal data class LoopSignature(
  val className: String,
  val template: String,
  val properties: List<Pair<String, BindingKind>>,
  val rows: List<JsonObject>,
)

/** The `fields` of each row, or null when `data` is not a list of row dictionaries. */
private fun UiBuilderNode.loopRows(): List<JsonObject>? {
  val data = obj("data")
  if (data.optionalString("type") != "list") return null
  val values = data["values"] as? JsonArray ?: return null
  return values.map { row ->
    val value = row as? JsonObject ?: return null
    if (value.optionalString("type") != "object") return null
    value["fields"] as? JsonObject ?: return null
  }
}

/** A Kotlin type name for a loop's rows, from the node that draws them. */
private fun String.rowClassName(taken: Set<String>): String {
  val base = identifier().replaceFirstChar { it.uppercaseChar() }
  val candidate = (if (base.firstOrNull()?.isLetter() == true) base else "Loop$base") + "Row"
  if (candidate !in taken) return candidate
  var index = 2
  while ("$candidate$index" in taken) index++
  return "$candidate$index"
}

/**
 * Where a loop may sit. Inside a lazy container rows would need `items(rows, key = …)`; a `forEach`
 * in one `item` would scroll and recycle as one item, so it is refused until that emitter exists.
 */
internal fun UiBuilderDocument.loopPlacementRefusals(): List<ExportRefusal> =
  nodes.values
    .sortedBy(UiBuilderNode::id)
    .filter { it.componentId in LAZY_CONTAINER_IDS }
    .flatMap { container ->
      container.slots.values
        .flatten()
        .filter { child -> nodes[child]?.componentId == FOR_EACH_COMPONENT_ID }
        .map { child ->
          ExportRefusal(
            "LOOP_IN_LAZY_CONTAINER",
            "a loop inside ${container.componentId} needs `items(rows, key = { … })`, which this " +
              "exporter does not write yet",
            child,
          )
        }
    }

/** The containers whose children are emitted as lazy items. */
private val LAZY_CONTAINER_IDS = setOf("layout/lazy-column", "layout/lazy-row", "layout/lazy-grid")

/** Every node with a dictionary around it: a component's body or a loop's template, in one set. */
internal fun UiBuilderDocument.scopedNodeIds(
  signatures: Map<String, ComponentSignature>
): Set<String> {
  val scoped =
    signatures.values.flatMapTo(mutableSetOf()) { signature ->
      bodyNodes(signature.root).map(UiBuilderNode::id)
    }
  nodes.values
    .filter { it.componentId == FOR_EACH_COMPONENT_ID }
    .flatMap { loop -> loop.slots["template"].orEmpty() }
    .forEach { template -> scoped += bodyNodes(template).map(UiBuilderNode::id) }
  return scoped
}

/**
 * The identity this node claims, read without throwing: a malformed `stableKey` is reported as
 * `INVALID_PROPERTY_TYPE` and must not crash `diagnose`.
 */
private fun UiBuilderNode.identityClaim(): String {
  fun claim(name: String) =
    ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty()
  return claim("stableKey").ifEmpty { claim("scrollStateKey") }
}

/** A loop template's nodes, root first, including a nested loop node but not its template. */
private fun UiBuilderDocument.templateNodes(root: String): List<UiBuilderNode> = ownNodes(root)

/** A component body, root first, in the order the emitter walks it. */
private fun UiBuilderDocument.bodyNodes(root: String): List<UiBuilderNode> = ownNodes(root)

/**
 * The nodes one scope owns: everything from [root] down, never through a loop's template (a
 * template's bindings read the loop's rows). Shared by component bodies and loop templates.
 */
private fun UiBuilderDocument.ownNodes(root: String): List<UiBuilderNode> {
  val visited = linkedSetOf<String>()
  fun walk(id: String) {
    if (!visited.add(id)) return
    val node = nodes[id] ?: return
    node.slots.entries
      .sortedBy { it.key }
      .forEach { (slot, children) ->
        if (node.componentId == FOR_EACH_COMPONENT_ID && slot == "template") return@forEach
        children.forEach(::walk)
      }
  }
  walk(root)
  return visited.mapNotNull(nodes::get)
}

/**
 * Everything from [root] down, including loop templates. Unlike [ownNodes], for whole-subtree
 * checks such as recursion and state reads.
 */
private fun UiBuilderDocument.subtreeNodes(root: String): List<UiBuilderNode> {
  val visited = linkedSetOf<String>()
  fun walk(id: String) {
    if (!visited.add(id)) return
    val node = nodes[id] ?: return
    node.slots.entries.sortedBy { it.key }.forEach { (_, children) -> children.forEach(::walk) }
  }
  walk(root)
  return visited.mapNotNull(nodes::get)
}

/**
 * Every node one repetition of [root] draws: its own scope plus every component body reachable
 * through placements, at any depth. [seen] guards cycles (refused elsewhere as `COMPONENT_CYCLE`);
 * nested loops are checked by their own level via [ownNodes].
 */
private fun UiBuilderDocument.drawnNodes(
  root: String,
  seen: MutableSet<String> = mutableSetOf(),
): List<UiBuilderNode> =
  ownNodes(root)
    .flatMap { node ->
      if (node.componentId != DESIGN_COMPONENT_INSTANCE_ID) listOf(node)
      else {
        val key = node.placementKey()
        val placedRoot = (components[key] as? JsonObject)?.primitiveString("root")
        if (key.isEmpty() || placedRoot == null || placedRoot !in nodes || !seen.add(key)) {
          listOf(node)
        } else {
          listOf(node) + drawnNodes(placedRoot, seen)
        }
      }
    }
    .distinctBy(UiBuilderNode::id)

/**
 * A Kotlin function name for a component: the sanitised declared name, or one derived from the
 * unique key.
 */
private fun String.componentFunctionName(key: String): String {
  val candidate = ifEmpty { key }.identifier().replaceFirstChar { it.uppercaseChar() }
  return if (candidate.firstOrNull()?.isLetter() == true) candidate
  else "Component${key.identifier().replaceFirstChar { it.uppercaseChar() }}"
}

internal data class ExportRefusal(val code: String, val message: String, val nodeId: String?)

/** The property-value shapes that read a state variable rather than holding a value. */
private val COMPONENT_STATE_VALUE_TYPES = setOf("state", "stateEquals")

/** A screen, laid out. Refused inside a component body — see the design record. */
private val SCAFFOLD_COMPONENT_IDS =
  setOf("layout/scaffold", "layout/supporting-pane-scaffold", "layout/list-detail-pane-scaffold")

/** The parameter every generated component function already has. */
private const val RESERVED_COMPONENT_PARAMETER = "modifier"

/** Whether this component's body reaches a placement of itself, at any depth. */
private fun UiBuilderDocument.placesItself(key: String, body: List<UiBuilderNode>): Boolean {
  val seen = mutableSetOf(key)
  var frontier = body
  while (frontier.isNotEmpty()) {
    val placed =
      frontier
        .filter { it.componentId == DESIGN_COMPONENT_INSTANCE_ID }
        .mapNotNull { it.component?.primitiveString("componentKey") }
    if (placed.any { it == key }) return true
    val fresh = placed.filter(seen::add)
    frontier = fresh.flatMap { placedKey ->
      val root = (components[placedKey] as? JsonObject)?.primitiveString("root")
      if (root != null && root in nodes) subtreeNodes(root) else emptyList()
    }
  }
  return false
}

/** The wire's own id for a node that places a component. */
internal const val DESIGN_COMPONENT_INSTANCE_ID = "design/component-instance"

/** The catalog's own id for a loop over the design's rows, whose template reads one. */
private const val FOR_EACH_COMPONENT_ID = "layout/for-each"

/**
 * The scalar a value wrapper holds, or null. Safe cast so a malformed wire argument becomes a
 * diagnostic, not an exception.
 */
private fun JsonObject.scalar(): String? = (this["value"] as? JsonPrimitive)?.contentOrNull

/**
 * The text at [name], or null when absent or non-primitive. Placement arguments aren't validated,
 * so `diagnose` reads them through this instead of throwing `optionalString`.
 */
private fun JsonObject.primitiveString(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

/** One generated component function: what it is called, and the parameters its body reads. */
internal data class ComponentSignature(
  val functionName: String,
  val root: String,
  /** Sorted, because declaration order is the one thing that must not drift between exports. */
  val parameters: List<Pair<String, BindingKind>>,
)

/** The binding this property is, or null when it holds a value of its own. */
private fun UiBuilderNode.bindingKey(property: String): String? {
  val value = properties[property] as? JsonObject ?: return null
  if (value.optionalString("type") != "binding") return null
  return value.optionalString("value")?.takeIf { it.isNotEmpty() }
}

/**
 * Every key this node reads, with what each read needs it to be. A placement reads through its
 * arguments, typed by the placed component's parameter.
 */
private fun UiBuilderNode.bindingKinds(
  components: Map<String, ComponentSignature> = emptyMap()
): List<Triple<String, String, BindingKind?>> {
  if (componentId == DESIGN_COMPONENT_INSTANCE_ID) {
    val placed = components[placementKey()] ?: return emptyList()
    // Only arguments the placed component declares; undeclared ones are refused by
    // `strayArgumentRefusals`.
    val arguments = placementArguments()
    return placed.parameters
      .map { it.first }
      .sorted()
      .mapNotNull { argument ->
        val value = arguments[argument] as? JsonObject ?: return@mapNotNull null
        if (value.primitiveString("type") != "binding") return@mapNotNull null
        val key =
          value.primitiveString("value")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Triple(argument, key, placed.parameters.firstOrNull { it.first == argument }?.second)
      }
  }
  return properties.keys.sorted().mapNotNull { property ->
    val key = bindingKey(property) ?: return@mapNotNull null
    Triple(property, key, BINDABLE_PROPERTIES[componentId]?.get(property))
  }
}

/** Which component a placement places, from its own field rather than a property. */
private fun UiBuilderNode.placementKey(): String =
  component?.primitiveString("componentKey").orEmpty()

/** The dictionary a placement passes. */
private fun UiBuilderNode.placementArguments(): JsonObject =
  component?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())

/** The binding a placement's argument is, or null when it passes a value of its own. */
private fun UiBuilderNode.argumentBindingKey(argument: String): String? {
  val value = placementArguments()[argument] as? JsonObject ?: return null
  if (value.primitiveString("type") != "binding") return null
  return value.primitiveString("value")?.takeIf { it.isNotEmpty() }
}

private data class HandledFields(
  val properties: Set<String> = emptySet(),
  val slots: Set<String> = emptySet(),
  val events: Set<String> = emptySet(),
)

internal fun UiBuilderNode.fieldCoverageDiagnostics(): List<ComposeExportDiagnostic> {
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
      // Every action, because the handler emits every action.
      actions
        .map { (it as? JsonObject)?.optionalString("type") }
        .filter { it !in SUPPORTED_ACTIONS }
        .distinct()
        .forEach { type ->
          diagnostics +=
            warning("UNSUPPORTED_EVENT_ACTION", "event '$event' action '$type' emits TODO")
        }
    }
  }
  return diagnostics
}

private fun JsonObject.floatValue(name: String): Float? =
  (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull

/**
 * One state variable's declared shape. `valueType`/`nullable` are authoritative; `initialValue` is
 * a fallback for older declarations, read respecting `isString`.
 */
private data class StateDeclaration(
  val kotlinType: String,
  val nullable: Boolean,
  val initialValue: JsonElement?,
) {
  companion object {
    fun of(declaration: JsonObject?): StateDeclaration {
      val initial = declaration?.get("initialValue")
      val primitive = initial as? kotlinx.serialization.json.JsonPrimitive
      // Safe casts: `stateVariables` isn't validated, so malformed values must not throw out of
      // `export()`.
      val nullable =
        (declaration?.get("nullable") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
          ?: (initial is JsonNull)
      val declaredType =
        (declaration?.get("valueType") as? kotlinx.serialization.json.JsonPrimitive)
          ?.takeIf { it.isString }
          ?.contentOrNull
      val base =
        when (declaredType) {
          "bool" -> "Boolean"
          // `Int`, not `Long`: integer literals are emitted unsuffixed.
          "int" -> "Int"
          "float" -> "Double"
          "string" -> "String"
          else ->
            when {
              primitive == null -> "String"
              primitive.isString -> "String"
              primitive.booleanOrNull != null -> "Boolean"
              primitive.longOrNull != null -> "Int"
              primitive.doubleOrNull != null -> "Double"
              else -> "String"
            }
        }
      return StateDeclaration(if (nullable) "$base?" else base, nullable, initial)
    }
  }
}

/**
 * A literal spelled for the variable's declared Kotlin type (e.g. `1.0` for a `Double` seeded with
 * `1`).
 */
private fun JsonElement?.literalAs(kotlinType: String): String {
  val bare = kotlinType.removeSuffix("?")
  val primitive = this as? kotlinx.serialization.json.JsonPrimitive
  if (bare == "Double" && primitive != null && !primitive.isString) {
    primitive.doubleOrNull?.let { value ->
      // Spelled from the authored JSON text: `toLong()` saturates, and `Double.toString` prints
      // `1.0` as `1` on JS.
      val authored = primitive.content
      return when {
        value.isNaN() -> "Double.NaN"
        value == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY"
        value == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY"
        // Already a floating-point literal — a point or an exponent makes it one.
        authored.any { it == '.' || it == 'e' || it == 'E' } -> authored
        else -> "$authored.0"
      }
    }
  }
  return kotlinLiteral()
}

/**
 * Whether the Kotlin type this names can hold [value] as `kotlinLiteral` will write it. Quoting
 * decides, not content (`"true"` is not a `Boolean`); `Double` must be finite.
 */
private fun String?.holds(value: JsonElement?): Boolean {
  if (this == null) return false
  if (value == null || value is JsonNull) return endsWith("?")
  val primitive = value as? JsonPrimitive ?: return false
  return when (removeSuffix("?")) {
    "Boolean" -> !primitive.isString && primitive.booleanOrNull != null
    "Int" -> !primitive.isString && primitive.intOrNull != null
    "Double" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
    "String" -> primitive.isString
    else -> false
  }
}

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

/** A dimension the design stated as a `Dp` literal, or `null`; absent is not zero. */
private fun UiBuilderNode.dpArgument(name: String): String =
  (properties[name] as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull?.dpLiteral() ?: "null"

private fun Float.floatLiteral(): String = if (this % 1f == 0f) "${toInt()}f" else "${this}f"

/** The shape argument for a node; a `shape` token wins over `shapeDp`, as in the renderer. */
private fun UiBuilderNode.shapeExpression(): String {
  val token = string("shape")
  val corner = if (token.isNotEmpty()) shapeDp(token) else number("shapeDp")
  return "RoundedCornerShape(${corner.dpLiteral()})"
}

/**
 * `parameter = <n>.dp, ` when the document carries the dimension, else nothing (e.g. a divider then
 * keeps Material's hairline).
 */
private fun UiBuilderNode.dimensionArgument(parameter: String, property: String): String =
  obj(property)["value"]?.jsonPrimitive?.floatOrNull?.let { "$parameter = ${it.dpLiteral()}, " }
    ?: ""

/** A floating toolbar's own padding, defaulting to 6dp when absent. */
private fun UiBuilderNode.toolbarContentPaddingExpression(): String =
  (properties["contentPadding"] as? JsonObject)?.paddingValuesExpression() ?: "PaddingValues(6.dp)"

private fun shapeDp(value: String?): Float =
  when (value) {
    "large" -> 16f
    "medium" -> 12f
    "small" -> 8f
    else -> value?.toFloatOrNull() ?: 0f
  }

/**
 * Kotlin's hard keywords: reserved wherever an identifier is expected, and not escapable by this
 * generator, which writes `var $name` bare.
 */

// The emitter and design creation must agree about what a name becomes, so there is one
// implementation of it, in `:ui-builder-export` beside the export projection.
private fun String.identifier(): String = exportedStateIdentifier(this)

/** The name an import statement binds: its alias if it has one, else its last segment. */
private fun String.importedSimpleName(): String =
  substringAfterLast(" as ").trim().substringAfterLast('.')

private fun String.escape(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun String.escapeComment(): String = replace("\n", " ").replace("\r", " ")

/**
 * How many identical siblings it takes before a `repeat` reads better than the calls themselves.
 */
private const val MINIMUM_FOLDED_RUN = 3

internal val EMITTER_IDS =
  setOf(
    "asset/image",
    // Its "typed call" is the `forEach` this exporter writes around its template, which is why
    // the catalog entry names no symbol of its own.
    FOR_EACH_COMPONENT_ID,
    "layout/box",
    "layout/column",
    "layout/flow-row",
    "layout/horizontal-carousel",
    "layout/lazy-column",
    "layout/lazy-grid",
    "layout/lazy-row",
    "layout/row",
    "layout/scaffold",
    "layout/supporting-pane-scaffold",
    "layout/list-detail-pane-scaffold",
    "m3/button",
    "m3/card",
    "m3/center-aligned-top-app-bar",
    "m3/checkbox",
    "m3/date-picker",
    "m3/dialog",
    "m3/filter-chip",
    "m3/horizontal-divider",
    "m3/horizontal-floating-toolbar",
    "m3/icon",
    "m3/icon-button",
    "m3/list-item",
    "m3/navigation-suite-item",
    "m3/navigation-suite-scaffold",
    "m3/primary-scrollable-tab-row",
    "m3/primary-tab-row",
    "m3/progress-indicator",
    "m3/radio-button",
    "m3/search-bar",
    "m3/search-input-field",
    "m3/slider",
    "m3/snackbar-host",
    "m3/surface",
    "m3/switch",
    "m3/tab",
    "m3/text",
    "m3/text-field",
    "m3/time-picker",
    "shape/colour-dot",
    "shape/linear-gradient",
    "shape/radial-gradient",
  )

internal val SUPPORTED_MODIFIERS =
  setOf(
    "clip",
    "fillMaxSize",
    "fillMaxWidth",
    "matchParentSize",
    "padding",
    "size",
    // Scope-bearing `align`/`weight` are excluded: they are printed from the same-named properties.
    "fillMaxHeight",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "aspectRatio",
    "wrapContentSize",
    "offset",
    "zIndex",
    "background",
    "border",
    "alpha",
    "shadow",
    "rotate",
    "scale",
    "verticalScroll",
    "horizontalScroll",
    "testTag",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
  )

private val SUPPORTED_ACTIONS =
  setOf("select", "selectOrClear", "setText", "set", "toggle", "increment", "navigatePage")

/**
 * The components whose `click` binding the Compose export emits, derived from `HANDLED_FIELDS` so
 * the editor cannot offer a wiring this exporter would drop (others report `UNEMITTED_EVENT`).
 */
internal val COMPOSE_EMITTED_CLICK_COMPONENTS: Set<String> by lazy {
  HANDLED_FIELDS.filterValues { "click" in it.events }.keys
}

/**
 * The dimensions this exporter emits, as `componentId.property`, so only `…Dp` properties some
 * emitter reads get a number editor. Derived, like [COMPOSE_EMITTED_CLICK_COMPONENTS], so adding a
 * dimension to an emitter makes it editable.
 */
internal val COMPOSE_EMITTED_DP_PROPERTIES: Set<String> by lazy {
  HANDLED_FIELDS.flatMap { (componentId, fields) ->
      fields.properties.filter { it.endsWith("Dp") }.map { "$componentId.$it" }
    }
    .toSet()
}

private val HANDLED_FIELDS =
  mapOf(
    "asset/image" to HandledFields(setOf("assetKey", "contentDescription", "contentScale")),
    "layout/box" to HandledFields(setOf("contentAlignment"), setOf("children")),
    "layout/column" to
      HandledFields(
        setOf(
          "verticalSpacingDp",
          "verticalArrangement",
          "horizontalAlignment",
          "weight",
          "alignment",
        ),
        setOf("children"),
      ),
    "layout/flow-row" to
      HandledFields(
        setOf(
          "horizontalArrangement",
          "horizontalSpacingDp",
          "verticalArrangement",
          "verticalSpacingDp",
          "maxItemsInEachRow",
          "weight",
          "alignment",
        ),
        setOf("children"),
      ),
    "layout/horizontal-carousel" to
      HandledFields(
        setOf(
          "itemWidthDp",
          "itemSpacingDp",
          "contentPaddingStartDp",
          "kind",
          "scrollStateKey",
          "span",
        ),
        setOf("items"),
      ),
    "layout/lazy-column" to
      HandledFields(setOf("contentPadding", "scrollStateKey", "verticalSpacingDp"), setOf("items")),
    "layout/lazy-grid" to
      HandledFields(
        setOf(
          "columns",
          "contentPadding",
          "horizontalSpacingDp",
          "scrollStateKey",
          "verticalSpacingDp",
        ),
        setOf("items"),
      ),
    "layout/lazy-row" to
      HandledFields(
        setOf("contentPadding", "horizontalSpacingDp", "span", "stableKey"),
        setOf("items"),
      ),
    "layout/row" to
      HandledFields(
        setOf("horizontalSpacingDp", "horizontalArrangement", "verticalAlignment"),
        setOf("children"),
      ),
    "layout/scaffold" to
      HandledFields(
        setOf("containerColor", "loading", "scrollStateKey"),
        setOf("topBar", "snackbarHost", "content"),
      ),
    "layout/list-detail-pane-scaffold" to
      HandledFields(
        setOf(
          "layoutMode",
          "paneSizing",
          "fixedPaneWidthDp",
          "splitFraction",
          "activePane",
          "activePaneIndex",
          "listPanePreferredWidthDp",
          "detailPanePreferredWidthDp",
          "extraPanePreferredWidthDp",
          "listPaneVisible",
          "detailPaneVisible",
          "paneSpacingDp",
        ),
        setOf("listPane", "detailPane", "extraPane"),
      ),
    "layout/supporting-pane-scaffold" to
      HandledFields(
        setOf(
          "layoutMode",
          "paneSizing",
          "fixedPaneWidthDp",
          "splitFraction",
          "mainPanePreferredWidthDp",
          "mainPaneVisible",
          "paneSpacingDp",
          "supportingPanePreferredWidthDp",
          "supportingPaneVisible",
        ),
        setOf("mainPane", "supportingPane"),
      ),
    "m3/button" to
      HandledFields(
        setOf("style", "containerColor", "contentColor", "selected"),
        setOf("content"),
        setOf("click"),
      ),
    "m3/card" to
      HandledFields(
        setOf("containerColor", "elevationDp", "shape", "stableKey", "style", "variant"),
        setOf("content"),
      ),
    "m3/center-aligned-top-app-bar" to
      HandledFields(
        setOf("containerColor", "scrolledContainerColor", "scrollBehavior"),
        setOf("title"),
      ),
    "m3/checkbox" to
      // `checkedChange` is the callback's own name; `click` is what designs authored before the
      // names were made consistent carry, and still emits.
      HandledFields(setOf("checked", "enabled"), events = setOf("checkedChange", "click")),
    "m3/date-picker" to HandledFields(setOf("mode", "selectedDate", "showModeToggle")),
    "m3/dialog" to
      HandledFields(
        setOf("containerColor", "shapeDp", "tonalElevationDp"),
        setOf("icon", "title", "text", "dismissButton", "confirmButton"),
      ),
    "m3/filter-chip" to
      HandledFields(
        setOf("selected", "enabled", "shape"),
        setOf("label", "leadingIcon"),
        setOf("click"),
      ),
    "m3/horizontal-divider" to HandledFields(setOf("color", "thicknessDp")),
    "m3/horizontal-floating-toolbar" to
      HandledFields(
        setOf("alignment", "containerColor", "contentPadding", "expanded"),
        setOf("content"),
      ),
    "m3/icon" to HandledFields(setOf("iconKey", "contentDescription", "color", "sizeDp")),
    "m3/icon-button" to
      HandledFields(
        setOf("alignment", "contentDescription", "selected", "sizeDp", "style", "variant"),
        slots = setOf("content"),
      ),
    "m3/list-item" to
      HandledFields(setOf("startAccentColor"), setOf("headline", "supporting", "trailing")),
    "m3/navigation-suite-item" to
      HandledFields(setOf("selected"), setOf("icon", "label"), setOf("click")),
    "m3/navigation-suite-scaffold" to
      HandledFields(emptySet(), setOf("navigationItems", "primaryAction", "content")),
    "m3/primary-scrollable-tab-row" to HandledFields(setOf("selectedIndex"), setOf("tabs")),
    "m3/primary-tab-row" to HandledFields(setOf("selectedIndex"), setOf("tabs")),
    "m3/progress-indicator" to HandledFields(setOf("variant", "progress", "indeterminate")),
    "m3/radio-button" to HandledFields(setOf("selected", "enabled"), events = setOf("click")),
    "m3/search-bar" to
      HandledFields(
        setOf("expanded", "tonalElevationDp", "shapeDp"),
        slots = setOf("inputField", "expandedContent"),
      ),
    "m3/search-input-field" to
      HandledFields(
        setOf("enabled", "value"),
        setOf("placeholder", "leadingIcon", "trailingIcon"),
        setOf("valueChange"),
      ),
    "m3/slider" to
      HandledFields(
        setOf("value", "valueFrom", "valueTo", "steps", "enabled"),
        events = setOf("valueChange"),
      ),
    "m3/snackbar-host" to HandledFields(setOf("visible")),
    "m3/surface" to
      HandledFields(
        setOf("containerColor", "shape", "shapeDp", "tonalElevationDp") +
          ThemeTypefaces.PROPERTIES +
          ThemeTextStyle.PROPERTY,
        setOf("content"),
      ),
    "m3/switch" to
      // `checkedChange` is the callback's own name; `click` is what designs authored before the
      // names were made consistent carry, and still emits.
      HandledFields(setOf("checked", "enabled"), events = setOf("checkedChange", "click")),
    "m3/tab" to HandledFields(setOf("selected"), setOf("text"), setOf("click")),
    "m3/text-field" to
      HandledFields(
        setOf("variant", "value", "enabled", "readOnly", "singleLine", "isError"),
        setOf("label", "placeholder", "supportingText", "leadingIcon", "trailingIcon"),
        setOf("valueChange"),
      ),
    "m3/time-picker" to HandledFields(setOf("mode", "hour", "minute", "is24Hour")),
    "m3/text" to
      HandledFields(
        setOf(
          "text",
          "style",
          "fontWeight",
          "fontStyle",
          "color",
          "fontSizeSp",
          "lineHeightSp",
          "letterSpacingSp",
          "minLines",
          "maxLines",
          "softWrap",
          "overflow",
          "textAlign",
          "textDecoration",
          "alignment",
          "weight",
        ) + FontSettings.PROPERTIES
      ),
    "shape/colour-dot" to HandledFields(setOf("color", "diameterDp")),
    "shape/linear-gradient" to HandledFields(setOf("startColor", "endColor", "direction")),
    "shape/radial-gradient" to
      HandledFields(setOf("innerColor", "outerColor", "innerAlpha", "center")),
  )

private val GENERATED_IMPORTS =
  listOf(
      "androidx.compose.foundation.background",
      "androidx.compose.foundation.border",
      "androidx.compose.foundation.horizontalScroll",
      "androidx.compose.foundation.rememberScrollState",
      "androidx.compose.foundation.verticalScroll",
      "androidx.compose.foundation.Canvas",
      "androidx.compose.foundation.layout.*",
      "androidx.compose.foundation.lazy.*",
      "androidx.compose.foundation.lazy.grid.*",
      "androidx.compose.foundation.shape.CircleShape",
      "androidx.compose.foundation.shape.RoundedCornerShape",
      "androidx.compose.foundation.text.BasicTextField",
      "androidx.compose.material.icons.Icons",
      "androidx.compose.material.icons.automirrored.filled.*",
      "androidx.compose.material.icons.automirrored.outlined.*",
      "androidx.compose.material.icons.automirrored.rounded.*",
      "androidx.compose.material.icons.automirrored.sharp.*",
      "androidx.compose.material.icons.automirrored.twotone.*",
      "androidx.compose.material.icons.filled.*",
      "androidx.compose.material.icons.outlined.*",
      "androidx.compose.material.icons.rounded.*",
      "androidx.compose.material.icons.sharp.*",
      "androidx.compose.material.icons.twotone.*",
      "androidx.compose.material3.*",
      "androidx.compose.material3.carousel.HorizontalUncontainedCarousel",
      "androidx.compose.material3.carousel.rememberCarouselState",
      "androidx.compose.runtime.*",
      "androidx.compose.ui.Alignment",
      "androidx.compose.ui.Modifier",
      "androidx.compose.ui.draw.alpha",
      "androidx.compose.ui.draw.clip",
      "androidx.compose.ui.draw.rotate",
      "androidx.compose.ui.draw.scale",
      "androidx.compose.ui.draw.shadow",
      "androidx.compose.ui.draw.drawBehind",
      "androidx.compose.ui.geometry.Offset",
      "androidx.compose.ui.geometry.Size",
      "androidx.compose.ui.graphics.Brush",
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.graphics.Path",
      "androidx.compose.ui.graphics.drawscope.Stroke",
      "androidx.compose.ui.graphics.vector.ImageVector",
      "androidx.compose.ui.text.font.FontWeight",
      "androidx.compose.ui.semantics.contentDescription",
      "androidx.compose.ui.semantics.selected",
      "androidx.compose.ui.semantics.semantics",
      "androidx.compose.ui.semantics.stateDescription",
      "androidx.compose.ui.text.style.TextOverflow",
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.platform.testTag",
      "androidx.compose.ui.unit.dp",
      "androidx.compose.ui.zIndex",
    )
    .sorted()
