package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates the Kotlin a launcher widget design becomes: a `RemoteComposeWidget` whose
 * `Content(context, widgetId)` is the design's body, and a `@Preview` at the launcher grid size the
 * design was authored at.
 *
 * Unlike [WearWidgetCodeExporter], a phone launcher widget is an `AppWidgetProvider` recording
 * `Content` under the `WIDGETS_V6` profile, with no Remote Material 3 theme. The body uses
 * [RemoteContentEmitter] with [RemoteTextVocabulary.CREATION]; the shape follows AndroidX's
 * `MyWidget` demo in `compose/remote/integration-tests/player-view-demos`.
 *
 * [ROOT] is erased into `Content`: its background becomes the box's `RemoteModifier.background`,
 * its size is the environment's launcher grid size ([LauncherWidgetGrid]); the launcher clips
 * corners. Anything only `remote-material3` can write is refused by name, as are pictures for now.
 */
object LauncherWidgetCodeExporter {

  /** The launcher widget root the `remote-widgets` catalog declares as its one builtin. */
  const val ROOT: String = "remote-widgets/launcher-widget"

  /** What a design generates, or why it does not. */
  sealed interface Result {
    data class Emitted(val source: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the file declares, or null for the Code pane's snippet.
   * @param components the catalog's own records, used by the body's record-driven fallback.
   * @param rootComponentId the component a design's root must be: [ROOT] when routed by id, or the
   *   declaring catalog's screen root under the catalog-owned cutover
   *   (`CatalogExportRouting.LAUNCHER_WIDGET`).
   * @param frameSizes the catalog's `frame.geometry.sizesDp`, labelled `width x height`, naming the
   *   preview. Null reads the [LauncherWidgetGrid] reference table.
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    components: Map<String, ComponentRecord> = emptyMap(),
    rootComponentId: String = ROOT,
    frameSizes: List<CatalogFrameSize>? = null,
  ): Result {
    val rootId =
      document.roots.singleOrNull() ?: return Result.Refused(listOf("a widget design has one root"))
    val root =
      document.nodes[rootId] ?: return Result.Refused(listOf("the root node `$rootId` is missing"))
    if (root.componentId != rootComponentId) {
      return Result.Refused(
        listOf("the root is `${root.componentId}`, not a launcher widget (`$rootComponentId`)")
      )
    }
    val contentIds = root.slots["content"].orEmpty()
    if (contentIds.size > 1) {
      return Result.Refused(
        listOf("the launcher widget holds one body; this design has ${contentIds.size}")
      )
    }

    val refusals = mutableListOf<String>()
    val emitter =
      RemoteContentEmitter(
        document,
        refusals,
        components = components,
        frameFillingRoot = contentIds.singleOrNull(),
        textVocabulary = RemoteTextVocabulary.CREATION,
      )
    // `Content` → the root box → the body: the body opens three levels in.
    val body =
      if (contentIds.isEmpty()) emptyList() else emitter.emit(contentIds.single(), depth = 3)
    val name = document.widgetIdentifier()
    emitter.validateFunctionNames(name)
    if (emitter.usesMaterial) {
      refusals +=
        "this design reaches for Remote Material 3 — a theme colour or type role, an ambient text " +
          "style or a Material component — which a launcher widget does not have: write colours " +
          "as #AARRGGBB literals and set text by size"
    }
    if (emitter.imageParameters.isNotEmpty() || emitter.inlineBitmaps.isNotEmpty()) {
      refusals +=
        "pictures in a launcher widget do not export yet: " +
          emitter.imageParameters.joinToString { "`${it.assetKey}`" }.ifEmpty { "the design's" }
    }
    val background =
      root.properties["background"]
        ?.stringOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?.let { declared ->
          if (!declared.startsWith("#")) {
            refusals +=
              "the launcher widget's background `$declared` is a theme role; a launcher widget " +
                "has no Remote theme, so write it as a #AARRGGBB literal"
            null
          } else "${declared.argbLiteral()}.rc"
        }
    if (refusals.isNotEmpty()) return Result.Refused(refusals.distinct())

    val widthDp =
      document.environmentDp("widthDp")
        ?: frameSizes?.firstOrNull()?.widthDp
        ?: LauncherWidgetGrid.DEFAULT.widthDp
    val heightDp =
      document.environmentDp("heightDp")
        ?: frameSizes?.firstOrNull()?.heightDp
        ?: LauncherWidgetGrid.DEFAULT.heightDp
    // Off-grid sizes are still previewed, named by dp rather than a cell count.
    val catalogLabel =
      frameSizes?.firstOrNull { it.widthDp == widthDp && it.heightDp == heightDp }?.label
    val previewLabel =
      if (frameSizes != null) catalogLabel ?: "${widthDp}x${heightDp}dp"
      else LauncherWidgetGrid.of(widthDp, heightDp)?.label ?: "${widthDp}x${heightDp}dp"
    val source = buildString {
      appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
      appendLine("@file:Suppress(\"RestrictedApi\")")
      appendLine()
      if (packageName != null) {
        appendLine("package $packageName")
        appendLine()
      }
      (emitter.imports(widget = null) +
          listOfNotNull(
            "android.content.Context",
            "androidx.compose.remote.creation.compose.layout.RemoteBox",
            "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
            "androidx.compose.remote.creation.compose.modifier.fillMaxSize",
            "androidx.compose.remote.creation.compose.modifier.background"
              .takeIf { background != null },
            "androidx.compose.remote.creation.compose.state.rc".takeIf { background != null },
            "androidx.compose.ui.graphics.Color".takeIf { background != null },
            "androidx.compose.remote.creation.compose.widgets.RemoteComposeWidget",
            "androidx.compose.remote.creation.profile.RcPlatformProfiles",
            "androidx.compose.remote.tooling.preview.RemoteContentPreview",
            "androidx.compose.ui.platform.LocalContext",
            "androidx.compose.ui.tooling.preview.Preview",
          ))
        .distinct()
        .sorted()
        .forEach { appendLine("import $it") }
      appendLine()
      appendLine("class $name : RemoteComposeWidget() {")
      appendLine("$INDENT@RemoteComposable")
      appendLine("$INDENT@Composable")
      appendLine("${INDENT}override fun Content(context: Context, widgetId: Int) {")
      emitter.stateLocals().forEach { appendLine("$INDENT$INDENT$it") }
      val modifier = "RemoteModifier.fillMaxSize()" + (background?.let { ".background($it)" } ?: "")
      if (body.isEmpty()) {
        appendLine("$INDENT${INDENT}RemoteBox(modifier = $modifier)")
      } else {
        appendLine("$INDENT${INDENT}RemoteBox(modifier = $modifier) {")
        body.forEach(::appendLine)
        appendLine("$INDENT$INDENT}")
      }
      appendLine("$INDENT}")
      appendLine("}")
      appendLine()
      // A catalog's label is authored text, so it is escaped like any other string the file writes.
      appendLine(
        "@Preview(name = ${previewLabel.quoted()}, widthDp = $widthDp, heightDp = $heightDp)"
      )
      appendLine("@Composable")
      appendLine("fun ${name}Preview() =")
      appendLine("${INDENT}RemoteContentPreview(profile = RcPlatformProfiles.WIDGETS_V6) {")
      appendLine("$INDENT$INDENT$name().Content(LocalContext.current, 0)")
      appendLine("$INDENT}")
      emitter.declarations.distinct().forEach {
        appendLine()
        appendLine(it)
      }
    }
    return Result.Emitted(source)
  }

