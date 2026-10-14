package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1

/**
 * Generates the `@RemoteComposable` function a [REMOTE_COMPOSE_INLINE_COMPONENT_ID] subtree is: a
 * piece of Remote Compose inside somebody else's screen (ordinary Remote catalog roots use the same
 * emitter). Delivery is the application's choice, so only the body is written, in
 * [RemoteContentEmitter]'s vocabulary.
 *
 * The call site is deliberately not written: `captureSingleRemoteDocument`'s display info and
 * density choices have pixel consequences a generator must not guess. See
 * `docs/design/UI_BUILDER_REMOTE_COMPOSE.md` → "Choosing the density behaviour at the call site".
 *
 * Unwritable nodes are refused by name, including an unnamed custom component (the name is the
 * whole contract with the host).
 */
public object InlineRemoteContentExporter {

  /** What an inline subtree generates, or why it does not. */
  public sealed interface Result {
    /**
     * @property functionName the `@RemoteComposable` function the body was written into.
     * @property source the function, its imports, and the header comment naming the design.
     * @property diagnostics what the source needs but cannot say for itself; see
     *   [ExportDiagnosticCodes].
     */
    public data class Emitted(
      val functionName: String,
      val source: String,
      val diagnostics: List<ExportDiagnosticV1> = emptyList(),
    ) : Result

    public data class Refused(val reasons: List<String>) : Result
  }

  /**
   * The body of the inline node [nodeId], as a pasteable Kotlin file fragment.
   *
   * @param packageName the package the fragment declares, or null for the editor's pane (as in
   *   [WearWidgetCodeExporter]).
   */
  public fun export(
    document: UiBuilderDocument,
    nodeId: String,
    packageName: String? = null,
  ): Result {
    val node =
      document.nodes[nodeId]
        ?: return Result.Refused(listOf("the node `$nodeId` is not in this design"))
    if (node.componentId != REMOTE_COMPOSE_INLINE_COMPONENT_ID) {
      return Result.Refused(
        listOf(
          "`$nodeId` is `${node.componentId}`, not remote content — this generator writes the " +
            "body of a `$REMOTE_COMPOSE_INLINE_COMPONENT_ID` node"
        )
      )
    }

    val contentIds = node.slots["content"].orEmpty()
    if (contentIds.size > 1)
      return Result.Refused(listOf("remote content has one body; `$nodeId` has ${contentIds.size}"))
    return source(
      document,
      contentIds,
      functionName(document, nodeId),
      "Remote content `${nodeId.escapeComment()}` of design",
      packageName,
      emptyMap(),
      WidgetAssetBytes { null },
      emptyBox = true,
    )
  }

  /**
   * Emits authored roots directly, without adding a widget or synthetic inline node to the tree.
   */
  internal fun exportRoots(
    document: UiBuilderDocument,
    packageName: String?,
    components: Map<String, ComponentRecord>,
    assets: WidgetAssetBytes,
  ): Result {
    val reasons = mutableListOf<String>()
    if (document.roots.isEmpty()) reasons += "document.roots: a Remote source export needs a root"
    val complete = mutableSetOf<String>()
    fun visit(id: String, ancestors: Set<String>) {
      if (id in ancestors) {
        reasons += "nodes.$id: cyclic child reference"
        return
      }
      if (id in complete) return
      val node = document.nodes[id]
      if (node == null) {
        reasons += "nodes.$id: missing node"
        return
      }
      if (ancestors.size >= 128) {
        reasons += "nodes.$id: layout nesting exceeds 128 levels"
        return
      }
      node.slots.values.flatten().forEach { visit(it, ancestors + id) }
      complete += id
    }
    document.roots.forEach { visit(it, emptySet()) }
    if (reasons.isNotEmpty()) return Result.Refused(reasons.distinct())
    return source(
      document,
      document.roots,
      functionName(document, ""),
      "Remote content of design",
      packageName,
      components,
      assets,
      emptyBox = false,
    )
  }

