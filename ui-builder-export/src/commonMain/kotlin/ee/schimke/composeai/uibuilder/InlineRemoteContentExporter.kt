package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecord

/**
 * Generates the `@RemoteComposable` function a [REMOTE_COMPOSE_INLINE_COMPONENT_ID] subtree is.
 *
 * ## Why this is neither of the other two generators
 *
 * [WearWidgetCodeExporter] writes a whole *widget* — a `GlanceWearWidget`, a `WearWidgetDocument`
 * and the Glance host preview around it — when the design has a Wear widget scaffold.
 * [CapabilityComposeCodeExporter] writes a Jetpack Compose screen. An inline node is a third thing:
 * a piece of Remote Compose *inside* somebody else's screen. Ordinary Remote catalog roots use the
 * same emitter. Their delivery — captured into a document at build time, fetched from a server,
 * played by whichever host the application already has — is the application's choice rather than
 * the design's.
 *
 * So this writes the one part the design does decide, which is the body, in the same vocabulary
 * [RemoteContentEmitter] writes a widget body in. What it deliberately does not write is the call
 * site. `captureSingleRemoteDocument` takes a `RemoteCreationDisplayInfo`, a `RemoteDensity` and a
 * density behaviour, and which of those an application wants is a decision with pixel consequences
 * (the densities disagree, and the disagreement is a bug this stack has already shipped twice). A
 * generator that guessed them would hand somebody code that compiles and draws the wrong size,
 * which is the failure every refusal in this file exists to avoid.
 *
 * Refusing to pick is not the same as having nothing to say about it: `docs/design/
 * UI_BUILDER_REMOTE_COMPOSE.md` → "Choosing the density behaviour at the call site" carries the
 * rule — one target device, capture a constant; several whose densities differ, use expressions and
 * let the document resolve against the host's own `ID_*` density at paint time.
 *
 * ## Refusals are by name
 *
 * The same discipline as the other two. A node the vocabulary cannot write is refused and said out
 * loud, by name and with the reason, rather than approximated into something that does not compile.
 * A custom component is no longer one of them — `remote-creation-compose` publishes
 * `RemoteCustomComponent`, so [RemoteContentEmitter] writes the operation from the node — but an
 * *unnamed* one still is, because the name is the whole contract with the host.
 */
public object InlineRemoteContentExporter {

  /** What an inline subtree generates, or why it does not. */
  public sealed interface Result {
    /**
     * @property functionName the `@RemoteComposable` function the body was written into.
     * @property source the function, its imports, and the header comment naming the design.
     */
    public data class Emitted(val functionName: String, val source: String) : Result

    public data class Refused(val reasons: List<String>) : Result
  }

  /**
   * The body of the inline node [nodeId], as a pasteable Kotlin file fragment.
   *
   * @param packageName the package the fragment declares, or null for the editor's pane. A pane is
   *   read and pasted into a file that already has one; the split is the same one
   *   [WearWidgetCodeExporter] makes and for the same reason.
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
        listOf("${INDENT.repeat(depth)}RemoteBox(modifier = RemoteModifier.fillMaxSize())")
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
          // The pictures the body draws, as parameters and without defaults: an inline fragment
          // has no generated preview to construct, and its call site is the application's own —
          // so the bitmap is asked for where the caller can see it is needed.
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
    )
  }

  /**
   * `Home screen` + node `hero` becomes `HomeScreenHeroRemoteContent`.
   *
   * The node id is in the name because a screen may hold more than one inline node and two
   * functions of the same name in one file do not compile. The design title alone was enough for a
   * widget, which has exactly one body.
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