  private fun UiBuilderDocument.environmentDp(key: String): Int? {
    val value = runCatching { environment[key]?.jsonPrimitive?.floatOrNull }.getOrNull()
    return value?.toInt()
  }

  private const val INDENT = "    "
}

/** A design whose root is the launcher widget, which [LauncherWidgetCodeExporter] writes. */
fun UiBuilderDocument.isLauncherWidget(): Boolean {
  val root = roots.singleOrNull()?.let(nodes::get) ?: return false
  return root.componentId == LauncherWidgetCodeExporter.ROOT
}

/**
 * The launcher home-screen grid a launcher widget is sized in: cell counts (`3x2`, `2x1`), with
 * Android's documented portrait reference size `(73n − 16) × (118m − 16)` dp
 * (https://developer.android.com/develop/ui/views/appwidgets/layouts). `remote-widgets` publishes
 * the same table as `frame.geometry.sizesDp`.
 */
object LauncherWidgetGrid {

  /** One footprint on the grid. */
  data class Size(val columns: Int, val rows: Int) {
    init {
      require(columns >= 1 && rows >= 1) { "a widget is at least one cell each way" }
    }

    /** `3x2`. */
    val label: String
      get() = "${columns}x$rows"

    val widthDp: Int
      get() = CELL_WIDTH_DP * columns - CELL_MARGIN_DP

    val heightDp: Int
      get() = CELL_HEIGHT_DP * rows - CELL_MARGIN_DP
  }

  const val CELL_WIDTH_DP: Int = 73
  const val CELL_HEIGHT_DP: Int = 118
  const val CELL_MARGIN_DP: Int = 16

  /** The size a design with no environment opens at: the sample widget's 3x2. */
  val DEFAULT: Size = Size(3, 2)

  /** The cell count whose reference size is exactly [widthDp] × [heightDp], or null. */
  fun of(widthDp: Int, heightDp: Int): Size? {
    val columns = (widthDp + CELL_MARGIN_DP) / CELL_WIDTH_DP
    val rows = (heightDp + CELL_MARGIN_DP) / CELL_HEIGHT_DP
    if (columns < 1 || rows < 1) return null
    return Size(columns, rows).takeIf { it.widthDp == widthDp && it.heightDp == heightDp }
  }

  /** `3x2` → three columns, two rows; null for anything else. */
  fun parse(label: String): Size? {
    val match = Regex("""(\d+)x(\d+)""").matchEntire(label.trim()) ?: return null
    val columns = match.groupValues[1].toIntOrNull() ?: return null
    val rows = match.groupValues[2].toIntOrNull() ?: return null
    return if (columns >= 1 && rows >= 1) Size(columns, rows) else null
  }
}