  /**
   * A design's component definitions alone, as `internal` `@RemoteComposable` functions: the Remote
   * counterpart of [ScreenExportGate.exportComponents], for a catalog that places the components
   * itself. Every root must place a component; the roots themselves are not written.
   *
   * A component's parameters are its `modifier`, its bindings (what each placement supplies, named
   * for their keys) and its captures. A colour or type token is read from `RemoteMaterialTheme`,
   * which the caller's own frame provides: no theme is written around a component.
   */
  public fun exportComponents(
    document: UiBuilderDocument,
    packageName: String? = null,
    components: Map<String, ComponentRecord> = emptyMap(),
    assets: WidgetAssetBytes = WidgetAssetBytes { null },
  ): Result {
    val reasons = mutableListOf<String>()
    if (document.roots.isEmpty()) reasons += "document.roots: place at least one component"
    document.roots.forEach { id ->
      val node = document.nodes[id]
      if (node?.componentId != "design/component-instance")
        reasons += "document.roots.$id: a components-only export writes components, not `$id`"
    }
    if (reasons.isNotEmpty()) return Result.Refused(reasons)
    val emitter =
      RemoteContentEmitter(
        document,
        reasons,
        assets = assets,
        components = components,
        componentVisibility = "internal",
        namedBindings = true,
      )
    document.roots.forEach { emitter.emit(it, depth = 1) }
    val declarations = emitter.declarations
    val first =
      Regex("""internal fun (\w+)\(""")
        .find(declarations.firstOrNull().orEmpty())
        ?.groupValues
        ?.get(1)
    if (first == null) reasons += "document: no component was written"
    if (reasons.isNotEmpty()) return Result.Refused(reasons.distinct())
    return Result.Emitted(
      functionName = first!!,
      source =
        buildString {
          appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
          appendLine(
            "// Components of design ${document.id.escapeComment()} revision ${document.revision}."
          )
          appendLine("@file:Suppress(\"RestrictedApi\")")
          appendLine()
          if (packageName != null) {
            appendLine("package $packageName")
            appendLine()
          }
          emitter.imports(widget = null).forEach { appendLine("import $it") }
          declarations.forEach {
            appendLine()
            appendLine(it)
          }
        },
      diagnostics = emitter.diagnostics,
    )
  }

  private fun source(
    document: UiBuilderDocument,
    contentIds: List<String>,
    name: String,
    description: String,
    packageName: String?,
    components: Map<String, ComponentRecord>,
    assets: WidgetAssetBytes,
    emptyBox: Boolean,
  ): Result {
    val refusals = mutableListOf<String>()
    // Theme use is known after emitting. Probe every root, then emit once at the correct depth.
    val probe =
      RemoteContentEmitter(document, mutableListOf(), assets = assets, components = components)
    contentIds.forEach { probe.emit(it, depth = 1) }
    val depth = if (probe.usesTheme) 2 else 1
    val emitter = RemoteContentEmitter(document, refusals, assets = assets, components = components)
    val body =
      if (contentIds.isEmpty() && emptyBox) {
        listOf(emitter.emptyBox(depth))
      } else contentIds.flatMap { emitter.emit(it, depth = depth) }
    emitter.validateFunctionNames(name)
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    return Result.Emitted(
      functionName = name,
      source =
        buildString {
          appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
          appendLine(
            "// $description " + "${document.id.escapeComment()} revision ${document.revision}."
          )
          appendLine("@file:Suppress(\"RestrictedApi\")")
          appendLine()
          if (packageName != null) {
            appendLine("package $packageName")
            appendLine()
          }
          emitter.imports(widget = null).forEach { appendLine("import $it") }
          appendLine()
          appendLine("@RemoteComposable")
          appendLine("@Composable")
          // Pictures as parameters without defaults: there is no generated preview, and the call
          // site is the application's.
          appendLine(
            "fun $name(${emitter.imageParameters.joinToString { "${it.identifier}: RemoteImageBitmap" }}) {"
          )
          emitter.stateLocals().forEach { appendLine("$INDENT$it") }
          if (emitter.usesTheme) {
            appendLine("${INDENT}RemoteMaterialTheme {")
            body.forEach(::appendLine)
            appendLine("$INDENT}")
          } else {
            body.forEach(::appendLine)
          }
          appendLine("}")
          emitter.declarations.forEach {
            appendLine()
            appendLine(it)
          }
        },
      diagnostics = emitter.diagnostics,
    )
  }

  /**
   * `Home screen` + node `hero` becomes `HomeScreenHeroRemoteContent`; the node id keeps several
   * inline nodes in one file from colliding.
   */
  public fun functionName(document: UiBuilderDocument, nodeId: String): String {
    val words =
      (document.title.split(WORD_BREAK) + nodeId.split(WORD_BREAK))
        .filter(String::isNotEmpty)
        .map { word -> word.replaceFirstChar(Char::uppercaseChar) }
    val joined = words.joinToString("").ifEmpty { "Generated" }
    val identifier = if (joined.first().isDigit()) "Design$joined" else joined
    return "${identifier}RemoteContent"
  }

  private val WORD_BREAK = Regex("[^A-Za-z0-9]+")

  private const val INDENT = "    "
}

/** A comment carries the rest of its line, so a newline in one would comment out the code below. */
internal fun String.escapeComment(): String = replace("\n", " ").replace("\r", " ")
