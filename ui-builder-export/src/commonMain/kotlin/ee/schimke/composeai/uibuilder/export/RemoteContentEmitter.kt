package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The modifier types [RemoteContentEmitter] can write, and therefore the only ones a `remote-m3`
 * component may advertise.
 *
 * Absent: `matchParentSize`, `aspectRatio`, `shadow` and `testTag` (no `RemoteModifier`
 * counterpart, refused by name). The `align*` modifiers become arguments of the enclosing row,
 * column or box.
 */
public val REMOTE_CONTENT_MODIFIERS: Set<String> =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
    "animateEnterExit",
    "background",
    "border",
    "clip",
    "collapsiblePriority",
    "fillMaxHeight",
    "fillMaxSize",
    "fillMaxWidth",
    "height",
    "heightIn",
    "horizontalScroll",
    "offset",
    "padding",
    "remoteCall",
    "rotate",
    "scale",
    "sharedElement",
    "size",
    "verticalScroll",
    "weight",
    "width",
    "widthIn",
    "wrapContentSize",
    "zIndex",
  )

/**
 * The component ids [RemoteContentEmitter] has an authored answer for — a call, or a refusal
 * stating the reason. A `remote-m3` catalog may only offer these; `RemoteContentVocabularyTest`
 * checks each has a record or a case.
 */
public val REMOTE_CONTENT_COMPONENT_IDS: Set<String> =
  setOf(
    "asset/image",
    "layout/box",
    "layout/column",
    "layout/row",
    "layout/for-each",
    "layout/fit-box",
    "layout/flow-row",
    "layout/collapsible-column",
    "layout/collapsible-row",
    "m3/text",
    "remote-m3/lottie",
    REMOTE_TEXT_COMPONENT_ID,
    LAUNCHER_WIDGET_TEXT_COMPONENT_ID,
    "shape/linear-gradient",
    "remote-compose/document",
    REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
    REMOTE_COMPOSE_INLINE_COMPONENT_ID,
  ) +
    // Remote Material 3 components, written from their embedded record in every lane.
    RemoteMaterial3.components.map { it.componentId } +
    // The drawing vocabulary: a canvas and the operations its `ops` slot holds.
    UiDrawing.COMPONENT_IDS +
    // Remote Material 3 components the record has no row for, written by hand.
    UiTimeText.ID +
    UiRemoteTheme.ID

/**
 * Which widget file a [RemoteContentEmitter] body is written into, which decides its imports.
 * [Exported] is the kept artifact (widget, document, previews); [NativePreview] is the body the
 * server compiles for the Native pane. Null is an [InlineRemoteContentExporter] fragment.
 */
internal sealed interface WidgetSourceShape {

  /**
   * @property previewParamsProviders the shipped providers the generated `@Preview`s unroll, one
   *   per host container shape the file previews the widget in.
   */
  data class Exported(val previewParamsProviders: List<String>) : WidgetSourceShape

  /** The native lane's source: body, brush and the design's own `WearWidgetParams`. */
  data object NativePreview : WidgetSourceShape
}

/**
 * Writes a widget's designed content as Remote Compose source, and its background as a
 * `WearWidgetBrush`. [WearWidgetCodeExporter] owns the widget's shape; this owns the body's
 * vocabulary. Unsupported nodes append to [refusals] and emit nothing, so one pass reports all.
 */
internal class RemoteContentEmitter(
  private val document: UiBuilderDocument,
  private val refusals: MutableList<String>,
  private val assets: WidgetAssetBytes = WidgetAssetBytes { null },
  /**
   * Whether content pictures carry their bytes rather than becoming parameters. Only for the native
   * preview lane, which cannot pass arguments.
   */
  private val inlineContentImages: Boolean = false,
  /**
   * The bundle lane's registry (`docs/design/UI_BUILDER_EXPORT_BUNDLE.md`): pictures become files
   * beside the source. Never set together with [inlineContentImages].
   */
  private val bundled: WidgetAssetContents? = null,
  /**
   * The catalog's own component record by id — the fallback in [emit]. Hand-written cases never
   * reach it; empty by default.
   */
  components: Map<String, ComponentRecord> = emptyMap(),
  /**
   * The node the widget container fills its frame with, or null outside a widget. It gets
   * `fillMaxSize` first, as on the canvas, so it fills differently sized host frames.
   */
  private val frameFillingRoot: String? = null,
  /**
   * Which `RemoteText` a text node is written as: [RemoteTextVocabulary.MATERIAL3] for Wear
   * catalogs, [RemoteTextVocabulary.CREATION] for catalogs without `remote-material3` (e.g.
   * `remote-widgets`).
   */
  private val textVocabulary: RemoteTextVocabulary = RemoteTextVocabulary.MATERIAL3,
  /**
   * The visibility a design's component definitions are written with: `private` beside the root
   * that places them, `internal` when [InlineRemoteContentExporter.exportComponents] writes the
   * components alone for a catalog's own code to call.
   */
  private val componentVisibility: String = "private",
  /**
   * Whether a component's binding parameters are named for their keys (`label`) rather than
   * numbered (`argument0`): a catalog calls the components-only export by name, while the widget
   * and inline exports keep their established spelling.
   */
  private val namedBindings: Boolean = false,
  /**
   * Whether the body is a Glance Wear widget's, whose capture admits only the Glance Wear widget
   * profile; a layout outside it is then an error on [diagnostics], not only a comment.
   */
  private val glanceWearWidget: Boolean = false,
  /**
   * Whether every placed node carries its [NativeNodeTags.tag] as a content description, for the
   * native preview lane to read each node's bounds back from the played semantics tree. Never set
   * for an export a person keeps: a screen reader would read the tag aloud.
   *
   * Written *first* in the node's `RemoteModifier` chain. The player applies a component's
   * semantics operations in document order, and Compose collapses one layout node's semantics so
   * the outermost `contentDescription` wins and a `clearAndSetSemantics` drops everything inside
   * it; ahead of the node's own semantics the tag survives both. The node keeps its own `semantics`
   * or `clearAndSetSemantics` call, and every property the tag does not set (role, state, enabled),
   * but in the played tree its own content description is shadowed by the tag.
   *
   * Only nodes written with a `RemoteModifier` are tagged: layouts, text, icons, images, buttons
   * and the record's other components, canvases, a component placement (with the placement's id)
   * and a loop (with the loop's id; each row's template node carries the template's id). Draw
   * operations inside a canvas, a theme wrapper, and anything a record writes without a `modifier`
   * parameter have no chain to carry one, and are not tagged. Nodes inside a component definition
   * carry the definition's node ids, which every placement shares.
   */
  private val tagNodes: Boolean = false,
) {
  /**
   * What the written source carries but cannot say for itself: a dependency it needs, a setting the
   * call has no parameter for, a layout that throws when captured. Unlike [refusals] these do not
   * stop the export; the caller hands them on as the artifact's diagnostics, so an author is told
   * before the compiler or the device does.
   */
  val diagnostics: List<ExportDiagnosticV1>
    get() = collectedDiagnostics.distinct()

  private val collectedDiagnostics = mutableListOf<ExportDiagnosticV1>()

  /**
   * The caller's record layered over the embedded [RemoteMaterial3.records]; the caller's wins, so
   * built-in palette widgets export without a host-supplied record.
   */
  private val components: Map<String, ComponentRecord> = RemoteMaterial3.records + components

  /** True once a colour or type token has been written, which only reads inside a theme. */
  var usesTheme: Boolean = false
    private set

  private var usesRemoteFontFamily = false

  /** What the last [themeTypography] wrote, for the generator that opens the theme wrapper. */
  var typography: List<String>? = null
    private set

  /**
   * The widget's typography as the lines of a `typography = …` argument to `RemoteMaterialTheme`,
   * or null when [host] names no typeface. See [ThemeTypefaces].
   *
   * Every role is re-pointed explicitly because `defaultFontFamily` only fills roles without a
   * family. Families go in by name (`RemoteFontFamily.Named("google:…")`); the player resolves
   * them.
   */
  fun themeTypography(host: UiBuilderNode, depth: Int): List<String>? {
    val roles = ThemeTypefaces.wearRoleFamilies(ThemeTypefaces.families(host))
    if (roles.isEmpty()) return null
    usesTheme = true
    usesRemoteFontFamily = true
    val pad = INDENT.repeat(depth)
    return (listOf("${pad}typography =", "$pad${INDENT}RemoteMaterialTheme.typography.run {") +
        listOf("$pad$INDENT${INDENT}copy(") +
        roles.map { (role, family) ->
          val named =
            "RemoteFontFamily.Named(${"google:${ThemeTypefaces.familyName(family)}".quoted()})"
          "$pad$INDENT$INDENT$INDENT$role = $role.copy(fontFamily = $named),"
        } +
        listOf("$pad$INDENT$INDENT)", "$pad$INDENT},"))
      .also { typography = it }
  }

  /** What the last [themeTextStyle] read: the Wear role text with no `style` is set in. */
  var textRole: String? = null
    private set

  /**
   * The role [host] sets text with no `style` of its own in (see [ThemeTextStyle]), or null when it
   * names none. Reading one puts the widget under `RemoteMaterialTheme`, inside which
   * [provideTextStyle] provides it.
   */
  fun themeTextStyle(host: UiBuilderNode): String? =
    ThemeTextStyle.role(host)?.let(ThemeTextStyle::wearRole)?.also {
      usesTheme = true
      textRole = it
    }

  /**
   * [body] under the role [themeTextStyle] read, as `ProvideRemoteTextStyle(…typography.<role>) { …
   * }`; unchanged when there is none. Replaces `RemoteMaterialTheme`'s `bodyLarge` default. The
   * call is `@RestrictTo`, covered by the file's `@file:Suppress("RestrictedApi")`.
   */
  fun provideTextStyle(body: List<String>, depth: Int): List<String> {
    val role = textRole ?: return body
    val pad = INDENT.repeat(depth)
    return listOf("${pad}ProvideRemoteTextStyle(RemoteMaterialTheme.typography.$role) {") +
      body.map { if (it.isEmpty()) it else INDENT + it } +
      listOf("$pad}")
  }

  private var usesMaterialText = false

  /**
   * `remote-creation-compose`'s own `RemoteText`, written under [RemoteTextVocabulary.CREATION].
   */
  private var usesCreationText = false

  /**
   * Whether the body used anything only `remote-material3` provides (theme roles, ambient text
   * style). A catalog without that library refuses the design.
   */
  val usesMaterial: Boolean
    get() =
      usesTheme ||
        usesLocalTextStyle ||
        usesRemoteColorScheme ||
        usedComponentImports.any { it.startsWith("androidx.wear.compose.remote.material3.") }

  private var usesColumn = false
  private var usesRow = false
  private var usesBox = false
  private var usesStateLayout = false
  private var usesValueChange = false
  private var usesArrangement = false
  private var usesAlignment = false
  private var usesDp = false
  private var usesTextAlign = false
  private var usesTextOverflow = false
  private var usesFontVariation = false
  private var usesLocalTextStyle = false
  private var usesRemoteBoolean = false
  private var usesRemoteInt = false
  private var usesLambdaAction = false
  private var usesCombinedAction = false

  /** Callables the record-driven fallback wrote, so their imports are the ones it used. */
  private val usedComponentImports = mutableSetOf<String>()

  /**
   * The document state variables emitted actions write to, as declarations. Collected while
   * emitting and read back through [stateLocals], since they are only known afterwards.
   */
  private val stateWrites = linkedMapOf<String, String>()

  /** `val <name> = rememberMutableRemote…(<initial>)` for each variable an action wrote to. */
  fun stateLocals(): List<String> = stateWrites.values.toList()

  /**
   * The body for a widget container with an empty content slot: a box that fills the frame. On the
   * emitter so its imports are recorded in [imports].
   */
  fun emptyBox(depth: Int): String {
    usesBox = true
    usesModifier = true
    usedModifierImports += "fillMaxSize"
    return "${INDENT.repeat(depth)}RemoteBox(modifier = RemoteModifier.fillMaxSize())"
  }

  /** The `WearWidgetBrush` chain a container's background declares. */
  data class Background(
    val expression: String,
    val locals: List<String>,
    /** The chain's calls in order, so a caller past its column budget can break between them. */
    val elements: List<String> = emptyList(),
  )

  fun background(container: UiBuilderNode): Background {
    val locals = mutableListOf<String>()
    val elements = mutableListOf<String>()
    val declared = container.properties["background"]?.stringOrNull().orEmpty()
    if (declared.isNotEmpty()) {
      // A theme token cannot be read here: `provideWidgetData` is not composition. The sample
      // instantiates the scheme for exactly this reason, so the generated code does too.
      if (!declared.startsWith("#")) {
        locals += "val colorScheme = RemoteColorScheme()"
        elements += "color(colorScheme.$declared)"
        usesRemoteColorScheme = true
        usesBrushColor = true
      } else {
        usesColorLiteral = true
        usesBrushColor = true
        elements += "color(${declared.argbLiteral()}.rc)"
      }
    }
    container.slots["background"].orEmpty().forEach { id ->
      val node = document.nodes[id] ?: return@forEach
      // A brush is a fill with nowhere to hang size, alpha, offset or rotation, yet the canvas
      // applies them to a background node — so such modifiers are refused rather than silently
      // dropped. `testTag` draws nothing and is the exception.
      val drawing =
        node.modifiers
          .mapNotNull { (it as? JsonObject)?.get("type")?.stringValue() }
          .filter { it != "testTag" }
          .distinct()
      if (drawing.isNotEmpty()) {
        val plural = drawing.size > 1
        refusals +=
          "the widget background `$id` carries the modifier${if (plural) "s" else ""} " +
            drawing.joinToString(", ") { "`$it`" } +
            " — a widget background is a `WearWidgetBrush` filling the whole frame, with no " +
            "geometry to apply ${if (plural) "them" else "it"} to; clear " +
            "${if (plural) "them" else "it"} here, or move the node into the content slot where " +
            "it is laid out"
        return@forEach
      }
      when (node.componentId) {
        "shape/linear-gradient" -> {
          val start = node.properties["startColor"]?.stringOrNull().orEmpty()
          val end = node.properties["endColor"]?.stringOrNull().orEmpty()
          if (!start.startsWith("#") || !end.startsWith("#")) {
            refusals +=
              "the gradient background `$id` uses a theme token; a widget background is built " +
                "outside composition, so its colours have to be literals"
            return@forEach
          }
          usesColorLiteral = true
          val stops = "listOf(${start.argbLiteral()}.rc, ${end.argbLiteral()}.rc)"
          val reversed = "listOf(${end.argbLiteral()}.rc, ${start.argbLiteral()}.rc)"
          elements +=
            when (node.properties["direction"]?.stringOrNull()) {
              // `horizontal`/`vertical` are what the widget templates write; read identically in
              // the canvas so an unknown direction does not fall through to `else`.
              "leftToRight",
              "horizontal" -> horizontal("horizontalGradient($stops)")
              "rightToLeft" -> horizontal("horizontalGradient($reversed)")
              "bottomToTop" -> vertical("verticalGradient($reversed)")
              "topToBottom",
              "vertical",
              null,
              "" -> vertical("verticalGradient($stops)")
              // Refused rather than redrawn, for the same reason: a direction nobody reads should
              // say so instead of picking an axis.
              else -> {
                refusals +=
                  "the gradient `$id` names the direction " +
                    "`${node.properties["direction"]?.stringOrNull()}`, which is not one this " +
                    "generator or the canvas draws"
                return@forEach
              }
            }
        }
        // The launcher draws the document out of the app's process, without its resources, so the
        // pixels must be in the document: standalone source inlines them; the bundle export
        // resolves a path (`docs/design/UI_BUILDER_EXPORT_BUNDLE.md`).
        "asset/image" -> {
          val key = node.properties["assetKey"]?.stringOrNull().orEmpty()
          when (val identifier = backgroundBitmap(key)) {
            null ->
              refusals +=
                "the image background `$id` names " +
                  (if (key.isEmpty()) "no asset"
                  else "the asset `$key`, whose bytes this export " + "could not read") +
                  " — pick a picture for it in the inspector"
            else -> {
              usesBrushImage = true
              elements += "image($identifier)"
            }
          }
        }
        else -> refusals += "`${node.componentId}` is not a widget background brush"
      }
    }
    // The empty chain is a real value upstream: `WearWidgetBrush` alone is what a widget passes to
    // accept the host's default fill.
    val expression =
      if (elements.isEmpty()) "WearWidgetBrush"
      else elements.joinToString(".", prefix = "WearWidgetBrush.")
    return Background(expression, locals, elements)
  }

  private var usesRemoteColorScheme = false
  var usesBrushImage = false
    private set

  private var usesColorLiteral = false
  private var usesBrushColor = false

  /** The node and its subtree, as indented source lines. */
  fun emit(nodeId: String, depth: Int): List<String> {
    if (activeNodes.size >= 128 || !activeNodes.add(nodeId)) {
      refusals += "nodes.$nodeId: cyclic reference or nesting exceeds 128 levels"
      return emptyList()
    }
    try {
      return emitNode(nodeId, depth)
    } finally {
      activeNodes.remove(nodeId)
    }
  }

  private val activeNodes = mutableSetOf<String>()

  private fun emitNode(nodeId: String, depth: Int): List<String> {
    val node =
      document.nodes[nodeId]
        ?: run {
          refusals += "nodes.$nodeId: missing node"
          return emptyList()
        }
    if (
      !UiBuilderBuildFeatures.remoteCompose &&
        (SHOW_BY_STATE in node.properties || node.componentId == "layout/for-each")
    ) {
      refusals += "nodes.$nodeId: Remote Compose authoring is disabled in this build"
      return emptyList()
    }
    validatePropertyBindings(node)
    node.component?.let { placement ->
      return placement(node, placement, depth)
    }
    if (SHOW_BY_STATE in node.properties) return stateSelection(node, depth)
    val pad = INDENT.repeat(depth)
    return when (node.componentId) {
      "m3/text" -> (pad + text(node, pad)).split("\n")
      // RemoteText is a fixed Remote Material 3 API, not a discovered component, so no record is
      // required. Catalog validation decides whether the node may be authored.
      REMOTE_TEXT_COMPONENT_ID,
      LAUNCHER_WIDGET_TEXT_COMPONENT_ID -> (pad + text(node, pad)).split("\n")
      "layout/box" -> container(node, depth, "RemoteBox", boxArguments(node, pad))
      "layout/column" -> container(node, depth, "RemoteColumn", columnArguments(node, pad))
      "layout/row" -> container(node, depth, "RemoteRow", rowArguments(node, pad))
      // `RemoteFitBox`: its children are alternatives, largest first, and the player shows the
      // first that fits the host. In the Glance Wear widget profile, unlike its siblings below.
      "layout/fit-box" -> container(node, depth, "RemoteFitBox", fitBoxArguments(node, pad))
      // Glance Wear's `GlanceWearProfiles` does not admit LAYOUT_FLOW or LAYOUT_COLLAPSIBLE_*, so
      // these compile but fail the native render; the comment above the generated call says so,
      // and inside a widget so does an error on [diagnostics].
      "layout/flow-row" ->
        outsideWidgetProfile(node, depth, "LAYOUT_FLOW", "RemoteFlowRow") +
          container(node, depth, "RemoteFlowRow", flowRowArguments(node, pad))
      "layout/collapsible-column" ->
        outsideWidgetProfile(node, depth, "LAYOUT_COLLAPSIBLE_COLUMN", "RemoteCollapsibleColumn") +
          container(
            node,
            depth,
            "RemoteCollapsibleColumn",
            collapsibleArguments(node, pad, vertical = true),
          )
      "layout/collapsible-row" ->
        outsideWidgetProfile(node, depth, "LAYOUT_COLLAPSIBLE_ROW", "RemoteCollapsibleRow") +
          container(
            node,
            depth,
            "RemoteCollapsibleRow",
            collapsibleArguments(node, pad, vertical = false),
          )
      "layout/for-each" -> repetition(node, depth)
      UiDrawing.CANVAS -> drawCanvas(node, depth)
      UiTimeText.ID -> timeText(node, pad)?.split("\n") ?: emptyList()
      UiRemoteTheme.ID -> materialTheme(node, depth)
      in UiDrawing.BY_ID ->
        emptyList<String>().also {
          refusals +=
            "the draw operation `${node.id}` draws only inside a ${UiDrawing.CANVAS}'s " +
              "`${UiDrawing.OPS_SLOT}` slot; place it in a canvas"
        }
      "remote-m3/lottie" -> lottie(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      // Ahead of the record fallback, which has no spelling for `RemoteIcon`'s `ImageVector`.
      REMOTE_ICON_COMPONENT_ID -> icon(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      REMOTE_HORIZONTAL_PAGE_INDICATOR_ID,
      REMOTE_VERTICAL_PAGE_INDICATOR_ID -> pageIndicator(node, pad)
      "asset/image" -> image(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      // A gradient is a widget-frame brush rather than Remote Compose content. It stays in the
      // catalog for the container's `background` slot but cannot enter a widget body.
      "shape/linear-gradient" ->
        emptyList<String>().also {
          refusals +=
            "the gradient `${node.id}` is a brush rather than content; put it in the widget " +
              "container's background slot, where it is written as a WearWidgetBrush chain"
        }
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID ->
        customComponent(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      REMOTE_COMPOSE_INLINE_COMPONENT_ID ->
        emptyList<String>().also {
          refusals +=
            "`${node.id}` switches into the Remote Compose vocabulary inside content that is " +
              "already written in it; drop its children into the enclosing remote content instead"
        }
      "remote-compose/document" ->
        emptyList<String>().also {
          refusals +=
            "the embedded document `${node.id}` is bytes rather than source, and a captured " +
              "document cannot be nested inside one being written from source here"
        }
      else -> recordCall(node, depth) ?: refuseUnknown(node)
    }
  }

  private class BoundField(
    val name: String,
    val type: String,
    val convert: (JsonElement) -> String?,
  )

  private class Bindings(val row: String? = null) {
    val fields = linkedMapOf<String, BoundField>()
  }

  private var bindings: Bindings? = null
  private var function: FunctionBody? = null
  private val functions = linkedMapOf<String, FunctionBody>()
  private val defining = mutableSetOf<String>()
  private var loopCount = 0

  private inner class FunctionBody(val name: String) {
    val bindings = Bindings()
    val captures = linkedMapOf<String, Capture>()
    /** Action-free events, as `Action` parameters the caller supplies: `onCardClick`. */
    val intents = linkedMapOf<String, String>()
    var body = emptyList<String>()

    fun capture(key: String, type: String, supply: () -> String?): String =
      captures.getOrPut(key) { Capture("capture${captures.size}", type, supply) }.name

    fun declaration(): String {
      val parameters =
        listOf("modifier: androidx.compose.remote.creation.compose.modifier.RemoteModifier") +
          bindings.fields.values.map { "${it.name}: ${it.type}" } +
          captures.values.map { "${it.name}: ${it.type}" } +
          intents.values.map { "$it: $ACTION_FQN" }
      return listOf(
          "@Composable",
          "@RemoteComposable",
          "$componentVisibility fun $name(${parameters.joinToString()}) {",
          "    RemoteBox(modifier = modifier) {",
        )
        .plus(body)
        .plus(listOf("    }", "}"))
        .joinToString("\n")
    }
  }

  private class Capture(val name: String, val type: String, val supply: () -> String?)

  private fun bound(
    value: JsonElement,
    type: String,
    where: String,
    convert: (JsonElement) -> String?,
  ): String? {
    val raw = value as? JsonObject
    if (raw?.plainString("type") != "binding") return convert(value)
    if (
      type !in
        setOf(
          "kotlin.String",
          "kotlin.Boolean",
          "kotlin.Int",
          "kotlin.Float",
          REMOTE_STRING_FQN,
          REMOTE_BOOLEAN_FQN,
          REMOTE_FLOAT_FQN,
          REMOTE_DP_FQN,
          REMOTE_COLOR_FQN,
        )
    ) {
      refusals += "$where: unsupported binding type `$type`"
      return null
    }
    val key = raw.plainString("value")
    val current = bindings
    if (key == null || raw.keys != setOf("type", "value") || current == null) {
      refusals += "$where: missing or invalid lexical binding"
      return null
    }
    val field =
      current.fields.getOrPut(key) { BoundField(parameterName(key, current), type, convert) }
    if (field.type != type) {
      refusals += "$where: binding `$key` is used as both ${field.type} and $type"
      return null
    }
    return current.row?.let { "$it.${field.name}" } ?: field.name
  }

  /**
   * A component parameter's name: its binding key when [namedBindings] and the key is a plain
   * Kotlin identifier no other parameter takes, else the numbered `argumentN` (always, inside a
   * loop row).
   */
  private fun parameterName(key: String, current: Bindings): String {
    if (!namedBindings || current.row != null) return "argument${current.fields.size}"
    val taken = current.fields.values.map { it.name }.toSet() + "modifier"
    // The emitter's own spellings (`argumentN`, and `captureN` for captures) are never a key's
    // name,
    // so a named key cannot collide with a fallback or a capture parameter.
    val named = key.takeIf {
      it.matches(Regex("[a-z][A-Za-z0-9]*")) &&
        it !in KOTLIN_HARD_KEYWORDS &&
        it !in taken &&
        !it.matches(GENERATED_PARAMETER)
    }
    return named
      ?: generateSequence(current.fields.size) { it + 1 }
        .map { "argument$it" }
        .first { it !in taken }
  }

  /** `argumentN` and `captureN`, and an intent's `onXEvent` ([intentParameter]). */
  private val GENERATED_PARAMETER = Regex("(argument|capture)[0-9]+|on[A-Z][A-Za-z0-9]*")

  fun validateFunctionNames(vararg topLevelNames: String) {
    val reserved =
      imageAssets.values +
        topLevelNames.toSet() +
        imports(null).map { it.substringAfterLast('.') } +
        document.stateVariables.keys.map { it.remoteIdentifier() }
    functions.values.forEach {
      if (
        it.name in reserved ||
          it.name == "modifier" ||
          it.name.matches(Regex("(argument|capture)[0-9]+"))
      )
        refusals += "component function `${it.name}` conflicts with a generated Kotlin identifier"
    }
  }

  private fun captureValue(key: String, type: String, supply: () -> String): String =
    function?.capture(key, type) { captureValue(key, type, supply) } ?: supply()

  private val allocatedLocals = mutableSetOf<String>()

  private fun localName(base: String): String {
    val reserved =
      document.stateVariables.keys.map { it.remoteIdentifier() }.toSet() +
        document.components.values.mapNotNull { (it as? JsonObject)?.plainString("name") } +
        allocatedLocals
    var name = base
    while (name in reserved) name += "_"
    allocatedLocals += name
    return name
  }

  private fun validatePropertyBindings(node: UiBuilderNode) {
    val supported =
      when (node.componentId) {
        "layout/row" -> setOf("horizontalSpacingDp")
        "layout/column",
        "layout/for-each" -> setOf("verticalSpacingDp")
        "m3/text",
        REMOTE_TEXT_COMPONENT_ID,
        LAUNCHER_WIDGET_TEXT_COMPONENT_ID -> setOf("text")
        else -> components[node.componentId]?.parameters?.map { it.name }?.toSet().orEmpty()
      }
    node.properties.forEach { (key, value) ->
      if ((value as? JsonObject)?.plainString("type") == "binding" && key !in supported)
        refusals += "nodes.${node.id}.$key: Remote binding lowering is not available"
    }
  }

  private fun placement(node: UiBuilderNode, placement: JsonObject, depth: Int): List<String> {
    validateLayoutEvents(node)
    val key = placement.plainString("componentKey")
    val definition = key?.let { document.components[it] as? JsonObject }
    val root = definition?.plainString("root")
    val name = definition?.plainString("name")
    val supplied = placement["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    if (key == null || root == null || name == null || root !in document.nodes) {
      refusals +=
        "nodes.${node.id} places `${key ?: "unnamed"}`: missing component definition or root"
      return emptyList()
    }
    if (
      node.componentId != "design/component-instance" ||
        node.properties.isNotEmpty() ||
        node.slots.isNotEmpty() ||
        placement.keys.any { it !in setOf("componentKey", "arguments") } ||
        (placement["arguments"] != null && placement["arguments"] !is JsonObject)
    ) {
      refusals += "nodes.${node.id}: invalid component placement"
      return emptyList()
    }
    if (
      !name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) ||
        name.all { it == '_' } ||
        name in KOTLIN_HARD_KEYWORDS ||
        document.components.any { (other, value) ->
          other != key && (value as? JsonObject)?.plainString("name") == name
        }
    ) {
      refusals += "component `$key`: invalid or duplicate Kotlin function name `$name`"
      return emptyList()
    }
    if (key in defining) {
      refusals += "component `$key`: recursive definition"
      return emptyList()
    }
    val target =
      functions[key]
        ?: run {
          val created = FunctionBody(name)
          functions[key] = created
          defining += key
          val previousBindings = bindings
          val previousFunction = function
          val previousScope = scope
          val previousArguments = placementArguments
          bindings = created.bindings
          function = created
          placementArguments = supplied
          scope = "RemoteBox"
          usesBox = true
          try {
            created.body = emit(root, 2)
          } finally {
            bindings = previousBindings
            function = previousFunction
            placementArguments = previousArguments
            scope = previousScope
            defining -= key
          }
          if (name in imports(null).map { it.substringAfterLast('.') })
            refusals += "component `$key`: name `$name` conflicts with an imported symbol"
          created
        }
    (supplied.keys - target.bindings.fields.keys).forEach {
      refusals += "nodes.${node.id}: unknown component argument `$it`"
    }
    val arguments = mutableListOf<String>()
    target.bindings.fields.forEach { (key, field) ->
      val value = supplied[key]
      if (value == null) refusals += "nodes.${node.id}: missing component argument `$key`"
      else
        bound(value, field.type, "nodes.${node.id}.arguments.$key", field.convert)?.let {
          arguments += "${field.name} = $it"
        }
    }
    target.captures.values.forEach { capture ->
      capture.supply()?.let { arguments += "${capture.name} = $it" }
    }
    target.intents.forEach { (key, intent) ->
      arguments += "$intent = ${forwardedIntent(node, key, intent)}"
    }
    val wrapper = node.copy(componentId = "layout/box", component = null)
    val modifier = wrapper.modifierExpression(INDENT.repeat(depth)) ?: "RemoteModifier"
    usesModifier = true
    arguments.add(0, "modifier = $modifier")
    return (INDENT.repeat(depth) + call(name, arguments, INDENT.repeat(depth))).split("\n")
  }

  private fun repetition(node: UiBuilderNode, depth: Int): List<String> {
    validateLayoutEvents(node)
    val where = "nodes.${node.id}"
    val template = node.slots["template"]?.singleOrNull()
    val data = node.properties["data"] as? JsonObject
    val rows = data?.get("values") as? JsonArray
    if (
      template == null ||
        node.slots.keys != setOf("template") ||
        rows == null ||
        data.plainString("type") != "list" ||
        data.keys != setOf("type", "values") ||
        node.properties.keys.any { it !in setOf("data", "verticalSpacingDp") }
    ) {
      refusals += "$where: a loop needs an authored row list and exactly one template child"
      return emptyList()
    }
    if (rows.size > 10000) {
      refusals += "$where: exceeds 10000 rows"
      return emptyList()
    }
    val number = loopCount++
    val rowName = localName("uiRow$number")
    val rowClass = localName("UiRows$number")
    val rowBindings = Bindings(rowName)
    val previousBindings = bindings
    val previousScope = scope
    bindings = rowBindings
    scope = "RemoteColumn"
    val body =
      try {
        emit(template, depth + 2)
      } finally {
        bindings = previousBindings
        scope = previousScope
      }
    val fields = rows.mapIndexed { index, value ->
      val row = value as? JsonObject
      val fields = row?.get("fields") as? JsonObject
      if (
        row?.plainString("type") != "object" ||
          row.keys != setOf("type", "fields") ||
          fields == null
      ) {
        refusals += "$where.data[$index]: expected a row object"
        JsonObject(emptyMap())
      } else fields
    }
    fields.forEach { record ->
      record.forEach { (key, value) ->
        if (key !in rowBindings.fields) {
          val type =
            when ((value as? JsonObject)?.plainString("type")) {
              "string" -> "kotlin.String"
              "bool" -> "kotlin.Boolean"
              "int" -> "kotlin.Int"
              "float" -> "kotlin.Float"
              else -> null
            }
          if (type == null) refusals += "$where.data.$key: cannot infer an unused field type"
          else
            rowBindings.fields[key] =
              BoundField("argument${rowBindings.fields.size}", type) {
                primitiveArgument(it, type, "$where.data.$key")
              }
        }
      }
    }
    val values = fields.mapIndexed { index, record ->
      val values =
        rowBindings.fields.mapNotNull { (key, field) ->
          val raw = record[key]
          if (raw == null) {
            refusals += "$where.data[$index]: missing row field `$key`"
            null
          } else bound(raw, field.type, "$where.data[$index].$key", field.convert)
        }
      "$rowClass(${values.joinToString()})"
    }
    val wrapper =
      node.copy(
        componentId = "layout/column",
        properties = JsonObject(node.properties - "data"),
        slots = emptyMap(),
      )
    val args = columnArguments(wrapper, INDENT.repeat(depth))
    usesColumn = true
    val pad = INDENT.repeat(depth)
    val parameters = rowBindings.fields.values.joinToString { "val ${it.name}: ${it.type}" }
    val declaration =
      if (parameters.isEmpty()) "class $rowClass" else "data class $rowClass($parameters)"
    return (pad + call("RemoteColumn", args, pad, trailing = 2) + " {").split("\n") +
      listOf(
        "$pad$INDENT$declaration",
        "$pad${INDENT}kotlin.collections.listOf<$rowClass>(${values.joinToString()}).forEach { $rowName ->",
      ) +
      body +
      listOf("$pad$INDENT}", "$pad}")
  }

  private fun validateLayoutEvents(node: UiBuilderNode) {
    node.eventBindings.forEach { (event, actions) ->
      if (event !in LAYOUT_EVENTS || actions !is JsonArray)
        refusals +=
          "nodes.${node.id}.eventBindings.$event: expected an action list for one of " +
            LAYOUT_EVENTS.joinToString()
    }
  }

  private fun primitiveArgument(value: JsonElement, type: String, where: String): String? {
    val raw = value as? JsonObject
    val kind = raw?.plainString("type")
    val literal = raw?.get("value") as? JsonPrimitive
    val result =
      when (type) {
        "kotlin.Float" ->
          literal
            ?.takeIf { kind in setOf("int", "float") && !it.isString }
            ?.floatOrNull
            ?.takeIf { it.isFinite() }
            ?.let { "${it}f" }
        "kotlin.Int" -> literal?.takeIf { kind == "int" && !it.isString }?.intOrNull?.toString()
        "kotlin.Boolean" ->
          literal?.takeIf { kind == "bool" && !it.isString }?.booleanOrNull?.toString()
        "kotlin.String" ->
          literal?.takeIf { kind == "string" && it.isString }?.let { "\"${it.content.escaped()}\"" }
        else -> null
      }
    if (result == null) refusals += "$where: expected $type"
    return result
  }

  private fun refuseUnknown(node: UiBuilderNode): List<String> {
    refusals += "`${node.componentId}` has no Remote Compose counterpart this generator can write"
    return emptyList()
  }

  /**
   * A call written from the component record, for components with no hand-written case. Each
   * parameter maps the design's value to the record's Remote type, refusing by name otherwise.
   *
   * Rules, each a refusal rather than a guess:
   * - one content slot, and it must be last (a trailing lambda);
   * - every required parameter, or none of it;
   * - an unauthored `onClick` is `lambdaAction {}`.
   *
   * Spellings are compile-verified in wear-m3-catalog (`RemoteValueVocabularyProbe`).
   */
  private fun recordCall(node: UiBuilderNode, depth: Int): List<String>? {
    val record = components[node.componentId] ?: return null
    // The same callable-level guards as `ComponentSnippets.callSite`: a recovered signature is not
    // necessarily a reachable call site (e.g. non-public), and no design value can fix that.
    val inaccessible =
      when {
        !record.callableFromAnotherFile ->
          "is not public or internal, so a generated file cannot call it"
        record.overloadsCollided ->
          "has overloads that collided under one id, so no single call site identifies one"
        record.hasTypeParameters ->
          "declares type parameters a call omitting defaulted arguments cannot infer"
        record.hasContextReceivers -> "declares a context a generated widget cannot supply"
        record.symbol.receiver != null ->
          "is declared on ${record.symbol.receiver}, so a call needs that scope around it"
        else -> null
      }
    if (inaccessible != null) {
      refusals += "`${node.componentId}` $inaccessible"
      return emptyList()
    }
    if (!record.signatureKnown) {
      refusals +=
        "`${node.componentId}` has no recovered signature, so no call to it can be written"
      return emptyList()
    }
    val pad = INDENT.repeat(depth)
    val slotParameters = record.parameters.filter { it.composableSlot }

    // Children fill the slot named after them; `children` is accepted only when there is exactly
    // one slot.
    val single = slotParameters.singleOrNull()
    val filled = slotParameters.mapNotNull { parameter ->
      val ids =
        node.slots[parameter.name] ?: if (parameter == single) node.slots["children"] else null
      ids?.takeIf { it.isNotEmpty() }?.let { parameter to it }
    }
    val strayChildren =
      node.slots["children"].orEmpty().isNotEmpty() && (single == null || filled.isEmpty())
    if (strayChildren) {
      refusals +=
        if (slotParameters.isEmpty()) {
          "`${node.componentId}` has children in the design and no content slot to put them in"
        } else {
          "`${node.componentId}` takes ${slotParameters.size} content slots " +
            "(${slotParameters.joinToString { it.name }}) and the design names none of them, so " +
            "which slot each child belongs in is not recoverable"
        }
      return emptyList()
    }

    // The indeterminate circular indicator is the overload without `progress`: the record recovers
    // only the determinate one, so the call keeps the parameters the two share.
    val indeterminate = node.properties[INDETERMINATE_PROPERTY]?.boolOrNull() == true
    if (indeterminate && record.symbol.callable != REMOTE_CIRCULAR_PROGRESS_FQN) {
      refusals +=
        "nodes.${node.id}.$INDETERMINATE_PROPERTY: only `RemoteCircularProgressIndicator` has an " +
          "indeterminate overload"
      return emptyList()
    }
    val parameters =
      if (!indeterminate) record.parameters
      else {
        val dropped =
          node.properties.keys.filter {
            it in record.parameters.map(TargetParameter::name) &&
              it !in INDETERMINATE_CIRCULAR_PARAMETERS
          }
        if (dropped.isNotEmpty()) {
          refusals +=
            "nodes.${node.id}: the indeterminate indicator takes no ${dropped.joinToString { "`$it`" }}"
          return emptyList()
        }
        record.parameters.filter { it.name in INDETERMINATE_CIRCULAR_PARAMETERS }
      }
    val arguments = mutableListOf<String>()
    val blocks = mutableListOf<Pair<String, List<String>>>()
    for (parameter in parameters) {
      if (parameter.composableSlot) {
        val children = filled.firstOrNull { it.first == parameter }?.second
        when {
          children != null -> blocks += parameter.name to children
          // A required slot with no children is an empty lambda: omitting it does not compile.
          !parameter.hasDefault -> blocks += parameter.name to emptyList()
        }
        continue
      }
      val authored = node.properties[parameter.name]
      val expression =
        when {
          parameter.name == MODIFIER_PARAMETER ->
            node.modifierExpression(
              pad,
              modifierClick =
                record.parameters.none { it.name == "onClick" && it.typeFqn == ACTION_FQN },
            )
          authored != null -> remoteValue(node, parameter, authored)
          parameter.typeFqn == REMOTE_BORDER_STROKE_FQN && node.has(BORDER_COLOR_PROPERTY) ->
            borderStroke(node, parameter) ?: return emptyList()
          REMOTE_COLOR_BUNDLES[parameter.typeFqn]?.roles?.any { node.has(it) } == true ->
            colorBundle(node, REMOTE_COLOR_BUNDLES.getValue(parameter.typeFqn.orEmpty()))
              ?: return emptyList()
          parameter.typeFqn == ACTION_FQN &&
            (!parameter.hasDefault ||
              eventActions(
                  node,
                  parameter.name.removePrefix("on").replaceFirstChar { it.lowercaseChar() },
                )
                .isNotEmpty()) -> actionExpression(node, parameter)
          parameter.hasDefault -> null
          // Nullable with no default: optional to the design, mandatory to Kotlin, so write `null`.
          parameter.nullable -> "null"
          else -> null
        }
      if (expression == null) {
        if (!parameter.hasDefault && parameter.name != MODIFIER_PARAMETER) {
          refusals +=
            "`${node.componentId}` requires `${parameter.name}: ${parameter.type}` and the " +
              "design carries no value this generator can write as one"
          return emptyList()
        }
        continue
      }
      arguments += "${parameter.name} = $expression"
    }

    // The painter overload: a picture behind the button or card, chosen by naming its parameter.
    if (node.componentId in REMOTE_CONTAINER_PAINTER_IDS) {
      node.properties[REMOTE_CONTAINER_IMAGE_PROPERTY]
        ?.stringOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { key ->
          val bitmap =
            imageBitmap(node, key, "the container image of `${node.id}`") ?: return emptyList()
          usedComponentImports +=
            "androidx.compose.remote.creation.compose.painter.painterRemoteImageBitmap"
          arguments += "containerPainter = painterRemoteImageBitmap($bitmap)"
        }
    }
    usedComponentImports += record.symbol.callable
    val symbol = record.symbol.name
    if (blocks.isEmpty()) return (pad + call(symbol, arguments, pad)).split("\n")

    // The last parameter's slot is a trailing lambda; others are named lambda arguments.
    val trailingName =
      record.parameters
        .lastOrNull()
        ?.takeIf { it.composableSlot }
        ?.name
        ?.takeIf { name -> blocks.any { it.first == name } }
    val named = blocks.filterNot { it.first == trailingName }
    val lines = mutableListOf<String>()
    val headArguments = arguments.toMutableList()
    if (named.isEmpty() && headArguments.size <= 1) {
      lines +=
        pad +
          (if (headArguments.isEmpty()) "$symbol {"
          else "${call(symbol, headArguments, pad, trailing = OPENING_BRACE.length)}$OPENING_BRACE")
    } else {
      lines += "$pad$symbol("
      headArguments.forEach { lines += "$pad$INDENT$it," }
      named.forEach { (name, ids) ->
        lines += "$pad$INDENT$name = {"
        lines +=
          inSlotScope(slotParameters.first { it.name == name }) {
            ids.flatMap { emit(it, depth + 2) }
          }
        lines += "$pad$INDENT},"
      }
      lines += if (trailingName == null) "$pad)" else "$pad)$OPENING_BRACE"
    }
    if (trailingName != null) {
      val slot = slotParameters.first { it.name == trailingName }
      lines +=
        inSlotScope(slot) {
          blocks.first { it.first == trailingName }.second.flatMap { emit(it, depth + 1) }
        }
      lines += "$pad}"
    }
    return lines
  }

  /**
   * What a design's event binding becomes, or `lambdaAction {}` when it binds nothing. Every action
   * is a state write, so all become one `valueChange` (verified by wear-m3-catalog's
   * `RemoteActionVocabularyProbe`). The event key is the parameter without `on`.
   *
   * Refused: `selectOrClear` (assigns null), undeclared variables, and `toggle` on a non-boolean.
   */
  private fun actionExpression(node: UiBuilderNode, parameter: TargetParameter): String? {
    val event = parameter.name.removePrefix("on").replaceFirstChar { it.lowercaseChar() }
    val actions = eventActions(node, event)
    if (actions.isEmpty()) return intentParameter(node, event) ?: lambdaActionExpression()
    // A two-way write back leads the authored actions; paths still name the authored index.
    val leading = actions.size - (node.eventBindings[event] as? JsonArray).orEmpty().size
    val operands = linkedMapOf<Int, ActionOperand>()
    actions.forEachIndexed { index, element ->
      val action = element as? JsonObject ?: return@forEachIndexed
      val value = action["value"] as? JsonObject ?: return@forEachIndexed
      if (value.plainString("type") != "binding") return@forEachIndexed
      if (!UiBuilderBuildFeatures.remoteCompose) {
        refusals +=
          "nodes.${node.id}.eventBindings.$event[${index - leading}]: bound actions are disabled in this build"
        return null
      }
      val where = "nodes.${node.id}.eventBindings.$event[${index - leading}].value"
      val variable = action.plainString("variable")
      val kind = (document.stateVariables[variable] as? JsonObject)?.plainString("valueType")
      val type =
        when (kind) {
          "int" -> "kotlin.Int"
          "float" -> "kotlin.Float"
          "bool" -> "kotlin.Boolean"
          "string" -> "kotlin.String"
          else -> null
        }
      if (action.plainString("type") !in setOf("set", "select", "setText") || type == null) {
        refusals += "$where: a bound action value needs an assignment to declared scalar state"
        return null
      }
      val expression =
        bound(value, type, where) { primitiveArgument(it, type, where) } ?: return null
      operands[index] = ActionOperand(type, kind!!, expression, localName("actionValue$index"))
    }
    function?.let { current ->
      if (operands.isNotEmpty()) {
        val factory = actionFactory(node, event, actions, operands) ?: return null
        return "$factory(${operands.values.joinToString { it.expression }})"
      }
      return current.capture("event:${node.id}:$event", ACTION_FQN) {
        actionExpression(node, parameter)
      }
    }
    return actionSequence(
      node,
      event,
      actions,
      operands.mapValues { (_, operand) -> remoteOperand(operand.kind, operand.expression) },
    )
  }

  /**
   * [event]'s authored actions, led by the write back of a two-way bound flag.
   *
   * When `checked` reads a declared `bool` that the actions do not already write
   * ([TwoWayStateBinding]), `checkedChange` negates it first — the Remote equivalent of
   * `onCheckedChange = { x = it }`, since a Remote action carries no event value.
   */
  private fun eventActions(node: UiBuilderNode, event: String): List<JsonElement> {
    val authored = (node.eventBindings[event] as? JsonArray).orEmpty()
    if (!event.endsWith("Change")) return authored
    val variable =
      TwoWayStateBinding.writeBackVariable(
        node.properties[event.removeSuffix("Change")],
        node.eventBindings[event],
      ) ?: return authored
    val declaration = document.stateVariables[variable] as? JsonObject ?: return authored
    // A host input is the host's to change: the control reads it and writes nothing back.
    if (declaration.isHostInput()) return authored
    if (declaration.plainString("valueType") != "bool") return authored
    val writeBack =
      JsonObject(mapOf("type" to JsonPrimitive("toggle"), "variable" to JsonPrimitive(variable)))
    return listOf(writeBack) + authored
  }

  private class ActionOperand(
    val type: String,
    val kind: String,
    val expression: String,
    val parameter: String,
  )

  /** Forward the factory unchanged through callers; only its owner closes over mutable state. */
  private fun actionFactory(
    node: UiBuilderNode,
    event: String,
    actions: List<JsonElement>,
    operands: Map<Int, ActionOperand>,
  ): String? {
    val type = operands.values.joinToString(", ", "(", ") -> $ACTION_FQN") { it.type }
    function?.let { current ->
      return current.capture("event-factory:${node.id}:$event", type) {
        actionFactory(node, event, actions, operands)
      }
    }
    val body =
      actionSequence(
        node,
        event,
        actions,
        operands.mapValues { (_, operand) -> remoteOperand(operand.kind, operand.parameter) },
      ) ?: return null
    return operands.values.joinToString(", ", "{ ", " -> $body }") { it.parameter }
  }

  private fun remoteOperand(kind: String, expression: String): String =
    when (kind) {
      "int" -> {
        usesRemoteInt = true
        "$expression.ri"
      }
      "float" -> {
        usesRemoteFloat = true
        "$expression.rf"
      }
      "bool" -> {
        usesRemoteBoolean = true
        "$expression.rb"
      }
      else -> {
        usesRemoteString = true
        "$expression.rs"
      }
    }

  private fun actionSequence(
    node: UiBuilderNode,
    event: String,
    actions: List<JsonElement>,
    operands: Map<Int, String>,
  ): String? {
    val emitted = actions.mapIndexed { index, action ->
      actionExpression(node, event, action as? JsonObject, operands[index]) ?: return null
    }
    if (emitted.size == 1) return emitted.single()
    usesCombinedAction = true
    return emitted.joinToString(", ", "combinedAction(", ")")
  }

  private fun actionExpression(
    node: UiBuilderNode,
    event: String,
    action: JsonObject?,
    operand: String? = null,
  ): String? {
    val kind = action?.plainString("type")
    val variable = action?.plainString("variable")
    if (action == null || kind == null || variable == null) {
      refusals += "the `$event` binding on `${node.id}` is not an action naming a variable"
      return null
    }
    if (kind == "selectOrClear") {
      refusals +=
        "`${node.id}` clears `$variable` on `$event`, and a Remote value cannot be null — " +
          "clearing a selection and setting it to a sentinel are different designs"
      return null
    }
    val declared = document.stateVariables[variable] as? JsonObject
    val valueType = declared?.plainString("valueType")
    if (declared == null || valueType == null) {
      refusals += "`${node.id}` writes `$variable` on `$event`, which the design does not declare"
      return null
    }
    if (declared.isHostInput()) {
      refusals +=
        "`${node.id}` writes host input `$variable` on `$event`; a host input is read-only, " +
          "the host pushes it by name"
      return null
    }
    val target = remoteState(variable, valueType, "`${node.id}`.$event") ?: return null
    // A formula is computed by the player when the action runs, over the state as it is then.
    fun formula(value: JsonElement?, field: String): String? =
      value?.takeIf(UiExpressions::isComputed)?.let {
        computed(it, UiValueKind.fromWire(valueType) ?: return null, "`${node.id}`.$event.$field")
      }
    val formulaField = if (kind == "increment") "amount" else "value"
    val formulaValue = action[formulaField]
    if (formulaValue != null && UiExpressions.isComputed(formulaValue) && operand == null) {
      val computedValue = formula(formulaValue, formulaField) ?: return null
      val written =
        when (kind) {
          "set",
          "select",
          "setText" -> computedValue
          "increment" -> "($target + $computedValue)"
          else -> null
        }
      if (written != null) {
        usesValueChange = true
        return "valueChange($target, $written)"
      }
    }
    val written =
      when (kind) {
        "set",
        "select",
        "setText" -> operand ?: remoteLiteral(valueType, action["value"])
        "toggle" ->
          if (valueType == "bool") "!$target"
          else {
            refusals +=
              "`${node.id}` toggles `$variable` on `$event` and it is declared `$valueType`, " +
                "which has no negation"
            return null
          }
        // `count + 1` is the creation DSL's own `RemoteInt.plus(Int)` / `RemoteFloat.plus(Float)`,
        // so the sum is computed by the player rather than baked in at export.
        "increment" -> {
          val amount = action["amount"] as? JsonPrimitive ?: JsonPrimitive(1)
          when {
            amount.isString -> null
            valueType == "int" -> amount.intOrNull?.let { "$target + $it" }
            valueType == "float" ->
              amount.floatOrNull?.takeIf(Float::isFinite)?.let { "$target + ${it}f" }
            else -> {
              refusals +=
                "`${node.id}` increments `$variable` on `$event` and it is declared " +
                  "`$valueType`, which is not a number"
              return null
            }
          }
        }
        else -> null
      }
    if (written == null) {
      refusals +=
        "`${node.id}` writes `$variable` on `$event` with `$kind`, which this generator " +
          "cannot express as a Remote value"
      return null
    }
    usesValueChange = true
    return "valueChange($target, $written)"
  }

  /** Reads and writes must name the same mutable, even when a selector has no local action. */
  private fun remoteState(variable: String, expectedType: String, where: String): String? {
    val declaration = document.stateVariables[variable] as? JsonObject
    val initial = declaration?.get("initialValue") as? JsonPrimitive
    val kind =
      declaration?.plainString("valueType")
        ?: initial?.let {
          when {
            it.isString -> "string"
            it.booleanOrNull != null -> "bool"
            it.intOrNull != null -> "int"
            else -> "float"
          }
        }
    val valid =
      initial != null &&
        initial !is kotlinx.serialization.json.JsonNull &&
        when (kind) {
          "string" -> initial.isString
          "bool" -> !initial.isString && initial.booleanOrNull != null
          "int" -> !initial.isString && initial.intOrNull != null
          "float" -> !initial.isString && initial.floatOrNull?.isFinite() == true
          else -> false
        }
    if (
      !valid ||
        kind != expectedType ||
        (declaration?.get("nullable") as? JsonPrimitive)?.booleanOrNull == true
    ) {
      refusals +=
        "$where requires declared, non-null $expectedType state `$variable` with a representable initial value"
      return null
    }
    val target = variable.remoteIdentifier()
    if (
      target in KOTLIN_HARD_KEYWORDS ||
        document.stateVariables.keys.any { it != variable && it.remoteIdentifier() == target } ||
        // Host inputs are declared through these types; a local of the same name would shadow one.
        (target in NAMED_STATE_TYPES &&
          document.stateVariables.values.any { (it as? JsonObject)?.isHostInput() == true })
    ) {
      refusals += "$where state `$variable` conflicts with a generated Kotlin identifier"
      return null
    }
    function?.let { current ->
      val type =
        "androidx.compose.remote.creation.compose.state.Remote" +
          when (expectedType) {
            "int" -> "Int"
            "bool" -> "Boolean"
            "float" -> "Float"
            else -> "String"
          }
      return current.capture("state:$variable", type) { remoteState(variable, expectedType, where) }
    }
    if (declaration?.isHostInput() == true) {
      // A host input keeps its declared name, verbatim, as a named value in the User domain: the
      // name is the contract a host pushes values by, so it is never mangled to an identifier.
      val type = remoteTypeName(expectedType)
      stateWrites.getOrPut(variable) {
        "val $target = Remote$type.createNamedRemote$type(\"${variable.escaped()}\", " +
          "${remoteInitial(expectedType, initial)}, RemoteState.Domain.User)"
      }
      return target
    }
    val factory = stateFactory(expectedType) ?: return null
    stateWrites.getOrPut(variable) {
      "val $target = $factory(${remoteInitial(expectedType, initial)})"
    }
    return target
  }

  private val selectionNames =
    document.stateVariables.keys.map { it.remoteIdentifier() }.toMutableSet()

  private fun selectionName(): String {
    var index = 0
    while (!selectionNames.add("selectionIndex$index")) index++
    return "selectionIndex$index"
  }

  /**
   * StateLayout indexes physical children, not authored case values. Retain the Box: expressions
   * directly under the implicit document root do not update in the alpha18 Android player. Each
   * selection is materialized separately to stay within IntegerExpression's operand mask.
   */
  private fun stateSelection(node: UiBuilderNode, depth: Int): List<String> {
    stateSelectionIssue(node, document.stateVariables)?.let {
      refusals += "`${node.id}`.$SHOW_BY_STATE: $it"
      return emptyList()
    }
    val selection = requireNotNull(node.stateSelection())
    val variable = selection.selector.plainString("variable")
    val declaration = variable?.let { document.stateVariables[it] as? JsonObject }
    val kind =
      if (variable != null)
        declaration?.plainString("valueType")
          ?: (declaration?.get("initialValue") as? JsonPrimitive)?.let {
            when {
              it.isString -> "string"
              it.booleanOrNull != null -> "bool"
              it.intOrNull != null -> "int"
              else -> "float"
            }
          }
      else selection.selector.plainString("type")
    if (kind !in setOf("bool", "int", "float")) {
      refusals +=
        "`${node.id}`.$SHOW_BY_STATE: this Remote Compose target has no String equality operation; use a number or flag selector"
      return emptyList()
    }
    val subject =
      if (variable != null) remoteState(variable, kind!!, "`${node.id}`.$SHOW_BY_STATE")
      else remoteLiteral(kind!!, selection.selector["value"])
    if (subject == null) return emptyList()
    usesRemoteInt = true
    usesStateLayout = true
    usesBox = true
    val pad = INDENT.repeat(depth)
    val clean = node.copy(properties = JsonObject(node.properties - SHOW_BY_STATE))
    val arguments = boxArguments(clean, pad)
    val head = if (arguments.isEmpty()) "RemoteBox {" else call("RemoteBox", arguments, pad) + " {"
    val lines = (pad + head).split("\n").toMutableList()
    var ordinal = "${selection.cases.size}.ri"
    selection.cases.entries.toList().withIndex().reversed().forEach { (index, entry) ->
      val literal = requireNotNull(remoteLiteral(kind, entry.value))
      // alpha18 equality takes abs(a - b), which overflows for Int.MIN_VALUE. Compare
      // each 16-bit half instead; their differences cannot overflow a signed Int.
      val match =
        if (kind == "int") {
          val value = entry.value.intOrNull!!
          val low = requireNotNull(remoteLiteral("int", JsonPrimitive(value and 65535)))
          val high = requireNotNull(remoteLiteral("int", JsonPrimitive(value shr 16)))
          "($subject and 65535.ri).isEqualTo($low).and(($subject shr 16.ri).isEqualTo($high))"
        } else "$subject.isEqualTo($literal)"
      val local = selectionName()
      lines += "$pad${INDENT}val $local ="
      val expressionPad = pad + INDENT.repeat(2)
      lines += (expressionPad + match.replace(".and(", "\n$expressionPad    .and(")).split("\n")
      lines += "$expressionPad    .select($index.ri, $ordinal).createReference()"
      ordinal = local
    }
    val branch = selectionName()
    val positions = (0..selection.cases.size).joinToString(", ")
    lines += "$pad${INDENT}RemoteStateLayout($ordinal, $positions) { $branch ->"
    lines += "$pad${INDENT.repeat(2)}when ($branch) {"
    val enclosing = scope
    scope = "RemoteBox"
    (selection.cases.keys.toList() + selection.fallback).forEachIndexed { index, id ->
      lines += "$pad${INDENT.repeat(3)}$index -> RemoteBox {"
      if (id != null) lines += emit(id, depth + 4)
      lines += "$pad${INDENT.repeat(3)}}"
    }
    scope = enclosing
    lines += "$pad${INDENT.repeat(2)}}"
    lines += "$pad$INDENT}"
    lines += "$pad}"
    return lines
  }

  private fun remoteTypeName(valueType: String): String =
    when (valueType) {
      "bool" -> "Boolean"
      "int" -> "Int"
      "float" -> "Float"
      else -> "String"
    }

  private fun stateFactory(valueType: String): String? =
    when (valueType) {
      "bool" -> "rememberMutableRemoteBoolean"
      "int" -> "rememberMutableRemoteInt"
      "float" -> "rememberMutableRemoteFloat"
      "string" -> "rememberMutableRemoteString"
      else -> null
    }

  /** The Kotlin literal a mutable's factory takes, which is a plain one and not a Remote value. */
  private fun remoteInitial(valueType: String, initial: JsonElement?): String {
    val primitive = initial as? JsonPrimitive
    return when (valueType) {
      "bool" -> (primitive?.booleanOrNull ?: false).toString()
      "int" -> (primitive?.intOrNull ?: 0).toString()
      "float" -> "${primitive?.floatOrNull ?: 0f}f"
      else -> "\"${primitive?.contentOrNull.orEmpty().escaped()}\""
    }
  }

  /** A design's action value as a Remote value of the variable's declared type. */
  private fun remoteLiteral(valueType: String, value: JsonElement?): String? {
    val primitive =
      (value as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?: return null
    if (valueType != "string" && primitive.isString) return null
    return when (valueType) {
      "bool" ->
        primitive.booleanOrNull?.let {
          usesRemoteBoolean = true
          "$it.rb"
        }
      "int" ->
        primitive.intOrNull?.let {
          usesRemoteInt = true
          if (it < 0) "($it).ri" else "$it.ri"
        }
      "float" ->
        primitive.floatOrNull
          ?.takeIf { it.isFinite() }
          ?.let {
            usesRemoteFloat = true
            "${it}f.rf"
          }
      "string" ->
        primitive
          .takeIf { it.isString }
          ?.contentOrNull
          ?.let {
            usesRemoteString = true
            "\"${it.escaped()}\".rs"
          }
      else -> null
    }
  }

  /**
   * Emit a slot's children inside the scope its lambda gives them (e.g. `RemoteButton.content` is
   * `RemoteRowScope`), so `weightCall` sees the right [scope].
   */
  private fun <T> inSlotScope(slot: TargetParameter, body: () -> T): T {
    val receiver = slot.composableSlotReceiver?.substringAfterLast('.')?.removeSuffix("Scope")
    if (receiver == null) return body()
    val enclosing = scope
    scope = receiver
    val result = body()
    scope = enclosing
    return result
  }

  /**
   * An action-free event inside a component the components-only export writes ([namedBindings]): an
   * `Action` parameter named for the node and the event (`onCardClick`), which the catalog that
   * places the component supplies — the Remote counterpart of a Compose component's `() -> Unit`
   * intent. Anywhere else, or when the name is not a usable identifier, null.
   */
  private fun intentParameter(node: UiBuilderNode, event: String): String? {
    val current = function ?: return null
    if (!namedBindings) return null
    val words = (node.id.split(INTENT_WORD_BREAK) + event).filter(String::isNotEmpty)
    val name = "on" + words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    if (!name.matches(Regex("on[A-Z][A-Za-z0-9]*"))) return null
    return allocateIntent(current, "${node.id}:$event", name)
  }

  /**
   * [name] as [current]'s intent for [key], or null when another parameter (a binding, a capture,
   * or a different intent: `card-one` and `card_one` both spell `onCardOneClick`) already has it.
   */
  private fun allocateIntent(current: FunctionBody, key: String, name: String): String? {
    current.intents[key]?.let {
      return it
    }
    val taken =
      current.bindings.fields.values.map { it.name } +
        current.captures.values.map { it.name } +
        current.intents.values
    if (name in taken) return null
    current.intents[key] = name
    return name
  }

  /**
   * The value a placement passes for its target's [intent]: the enclosing component's own intent of
   * the same name (`onCardClick`, or `onPlaceCardClick` when that is taken), so the catalog still
   * supplies it; `lambdaAction {}` outside a component, or when neither name is free.
   */
  private fun forwardedIntent(node: UiBuilderNode, key: String, intent: String): String {
    val current = function?.takeIf { namedBindings } ?: return lambdaActionExpression()
    val forwardKey = "${node.id}>$key"
    val words = node.id.split(INTENT_WORD_BREAK).filter(String::isNotEmpty)
    val prefixed =
      "on" +
        words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } +
        intent.removePrefix("on")
    return allocateIntent(current, forwardKey, intent)
      ?: prefixed
        .takeIf { it.matches(Regex("on[A-Z][A-Za-z0-9]*")) }
        ?.let { allocateIntent(current, forwardKey, it) }
      ?: lambdaActionExpression()
  }

  private val INTENT_WORD_BREAK = Regex("[^A-Za-z0-9]+")

  private fun lambdaActionExpression(): String {
    usesLambdaAction = true
    return "lambdaAction {}"
  }

  /**
   * A design's value as an expression of the parameter's declared type, or null when there is none.
   * Matched on the qualified type, since simple names can collide.
   */
  private fun scopedArgument(
    node: UiBuilderNode,
    parameter: TargetParameter,
    value: JsonElement,
  ): String? {
    val type = (value as? JsonObject)?.plainString("type")
    if (UiExpressions.isComputed(value)) return remoteValue(node, parameter, value)
    val expected =
      when (parameter.typeFqn) {
        REMOTE_STRING_FQN,
        "kotlin.String" -> setOf("string")
        REMOTE_BOOLEAN_FQN,
        "kotlin.Boolean" -> setOf("bool")
        REMOTE_FLOAT_FQN,
        "kotlin.Float" -> setOf("int", "float")
        "kotlin.Int" -> setOf("int")
        // A size is authored as a number; whether THIS number has a spelling is asked below.
        REMOTE_TEXT_UNIT_FQN -> setOf("int", "float")
        REMOTE_DP_FQN -> setOf("int", "float")
        REMOTE_COLOR_FQN -> setOf("color", "colorToken")
        else -> emptySet()
      }
    if (type != "state" && type !in expected) {
      refusals += "nodes.${node.id}.${parameter.name}: expected ${parameter.typeFqn} argument"
      return null
    }
    if (type != "state") {
      val primitiveType =
        when (parameter.typeFqn) {
          REMOTE_STRING_FQN -> "kotlin.String"
          REMOTE_BOOLEAN_FQN -> "kotlin.Boolean"
          REMOTE_FLOAT_FQN -> "kotlin.Float"
          else -> parameter.typeFqn
        }
      if (primitiveType in setOf("kotlin.String", "kotlin.Boolean", "kotlin.Float", "kotlin.Int")) {
        if (
          primitiveArgument(value, primitiveType.orEmpty(), "nodes.${node.id}.${parameter.name}") ==
            null
        )
          return null
      } else if (
        parameter.typeFqn == REMOTE_COLOR_FQN &&
          value.stringOrNull()?.let {
            it.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) ||
              it == TRANSPARENT_TOKEN ||
              it.matches(COLOR_ROLE)
          } != true
      ) {
        refusals +=
          "nodes.${node.id}.${parameter.name}: expected a colour literal or a theme role argument"
        return null
      }
    }
    val expression = remoteValue(node, parameter, value)
    if (expression == null)
      refusals += "nodes.${node.id}.${parameter.name}: cannot emit ${parameter.typeFqn} argument"
    return expression
  }

  /**
   * A computed value as the Remote expression the player evaluates, or null with a located refusal.
   * [UiExpressions.check] types the tree first; state reads go through [remoteState].
   */
  private fun computed(value: JsonElement, expected: UiValueKind, where: String): String? {
    val documentScope = UiExpressions.Scope.of(document)
    val scope =
      if (drawBindings.isEmpty() && componentArgumentKinds().isEmpty()) documentScope
      else
        UiExpressions.Scope(documentScope.stateKinds) { key ->
          if (key in drawBindings) UiValueKind.FLOAT else componentArgumentKinds()[key]
        }
    val checked = UiExpressions.check(value, scope, where)
    val expr =
      when (checked) {
        is UiExpressions.Checked.Issue -> {
          refusals += checked.message
          return null
        }
        is UiExpressions.Checked.Ok -> checked.expr
      }
    val lowered = lower(expr, where) ?: return null
    return computedResult(expr, expected, lowered, where)?.also {
      if (INT_LITERAL.containsMatchIn(it)) usesRemoteInt = true
    }
  }

  private fun computedResult(
    expr: UiExpressions.Expr,
    expected: UiValueKind,
    lowered: String,
    where: String,
  ): String? {
    return when {
      expr.kind == expected -> lowered
      expected == UiValueKind.FLOAT && expr.kind == UiValueKind.INT -> asFloat(expr, lowered)
      expected == UiValueKind.STRING && expr.kind != UiValueKind.COLOR ->
        stringOperand(expr.kind, lowered)
      else -> {
        refusals += "$where: a ${expr.kind.wire} expression cannot be used as ${expected.wire}"
        null
      }
    }
  }

  private fun lower(expr: UiExpressions.Expr, where: String): String? =
    when (expr) {
      is UiExpressions.Expr.Literal ->
        when (expr.kind) {
          UiValueKind.FLOAT -> expr.value.content.toFloat().floatLiteral()
          // `usesRemoteInt` is decided on the finished expression in [computed]: an Int literal in
          // a Float context is rewritten there.
          UiValueKind.INT -> "${expr.value.content.toInt()}.ri"
          UiValueKind.BOOL -> {
            usesRemoteBoolean = true
            "${expr.value.content.toBoolean()}.rb"
          }
          UiValueKind.STRING -> {
            usesRemoteString = true
            "\"${expr.value.content.escaped()}\".rs"
          }
          UiValueKind.COLOR ->
            if (expr.value.content == TRANSPARENT_TOKEN) {
              usesColorLiteral = true
              "Color(0x00000000).rc"
            } else if (UiExpressions.isThemeRole(expr.value.content)) {
              usesTheme = true
              "RemoteMaterialTheme.colorScheme.${expr.value.content}"
            } else {
              usesColorLiteral = true
              "${expr.value.content.argbLiteral()}.rc"
            }
        }
      is UiExpressions.Expr.State ->
        if (expr.kind == UiValueKind.COLOR) {
          refusals += "$where: colour state `${expr.variable}` has no Remote state factory"
          null
        } else remoteState(expr.variable, expr.kind.wire, where)
      // A draw loop's index is the lambda parameter the loop hands its body.
      is UiExpressions.Expr.Binding ->
        drawBindings[expr.key]
          ?: componentArgument(expr, where)
          ?: run {
            refusals +=
              "$where: a row field inside an expression needs a typed loop lowering; bind the " +
                "field to the property directly"
            null
          }
      is UiExpressions.Expr.System ->
        when (expr.value.id) {
          UiExpressions.CANVAS_WIDTH -> canvasExtent?.widthDp ?: outsideCanvas(expr, where)
          UiExpressions.CANVAS_HEIGHT -> canvasExtent?.heightDp ?: outsideCanvas(expr, where)
          else -> {
            usedComponentImports += expr.value.imports
            expr.value.remote
          }
        }
      is UiExpressions.Expr.Call -> lowerCall(expr, where)
    }

  /**
   * The arguments the placement being emitted supplies, read while its component's body is written:
   * an expression inside the component may name one (`lerp(theme.primary, theme.primaryDim, @on)`),
   * and the argument's literal type is the parameter's.
   */
  private var placementArguments: JsonObject? = null

  /** The kinds of [placementArguments] an expression may read, inside a component only. */
  private fun componentArgumentKinds(): Map<String, UiValueKind> {
    val arguments = placementArguments ?: return emptyMap()
    if (function == null || bindings?.row != null) return emptyMap()
    return arguments
      .mapNotNull { (key, value) ->
        (value as? JsonObject)
          ?.plainString("type")
          ?.let(UiExpressions::kindOfWrapper)
          ?.takeIf { it != UiValueKind.INT }
          ?.let { key to it }
      }
      .toMap()
  }

  /**
   * A component argument read inside an expression, as the component's parameter of the matching
   * Remote type; the placement supplies it like any other binding. Null where [expr] is not one.
   */
  private fun componentArgument(expr: UiExpressions.Expr.Binding, where: String): String? {
    if (expr.key !in componentArgumentKinds()) return null
    val type =
      when (expr.kind) {
        UiValueKind.FLOAT -> REMOTE_FLOAT_FQN
        UiValueKind.BOOL -> REMOTE_BOOLEAN_FQN
        UiValueKind.STRING -> REMOTE_STRING_FQN
        UiValueKind.COLOR -> REMOTE_COLOR_FQN
        UiValueKind.INT -> return null
      }
    val reference =
      JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(expr.key)))
    return bound(reference, type, where) { argumentLiteral(it, expr.kind) }
  }

  /** A placement's literal argument as the Remote value of [kind]. */
  private fun argumentLiteral(value: JsonElement, kind: UiValueKind): String? =
    when (kind) {
      UiValueKind.FLOAT -> value.numberOrNull()?.floatLiteral()
      UiValueKind.BOOL -> value.boolOrNull()?.let { "$it.rb".also { usesRemoteBoolean = true } }
      UiValueKind.STRING ->
        value.stringOrNull()?.let { "\"${it.escaped()}\".rs".also { usesRemoteString = true } }
      UiValueKind.COLOR -> remoteColor(value)
      UiValueKind.INT -> null
    }

  /** The player's time-from-now attribute of a countdown's instant, in [unit]. */
  private fun countdown(expr: UiExpressions.Expr.Call, unit: String): String {
    usedComponentImports += "$REMOTE_STATE_PACKAGE.RemoteLong"
    usedComponentImports += "$REMOTE_STATE_PACKAGE.deltaFromReferenceIn$unit"
    return "deltaFromReferenceIn$unit(RemoteLong(${UiExpressions.instantMillis(expr)}L))"
  }

  private fun lowerCall(expr: UiExpressions.Expr.Call, where: String): String? {
    val args = expr.args.map { lower(it, where) ?: return null }
    val kinds = expr.args.map { it.kind }
    fun f(index: Int): String = asFloat(expr.args[index], args[index])
    fun math(name: String, vararg operands: String): String {
      usedComponentImports += "$REMOTE_STATE_PACKAGE.$name"
      return "$name(${operands.joinToString()})"
    }
    val integral = expr.kind == UiValueKind.INT
    fun infix(symbol: String): String =
      if (integral) "(${args[0]} $symbol ${args[1]})" else "(${f(0)} $symbol ${f(1)})"
    fun compare(name: String): String =
      if (kinds.all { it == UiValueKind.INT } || kinds.all { it == UiValueKind.BOOL })
        "${args[0]}.$name(${args[1]})"
      else "${f(0)}.$name(${f(1)})"
    return when (expr.op) {
      UiExpressions.Op.ADD -> infix("+")
      UiExpressions.Op.SUB -> infix("-")
      UiExpressions.Op.MUL -> infix("*")
      UiExpressions.Op.DIV -> infix("/")
      UiExpressions.Op.MOD -> infix("%")
      UiExpressions.Op.NEG -> "(-${args[0]})"
      UiExpressions.Op.MIN ->
        if (integral) math("min", args[0], args[1]) else math("min", f(0), f(1))
      UiExpressions.Op.MAX ->
        if (integral) math("max", args[0], args[1]) else math("max", f(0), f(1))
      // The two overloads disagree on argument order upstream: the Int one takes the bounds first.
      UiExpressions.Op.CLAMP ->
        if (integral) math("clamp", args[1], args[2], args[0]) else math("clamp", f(0), f(1), f(2))
      UiExpressions.Op.ABS -> if (integral) "${args[0]}.absoluteValue" else math("abs", f(0))
      UiExpressions.Op.FLOOR -> math("floor", f(0))
      UiExpressions.Op.CEIL -> math("ceil", f(0))
      UiExpressions.Op.ROUND -> math("round", f(0))
      UiExpressions.Op.SQRT -> math("sqrt", f(0))
      UiExpressions.Op.SIN -> math("sin", f(0))
      UiExpressions.Op.COS -> math("cos", f(0))
      UiExpressions.Op.TAN -> math("tan", f(0))
      UiExpressions.Op.ASIN -> math("asin", f(0))
      UiExpressions.Op.ACOS -> math("acos", f(0))
      UiExpressions.Op.ATAN -> math("atan", f(0))
      UiExpressions.Op.ATAN2 -> math("atan2", f(0), f(1))
      UiExpressions.Op.DEGREES -> math("toDeg", f(0))
      UiExpressions.Op.RADIANS -> math("toRad", f(0))
      UiExpressions.Op.EXP -> math("exp", f(0))
      UiExpressions.Op.LN -> math("ln", f(0))
      // The Remote `log` is base 10.
      UiExpressions.Op.LOG10 -> math("log", f(0))
      UiExpressions.Op.SIGN -> math("sign", f(0))
      // Written out rather than the player's FRACT, which truncates: `fract(-0.25)` is 0.75 here.
      UiExpressions.Op.FRACT -> "(${f(0)} - ${math("floor", f(0))})"
      UiExpressions.Op.STEP ->
        "${f(1)}.isGreaterThanOrEqualTo(${f(0)}).select(${1f.floatLiteral()}, ${0f.floatLiteral()})"
      UiExpressions.Op.SMOOTHSTEP -> {
        val t =
          math(
            "clamp",
            "((${f(2)} - ${f(0)}) / (${f(1)} - ${f(0)}))",
            0f.floatLiteral(),
            1f.floatLiteral(),
          )
        "($t * $t * (${3f.floatLiteral()} - ${2f.floatLiteral()} * $t))"
      }
      UiExpressions.Op.HYPOT -> math("sqrt", "(${f(0)} * ${f(0)} + ${f(1)} * ${f(1)})")
      UiExpressions.Op.HSV,
      UiExpressions.Op.RGB -> {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.RemoteColor"
        val factory = if (expr.op == UiExpressions.Op.HSV) "hsv" else "rgb"
        "RemoteColor.$factory(${args.indices.joinToString { f(it) }})"
      }
      UiExpressions.Op.UPPERCASE -> "${args[0]}.uppercase()"
      UiExpressions.Op.LOWERCASE -> "${args[0]}.lowercase()"
      UiExpressions.Op.TRIM -> "${args[0]}.trim()"
      UiExpressions.Op.SUBSTRING -> "${args[0]}.substring(${args.drop(1).joinToString()})"
      UiExpressions.Op.RAND -> math("rand")
      UiExpressions.Op.RAND_RANGE -> math("randRange", f(0), f(1))
      // Written out over public operators, as the player's PINGPONG folds: x % 2max, then the
      // part past max runs back down.
      UiExpressions.Op.PING_PONG -> {
        val period = "(${f(1)} * ${2f.floatLiteral()})"
        val folded = "(${math("abs", f(0))} % $period)"
        "$folded.isLessThan(${f(1)}).select($folded, ($period - $folded))"
      }
      UiExpressions.Op.POW -> math("pow", f(0), f(1))
      // The control points are literal (checked), written as the float array the player splines.
      UiExpressions.Op.SPLINE,
      UiExpressions.Op.SPLINE_LOOP -> {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.RemoteFloatArray"
        // The list constructor, which every revision of RemoteFloatArray has.
        val points =
          expr.args.drop(1).joinToString {
            (it as UiExpressions.Expr.Literal).value.content.toFloat().floatLiteral()
          }
        val loop = expr.op == UiExpressions.Op.SPLINE_LOOP
        math("evalSpline", "RemoteFloatArray(listOf($points))", "$loop", f(0))
      }
      UiExpressions.Op.CBRT -> math("cbrt", f(0))
      UiExpressions.Op.COPY_SIGN -> math("copySign", f(0), f(1))
      UiExpressions.Op.SECONDS_UNTIL -> countdown(expr, "Seconds")
      UiExpressions.Op.MINUTES_UNTIL -> countdown(expr, "Minutes")
      UiExpressions.Op.HOURS_UNTIL -> countdown(expr, "Hours")
      UiExpressions.Op.LERP ->
        if (expr.kind == UiValueKind.COLOR) {
          usedComponentImports += "$REMOTE_STATE_PACKAGE.tween"
          "tween(${args[0]}, ${args[1]}, ${f(2)})"
        } else math("lerp", f(0), f(1), f(2))
      UiExpressions.Op.TO_INT ->
        if (kinds[0] == UiValueKind.INT) args[0] else "${args[0]}.toRemoteInt()"
      UiExpressions.Op.TO_FLOAT -> f(0)
      UiExpressions.Op.EQ -> compare("isEqualTo")
      UiExpressions.Op.NE -> compare("isNotEqualTo")
      UiExpressions.Op.LT -> compare("isLessThan")
      UiExpressions.Op.LE -> compare("isLessThanOrEqualTo")
      UiExpressions.Op.GT -> compare("isGreaterThan")
      UiExpressions.Op.GE -> compare("isGreaterThanOrEqualTo")
      UiExpressions.Op.AND -> "(${args[0]} and ${args[1]})"
      UiExpressions.Op.OR -> "(${args[0]} or ${args[1]})"
      UiExpressions.Op.NOT -> "(!${args[0]})"
      UiExpressions.Op.SELECT -> {
        val branch = { index: Int ->
          if (expr.kind == UiValueKind.FLOAT) asFloat(expr.args[index], args[index])
          else args[index]
        }
        "${args[0]}.select(${branch(1)}, ${branch(2)})"
      }
      UiExpressions.Op.CONCAT ->
        args.indices.joinToString(" + ", "(", ")") { stringOperand(kinds[it], args[it]) }
      UiExpressions.Op.TO_STRING -> stringOperand(kinds[0], args[0])
      // The spec is literal (checked when the tree is typed), so it is written as the format.
      UiExpressions.Op.FORMAT -> {
        fun literal(index: Int): Int? =
          (expr.args.getOrNull(index) as? UiExpressions.Expr.Literal)
            ?.value
            ?.content
            ?.toDouble()
            ?.toInt()
        val decimals = literal(1) ?: 0
        val minDigits = literal(2) ?: 1
        // The released Android API takes a `java.text.DecimalFormat`, read from its pattern and
        // digit counts; root symbols keep the point a `.` whatever the writing machine's locale.
        usedComponentImports += "java.text.DecimalFormat"
        usedComponentImports += "java.text.DecimalFormatSymbols"
        usedComponentImports += "java.util.Locale"
        val pattern = "0".repeat(minDigits) + if (decimals > 0) "." + "0".repeat(decimals) else ""
        "${f(0)}.toRemoteString(DecimalFormat(\"$pattern\", DecimalFormatSymbols(Locale.ROOT)))"
      }
      UiExpressions.Op.TWEEN,
      UiExpressions.Op.SPRING -> animated(expr, f(0))
    }
  }

  /** `tween`/`spring`: [target] animated by the player whenever it changes. */
  private fun animated(expr: UiExpressions.Expr.Call, target: String): String {
    fun literal(index: Int): String? =
      (expr.args.getOrNull(index) as? UiExpressions.Expr.Literal)?.value?.content
    val spec =
      if (expr.op == UiExpressions.Op.TWEEN) {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.remoteTween"
        usedComponentImports += "$REMOTE_STATE_PACKAGE.RemoteEasing"
        val easing = (literal(2) ?: "standard").replaceFirstChar { it.uppercase() }
        "remoteTween(${literal(1)!!.toDouble().toInt()}, RemoteEasing.$easing)"
      } else {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.remoteSpring"
        val stiffness = literal(1)?.let { "stiffness = ${it.toFloat()}f" }
        val damping = literal(2)?.let { "dampingRatio = ${it.toFloat()}f" }
        "remoteSpring(${listOfNotNull(stiffness, damping).joinToString()})"
      }
    usedComponentImports += "$REMOTE_STATE_PACKAGE.animateRemoteFloatAsState"
    return "animateRemoteFloatAsState($target, $spec)"
  }

  /** [lowered] as a `RemoteFloat`: an Int literal is written as the float it is, not converted. */
  private fun asFloat(expr: UiExpressions.Expr, lowered: String): String =
    when {
      expr.kind != UiValueKind.INT -> lowered
      expr is UiExpressions.Expr.Literal -> expr.value.content.toFloat().floatLiteral()
      else -> "$lowered.toRemoteFloat()"
    }

  /** A value of [kind] as a `RemoteString` operand: numbers print as the player formats them. */
  private fun stringOperand(kind: UiValueKind, lowered: String): String =
    when (kind) {
      UiValueKind.STRING -> lowered
      UiValueKind.BOOL -> {
        usesRemoteString = true
        "$lowered.select(\"true\".rs, \"false\".rs)"
      }
      else -> "$lowered.toRemoteString()"
    }

  /**
   * A page indicator, building the `RemotePageIndicatorState` from `pageCount` and a `selectedPage`
   * that may read Int state.
   */
  private fun pageIndicator(node: UiBuilderNode, pad: String): List<String> {
    val symbol =
      if (node.componentId == REMOTE_VERTICAL_PAGE_INDICATOR_ID) "RemoteVerticalPageIndicator"
      else "RemoteHorizontalPageIndicator"
    usedComponentImports += "androidx.wear.compose.remote.material3.$symbol"
    usedComponentImports +=
      "androidx.wear.compose.remote.material3.rememberRemotePageIndicatorState"
    val pageCount = node.properties["pageCount"]?.intOrNull() ?: 4
    if (pageCount < 1) {
      refusals += "nodes.${node.id}.pageCount: a page indicator needs at least one page"
      return emptyList()
    }
    val authored = node.properties["selectedPage"]
    val selected =
      when {
        authored == null -> null
        authored.isDrawComputed() ->
          computed(authored, UiValueKind.INT, "nodes.${node.id}.selectedPage") ?: return emptyList()
        else ->
          authored.intOrNull()?.let {
            usesRemoteInt = true
            "$it.ri"
          }
      }
    val state =
      "rememberRemotePageIndicatorState(pageCount = $pageCount" +
        (selected?.let { ", selectedPage = $it" } ?: "") +
        ")"
    val arguments = mutableListOf("state = $state")
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // The colours remote-material3 1.0.0-alpha13 — the alpha the builder authors against — takes.
    // `backgroundColor` arrived in alpha13, the catalog offers it and the canvas draws it, so an
    // export that left it out silently lost a setting the author could see.
    listOf("selectedColor", "unselectedColor", "backgroundColor").forEach { name ->
      pageIndicatorColor(node, name)?.let { arguments += "$name = $it" }
    }
    // The two lengths the record declares, in dp, so the catalog's number editors reach the export.
    listOf("indicatorRadius", "padding").forEach { name ->
      node.properties[name]?.numberOrNull()?.let { arguments += "$name = ${it.dpLiteral()}" }
    }
    return (pad + call(symbol, arguments, pad)).split("\n")
  }

  /**
   * `RemoteTimeText(…)`: the time is the player's own, so only the text beside it, its size and its
   * colour are written, each left to the component's default when the design does not state it.
   */
  private fun timeText(node: UiBuilderNode, pad: String): String? {
    usedComponentImports += "androidx.wear.compose.remote.material3.RemoteTimeText"
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    listOf("leadingText", "trailingText", "separator").forEach { name ->
      val authored = node.properties[name] ?: return@forEach
      val value =
        if (authored.isDrawComputed())
          computed(authored, UiValueKind.STRING, "nodes.${node.id}.$name") ?: return null
        else {
          usesRemoteString = true
          "\"${authored.stringOrNull().orEmpty().escaped()}\".rs"
        }
      arguments += "$name = $value"
    }
    node.properties["textSizeSp"]?.let { authored ->
      if (authored.isDrawComputed()) {
        // `RemoteTextUnit`'s constructor is internal: a computed size has no spelling here.
        refusals += "nodes.${node.id}.textSizeSp: a time text's size is a literal"
        return null
      }
      authored.numberOrNull()?.let { arguments += "fontSize = ${it.spLiteral()}" }
    }
    pageIndicatorColor(node, "color")?.let { arguments += "color = $it" }
    return pad + call("RemoteTimeText", arguments, pad)
  }

  /**
   * `RemoteMaterialTheme(colorScheme = RemoteMaterialTheme.colorScheme.copy(…)) { … }` around the
   * themed child, with only the roles the design overrides. A role named as a token reads the
   * scheme around it, as the canvas and the Wear screen's theme do. With no override the wrapper
   * would change nothing, so the child is written alone.
   */
  private fun materialTheme(node: UiBuilderNode, depth: Int): List<String> {
    val pad = INDENT.repeat(depth)
    val roles =
      WearScreenTheme.ROLES.mapNotNull { role ->
        pageIndicatorColor(node, WearScreenTheme.property(role))?.let { "$role = $it" }
      }
    val children = node.slots[UiRemoteTheme.SLOT].orEmpty()
    if (roles.isEmpty()) return children.flatMap { emit(it, depth) }
    usesTheme = true
    val scheme = call("RemoteMaterialTheme.colorScheme.copy", roles, "$pad$INDENT")
    val head =
      call("RemoteMaterialTheme", listOf("colorScheme = $scheme"), pad, OPENING_BRACE.length) +
        OPENING_BRACE
    // Not a layout: `weight` and the other scoped modifiers have no receiver inside it.
    val enclosing = scope
    scope = "RemoteMaterialTheme"
    val body = children.flatMap { emit(it, depth + 1) }
    scope = enclosing
    return (pad + head).split("\n") + body + listOf("$pad}")
  }

  private fun pageIndicatorColor(node: UiBuilderNode, name: String): String? {
    val authored = node.properties[name] ?: return null
    if (authored.isDrawComputed())
      return computed(authored, UiValueKind.COLOR, "nodes.${node.id}.$name")
    val value = authored.stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value == TRANSPARENT_TOKEN) {
      usesColorLiteral = true
      "Color(0x00000000).rc"
    } else if (value.startsWith("#")) {
      usesColorLiteral = true
      "${value.argbLiteral()}.rc"
    } else {
      usesTheme = true
      "RemoteMaterialTheme.colorScheme.$value"
    }
  }

  /**
   * `RemoteCanvas { … }` with each `ops` operation as its `RemoteDrawScope` call.
   *
   * Coordinates are dp written as `n.rdp.toPx()`. Theme colours are read above the canvas because
   * the draw lambda is not composable.
   */
  private fun drawCanvas(node: UiBuilderNode, depth: Int): List<String> {
    val pad = INDENT.repeat(depth)
    usedComponentImports += "androidx.compose.remote.creation.compose.layout.RemoteCanvas"
    val hoisted = mutableListOf<String>()
    val previous = canvasExtent
    val stated = node.statedExtent()
    val viewport = UiDrawing.Viewport.of(node)
    if (viewport == null && (node.has("viewportWidth") || node.has("viewportHeight"))) {
      refusals +=
        "nodes.${node.id}.viewportWidth: a canvas's viewport needs viewportWidth and " +
          "viewportHeight, both literal numbers above 0"
      return emptyList()
    }
    // Inside a viewport the operations' "whole canvas" is the viewport, in its own units.
    canvasExtent =
      viewport?.let {
        CanvasExtent(
          "${it.width.dpLiteral()}.toPx()",
          "${it.height.dpLiteral()}.toPx()",
          it.width.floatLiteral(),
          it.height.floatLiteral(),
        )
      } ?: stated
    val opsDepth = if (viewport == null) depth + 1 else depth + 2
    val ops =
      try {
        node.slots[UiDrawing.OPS_SLOT].orEmpty().flatMap { drawOperation(it, opsDepth, hoisted) }
      } finally {
        canvasExtent = previous
      }
    val body =
      if (viewport == null) ops
      else {
        val inner = INDENT.repeat(depth + 1)
        listOf(inner + viewportTransform(viewport, stated) + " {") + ops + listOf("$inner}")
      }
    val modifier = node.modifierExpression(pad)
    val head = if (modifier == null) "RemoteCanvas {" else "RemoteCanvas(modifier = $modifier) {"
    return hoisted.map { pad + it } + listOf(pad + head) + body + listOf("$pad}")
  }

  /**
   * The transform that draws [viewport]'s units on a canvas of [canvas]'s size: `RemoteCanvas`'s
   * `scale` is about the origin, so `fit` and `fill` centre with a translate first. `fill` clips
   * what overflows the canvas.
   */
  private fun viewportTransform(viewport: UiDrawing.Viewport, canvas: CanvasExtent): String {
    usesRemoteFloat = true
    val width = "${viewport.width.dpLiteral()}.toPx()"
    val height = "${viewport.height.dpLiteral()}.toPx()"
    val sx = "(${canvas.width} / $width)"
    val sy = "(${canvas.height} / $height)"
    if (viewport.mode == "stretch") return "withTransform({ scale($sx, $sy) })"
    val pick = if (viewport.mode == "fill") "max" else "min"
    usedComponentImports += "$REMOTE_STATE_PACKAGE.$pick"
    val scale = "$pick($sx, $sy)"
    val x = "((${canvas.width} - $width * $scale) / 2.rf)"
    val y = "((${canvas.height} - $height * $scale) / 2.rf)"
    val clip =
      if (viewport.mode == "fill") "clipRect(0.rf, 0.rf, ${canvas.width}, ${canvas.height}); "
      else ""
    return "withTransform({ ${clip}translate($x, $y); scale($scale, $scale) })"
  }

  /**
   * The canvas's size as the draw scope reads it. Measured `width`/`height` read as zero inside a
   * Glance Wear capture, so a canvas with a dp size gets literals; only an unsized one reads its
   * measured size.
   */
  private inner class CanvasExtent(
    val width: String,
    val height: String,
    /** The same, in dp: a stated size's own number, a measured one over the density. */
    val widthDp: String = inDp(width),
    val heightDp: String = inDp(height),
  ) {
    val centerX: String = "($width / 2.rf)"
    val centerY: String = "($height / 2.rf)"
    val center: String = "RemoteOffset($centerX, $centerY)"
  }

  private fun inDp(pixels: String): String = "($pixels / ${1f.dpLiteral()}.toPx())"

  /** A `canvas.*` read where no canvas is being written: there is no size to read. */
  private fun outsideCanvas(expr: UiExpressions.Expr.System, where: String): String? {
    refusals += "$where: `${expr.value.id}` is the canvas's size, read only by its operations"
    return null
  }

  private var canvasExtent: CanvasExtent? = null

  private val extent: CanvasExtent
    get() = canvasExtent ?: CanvasExtent("width", "height")

  private fun UiBuilderNode.statedExtent(): CanvasExtent {
    var width: Float? = null
    var height: Float? = null
    modifiers.forEach { element ->
      val modifier = element as? JsonObject ?: return@forEach
      when (modifier["type"]?.stringValue()) {
        "size" -> {
          modifier["widthDp"]?.numberValue()?.let { width = it }
          modifier["heightDp"]?.numberValue()?.let { height = it }
        }
        "width" -> modifier["widthDp"]?.numberValue()?.let { width = it }
        "height" -> modifier["heightDp"]?.numberValue()?.let { height = it }
      }
    }
    return CanvasExtent(
      width?.let { "${it.dpLiteral()}.toPx()" } ?: "width",
      height?.let { "${it.dpLiteral()}.toPx()" } ?: "height",
      width?.floatLiteral() ?: inDp("width"),
      height?.floatLiteral() ?: inDp("height"),
    )
  }

  private fun drawOperation(
    nodeId: String,
    depth: Int,
    hoisted: MutableList<String>,
  ): List<String> {
    val pad = INDENT.repeat(depth)
    val node =
      document.nodes[nodeId]
        ?: run {
          refusals += "nodes.$nodeId: missing node"
          return emptyList()
        }
    val operation =
      UiDrawing.BY_ID[node.componentId]
        ?: run {
          refusals +=
            "`$nodeId` (${node.componentId}) is not a draw operation; a ${UiDrawing.CANVAS} " +
              "holds only ${UiDrawing.OPERATIONS.joinToString { it.componentId }}"
          return emptyList()
        }
    usedComponentImports += "androidx.compose.remote.creation.compose.layout.RemoteOffset"
    val stroked = node.properties["style"]?.stringOrNull() == "stroke"
    if (operation.container) return drawContainer(node, pad, depth, hoisted)
    val paint =
      localName("paint${node.id.remoteIdentifier().replaceFirstChar { it.uppercaseChar() }}")
    val lines = mutableListOf<String>()
    lines += "${pad}val $paint = " + drawPaint(node, operation, stroked, hoisted, pad)
    // The stroke straddles the outline; a box the author did not state is inset by half of it so
    // a ring drawn with defaults stays inside its canvas.
    val inset =
      if (stroked && listOf("xDp", "yDp", "widthDp", "heightDp").none { node.has(it) })
        node.drawPx("strokeWidthDp")?.let { "($it / 2.rf)" }
      else null
    fun box(): Pair<String, String> {
      val x = node.drawPx("xDp") ?: inset ?: "0.rf"
      val y = node.drawPx("yDp") ?: inset ?: "0.rf"
      val w =
        node.drawPx("widthDp")
          ?: inset?.let { "(${extent.width} - $it * 2.rf)" }
          ?: "(${extent.width} - $x)"
      val h =
        node.drawPx("heightDp")
          ?: inset?.let { "(${extent.height} - $it * 2.rf)" }
          ?: "(${extent.height} - $y)"
      usedComponentImports += "androidx.compose.remote.creation.compose.layout.RemoteSize"
      usesRemoteFloat = true
      return "RemoteOffset($x, $y)" to "RemoteSize($w, $h)"
    }
    fun draw(symbol: String, vararg arguments: String) {
      lines += pad + call(symbol, arguments.toList(), pad)
    }
    when (node.componentId) {
      "draw/rect" -> {
        val (topLeft, size) = box()
        val radius = node.drawPx("cornerRadiusDp")
        if (radius == null) draw("drawRect", paint, topLeft, size)
        else draw("drawRoundRect", paint, topLeft, size, "RemoteOffset($radius, $radius)")
      }
      "draw/oval" -> {
        val (topLeft, size) = box()
        draw("drawOval", paint, topLeft, size)
      }
      "draw/arc" -> {
        val (topLeft, size) = box()
        val start = node.drawFloat("startAngle") ?: 0f.floatLiteral()
        val sweep = node.drawFloat("sweepAngle") ?: 360f.floatLiteral()
        val useCenter = node.properties["useCenter"]?.boolOrNull() ?: false
        draw(
          "drawArc",
          paint,
          "startAngle = $start",
          "sweepAngle = $sweep",
          "useCenter = $useCenter",
          "topLeft = $topLeft",
          "size = $size",
        )
      }
      "draw/circle" -> {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.min"
        usesRemoteFloat = true
        val radius =
          node.drawPx("radiusDp")
            ?: ("(min(${extent.width}, ${extent.height}) / 2.rf" +
              (inset?.let { " - $it" } ?: "") +
              ")")
        val centre =
          if (node.has("centerXDp") || node.has("centerYDp"))
            "RemoteOffset(${node.drawPx("centerXDp") ?: extent.centerX}, " +
              "${node.drawPx("centerYDp") ?: extent.centerY})"
          else extent.center
        draw("drawCircle", paint, "radius = $radius", "center = $centre")
      }
      "draw/line" -> {
        usesRemoteFloat = true
        val start =
          "RemoteOffset(${node.drawPx("startXDp") ?: "0.rf"}, ${node.drawPx("startYDp") ?: "0.rf"})"
        val end =
          "RemoteOffset(${node.drawPx("endXDp") ?: extent.width}, ${node.drawPx("endYDp") ?: extent.height})"
        draw("drawLine", paint, "start = $start", "end = $end")
      }
      "draw/path" -> {
        val data = node.properties["pathData"]?.stringOrNull().orEmpty()
        if (data.isBlank()) {
          refusals += "nodes.${node.id}.pathData: a path needs SVG path data to draw"
          return emptyList()
        }
        usesRemotePath = true
        usesRemoteFloat = true
        val viewportWidth = node.drawFloat("viewportWidth") ?: 24f.floatLiteral()
        val viewportHeight = node.drawFloat("viewportHeight") ?: 24f.floatLiteral()
        lines +=
          "${pad}withTransform({ scale(${extent.width} / $viewportWidth, ${extent.height} / $viewportHeight, " +
            "RemoteOffset(0.rf, 0.rf)) }) {"
        lines += "$pad${INDENT}drawPath(remotePathOf(\"${data.escaped()}\"), $paint)"
        lines += "$pad}"
      }
      UiDrawing.POLYLINE -> lines += polyline(node, paint, pad) ?: return emptyList()
      UiDrawing.MORPH -> {
        val from = node.properties["pathData"]?.stringOrNull().orEmpty()
        val to = node.properties["toPathData"]?.stringOrNull().orEmpty()
        if (UiDrawing.tweenPathData(from, to, 0f) == null) {
          refusals +=
            "nodes.${node.id}.toPathData: a morph needs two paths with the same commands and " +
              "the same count of numbers"
          return emptyList()
        }
        usesRemotePath = true
        usesRemoteFloat = true
        // Clamped to 0..1 as the canvas clamps it: the player's tween is `from + (to - from) * t`
        // and extrapolates past either end.
        val authoredProgress = node.properties["progress"]
        val progress =
          when {
            authoredProgress == null -> 0f.floatLiteral()
            authoredProgress.isDrawComputed() -> {
              val live = node.drawFloat("progress") ?: return emptyList()
              usedComponentImports += "$REMOTE_STATE_PACKAGE.clamp"
              "clamp($live, 0.rf, 1.rf)"
            }
            else -> (authoredProgress.numberOrNull() ?: 0f).coerceIn(0f, 1f).floatLiteral()
          }
        val viewportWidth = node.drawFloat("viewportWidth") ?: 24f.floatLiteral()
        val viewportHeight = node.drawFloat("viewportHeight") ?: 24f.floatLiteral()
        lines +=
          "${pad}withTransform({ scale(${extent.width} / $viewportWidth, ${extent.height} / $viewportHeight, " +
            "RemoteOffset(0.rf, 0.rf)) }) {"
        lines +=
          "$pad${INDENT}drawTweenPath(remotePathOf(\"${from.escaped()}\"), " +
            "remotePathOf(\"${to.escaped()}\"), tween = $progress, paint = $paint)"
        lines += "$pad}"
      }
      "draw/text" -> {
        val text = drawText(node) ?: return emptyList()
        val pan =
          when (node.properties["align"]?.stringOrNull()) {
            "start" -> "-1"
            "end" -> "1"
            else -> "0"
          }
        usesRemoteFloat = true
        draw(
          "drawAnchoredText",
          text,
          node.drawPx("xDp") ?: extent.centerX,
          node.drawPx("yDp") ?: extent.centerY,
          paint,
          "panX = $pan.rf",
          "panY = ${UiDrawing.textPanY(node.properties["verticalAlign"]?.stringOrNull()).toInt()}.rf",
        )
      }
      UiDrawing.TEXT_CIRCLE -> {
        val text = drawText(node) ?: return emptyList()
        usedComponentImports += "$REMOTE_STATE_PACKAGE.min"
        usesRemoteFloat = true
        val radius =
          node.drawPx("radiusDp")
            ?: ("(min(${extent.width}, ${extent.height}) / 2.rf - " +
              "${node.drawSpPx("textSizeSp") ?: 14f.spLiteral() + ".toPx()"})")
        draw(
          "drawTextOnCircle",
          text,
          node.drawPx("centerXDp") ?: extent.centerX,
          node.drawPx("centerYDp") ?: extent.centerY,
          radius,
          node.drawFloat("angle") ?: 270f.floatLiteral(),
          "0.rf",
          paint,
        )
      }
      UiDrawing.TEXT_PATH -> {
        val data = node.properties["pathData"]?.stringOrNull().orEmpty()
        if (data.isBlank()) {
          refusals += "nodes.${node.id}.pathData: text on a path needs SVG path data to follow"
          return emptyList()
        }
        val text = drawText(node) ?: return emptyList()
        usesRemotePath = true
        usesRemoteFloat = true
        // The path is in dp, so the drawing is scaled by one dp's pixels; the paint's text size
        // is divided by the same, so the glyphs come out the size authored.
        fun dp(name: String) = node.drawFloat(name) ?: "0.rf"
        val density = "${1f.dpLiteral()}.toPx()"
        lines += "${pad}withTransform({ scale($density, $density, RemoteOffset(0.rf, 0.rf)) }) {"
        lines +=
          "$pad${INDENT}drawTextOnPath($text, remotePathOf(\"${data.escaped()}\"), " +
            "hOffset = ${dp("startDp")}, vOffset = ${dp("offsetDp")}, paint = $paint)"
        lines += "$pad}"
      }
    }
    return lines
  }

  /** A drawn text: a literal, or a value the player computes. */
  private fun drawText(node: UiBuilderNode): String? {
    val authored = node.properties["text"]
    if (authored != null && authored.isDrawComputed())
      return computed(authored, UiValueKind.STRING, "nodes.${node.id}.text")
    usesRemoteString = true
    return "\"${authored?.stringOrNull().orEmpty().escaped()}\".rs"
  }

  /**
   * A container operation: a transform group, a clip, a conditional or a loop, each the
   * `RemoteDrawScope` block it is, around its own `ops`.
   */
  private fun drawContainer(
    node: UiBuilderNode,
    pad: String,
    depth: Int,
    hoisted: MutableList<String>,
  ): List<String> {
    usesRemoteFloat = true
    fun children() =
      node.slots[UiDrawing.OPS_SLOT].orEmpty().flatMap { drawOperation(it, depth + 1, hoisted) }
    fun block(head: String, parameter: String = "") =
      listOf("$pad$head {$parameter") + children() + listOf("$pad}")
    return when (node.componentId) {
      UiDrawing.CLIP -> {
        val x = node.drawPx("xDp")
        val y = node.drawPx("yDp")
        fun far(near: String?, length: String?, whole: String) =
          when {
            length == null -> whole
            near == null -> length
            else -> "($near + $length)"
          }
        val right = far(x, node.drawPx("widthDp"), extent.width)
        val bottom = far(y, node.drawPx("heightDp"), extent.height)
        val authoredExclude = node.properties["exclude"]
        if (authoredExclude != null && authoredExclude.isDrawComputed()) {
          // `clipRect` takes its ClipOp at write time; the player has no way to switch it.
          refusals +=
            "nodes.${node.id}.exclude: whether a clip excludes is fixed when it is written; " +
              "give it a literal, or put the operations under a draw/if"
          return emptyList()
        }
        val exclude = authoredExclude?.boolOrNull() == true
        val clipPath = node.properties["pathData"]?.stringOrNull()?.takeIf { it.isNotBlank() }
        if (clipPath != null) {
          // Scale into the path's viewport, clip, and scale back out, so the clip has the
          // viewport's shape while enclosed operations keep canvas coordinates.
          usesRemotePath = true
          val viewportWidth = node.drawFloat("viewportWidth") ?: 24f.floatLiteral()
          val viewportHeight = node.drawFloat("viewportHeight") ?: 24f.floatLiteral()
          val op =
            if (exclude) {
              usedComponentImports += "androidx.compose.ui.graphics.ClipOp"
              ", ClipOp.Difference"
            } else ""
          return block(
            "withTransform({ scale(${extent.width} / $viewportWidth, ${extent.height} / " +
              "$viewportHeight); clipPath(remotePathOf(\"${clipPath.escaped()}\")$op); " +
              "scale($viewportWidth / ${extent.width}, $viewportHeight / ${extent.height}) })"
          )
        }
        val op =
          if (exclude) {
            usedComponentImports += "androidx.compose.ui.graphics.ClipOp"
            ", clipOp = ClipOp.Difference"
          } else ""
        block("clipRect(${x ?: "0.rf"}, ${y ?: "0.rf"}, $right, $bottom$op)")
      }
      UiDrawing.IF -> {
        val authored = node.properties["condition"]
        val condition =
          when {
            authored == null -> {
              refusals +=
                "nodes.${node.id}.condition: a ${UiDrawing.IF} needs a condition; bind it to " +
                  "state or a formula"
              return emptyList()
            }
            authored.isDrawComputed() ->
              computed(authored, UiValueKind.BOOL, "nodes.${node.id}.condition")
                ?: return emptyList()
            else -> {
              usesRemoteBoolean = true
              "${authored.boolOrNull() == true}.rb"
            }
          }
        block("drawConditionally($condition)")
      }
      UiDrawing.REPEAT -> {
        val index = UiDrawing.indexName(node)
        if (index == null || !UiDrawing.isIndexName(index) || index in drawBindings) {
          refusals +=
            "nodes.${node.id}.index: the index must be a literal name a formula can read, " +
              "unused by an enclosing repeat"
          return emptyList()
        }
        val until =
          node.drawFloat("until")
            ?: run {
              refusals += "nodes.${node.id}.until: a ${UiDrawing.REPEAT} needs `until`"
              return emptyList()
            }
        val from = node.drawFloat("from") ?: 0f.floatLiteral()
        // A step that reached 0 or below while playing would never end the loop, so it is a
        // literal, checked here, rather than state or a formula nobody can check.
        val authoredStep = node.properties["step"]
        val step =
          when {
            authoredStep == null -> 1f.floatLiteral()
            authoredStep.isDrawComputed() || (authoredStep.numberOrNull() ?: 0f) <= 0f -> {
              refusals += "nodes.${node.id}.step: a repeat's step must be a number above 0"
              return emptyList()
            }
            else -> node.drawFloat("step")!!
          }
        // The lambda parameter is named apart from every state variable and local, so a formula
        // reading state `i` inside a loop over `i` still reads the state.
        val parameter = localName(index)
        drawBindings[index] = parameter
        try {
          block("loop($from, $until, $step)", " $parameter ->")
        } finally {
          drawBindings.remove(index)
        }
      }
      else -> {
        val pivot =
          if (node.has("pivotXDp") || node.has("pivotYDp"))
            "RemoteOffset(${node.drawPx("pivotXDp") ?: extent.centerX}, " +
              "${node.drawPx("pivotYDp") ?: extent.centerY})"
          else extent.center
        val transforms =
          listOfNotNull(
            if (node.has("translateXDp") || node.has("translateYDp"))
              "translate(${node.drawPx("translateXDp") ?: "0.rf"}, " +
                "${node.drawPx("translateYDp") ?: "0.rf"})"
            else null,
            node.drawFloat("rotate")?.let { "rotate($it, $pivot)" },
            scale(node)?.let { (x, y) -> "scale($x, $y, $pivot)" },
          )
        block("withTransform({ ${transforms.joinToString("; ")} })")
      }
    }
  }

  /**
   * A [UiDrawing.POLYLINE] as a path built while the document is written: one segment per point,
   * each point's formulas read with the index bound to that point's number. The coordinates stay
   * live, so state and time still move the points.
   */
  private fun polyline(node: UiBuilderNode, paint: String, pad: String): List<String>? {
    val where = "nodes.${node.id}"
    val index = UiDrawing.indexName(node)
    if (index == null || !UiDrawing.isIndexName(index) || index in drawBindings) {
      refusals +=
        "$where.index: the index must be a literal name a formula can read, unused by an " +
          "enclosing repeat"
      return null
    }
    val authoredCount = node.properties["count"]
    val count =
      authoredCount
        ?.takeUnless { it.isDrawComputed() }
        ?.numberOrNull()
        ?.takeIf { it == kotlin.math.floor(it) && it >= 2f && it <= UiDrawing.MAX_POINTS }
        ?.toInt()
    if (count == null) {
      refusals +=
        "$where.count: a ${UiDrawing.POLYLINE} needs a literal count of points from 2 to " +
          "${UiDrawing.MAX_POINTS}"
      return null
    }
    val parameter = localName(index)
    drawBindings[index] = "$parameter.rf"
    val (x, y) =
      try {
        node.drawPx("pointXDp") to node.drawPx("pointYDp")
      } finally {
        drawBindings.remove(index)
      }
    for ((name, value) in listOf("pointXDp" to x, "pointYDp" to y)) {
      if (value == null && name !in node.properties) {
        refusals += "$where.$name: a ${UiDrawing.POLYLINE} needs a formula for each point"
      }
    }
    if (x == null || y == null) return null
    usesRemoteFloat = true
    usedComponentImports += "$REMOTE_STATE_PACKAGE.remotePath"
    val path =
      localName("path${node.id.remoteIdentifier().replaceFirstChar { it.uppercaseChar() }}")
    val point = localName("point")
    return listOfNotNull(
      "${pad}val $path = remotePath {",
      "$pad${INDENT}for ($parameter in 0 until $count) {",
      "$pad$INDENT${INDENT}val $point = RemoteOffset($x, $y)",
      "$pad$INDENT${INDENT}if ($parameter == 0) moveTo($point) else lineTo($point)",
      "$pad$INDENT}",
      if (node.properties["closed"]?.boolOrNull() == true) "$pad${INDENT}close()" else null,
      "$pad}",
      "${pad}drawPath($path, $paint)",
    )
  }

  /** A group's scale across and down: `scaleX`/`scaleY`, each falling back to `scale`, then 1. */
  private fun scale(node: UiBuilderNode): Pair<String, String>? {
    val both = node.drawFloat("scale")
    val x = node.drawFloat("scaleX") ?: both
    val y = node.drawFloat("scaleY") ?: both
    if (x == null && y == null) return null
    return (x ?: 1f.floatLiteral()) to (y ?: 1f.floatLiteral())
  }

  /**
   * The loop indices in scope while a canvas is written, by the name a formula reads them as and
   * the lambda parameter that holds them.
   */
  private val drawBindings = linkedMapOf<String, String>()

  /** `RemotePaint { … }` for one operation's colour, style, stroke and alpha. */
  private fun drawPaint(
    node: UiBuilderNode,
    operation: UiDrawing.Operation,
    stroked: Boolean,
    hoisted: MutableList<String>,
    pad: String,
  ): String {
    usedComponentImports += "$REMOTE_STATE_PACKAGE.RemotePaint"
    val colour = drawColor(node, hoisted)
    val lines = mutableListOf("color = $colour")
    if (stroked || operation.componentId == "draw/line") {
      usedComponentImports += "androidx.compose.ui.graphics.PaintingStyle"
      lines += "style = PaintingStyle.Stroke"
      val width = node.drawPx("strokeWidthDp") ?: (1f.dpLiteral() + ".toPx()")
      lines +=
        if (operation.componentId == "draw/path" || operation.componentId == UiDrawing.MORPH) {
          // The viewport scale also scales the stroke, so divide it back out to keep strokeWidthDp
          // in canvas dp. The two-axis mean is exact when the aspect is preserved.
          val viewportWidth = node.drawFloat("viewportWidth") ?: 24f.floatLiteral()
          val viewportHeight = node.drawFloat("viewportHeight") ?: 24f.floatLiteral()
          "strokeWidth = ($width / ((${extent.width} / $viewportWidth + " +
            "${extent.height} / $viewportHeight) / 2.rf))"
        } else "strokeWidth = $width"
    }
    node.properties["strokeCap"]?.stringOrNull()?.let { cap ->
      usedComponentImports += "androidx.compose.ui.graphics.StrokeCap"
      lines += "strokeCap = StrokeCap.${cap.replaceFirstChar { it.uppercaseChar() }}"
    }
    node.properties["strokeJoin"]?.stringOrNull()?.let { join ->
      if (join !in UiDrawing.STROKE_JOINS) {
        refusals += "nodes.${node.id}.strokeJoin: `$join` is not one of ${UiDrawing.STROKE_JOINS}"
      } else {
        usedComponentImports += "androidx.compose.ui.graphics.StrokeJoin"
        lines += "strokeJoin = StrokeJoin.${join.replaceFirstChar { it.uppercaseChar() }}"
      }
    }
    node.properties["blendMode"]?.stringOrNull()?.let { mode ->
      if (mode !in UiDrawing.BLEND_MODES) {
        refusals += "nodes.${node.id}.blendMode: `$mode` is not one of ${UiDrawing.BLEND_MODES}"
      } else {
        usedComponentImports += "androidx.compose.ui.graphics.BlendMode"
        lines += "blendMode = BlendMode.${mode.replaceFirstChar { it.uppercaseChar() }}"
      }
    }
    when (operation.componentId) {
      "draw/text",
      UiDrawing.TEXT_CIRCLE ->
        lines += "textSize = ${node.drawSpPx("textSizeSp") ?: 14f.spLiteral() + ".toPx()"}"
      UiDrawing.TEXT_PATH ->
        lines +=
          "textSize = ${node.drawSpPx("textSizeSp") ?: 14f.spLiteral() + ".toPx()"} / " +
            "${1f.dpLiteral()}.toPx()"
    }
    drawGradient(node, hoisted, colour)?.let { lines += it }
    return "RemotePaint {\n" + lines.joinToString("\n") { "$pad$INDENT$it" } + "\n$pad}"
  }

  /**
   * A gradient paint: the `RemoteBrush` from `color` to `gradientColor`, applied to the paint over
   * the whole canvas, as `RemoteModifier.background(brush)` applies one over its component.
   */
  private fun drawGradient(
    node: UiBuilderNode,
    hoisted: MutableList<String>,
    from: String,
  ): String? {
    val kind = node.properties["gradient"]?.stringOrNull() ?: return null
    if (kind !in UiDrawing.GRADIENTS) {
      refusals += "nodes.${node.id}.gradient: `$kind` is not one of ${UiDrawing.GRADIENTS}"
      return null
    }
    val factory = "${kind}Gradient"
    usedComponentImports += "androidx.compose.remote.creation.compose.shaders.RemoteBrush"
    usedComponentImports += "androidx.compose.remote.creation.compose.shaders.$factory"
    usedComponentImports += "androidx.compose.remote.creation.compose.layout.RemoteSize"
    // A listed run of colours replaces the two ends, each faded by the paint's alpha as they are.
    val colours =
      UiDrawing.gradientColors(node)?.map { gradientStop(node, it, hoisted) ?: return null }
        ?: listOf(from, drawColor(node, hoisted, "gradientColor", transparentWhenAbsent = true))
    // A path is drawn under a viewport-to-canvas scale, which scales the shader too, so its
    // gradient spans the viewport rather than the canvas, and its geometry is in viewport units.
    val inViewport = node.componentId == "draw/path" || node.componentId == UiDrawing.MORPH
    val width =
      if (inViewport) node.drawFloat("viewportWidth") ?: 24f.floatLiteral() else extent.width
    val height =
      if (inViewport) node.drawFloat("viewportHeight") ?: 24f.floatLiteral() else extent.height
    fun length(name: String): String? = if (inViewport) node.drawFloat(name) else node.drawPx(name)
    val geometry = gradientGeometry(node, kind, ::length, width, height) ?: return null
    val stops = UiDrawing.gradientStops(node)
    val brush =
      if (stops == null) {
        (listOf("listOf(${colours.joinToString()})") + geometry).joinToString()
      } else {
        if (
          UiDrawing.gradientColors(node)?.size != stops.size ||
            stops.any { it == null || it !in 0f..1f }
        ) {
          refusals +=
            "nodes.${node.id}.gradientStops: one number from 0 to 1 per gradientColors entry, " +
              "separated by commas"
          return null
        }
        // The stops overload of a horizontal or vertical gradient has no defaults for its ends.
        val ends =
          when (kind) {
              "horizontal" -> listOf("startX", "endX")
              "vertical" -> listOf("startY", "endY")
              else -> emptyList()
            }
            .filter { end -> geometry.none { it.startsWith("$end =") } }
            .map { "$it = null" }
        (stops.zip(colours) { stop, colour -> "${stop!!.floatLiteral()} to $colour" } +
            geometry +
            ends)
          .joinToString()
      }
    return "with(RemoteBrush.$factory($brush)) { " +
      "applyTo(this@RemotePaint, RemoteSize($width, $height)) }"
  }

  /**
   * A gradient's ends, centre, radius and tile mode as `RemoteBrush` arguments, in pixels from the
   * canvas's top-left; null, refused, when the tile mode is not one or the gradient cannot take it.
   */
  private fun gradientGeometry(
    node: UiBuilderNode,
    kind: String,
    length: (String) -> String?,
    width: String,
    height: String,
  ): List<String>? {
    val arguments = mutableListOf<String>()
    when (kind) {
      "horizontal",
      "vertical" -> {
        val axis = if (kind == "horizontal") "X" else "Y"
        length("gradientStartDp")?.let { arguments += "start$axis = $it" }
        length("gradientEndDp")?.let { arguments += "end$axis = $it" }
      }
      else -> {
        val x = length("gradientCenterXDp")
        val y = length("gradientCenterYDp")
        if (x != null || y != null) {
          usedComponentImports += "androidx.compose.remote.creation.compose.layout.RemoteOffset"
          arguments +=
            "center = RemoteOffset(${x ?: "($width / 2.rf)"}, ${y ?: "($height / 2.rf)"})"
        }
        if (kind == "radial") {
          // A shader's radius must be above 0; a computed one is the player's to keep there.
          val literal = node.properties["gradientRadiusDp"]?.numberOrNull()
          if (literal != null && literal <= 0f) {
            refusals += "nodes.${node.id}.gradientRadiusDp: a radial gradient's radius is above 0"
            return null
          }
          length("gradientRadiusDp")?.let { arguments += "radius = $it" }
        }
      }
    }
    val tileMode = node.properties["gradientTileMode"]?.stringOrNull() ?: return arguments
    if (tileMode !in UiDrawing.GRADIENT_TILE_MODES || kind == "sweep") {
      refusals +=
        "nodes.${node.id}.gradientTileMode: `$tileMode` is not one of " +
          "${UiDrawing.GRADIENT_TILE_MODES} on a horizontal, vertical or radial gradient"
      return null
    }
    usedComponentImports += "androidx.compose.ui.graphics.TileMode"
    arguments += "tileMode = TileMode.${tileMode.replaceFirstChar(Char::uppercaseChar)}"
    return arguments
  }

  /**
   * One colour of a `gradientColors` list: a hex literal or a theme role read above the canvas,
   * faded by the paint's alpha; null, refused, when it is neither.
   */
  private fun gradientStop(
    node: UiBuilderNode,
    token: String,
    hoisted: MutableList<String>,
  ): String? {
    val base =
      when {
        COLOR_HEX.matches(token) -> {
          usesColorLiteral = true
          "${token.argbLiteral()}.rc"
        }
        token == TRANSPARENT_TOKEN -> {
          usesColorLiteral = true
          "Color(0x00000000).rc"
        }
        token in PropertyValueKinds.CANVAS_COLOR_TOKENS -> {
          usesTheme = true
          val name = localName("drawColor")
          hoisted += "val $name = RemoteMaterialTheme.colorScheme.$token"
          name
        }
        else -> {
          refusals +=
            "nodes.${node.id}.gradientColors: `$token` is neither a #RRGGBB/#AARRGGBB colour " +
              "nor a theme role the canvas draws"
          return null
        }
      }
    val alpha = node.drawFloat("alpha") ?: return base
    return "$base.let { it.copy(alpha = it.alpha * $alpha) }"
  }

  /** A draw colour: a literal, a theme role read above the canvas, or a computed colour. */
  private fun drawColor(
    node: UiBuilderNode,
    hoisted: MutableList<String>,
    property: String = "color",
    transparentWhenAbsent: Boolean = false,
  ): String {
    val authored = node.properties[property]
    val alpha = node.drawFloat("alpha")
    val base =
      when {
        authored != null && authored.isDrawComputed() ->
          computed(authored, UiValueKind.COLOR, "nodes.${node.id}.$property")
        authored?.stringOrNull()?.startsWith("#") == true -> {
          usesColorLiteral = true
          "${authored.stringOrNull()!!.argbLiteral()}.rc"
        }
        authored?.stringOrNull() == TRANSPARENT_TOKEN -> {
          usesColorLiteral = true
          "Color(0x00000000).rc"
        }
        !authored?.stringOrNull().isNullOrEmpty() -> {
          usesTheme = true
          val name = localName("drawColor")
          hoisted += "val $name = RemoteMaterialTheme.colorScheme.${authored!!.stringOrNull()}"
          name
        }
        else -> null
      }
        ?: run {
          usesColorLiteral = true
          if (transparentWhenAbsent) "Color(0x00000000).rc" else "Color(0xFF000000).rc"
        }
    // Multiplied, as the canvas does: replacing it would turn a transparent end opaque.
    return if (alpha == null) base else "$base.let { it.copy(alpha = it.alpha * $alpha) }"
  }

  private fun UiBuilderNode.has(name: String): Boolean = name in properties

  /** A draw property that the player computes rather than a literal: state or an expression. */
  private fun JsonElement.isDrawComputed(): Boolean =
    UiExpressions.isComputed(this) ||
      (this as? JsonObject)?.plainString("type").let { it == "state" || it == "binding" }

  /** A dp property as pixels, or null when the operation does not state it. */
  private fun UiBuilderNode.drawPx(name: String): String? {
    val value = properties[name] ?: return null
    if (value.isDrawComputed()) {
      val expression = computed(value, UiValueKind.FLOAT, "nodes.$id.$name") ?: return null
      usedComponentImports += "$REMOTE_STATE_PACKAGE.asRemoteDp"
      return "$expression.asRemoteDp().toPx()"
    }
    return value.numberOrNull()?.let { "${it.dpLiteral()}.toPx()" }
  }

  /**
   * A text size authored in sp, in pixels as the player sizes text: literals via
   * `RemoteTextUnit.toPx()` (non-linear font scale); computed sizes scale by one sp's pixels
   * (linear), since `RemoteTextUnit`'s constructor is internal.
   */
  private fun UiBuilderNode.drawSpPx(name: String): String? {
    val value = properties[name] ?: return null
    if (value.isDrawComputed()) {
      val expression = computed(value, UiValueKind.FLOAT, "nodes.$id.$name") ?: return null
      return "($expression) * ${1f.spLiteral()}.toPx()"
    }
    return value.numberOrNull()?.let { "${it.spLiteral()}.toPx()" }
  }

  /** A unitless float property — an angle, a scale, an alpha — or null when absent. */
  private fun UiBuilderNode.drawFloat(name: String): String? {
    val value = properties[name] ?: return null
    if (value.isDrawComputed()) return computed(value, UiValueKind.FLOAT, "nodes.$id.$name")
    return value.numberOrNull()?.floatLiteral()
  }

  private fun remoteValue(
    node: UiBuilderNode,
    parameter: TargetParameter,
    value: JsonElement,
  ): String? {
    if ((value as? JsonObject)?.plainString("type") == "binding") {
      return bound(value, parameter.typeFqn.orEmpty(), "nodes.${node.id}.${parameter.name}") {
        scopedArgument(node, parameter, it)
      }
    }
    if (UiExpressions.isComputed(value)) {
      val kind =
        when (parameter.typeFqn) {
          REMOTE_STRING_FQN -> UiValueKind.STRING
          REMOTE_BOOLEAN_FQN -> UiValueKind.BOOL
          REMOTE_FLOAT_FQN -> UiValueKind.FLOAT
          REMOTE_INT_FQN -> UiValueKind.INT
          REMOTE_COLOR_FQN -> UiValueKind.COLOR
          else -> null
        }
      if (kind == null) {
        refusals +=
          "`${node.id}`.${parameter.name} cannot take a computed value as ${parameter.typeFqn}"
        return null
      }
      return computed(value, kind, "nodes.${node.id}.${parameter.name}")
    }
    if ((value as? JsonObject)?.plainString("type") == "state") {
      val kind =
        when (parameter.typeFqn) {
          REMOTE_STRING_FQN -> "string"
          REMOTE_BOOLEAN_FQN -> "bool"
          REMOTE_FLOAT_FQN -> "float"
          "androidx.compose.remote.creation.compose.state.RemoteInt" -> "int"
          else -> null
        }
      if (kind == null) {
        refusals +=
          "`${node.id}`.${parameter.name} cannot bind Remote scalar state to ${parameter.typeFqn}"
        return null
      }
      return remoteState(
        value.plainString("variable").orEmpty(),
        kind,
        "`${node.id}`.${parameter.name}",
      )
    }
    return when (parameter.typeFqn) {
      REMOTE_STRING_FQN ->
        value.stringOrNull()?.let {
          usesRemoteString = true
          "\"${it.escaped()}\".rs"
        }
      REMOTE_BOOLEAN_FQN ->
        value.boolOrNull()?.let {
          usesRemoteBoolean = true
          "$it.rb"
        }
      REMOTE_FLOAT_FQN ->
        value.numberOrNull()?.let {
          usesRemoteFloat = true
          "${it}f.rf"
        }
      REMOTE_TEXT_UNIT_FQN ->
        value.numberOrNull()?.let { sp ->
          // `rsp` only exists on Int; rather than round, refuse and name the value. The parameter
          // is defaulted, so the call is still written without a size.
          if (sp != sp.toInt().toFloat()) {
            refusals +=
              "nodes.${node.id}.${parameter.name}: a text size is written `<Int>.rsp`, and " +
                "$sp is not whole"
            null
          } else {
            usesRemoteTextUnit = true
            "${sp.toInt()}.rsp"
          }
        }
      REMOTE_COLOR_FQN -> remoteColor(value)
      REMOTE_DP_FQN -> value.numberOrNull()?.dpLiteral()
      "kotlin.String" -> value.stringOrNull()?.let { "\"${it.escaped()}\"" }
      "kotlin.Boolean" -> value.boolOrNull()?.toString()
      "kotlin.Int" -> value.intOrNull()?.toString()
      "kotlin.Float" -> value.numberOrNull()?.let { "${it}f" }
      else -> null
    }
  }

  /**
   * A colour a record parameter takes: an ARGB literal (`#FF…`), `transparent`, or a theme role
   * (`outline`, read as `RemoteMaterialTheme.colorScheme.outline`). Null for anything else.
   */
  private fun remoteColor(value: JsonElement): String? {
    val color = value.stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
      color.startsWith("#") -> "${color.argbLiteral()}.rc".also { usesColorLiteral = true }
      color == TRANSPARENT_TOKEN -> "Color(0x00000000).rc".also { usesColorLiteral = true }
      color.matches(COLOR_ROLE) ->
        "RemoteMaterialTheme.colorScheme.$color".also { usesTheme = true }
      else -> null
    }
  }

  /**
   * A `…Colors` parameter from the node's colour roles, through the library's factory:
   * `RemoteButtonDefaults.buttonColors(containerColor = …, contentColor = …)`. The roles are the
   * factory's own parameter names; any the node leaves out keep the factory's default. Null, with a
   * refusal, when a role is not a colour.
   */
  private fun colorBundle(node: UiBuilderNode, bundle: RemoteColorBundle): String? {
    val arguments =
      bundle.roles.mapNotNull { role ->
        val authored = node.properties[role] ?: return@mapNotNull null
        val color =
          if (UiExpressions.isComputed(authored))
            computed(authored, UiValueKind.COLOR, "nodes.${node.id}.$role") ?: return null
          else remoteColor(authored)
        if (color == null) {
          refusals += "nodes.${node.id}.$role: expected a colour literal or a theme role"
          return null
        }
        "$role = $color"
      }
    usedComponentImports += bundle.defaultsFqn
    return "${bundle.defaultsFqn.substringAfterLast('.')}.${bundle.factory}" +
      "(${arguments.joinToString()})"
  }

  /**
   * A `RemoteBorderStroke` parameter (the outlined card's `border`) from the node's `borderColor`
   * and `borderWidthDp`, the two properties the Compose export reads for a `BorderStroke`. The
   * width defaults to Material's 1dp outline. Null, with a refusal, when the colour is not one.
   */
  private fun borderStroke(node: UiBuilderNode, parameter: TargetParameter): String? {
    val where = "nodes.${node.id}.$BORDER_COLOR_PROPERTY"
    val color = node.properties[BORDER_COLOR_PROPERTY]?.let(::remoteColor)
    if (color == null) {
      refusals += "$where: `${parameter.name}` needs a colour literal or a theme role"
      return null
    }
    val width = node.properties[BORDER_WIDTH_PROPERTY]?.numberOrNull() ?: 1f
    usedComponentImports += REMOTE_BORDER_STROKE_FQN
    return "RemoteBorderStroke(${width.dpLiteral()}, $color)"
  }

  private fun container(
    node: UiBuilderNode,
    depth: Int,
    symbol: String,
    arguments: List<String>,
  ): List<String> {
    when (symbol) {
      "RemoteBox" -> usesBox = true
      "RemoteColumn" -> usesColumn = true
      "RemoteRow" -> usesRow = true
      "RemoteFitBox" -> usesFitBox = true
      "RemoteFlowRow" -> usesFlowRow = true
      "RemoteCollapsibleColumn" -> usesCollapsibleColumn = true
      "RemoteCollapsibleRow" -> usesCollapsibleRow = true
    }
    val pad = INDENT.repeat(depth)
    val children = node.slots["children"].orEmpty()
    if (children.isEmpty()) {
      return (pad + if (arguments.isEmpty()) "$symbol()" else call(symbol, arguments, pad)).split(
        "\n"
      )
    }
    val head =
      if (arguments.isEmpty()) "$symbol {"
      // `call` measures the call alone; the ` {` this appends is two more columns, and without
      // counting them a call landing on 99 or 100 columns is emitted one or two over the budget.
      else "${call(symbol, arguments, pad, trailing = OPENING_BRACE.length)}$OPENING_BRACE"
    // Children are emitted in this container's scope, which decides whether `weight` is legal.
    // Saved and restored because emission is depth-first.
    val enclosing = scope
    scope = symbol
    val body = children.flatMap { emit(it, depth + 1) }
    scope = enclosing
    return (pad + head).split("\n") + body + listOf("$pad}")
  }

  /** The container symbol whose lambda the node being emitted sits in, or null at the top. */
  private var scope: String? = null

  private var usesFitBox = false
  private var usesFlowRow = false
  private var usesCollapsibleColumn = false
  private var usesCollapsibleRow = false

  /**
   * [scope] as the axis it lays children out on; the collapsible scopes behave like the eager ones.
   */
  private val axisScope: String?
    get() =
      when (scope) {
        "RemoteCollapsibleColumn" -> "RemoteColumn"
        "RemoteCollapsibleRow" -> "RemoteRow"
        else -> scope
      }

  /**
   * The comment written above a layout a Glance Wear widget document cannot carry. Inside a widget
   * it is also an error on [diagnostics] at [node]: the file compiles, so nothing else would say so
   * before the capture throws.
   */
  private fun outsideWidgetProfile(
    node: UiBuilderNode,
    depth: Int,
    operation: String,
    symbol: String,
  ): List<String> {
    if (glanceWearWidget) {
      collectedDiagnostics +=
        ExportDiagnosticV1(
          severity = DiagnosticSeverityV1.ERROR,
          code = ExportDiagnosticCodes.OUTSIDE_WIDGET_PROFILE,
          message =
            "`${node.componentId}` `${node.id}` is written as `$symbol` ($operation), which is " +
              "outside the Glance Wear widget profile: the file compiles, but capturing this " +
              "widget on Android throws \"Operation … is not supported\"; use `layout/row` or " +
              "`layout/column` instead",
          nodeId = node.id,
        )
    }
    val pad = INDENT.repeat(depth)
    return listOf(
      "$pad// $operation is outside the Glance Wear widget profile.",
      "$pad// Capturing this widget on Android throws \"Operation … is not supported\".",
    )
  }

  /**
   * `RemoteFlowRow`'s arguments: both axes' arrangements and the per-line ceiling. The vertical
   * arrangement distributes the wrapped lines.
   */
  private fun flowRowArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    arrangement(node, "horizontalArrangement", "horizontalSpacingDp", horizontal = true)?.let {
      arguments += "horizontalArrangement = $it"
    }
    arrangement(node, "verticalArrangement", "verticalSpacingDp", horizontal = false)?.let {
      arguments += "verticalArrangement = $it"
    }
    node.properties["maxItemsInEachRow"]?.numberOrNull()?.let { max ->
      if (max < 1f || max % 1f != 0f) {
        refusals +=
          "the flow row `${node.id}` allows `$max` items per row; a ceiling is a whole number of " +
            "at least one, or absent for as many as fit"
      } else arguments += "maxItemsInEachRow = ${max.toInt()}"
    }
    return arguments
  }

  /**
   * A collapsible column's or row's arguments: the main-axis arrangement and the agreed or declared
   * cross-axis alignment. A row writes centre explicitly since `RemoteCollapsibleRow` defaults to
   * `Top`.
   */
  private fun collapsibleArguments(
    node: UiBuilderNode,
    pad: String,
    vertical: Boolean,
  ): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    if (vertical) {
      arrangement(node, "verticalArrangement", "verticalSpacingDp", horizontal = false)?.let {
        arguments += "verticalArrangement = $it"
      }
      (crossAxisAlignment(node, "alignHorizontal") ?: node.canvasHorizontalAlignment())
        .takeIf { it != "start" }
        ?.let {
          usesAlignment = true
          arguments += "horizontalAlignment = RemoteAlignment.${it.remoteHorizontal()}"
        }
    } else {
      arrangement(node, "horizontalArrangement", "horizontalSpacingDp", horizontal = true)?.let {
        arguments += "horizontalArrangement = $it"
      }
      (crossAxisAlignment(node, "alignVertical") ?: node.canvasVerticalAlignment())
        .takeIf { it != "top" }
        ?.let {
          usesAlignment = true
          arguments += "verticalAlignment = RemoteAlignment.${it.remoteVertical()}"
        }
    }
    return arguments
  }

  /**
   * One axis's arrangement from its word and spacing, or null for the default. Aligned words use
   * `spacedBy(space, alignment)`; `space*` words take no gap.
   */
  private fun arrangement(
    node: UiBuilderNode,
    wordProperty: String,
    spacingProperty: String,
    horizontal: Boolean,
  ): String? {
    val word = node.properties[wordProperty]?.stringOrNull().orEmpty()
    val spacing = node.properties[spacingProperty]?.numberOrNull()?.takeIf { it != 0f }
    val distributed =
      when (word) {
        "spaceBetween" -> "SpaceBetween"
        "spaceAround" -> "SpaceAround"
        "spaceEvenly" -> "SpaceEvenly"
        else -> null
      }
    if (distributed != null) {
      usesArrangement = true
      return "RemoteArrangement.$distributed"
    }
    val alignment = arrangementAlignment(word, horizontal)
    if (spacing == null && alignment == null) return null
    usesArrangement = true
    return when {
      spacing == null -> "RemoteArrangement.${if (word == "center") "Center" else alignment}"
      alignment == null -> "RemoteArrangement.spacedBy(${spacing.dpLiteral()})"
      else -> {
        usesAlignment = true
        "RemoteArrangement.spacedBy(${spacing.dpLiteral()}, RemoteAlignment.$alignment)"
      }
    }
  }

  /** The `RemoteAlignment` an aligned arrangement word names, or null for the axis's start. */
  private fun arrangementAlignment(word: String, horizontal: Boolean): String? =
    when (word) {
      "center" -> if (horizontal) "CenterHorizontally" else "CenterVertically"
      "end" -> if (horizontal) "End" else null
      "bottom" -> if (horizontal) null else "Bottom"
      else -> null
    }

  /**
   * A row's or column's main-axis arrangement: [arrangement] for its word and a literal spacing, or
   * `spacedBy` over the bound value when the spacing is a binding, aligned by the same word. A
   * `space*` word ignores the gap either way, as on the canvas.
   */
  private fun mainAxisArrangement(
    node: UiBuilderNode,
    wordProperty: String,
    spacingProperty: String,
    horizontal: Boolean,
  ): String? {
    val value = node.properties[spacingProperty]
    val word = node.properties[wordProperty]?.stringOrNull().orEmpty()
    if ((value as? JsonObject)?.plainString("type") != "binding" || word.startsWith("space")) {
      return arrangement(node, wordProperty, spacingProperty, horizontal)
    }
    val path = "nodes.${node.id}.$spacingProperty"
    val spacing =
      bound(value, "kotlin.Float", path) { primitiveArgument(it, "kotlin.Float", path) }
        ?: return null
    usesDp = true
    usesArrangement = true
    val alignment =
      arrangementAlignment(word, horizontal) ?: return "RemoteArrangement.spacedBy($spacing.rdp)"
    usesAlignment = true
    return "RemoteArrangement.spacedBy($spacing.rdp, RemoteAlignment.$alignment)"
  }

  /**
   * `RemoteFitBox`'s arguments. Its defaults centre on both axes, the opposite of `RemoteBox`, so
   * only a design that asks for an edge writes anything.
   */
  private fun fitBoxArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    when (val horizontal = node.properties["horizontalAlignment"]?.stringOrNull()) {
      null,
      "",
      "center" -> Unit
      else -> {
        usesAlignment = true
        arguments += "horizontalAlignment = RemoteAlignment.${horizontal.remoteHorizontal()}"
      }
    }
    when (val vertical = node.properties["verticalArrangement"]?.stringOrNull()) {
      null,
      "",
      "center" -> Unit
      "top" -> {
        usesArrangement = true
        arguments += "verticalArrangement = RemoteArrangement.Top"
      }
      "bottom" -> {
        usesArrangement = true
        arguments += "verticalArrangement = RemoteArrangement.Bottom"
      }
      else ->
        refusals +=
          "the fit box `${node.id}` arranges its child `$vertical`; a RemoteFitBox shows ONE " +
            "child, so it takes top, center or bottom"
    }
    return arguments
  }

  private fun boxArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // Each child aligns by its own `alignment`, else the box's `contentAlignment` (as Compose's
    // `Box`); `RemoteBox` aligns all children together. So all children must resolve to the same
    // corner, or the design cannot be written.
    val boxAlignment = node.properties["contentAlignment"]?.stringOrNull().orEmpty()
    val children = node.slots["children"].orEmpty().mapNotNull(document.nodes::get)
    // Each child's resolved alignment, as declared ("" when neither child nor box says, which both
    // models draw at the top start).
    val resolved =
      children
        .map { it.declaredAlignment("align").ifEmpty { boxAlignment } }
        .ifEmpty { listOf(boxAlignment) }
    // Compared with the unstated default spelled out, so an explicit `topStart` beside an unaligned
    // child is one corner, not two.
    val corners = resolved.map { it.ifEmpty { DEFAULT_BOX_ALIGNMENT } }.distinct()
    val declared = resolved.firstOrNull { it.isNotEmpty() }
    when {
      corners.size > 1 ->
        refusals +=
          "the box `${node.id}` aligns its children differently from one another, which " +
            "RemoteBox aligns as a group"
      declared == null -> Unit
      else -> {
        usesAlignment = true
        arguments += "contentAlignment = RemoteAlignment.${declared.remoteAlignment()}"
      }
    }
    return arguments
  }

  /**
   * What a child asks to be aligned to: its scoped modifier if it has one (what the inspector
   * writes now), else its property (older designs).
   */
  private fun UiBuilderNode.declaredAlignment(modifierType: String): String =
    modifiers
      .mapNotNull { it as? JsonObject }
      .firstOrNull { it["type"]?.stringValue() == modifierType }
      ?.get("alignment")
      ?.stringValue() ?: properties["alignment"]?.stringOrNull().orEmpty()

  /**
   * The one alignment a row or column applies to every child, or null when none was asked for.
   * Children that disagree are refused.
   *
   * @param modifierType the scoped modifier this axis is read from, and the only source.
   */
  private fun crossAxisAlignment(node: UiBuilderNode, modifierType: String): String? {
    val children = node.slots["children"].orEmpty().mapNotNull(document.nodes::get)
    val scoped =
      children
        .mapNotNull { child ->
          child.modifiers
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["type"]?.stringValue() == modifierType }
            ?.get("alignment")
            ?.stringValue()
        }
        .distinct()
    if (scoped.size > 1) {
      refusals +=
        "`${node.id}` aligns its children differently from one another, and a " +
          "${if (modifierType == "alignHorizontal") "RemoteColumn" else "RemoteRow"} aligns them " +
          "as a group"
      return null
    }
    // Only the modifier: the canvas does not read the node's own alignment property.
    return scoped.singleOrNull()?.ifEmpty { null }
  }

  private fun columnArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    mainAxisArrangement(node, "verticalArrangement", "verticalSpacingDp", horizontal = false)?.let {
      arguments += "verticalArrangement = $it"
    }
    (crossAxisAlignment(node, "alignHorizontal") ?: node.canvasHorizontalAlignment())
      .takeIf { it != "start" }
      ?.let {
        usesAlignment = true
        arguments += "horizontalAlignment = RemoteAlignment.${it.remoteHorizontal()}"
      }
    return arguments
  }

  /**
   * A column's own `horizontalAlignment`, as the canvas reads it (default `Start`, same as
   * `RemoteColumn`).
   */
  private fun UiBuilderNode.canvasHorizontalAlignment(): String =
    when (properties["horizontalAlignment"]?.stringOrNull()) {
      "center" -> "center"
      "end" -> "end"
      else -> "start"
    }

  private fun rowArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    mainAxisArrangement(node, "horizontalArrangement", "horizontalSpacingDp", horizontal = true)
      ?.let { arguments += "horizontalArrangement = $it" }
    (crossAxisAlignment(node, "alignVertical") ?: node.canvasVerticalAlignment())
      .takeIf { it != "top" }
      ?.let {
        usesAlignment = true
        arguments += "verticalAlignment = RemoteAlignment.${it.remoteVertical()}"
      }
    return arguments
  }

  /**
   * A row's `verticalAlignment` as the canvas reads it, defaulting to centre. Written explicitly
   * because `RemoteRow` defaults to `Top`.
   */
  private fun UiBuilderNode.canvasVerticalAlignment(): String =
    when (properties["verticalAlignment"]?.stringOrNull()) {
      "top" -> "top"
      "bottom" -> "bottom"
      else -> "centerVertically"
    }

  /** `Symbol(a, b)` on one line, or one argument per line once that would exceed [MAX_LINE]. */
  private fun call(
    symbol: String,
    arguments: List<String>,
    pad: String = "",
    trailing: Int = 0,
  ): String {
    val single = "$symbol(${arguments.joinToString(", ")})"
    if (pad.length + single.length + trailing <= MAX_LINE) return single
    return buildString {
      appendLine("$symbol(")
      arguments.forEach { appendLine("$pad$INDENT$it,") }
      append("$pad)")
    }
  }

  private fun text(node: UiBuilderNode, pad: String = ""): String {
    when (textVocabulary) {
      RemoteTextVocabulary.MATERIAL3 -> usesMaterialText = true
      RemoteTextVocabulary.CREATION -> usesCreationText = true
    }
    val authored = node.properties["text"]
    val expression =
      if ((authored as? JsonObject)?.plainString("type") == "binding")
        bound(authored, REMOTE_STRING_FQN, "nodes.${node.id}.text") {
          scopedArgument(
            node,
            TargetParameter.Builder("text", "RemoteString")
              .apply { typeFqn = REMOTE_STRING_FQN }
              .build(),
            it,
          )
        } ?: "\"\".rs"
      else if ((authored as? JsonObject)?.plainString("type") == "state")
        remoteState(authored.plainString("variable").orEmpty(), "string", "`${node.id}`.text")
          ?: "\"\".rs"
      else if (UiExpressions.isComputed(authored))
        computed(authored!!, UiValueKind.STRING, "nodes.${node.id}.text") ?: "\"\".rs"
      else "\"${authored?.stringOrNull().orEmpty().escaped()}\".rs"
    val arguments = mutableListOf("text = $expression")
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    node.properties["color"]
      ?.takeIf(UiExpressions::isComputed)
      ?.let { computed(it, UiValueKind.COLOR, "nodes.${node.id}.color") }
      ?.let { arguments += "color = $it" }
    node.properties["color"]
      ?.takeUnless(UiExpressions::isComputed)
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let { color ->
        arguments +=
          if (color.startsWith("#")) {
            usesColorLiteral = true
            "color = ${color.argbLiteral()}.rc"
          } else {
            usesTheme = true
            "color = RemoteMaterialTheme.colorScheme.$color"
          }
      }
    // Either spelling: `m3/text` designs say `fontSizeSp`, the published component's derived
    // properties say `fontSize`.
    (node.properties["fontSizeSp"] ?: node.properties["fontSize"])
      ?.numberOrNull()
      ?.takeIf { it > 0f }
      ?.let { arguments += "fontSize = ${it.spLiteral()}" }
    val role = node.properties["style"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
    // Features ride on the style: `RemoteText` takes the axes as an argument of its own but has no
    // feature parameter, and `RemoteTextStyle.merge` writes either into the document.
    val features =
      FontSettings.formatFeatures(
          FontSettings.parseFeatures(node.properties[FontSettings.FEATURE_PROPERTY]?.stringOrNull())
        )
        .ifEmpty { null }
    if (role != null) usesTheme = true
    val base =
      if (role != null) "RemoteMaterialTheme.typography.$role"
      else if (features != null) {
        usesLocalTextStyle = true
        "LocalRemoteTextStyle.current"
      } else null
    base?.let {
      arguments +=
        if (features == null) "style = $it"
        else "style = $it.merge(fontFeatureSettings = \"${features.escaped()}\")"
    }
    FontSettings.parseVariations(node.properties[FontSettings.VARIATION_PROPERTY]?.stringOrNull())
      .takeIf { it.isNotEmpty() }
      ?.let { axes ->
        usesFontVariation = true
        arguments +=
          "fontVariationSettings = FontVariation.Settings(" +
            axes.joinToString(", ") {
              "FontVariation.Setting(\"${it.tag.escaped()}\", ${FontSettings.number(it.value)}f)"
            } +
            ")"
      }
    node.properties["textAlign"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        usesTextAlign = true
        arguments += "textAlign = TextAlign.${it.replaceFirstChar(Char::uppercaseChar)}"
      }
    node.properties["maxLines"]?.intOrNull()?.let { arguments += "maxLines = $it" }
    node.properties["overflow"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let { overflow ->
        val member = TEXT_OVERFLOW_MEMBERS[overflow]
        if (member == null) {
          notWritten(node, "overflow", "`$overflow` is not a `TextOverflow` this export names")
        } else {
          usesTextOverflow = true
          arguments += "overflow = TextOverflow.$member"
        }
      }
    // Offered on `m3/text` but with no lowering here yet: said on the node rather than dropped.
    UNWRITTEN_TEXT_SETTINGS.forEach { (property, neutral) ->
      val value = node.properties[property]?.stringOrNull()?.takeIf { it.isNotEmpty() }
      val number = value?.toFloatOrNull()
      val unchanged =
        value == neutral || (number != null && number == (neutral?.toFloatOrNull() ?: 0f))
      if (value != null && !unchanged) {
        notWritten(node, property, "`RemoteText` is not written with it by this export")
      }
    }
    return call("RemoteText", arguments, pad)
  }

  /**
   * A warning on [diagnostics] that [node]'s [property] is set and the source leaves it out,
   * because [reason]. For settings with no Remote spelling, which would otherwise vanish silently.
   */
  private fun notWritten(node: UiBuilderNode, property: String, reason: String) {
    collectedDiagnostics +=
      ExportDiagnosticV1(
        severity = DiagnosticSeverityV1.WARNING,
        code = ExportDiagnosticCodes.SETTING_NOT_WRITTEN,
        message =
          "`${node.componentId}` `${node.id}` sets `$property`, but $reason, so the generated " +
            "source leaves it out and the widget draws without it",
        nodeId = node.id,
      )
  }

  /**
   * `RemoteCustomComponent(name = "field", …)`: a hole the host fills with its own renderer. The
   * `content` slot is not walked, and the size comes from `widthDp`/`heightDp` because the player
   * cannot measure absent content. `@RestrictTo`, hence `@file:Suppress("RestrictedApi")`.
   */
  private fun customComponent(node: UiBuilderNode, pad: String): String? {
    // A blank `name` is a hole nothing can fill; refuse rather than emit `""`.
    val name = node.properties["name"]?.stringOrNull().orEmpty()
    if (name.isBlank()) {
      refusals +=
        "the custom component `${node.id}` has no name, and a custom operation is only reachable " +
          "by the name the host registers its renderer under — give it one in the inspector"
      return null
    }
    usesCustomComponent = true
    val width = node.properties["widthDp"]?.numberOrNull()?.takeIf { it > 0f }
    val height = node.properties["heightDp"]?.numberOrNull()?.takeIf { it > 0f }
    // Reserved bounds first, then the author's modifiers, so a padding insets the sized hole.
    val reserved =
      when {
        width != null && height != null ->
          listOf("size(${width.dpLiteral()}, ${height.dpLiteral()})").also {
            usedModifierImports += "size"
          }
        width != null ->
          listOf("width(${width.dpLiteral()})").also { usedModifierImports += "width" }
        height != null ->
          listOf("height(${height.dpLiteral()})").also { usedModifierImports += "height" }
        else -> emptyList()
      }
    val arguments = mutableListOf("name = \"${name.escaped()}\"")
    node.modifierExpression(pad, reserved)?.let { arguments += "modifier = $it" }
    return call("RemoteCustomComponent", arguments, pad)
  }

  /**
   * `LottieAnimation(json = …)` — Horologist's build-time Lottie compiler. URL-only elements are
   * refused (the builder resolves URLs into `json` at authoring time). The JSON goes into a
   * top-level constant.
   */
  private fun lottie(node: UiBuilderNode, pad: String): String? {
    val url = node.properties["url"]?.stringOrNull().orEmpty()
    val json = node.properties["json"]?.stringOrNull().orEmpty()
    if (json.isBlank()) {
      refusals +=
        if (url.isNotEmpty()) {
          "the Lottie element `${node.id}` carries only its URL (`$url`): a widget is built with " +
            "no network to fetch it from, so the animation's JSON has to be resolved in the " +
            "builder first"
        } else {
          "the Lottie element `${node.id}` has no animation — give it a URL to fetch, or paste " +
            "the animation's JSON in"
        }
      return null
    }
    // Reparsed: catches non-JSON here and drops pretty-print whitespace.
    val compact =
      try {
        Json.parseToJsonElement(json).toString()
      } catch (failure: Exception) {
        refusals +=
          "the Lottie element `${node.id}` does not hold valid JSON: ${failure.message ?: "it could not be parsed"}"
        return null
      }
    val literal = compact.escaped()
    // A JVM string constant is capped at 65535 bytes of modified UTF-8; past that the file would
    // not compile.
    if (literal.encodeToByteArray().size > MAX_STRING_CONSTANT_BYTES) {
      refusals +=
        "the Lottie animation on `${node.id}` is ${compact.encodeToByteArray().size / 1024}KiB, past the 64KiB a " +
          "Kotlin string constant holds — put the JSON in `res/raw` and call " +
          "`LottieAnimation(rawRes = R.raw.…)`, which takes the same animation"
      return null
    }
    usesLottie = true
    val constant =
      if (lottieDeclarations.isEmpty()) LOTTIE_CONSTANT
      else "${LOTTIE_CONSTANT}_${lottieDeclarations.size + 1}"
    lottieDeclarations += "private const val $constant = \"$literal\""
    val arguments = mutableListOf("json = $constant")
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // Absent progress means "run" on the document clock; a value pins one frame.
    node.properties["progress"]?.numberOrNull()?.let {
      usesRemoteFloat = true
      arguments += "progress = ${if (it % 1f == 0f) "${it.toInt()}.rf" else "${it}f.rf"}"
    }
    return call("LottieAnimation", arguments, pad)
  }

  /**
   * `RemoteIcon(Icons.Filled.Home.toRemoteImageVector(), …)`: a Material icon, keyed like `m3/icon`
   * and resolved through the same generated table, imported from the lowercased style package. An
   * unset key is the catalog default glyph; an unknown one is refused by name.
   */
  private fun icon(node: UiBuilderNode, pad: String): String? {
    val key =
      node.properties["imageVector"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
        ?: REMOTE_ICON_DEFAULT_KEY
    val member =
      ScreenDocumentProjection.ICON_MEMBERS[key]
        ?: run {
          refusals +=
            "the icon `${node.id}` names the icon key `$key`, which is not one of the Material " +
              "icon keys this builder resolves"
          return null
        }
    MaterialIconArtifacts.diagnostic(node.id, node.componentId, "imageVector", key)?.let {
      collectedDiagnostics += it
    }
    val path = member.split(".")
    usedComponentImports += "androidx.wear.compose.remote.material3.RemoteIcon"
    usedComponentImports += "androidx.compose.material.icons.Icons"
    usedComponentImports +=
      "androidx.compose.material.icons." +
        path.dropLast(1).joinToString(".") { it.lowercase() } +
        "." +
        path.last()
    usedComponentImports += "androidx.compose.remote.creation.compose.capture.toRemoteImageVector"
    val arguments = mutableListOf("imageVector = Icons.$member.toRemoteImageVector()")
    val description = node.properties["contentDescription"]?.stringOrNull().orEmpty()
    arguments +=
      if (description.isEmpty()) "contentDescription = null"
      else {
        usesRemoteString = true
        "contentDescription = \"${description.escaped()}\".rs"
      }
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // Unset inherits the enclosing content colour, which is how a button's colour reaches its icon.
    node.properties["tint"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let { color ->
        arguments +=
          if (color.startsWith("#")) {
            usesColorLiteral = true
            "tint = ${color.argbLiteral()}.rc"
          } else {
            usesTheme = true
            "tint = RemoteMaterialTheme.colorScheme.$color"
          }
      }
    return call("RemoteIcon", arguments, pad)
  }

  /**
   * The `RemoteImageBitmap` for asset [key], as a captured parameter or — on the native lane — the
   * bytes themselves. Null, with a refusal naming [what], when the lane needs bytes this host does
   * not have.
   */
  private fun imageBitmap(node: UiBuilderNode, key: String, what: String): String? {
    // The native lane wants bytes, not a parameter; a key with no bytes on this host is refused.
    val bitmap =
      if (!inlineContentImages) imageParameter(key)
      else
        when (val encoded = assets.base64(key)) {
          null -> {
            refusals +=
              "$what draws the asset `$key`, whose bytes this host could not " +
                "read — a native render carries the picture inside the document, so there is " +
                "nothing to draw it from"
            return null
          }
          else -> inlineBitmap(key, encoded)
        }
    usesRemoteImage = true
    val argument =
      captureValue(
        "image:$key",
        "androidx.compose.remote.creation.compose.state.RemoteImageBitmap",
      ) {
        bitmap
      }
    return argument
  }

  private fun image(node: UiBuilderNode, pad: String): String? {
    val key = node.properties["assetKey"]?.stringOrNull().orEmpty()
    if (key.isBlank()) {
      refusals +=
        "the image `${node.id}` names no asset, and a picture with no key is one nothing can " +
          "resolve — pick an asset for it in the inspector"
      return null
    }
    val argument = imageBitmap(node, key, "the image `${node.id}`") ?: return null
    val arguments = mutableListOf("remoteBitmap = $argument")
    val description = node.properties["contentDescription"]?.stringOrNull().orEmpty()
    arguments +=
      if (description.isEmpty()) "contentDescription = null"
      else {
        usesRemoteString = true
        "contentDescription = \"${description.escaped()}\".rs"
      }
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // Always written: the builder's default is `crop` but upstream's is `Fit`.
    usesContentScale = true
    arguments += "contentScale = ContentScale.${node.contentScale()}"
    return call("RemoteImage", arguments, pad)
  }

  private fun UiBuilderNode.contentScale(): String =
    when (properties["contentScale"]?.stringOrNull()) {
      "fit" -> "Fit"
      "fillBounds" -> "FillBounds"
      "inside" -> "Inside"
      else -> "Crop"
    }

  /**
   * The parameter name an asset key becomes, allocated once per key (nodes sharing an asset share a
   * parameter). Identifier collisions are suffixed.
   */
  private fun imageParameter(key: String): String {
    imageAssets[key]?.let {
      return it
    }
    val base = exportedStateIdentifier(key)
    val taken =
      imageAssets.values.toSet() +
        allocatedLocals +
        document.stateVariables.keys.map { it.remoteIdentifier() } +
        functions.values.map { it.name }
    val name =
      if (base !in taken) base
      else generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
    imageAssets[key] = name
    // In the bundle lane the parameter defaults to the shipped artwork so the `@Preview` draws the
    // design. A key the registry cannot answer is not a refusal: the app supplies content pictures.
    bundled?.contents(key)?.let { content ->
      bundledParameters[key] = bundleFile(name, key, content)
    }
    return name
  }

  /**
   * The identifier a widget background picture is drawn through, or null when there is none: an
   * inlined file-level `val`, or a bundle local that opens the archived file.
   */
  private fun backgroundBitmap(key: String): String? {
    if (key.isEmpty()) return null
    bundled?.let { registry ->
      val content = registry.contents(key) ?: return null
      bundledBackgroundAssets[key]?.let {
        return it.identifier
      }
      val name = allocateBitmapIdentifier(key)
      bundledBackgroundAssets[key] = bundleFile(name, key, content)
      return name
    }
    return assets.base64(key)?.let { inlineBitmap(key, it) }
  }

  /**
   * Where an asset's bytes go in the archive: `uibuilder/<design>/<key>.<extension>`, scoped by
   * design so keys cannot collide. The extension is cosmetic.
   */
  private fun bundleFile(identifier: String, key: String, content: WidgetAssetContent) =
    BundledAsset(
      identifier = identifier,
      assetKey = key,
      path =
        "$BUNDLE_DIRECTORY/${document.id.bundleSegment()}/$key.${content.mediaType.pictureExtension()}",
      mediaType = content.mediaType,
      base64 = content.base64,
    )

  private fun allocateBitmapIdentifier(key: String): String {
    val base = exportedStateIdentifier(key)
    val taken =
      inlineAssets.values.map(InlineAsset::identifier).toSet() +
        bundledBackgroundAssets.values.map(BundledAsset::identifier) +
        imageAssets.values
    return if (base !in taken) base
    else generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
  }

  private val bundledBackgroundAssets = linkedMapOf<String, BundledAsset>()

  private val bundledParameters = linkedMapOf<String, BundledAsset>()

  /** The background pictures the archive carries, in the order they were reached. */
  val bundledBackgrounds: List<BundledAsset>
    get() = bundledBackgroundAssets.values.toList()

  /** True once a picture has been written as a file rather than as bytes in the source. */
  val usesBundledBitmap: Boolean
    get() = bundledBackgroundAssets.isNotEmpty() || bundledParameters.isNotEmpty()

  /**
   * One picture shipped beside the source.
   *
   * @property identifier the local or parameter the source draws it through.
   * @property path where it goes inside the archive, and what the generated source opens.
   */
  data class BundledAsset(
    val identifier: String,
    val assetKey: String,
    val path: String,
    val mediaType: String,
    val base64: String,
  )

  /**
   * The identifier for a picture whose bytes the file carries, one per asset key. Backgrounds are
   * drawn by the system host without the app's process or resources, so their bytes must be inline.
   */
  private fun inlineBitmap(key: String, base64: String): String {
    inlineAssets[key]?.let {
      return it.identifier
    }
    val base = exportedStateIdentifier(key)
    val taken = inlineAssets.values.map(InlineAsset::identifier).toSet() + imageAssets.values
    val name =
      if (base !in taken) base
      else generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
    inlineAssets[key] = InlineAsset(name, key, base64)
    return name
  }

  private val inlineAssets = linkedMapOf<String, InlineAsset>()

  /** The pictures whose bytes the generated file carries, in the order they were reached. */
  val inlineBitmaps: List<InlineAsset>
    get() = inlineAssets.values.toList()

  /** One inlined picture: the val it becomes, the key it came from, and its bytes as base64. */
  data class InlineAsset(val identifier: String, val assetKey: String, val base64: String)

  private val imageAssets = linkedMapOf<String, String>()

  /**
   * The bitmaps this body was written against, in first-drawn order. The exporters turn these into
   * parameters, each with its own default policy.
   */
  val imageParameters: List<ImageParameter>
    get() = imageAssets.map { (key, identifier) ->
      ImageParameter(identifier, key, bundledParameters[key])
    }

  /**
   * @property assetKey the design's own key, named in the parameter's doc comment.
   * @property bundled the shipped artwork the parameter defaults to, or null when there is none.
   */
  data class ImageParameter(
    val identifier: String,
    val assetKey: String,
    val bundled: BundledAsset? = null,
  )

  /**
   * Top-level declarations the body refers to, in emission order; the exporter places them at file
   * level after the preview.
   */
  val declarations: List<String>
    get() =
      functions.values.map { it.declaration() } +
        lottieDeclarations.toList() +
        listOfNotNull(REMOTE_PATH_HELPER.takeIf { usesRemotePath })

  private val lottieDeclarations = mutableListOf<String>()

  /**
   * The imports the emitted file needs, sorted Kotlin-style and gated on what was actually written
   * so pasted code has no unused imports.
   *
   * @param widget which widget file this body is going into, or null for a non-widget fragment.
   */
  fun imports(widget: WidgetSourceShape?): List<String> {
    val imports = mutableSetOf<String>()
    if (widget is WidgetSourceShape.Exported) imports += "android.content.Context"
    if (usesBox) imports += "androidx.compose.remote.creation.compose.layout.RemoteBox"
    if (usesStateLayout)
      imports += "androidx.compose.remote.creation.compose.layout.RemoteStateLayout"
    if (usesColumn) imports += "androidx.compose.remote.creation.compose.layout.RemoteColumn"
    if (usesCollapsibleColumn)
      imports += "androidx.compose.remote.creation.compose.layout.RemoteCollapsibleColumn"
    if (usesCollapsibleRow)
      imports += "androidx.compose.remote.creation.compose.layout.RemoteCollapsibleRow"
    if (usesFitBox) imports += "androidx.compose.remote.creation.compose.layout.RemoteFitBox"
    if (usesFlowRow) imports += "androidx.compose.remote.creation.compose.layout.RemoteFlowRow"
    imports += "androidx.compose.remote.creation.compose.layout.RemoteComposable"
    if (usesCustomComponent) {
      imports += "androidx.compose.remote.creation.compose.layout.RemoteCustomComponent"
    }
    if (usesRemoteImage) imports += "androidx.compose.remote.creation.compose.layout.RemoteImage"
    if (usesRow) imports += "androidx.compose.remote.creation.compose.layout.RemoteRow"
    if (usesLottie) imports += "com.google.android.horologist.remotecompose.lottie.LottieAnimation"
    if (usesRemoteFloat) imports += "androidx.compose.remote.creation.compose.state.rf"
    if (usesRemotePath) imports += REMOTE_PATH_HELPER_IMPORTS
    if (usesRemoteTextUnit) imports += "androidx.compose.remote.creation.compose.state.rsp"
    if (usesRemoteBoolean) imports += "androidx.compose.remote.creation.compose.state.rb"
    if (usesCombinedAction)
      imports += "androidx.compose.remote.creation.compose.action.combinedAction"
    if (usesLambdaAction) {
      imports += "androidx.compose.remote.creation.compose.action.lambdaAction"
    }
    if (stateWrites.isNotEmpty()) {
      if (usesValueChange) imports += "androidx.compose.remote.creation.compose.action.valueChange"
      stateWrites.values.forEach { declaration ->
        val factory = declaration.substringAfter("= ").substringBefore('(')
        if ('.' in factory) {
          // `RemoteString.createNamedRemoteString(…, RemoteState.Domain.User)`: a host input.
          val type = factory.substringBefore('.')
          imports += "androidx.compose.remote.creation.compose.state.$type"
          imports += "androidx.compose.remote.creation.compose.state.RemoteState"
        } else {
          imports += "androidx.compose.remote.creation.compose.state.$factory"
        }
      }
    }
    if (usesRemoteInt) imports += "androidx.compose.remote.creation.compose.state.ri"
    imports += usedComponentImports
    if (usesAlignment) imports += "androidx.compose.remote.creation.compose.layout.RemoteAlignment"
    if (usesArrangement) {
      imports += "androidx.compose.remote.creation.compose.layout.RemoteArrangement"
    }
    if (usesModifier) imports += "androidx.compose.remote.creation.compose.modifier.RemoteModifier"
    if (usesRemoteScrollState) {
      imports += "androidx.compose.remote.creation.compose.modifier.rememberRemoteScrollState"
    }
    if (usesRoundedCornerShape) {
      imports += "androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape"
    }
    usedModifierImports.forEach {
      imports += "androidx.compose.remote.creation.compose.modifier.$it"
    }
    if (usesDp) imports += "androidx.compose.remote.creation.compose.state.rdp"
    if (usesColorLiteral) imports += "androidx.compose.remote.creation.compose.state.rc"
    if (usesRemoteImage || usesBrushImage)
      imports += "androidx.compose.remote.creation.compose.state.RemoteImageBitmap"
    if (usesMaterialText || usesCreationText || usesRemoteString) {
      imports += "androidx.compose.remote.creation.compose.state.rs"
    }
    if (usesSp) imports += "androidx.compose.remote.creation.compose.state.rsp"
    if (usesFractionalSp) {
      imports += "androidx.compose.remote.creation.compose.state.asRemoteTextUnit"
      imports += "androidx.compose.ui.unit.sp"
    }
    imports += "androidx.compose.runtime.Composable"
    if (usesColorLiteral) imports += "androidx.compose.ui.graphics.Color"
    if (usesContentScale) imports += "androidx.compose.ui.layout.ContentScale"
    if (usesTextAlign) imports += "androidx.compose.ui.text.style.TextAlign"
    if (usesTextOverflow) imports += "androidx.compose.ui.text.style.TextOverflow"
    if (usesFontVariation) imports += "androidx.compose.ui.text.font.FontVariation"
    if (usesLocalTextStyle) imports += "androidx.wear.compose.remote.material3.LocalRemoteTextStyle"
    // Widget-only imports; inline remote content in a screen needs none of these.
    if (widget != null) {
      imports += "androidx.glance.wear.WearWidgetBrush"
      if (usesBrushColor) imports += "androidx.glance.wear.color"
      if (usesBrushImage) imports += "androidx.glance.wear.image"
      if (usesHorizontalGradient) imports += "androidx.glance.wear.horizontalGradient"
      if (usesVerticalGradient) imports += "androidx.glance.wear.verticalGradient"
      imports += "androidx.glance.wear.core.WearWidgetParams"
      // Inlined bytes decode via Android's `Bitmap` before becoming the `ImageBitmap` `.rb` wraps.
      if (inlineAssets.isNotEmpty() || usesBundledBitmap) {
        imports += "android.graphics.BitmapFactory"
        imports += "androidx.compose.remote.creation.compose.state.rb"
        imports += "androidx.compose.ui.graphics.asImageBitmap"
      }
      // The one import that separates the lanes: a bundled picture is read from the byte
      // `AssetManager` opens the file at, and needs no base64 to get there.
      if (inlineAssets.isNotEmpty()) imports += "android.util.Base64"
    }
    if (widget is WidgetSourceShape.Exported) {
      imports += "androidx.compose.ui.tooling.preview.Preview"
      imports += "androidx.glance.wear.GlanceWearWidget"
      imports += "androidx.glance.wear.WearWidgetData"
      imports += "androidx.glance.wear.WearWidgetDocument"
      // The blank placeholder the generated widget class defaults an image parameter to, so the
      // `@Preview` beside it compiles without a bitmap only the application has.
      if (usesRemoteImage) {
        imports += "androidx.compose.remote.creation.compose.state.rb"
        imports += "androidx.compose.ui.graphics.ImageBitmap"
      }
      widget.previewParamsProviders.forEach {
        imports += "androidx.glance.wear.tooling.preview.$it"
      }
      imports += "androidx.glance.wear.tooling.preview.WearWidgetPreview"
    }
    if (widget is WidgetSourceShape.NativePreview) {
      // This lane builds the container spec for the selected host shape; padding and radius come
      // from `hostSpec`, never the design.
      imports += "androidx.glance.wear.core.ContainerInfo"
      imports += "androidx.glance.wear.core.WidgetInstanceId"
    }
    if (usesRemoteColorScheme) {
      imports += "androidx.wear.compose.remote.material3.RemoteColorScheme"
    }
    if (usesTheme) imports += "androidx.wear.compose.remote.material3.RemoteMaterialTheme"
    if (textRole != null) imports += "androidx.wear.compose.remote.material3.ProvideRemoteTextStyle"
    if (usesRemoteFontFamily)
      imports += "androidx.compose.remote.creation.compose.text.RemoteFontFamily"
    if (usesMaterialText) imports += "androidx.wear.compose.remote.material3.RemoteText"
    if (usesCreationText) imports += "androidx.compose.remote.creation.compose.layout.RemoteText"
    return imports.sorted()
  }

  /** A gradient call, recorded so [imports] names only the direction actually written. */
  private fun horizontal(call: String): String = call.also { usesHorizontalGradient = true }

  private fun vertical(call: String): String = call.also { usesVerticalGradient = true }

  private var usesModifier = false
  private var usesCustomComponent = false
  private var usesLottie = false
  private var usesRemoteFloat = false
  /** A path is drawn, clipped to or tweened, so the file carries [REMOTE_PATH_HELPER]. */
  private var usesRemotePath = false
  private var usesSp = false
  private var usesFractionalSp = false
  private var usesHorizontalGradient = false
  private var usesVerticalGradient = false
  private var usesRemoteScrollState = false
  private var usesRoundedCornerShape = false
  private var usesRemoteImage = false
  private var usesRemoteString = false
  private var usesRemoteTextUnit = false
  private var usesContentScale = false
  private val usedModifierImports = mutableSetOf<String>()

  /**
   * @param leading modifier calls this emitter derived from the node's own properties, applied
   *   before the authored chain.
   */
  private fun UiBuilderNode.modifierExpression(
    pad: String,
    leading: List<String> = emptyList(),
    modifierClick: Boolean = true,
  ): String? {
    fun bound(event: String) = (eventBindings[event] as? JsonArray)?.isNotEmpty() == true
    val click =
      if (modifierClick && (bound("longClick") || bound("doubleClick"))) {
        // A press, a long press and a double tap, each with its own ordered actions.
        val arguments =
          listOf(
              "click" to "onClick",
              "longClick" to "onLongClick",
              "doubleClick" to "onDoubleClick",
            )
            .filter { (event, _) -> bound(event) }
            .map { (_, parameter) ->
              "$parameter = " +
                (actionExpression(this, TargetParameter.Builder(parameter, "Action").build())
                  ?: return null)
            }
        modifierCall("combinedClickable(${arguments.joinToString()})")
      } else if (modifierClick && bound("click")) {
        actionExpression(this, TargetParameter.Builder("onClick", "Action").build())?.let {
          modifierCall("clickable($it)")
        }
      } else null
    // Touch down, up and cancel, each with its own ordered actions, after the click.
    val touches =
      if (!modifierClick) emptyList()
      else
        TOUCH_EVENTS.filter { (event, _) -> bound(event) }
          .map { (_, call) ->
            // The event key is the call without `on`, as for the click parameters.
            val action = actionExpression(this, TargetParameter.Builder(call, "Action").build())
            modifierCall("$call(${action ?: return null})")
          }
    // The frame's fill comes before [leading], as the container applies it. Omitted only when the
    // chain already starts with it; a later fill (`size(48.dp).fillMaxSize()`) is not equivalent.
    val frame =
      if (
        id == frameFillingRoot &&
          (modifiers.firstOrNull() as? JsonObject)?.get("type")?.stringValue() != "fillMaxSize"
      ) {
        listOf(modifierCall("fillMaxSize()"))
      } else emptyList()
    val parts =
      listOfNotNull(if (tagNodes) nodeTagCall(id) else null) +
        frame +
        leading +
        modifiers.flatMap { element -> modifierCalls(element) } +
        listOfNotNull(click) +
        touches
    if (parts.isEmpty()) return null
    usesModifier = true
    val single = parts.joinToString(".", prefix = "RemoteModifier.")
    // [call] only breaks between arguments, so a long modifier chain is broken at its dots, as a
    // formatter would.
    if (pad.length + INDENT.length + MODIFIER_ARGUMENT.length + single.length <= MAX_LINE) {
      return single
    }
    val continuation = "\n$pad$INDENT$INDENT."
    return parts.joinToString(continuation, prefix = "RemoteModifier.")
  }

  /** `semantics { contentDescription = "uib-node:<id>".rs }`; see [tagNodes]. */
  private fun nodeTagCall(nodeId: String): String {
    usesRemoteString = true
    return semanticsCall(
        mapOf("contentDescription" to "\"${NativeNodeTags.tag(nodeId).escaped()}\".rs"),
        "nodes.$nodeId",
      )
      .single()
  }

  /**
   * One authored modifier as zero, one or two calls. Two is `background(colour, shape)`, written as
   * `clip(shape).background(colour)` since `RemoteModifier` has no shape overload. A modifier with
   * no counterpart is refused by name with the reason, never dropped.
   */
  private fun UiBuilderNode.modifierCalls(element: JsonElement): List<String> {
    val modifier = element as? JsonObject ?: return emptyList()
    fun hasBinding(value: JsonElement): Boolean =
      when (value) {
        is JsonObject -> value.plainString("type") == "binding" || value.values.any(::hasBinding)
        is JsonArray -> value.any(::hasBinding)
        else -> false
      }
    if (hasBinding(modifier)) {
      refusals += "nodes.$id.modifiers: Remote modifier bindings need a typed lowering"
      return emptyList()
    }
    return when (val type = modifier["type"]?.stringValue()) {
      "fillMaxSize" -> listOf(modifierCall("fillMaxSize()"))
      "fillMaxWidth" -> listOf(modifierCall("fillMaxWidth()"))
      "fillMaxHeight" -> listOf(modifierCall("fillMaxHeight()"))
      "wrapContentSize" -> {
        // Upstream's `wrapContentSize()` takes no alignment; a named corner is refused rather than
        // centred.
        val alignment = modifier["alignment"]?.stringValue().orEmpty()
        if (alignment.isEmpty() || alignment == "center") listOf(modifierCall("wrapContentSize()"))
        else {
          refusals +=
            "the `wrapContentSize` modifier on `$id` aligns its content `$alignment`; a " +
              "RemoteModifier wraps and centres, with no alignment to give it"
          emptyList()
        }
      }
      "padding" -> {
        val start = modifier["startDp"]?.numberValue() ?: 0f
        val top = modifier["topDp"]?.numberValue() ?: 0f
        val end = modifier["endDp"]?.numberValue() ?: 0f
        val bottom = modifier["bottomDp"]?.numberValue() ?: 0f
        listOf(
          modifierCall(
            "padding(${start.dpLiteral()}, ${top.dpLiteral()}, ${end.dpLiteral()}, ${bottom.dpLiteral()})"
          )
        )
      }
      // `size(width, height)` is `width(width).height(height)` upstream, and both halves are
      // imported by the call rather than by the modifier's name.
      "size" -> listOf(modifierCall("size(${modifier.dp("widthDp")}, ${modifier.dp("heightDp")})"))
      "width" -> listOf(modifierCall("width(${modifier.dp("widthDp")})"))
      "height" -> listOf(modifierCall("height(${modifier.dp("heightDp")})"))
      "widthIn" -> listOf(modifierCall("widthIn(${modifier.boundsArguments()})"))
      "heightIn" -> listOf(modifierCall("heightIn(${modifier.boundsArguments()})"))
      "offset" -> listOf(modifierCall("offset(${modifier.dp("xDp")}, ${modifier.dp("yDp")})"))
      "alpha" -> listOf(modifierCall("alpha(${modifier.float("alpha", 1f)})"))
      "rotate" -> listOf(modifierCall("rotate(${modifier.float("degrees")})"))
      "scale" ->
        listOf(
          modifierCall("scale(${modifier.float("scaleX", 1f)}, ${modifier.float("scaleY", 1f)})")
        )
      "zIndex" -> listOf(modifierCall("zIndex(${modifier.float("zIndex")})"))
      "clip" -> listOf(modifierCall("clip(${modifier.shapeExpression()})"))
      "background" ->
        listOfNotNull(
          modifier["shape"]?.stringValue()?.let {
            modifierCall("clip(${modifier.shapeExpression()})")
          },
          modifierCall("background(${modifier.colorExpression()})"),
        )
      "border" ->
        listOf(
          modifierCall(
            "border(${modifier.dp("widthDp", 1f)}, ${modifier.colorExpression()}" +
              (modifier["shape"]?.stringValue()?.let { ", ${modifier.shapeExpression()}" } ?: "") +
              ")"
          )
        )
      // The scroll state is remembered at the call site, as in the Compose exporter.
      "verticalScroll",
      "horizontalScroll" -> {
        usesRemoteScrollState = true
        listOf(modifierCall("$type(rememberRemoteScrollState())"))
      }
      "weight" -> weightCall(modifier)
      "collapsiblePriority" -> collapsiblePriorityCall(modifier)
      // A shared element is matched across state-layout branches by key. A key alone is written as
      // positional `animationSpec(animationId, enabled)`: same `AnimationSpec` op, available back
      // to alpha19, and short enough for the column budget. Timing or enter/exit needs alpha20's
      // `sharedElement` overload.
      "sharedElement" -> {
        val key = modifier["key"]?.numberValue()
        if (key == null || key % 1f != 0f || key < 1f) {
          refusals +=
            "the `sharedElement` modifier on `$id` needs a whole-number `key` of at least one; " +
              "the key is what matches this element to its counterpart in the other states"
          emptyList()
        } else if (!modifier.hasMotionOptions()) {
          listOf(modifierCall("animationSpec(${key.toInt()}, true)"))
        } else {
          motionArguments(modifier)?.let { (spec, enter, exit) ->
            listOf(modifierCall("sharedElement(${key.toInt()}, $spec, $enter, $exit)"))
          } ?: emptyList()
        }
      }
      // Not the creation library's `animateEnterExit`, which writes animation id 0 (disabled). `-1`
      // is the unset id: enabled, matched to nothing across branches.
      "animateEnterExit" ->
        motionArguments(modifier)?.let { (spec, enter, exit) ->
          listOf(modifierCall("animationSpec(-1, $spec, $spec, $enter, $exit)"))
        } ?: emptyList()
      // No `RemoteModifier` counterpart at `remote-creation-compose` 1.0.0-alpha18; each refusal
      // says what to use instead.
      "matchParentSize" -> {
        refusals +=
          "the `matchParentSize` modifier on `$id` is a Compose BoxScope member; a RemoteBox has " +
            "no scope of its own, so a child that fills its box uses `fillMaxSize` here"
        emptyList()
      }
      "aspectRatio" -> {
        refusals +=
          "the `aspectRatio` modifier on `$id` has no RemoteModifier counterpart; a widget body " +
            "states the size it wants, so give the node `size`, or `width` and `height`"
        emptyList()
      }
      "shadow" -> {
        refusals +=
          "the `shadow` modifier on `$id` has no RemoteModifier counterpart — a played document " +
            "draws no elevation shadow; a `border`, or a darker `background`, is what a widget has"
        emptyList()
      }
      "testTag" -> {
        refusals +=
          "the `testTag` modifier on `$id` has no RemoteModifier counterpart; the semantics a " +
            "player carries are the content description and role, which are node properties"
        emptyList()
      }
      // Consumed by the parent container as a group argument (see [boxArguments],
      // [crossAxisAlignment]); in the wrong container it is refused.
      "align" -> alignmentConsumedBy("RemoteBox", type, "a box")
      "alignHorizontal" -> alignmentConsumedBy("RemoteColumn", type, "a column")
      "alignVertical" -> alignmentConsumedBy("RemoteRow", type, "a row")
      RemoteModifierVocabulary.TYPE -> remoteCall(modifier)
      null -> emptyList()
      else -> {
        refusals += "the `$type` modifier on `$id` has no RemoteModifier counterpart here"
        emptyList()
      }
    }
  }

  /**
   * A `remoteCall` modifier: a `RemoteModifier` function from the generated vocabulary, with named
   * arguments resolved by [RemoteModifierVocabulary.resolve]. Unknown names or arguments, and
   * computed values for plain Kotlin types, are refused.
   */
  private fun UiBuilderNode.remoteCall(modifier: JsonObject): List<String> {
    val name = modifier.plainString("name").orEmpty()
    val where = "nodes.$id.modifiers.remoteCall"
    val entry = RemoteModifierVocabulary.modifiers[name]
    if (entry == null) {
      refusals +=
        "$where: `$name` is not a RemoteModifier call in remote-creation-compose " +
          RemoteModifierVocabulary.version
      return emptyList()
    }
    val args = modifier["args"] as? JsonObject ?: JsonObject(emptyMap())
    val overload = RemoteModifierVocabulary.resolve(name, args.keys)
    if (overload == null) {
      refusals +=
        "$where: `$name` takes " +
          entry.overloads.joinToString(" or ") { o ->
            "(" + o.parameters.joinToString { it.name + if (it.optional) "?" else "" } + ")"
          } +
          ", not (${args.keys.joinToString()})"
      return emptyList()
    }
    val arguments =
      overload.parameters.mapNotNull { parameter ->
        val value = args[parameter.name] ?: return@mapNotNull null
        parameter.name to
          (remoteCallArgument(parameter, value, "$where.$name.${parameter.name}")
            ?: return emptyList())
      }
    if (name == RemoteModifierVocabulary.SEMANTICS) return semanticsCall(arguments.toMap(), where)
    usedComponentImports += "${entry.`package`}.$name"
    return listOf("$name(${arguments.joinToString { (key, value) -> "$key = $value" }})")
  }

  /**
   * `semantics(mergeDescendants) { … }` or `clearAndSetSemantics { … }`, with each value the design
   * states set inside the receiver lambda, as the released API takes them.
   */
  private fun semanticsCall(arguments: Map<String, String>, where: String): List<String> {
    val modifierPackage = "androidx.compose.remote.creation.compose.modifier"
    val body =
      listOf("contentDescription", "stateDescription", "role", "enabled").mapNotNull { key ->
        val value = arguments[key] ?: return@mapNotNull null
        usedComponentImports += "$modifierPackage.$key"
        if (key == "role") {
          val role = value.removeSurrounding("\"")
          if (role !in RemoteModifierVocabulary.ROLES) {
            refusals +=
              "$where.semantics.role: `$role` is not one of ${RemoteModifierVocabulary.ROLES}"
            return emptyList()
          }
          usedComponentImports += "androidx.compose.ui.semantics.Role"
          "role = Role.$role"
        } else "$key = $value"
      }
    val clear = arguments["clear"] == "true"
    val merge = arguments["mergeDescendants"] == "true"
    if (clear && merge) {
      refusals += "$where.semantics: clearing a node's semantics already replaces its children's"
      return emptyList()
    }
    val head =
      when {
        clear -> "clearAndSetSemantics"
        merge -> "semantics(mergeDescendants = true)"
        else -> "semantics"
      }
    usedComponentImports += "$modifierPackage.${if (clear) "clearAndSetSemantics" else "semantics"}"
    return listOf("$head { ${body.joinToString("; ")} }")
  }

  private fun remoteCallArgument(
    parameter: RemoteModifierVocabulary.Parameter,
    value: JsonElement,
    where: String,
  ): String? {
    val remote = parameter.type.startsWith("Remote")
    if (UiExpressions.isComputed(value) || (value as? JsonObject)?.plainString("type") == "state") {
      if (!remote) {
        refusals +=
          "$where: `${parameter.type}` is a plain Kotlin value upstream, so it cannot be a state " +
            "read or a computed value"
        return null
      }
      val kind =
        when (parameter.kind) {
          "int" -> UiValueKind.INT
          "bool" -> UiValueKind.BOOL
          "color" -> UiValueKind.COLOR
          "string" -> UiValueKind.STRING
          else -> UiValueKind.FLOAT
        }
      val expression = computed(value, kind, where) ?: return null
      return if (parameter.kind == "dp") {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.asRemoteDp"
        "$expression.asRemoteDp()"
      } else expression
    }
    val literal = (value as? JsonObject)?.get("value") as? JsonPrimitive
    fun refuse(): String? {
      refusals += "$where: expected a ${parameter.kind} value for `${parameter.type}`"
      return null
    }
    return when (parameter.kind) {
      "float" -> {
        val number = literal?.floatOrNull ?: return refuse()
        if (remote) number.floatLiteral() else "${number}f"
      }
      "dp" -> (literal?.floatOrNull ?: return refuse()).dpLiteral()
      "int" -> {
        val number = literal?.intOrNull ?: return refuse()
        if (remote) "$number.ri".also { usesRemoteInt = true } else "$number"
      }
      "bool" -> {
        val flag = literal?.booleanOrNull ?: return refuse()
        if (remote) "$flag.rb".also { usesRemoteBoolean = true } else "$flag"
      }
      "color" -> {
        val color = literal?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return refuse()
        when {
          color == TRANSPARENT_TOKEN -> "Color(0x00000000).rc".also { usesColorLiteral = true }
          color.startsWith("#") -> "${color.argbLiteral()}.rc".also { usesColorLiteral = true }
          else -> "RemoteMaterialTheme.colorScheme.$color".also { usesTheme = true }
        }
      }
      "string" -> {
        val text = literal?.contentOrNull ?: return refuse()
        val quoted =
          "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        if (remote) "$quoted.rs".also { usesRemoteString = true } else quoted
      }
      else -> refuse()
    }
  }

  /**
   * Nothing, when the container above has already written the alignment as an argument.
   *
   * @param container the symbol whose lambda consumes it.
   */
  private fun UiBuilderNode.alignmentConsumedBy(
    container: String,
    type: String,
    article: String,
  ): List<String> {
    if (axisScope == container) return emptyList()
    refusals +=
      "the `$type` modifier on `$id` aligns a child of $article, and this node is in " +
        "${scope?.let { "a $it" } ?: "the widget's content slot"}; a played document has no " +
        "per-child alignment outside the container that lays it out"
    return emptyList()
  }

  /**
   * `weight`, legal only inside a row or column lambda (`RemoteColumnScope`/`RemoteRowScope`);
   * elsewhere it is refused by name.
   */
  private fun UiBuilderNode.weightCall(modifier: JsonObject): List<String> {
    if (axisScope != "RemoteColumn" && axisScope != "RemoteRow") {
      refusals +=
        "the `weight` modifier on `$id` divides the space of a row or a column, and this node is " +
          "in ${scope?.let { "a $it" } ?: "the widget's content slot"}; put it in a " +
          "`layout/row` or `layout/column` to weight it"
      return emptyList()
    }
    // Compose's `fill = false` weights the space but does not make the child take it. There is no
    // second argument upstream, so a design asking for it is refused rather than filled anyway.
    if (modifier["fill"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
      refusals +=
        "the `weight` modifier on `$id` asks for `fill = false`, which a RemoteModifier weight " +
          "has no argument for — it always fills the space it is given"
      return emptyList()
    }
    // Weight must be positive: the canvas drops non-positive ones (Compose throws), and
    // `weight(0f)` would fail at document build time. Refuse rather than normalise.
    val weight = modifier["weight"]?.numberValue() ?: 1f
    if (weight <= 0f) {
      refusals +=
        "the `weight` modifier on `$id` asks for `${weight.floatLiteral()}`; a weight divides the " +
          "space a row or column has left over, so it has to be greater than zero — the canvas " +
          "ignores this one and Remote Compose refuses it, so give it a positive weight or drop it"
      return emptyList()
    }
    return listOf("weight(${weight.floatLiteral()})")
  }

  /**
   * `collapsiblePriority`, legal only in the collapsible scopes. Higher priority stays longer;
   * unset is kept longest. Takes a plain `Float`.
   */
  private fun UiBuilderNode.collapsiblePriorityCall(modifier: JsonObject): List<String> {
    if (scope != "RemoteCollapsibleColumn" && scope != "RemoteCollapsibleRow") {
      refusals +=
        "the `collapsiblePriority` modifier on `$id` orders which children a collapsible " +
          "column or row hides first, and this node is in " +
          "${scope?.let { "a $it" } ?: "the widget's content slot"}; put it in a " +
          "`layout/collapsible-column` or `layout/collapsible-row`"
      return emptyList()
    }
    val priority = modifier["priority"]?.numberValue() ?: 0f
    return listOf(
      "collapsiblePriority(${if (priority % 1f == 0f) priority.toInt() else priority}f)"
    )
  }

  /** Whether a motion modifier names more than its key: a timing, or a way in or out. */
  private fun JsonObject.hasMotionOptions(): Boolean = MOTION_FIELDS.any {
    (this[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
  }

  /**
   * A motion modifier's `remoteTween`, enter and exit, as the creation API spells them, or null
   * (with the refusal recorded) when one names something the API has not got.
   */
  private fun UiBuilderNode.motionArguments(modifier: JsonObject): Triple<String, String, String>? {
    fun text(name: String) =
      (modifier[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val duration = modifier["durationMs"]?.numberValue() ?: 300f
    if (duration % 1f != 0f || duration < 0f) {
      refusals +=
        "the `${modifier.type()}` modifier on `$id` needs a whole number of milliseconds, " +
          "not $duration"
      return null
    }
    val easing = text("easing") ?: "standard"
    val enter = text("enter") ?: "fadeIn"
    val exit = text("exit") ?: "fadeOut"
    val unknown =
      listOfNotNull(
        easing.takeIf { it !in UiExpressions.EASINGS }?.let { "easing `$it`" },
        enter.takeIf { it !in MOTION_ENTERS }?.let { "enter `$it`" },
        exit.takeIf { it !in MOTION_EXITS }?.let { "exit `$it`" },
      )
    if (unknown.isNotEmpty()) {
      refusals +=
        "the `${modifier.type()}` modifier on `$id` names ${unknown.joinToString(" and ")}; " +
          "the creation API has ${MOTION_ENTERS.joinToString()} in and " +
          "${MOTION_EXITS.joinToString()} out"
      return null
    }
    usedComponentImports += "$REMOTE_STATE_PACKAGE.remoteTween"
    usedComponentImports += "$REMOTE_STATE_PACKAGE.RemoteEasing"
    usedComponentImports += "$REMOTE_MODIFIER_PACKAGE.RemoteEnterTransition"
    usedComponentImports += "$REMOTE_MODIFIER_PACKAGE.RemoteExitTransition"
    val easingName = easing.replaceFirstChar { it.uppercase() }
    return Triple(
      "remoteTween(${duration.toInt()}, RemoteEasing.$easingName)",
      "RemoteEnterTransition.${enter.replaceFirstChar { it.uppercase() }}",
      "RemoteExitTransition.${exit.replaceFirstChar { it.uppercase() }}",
    )
  }

  private fun JsonObject.type(): String = (this["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()

  private fun modifierCall(call: String): String {
    usedModifierImports += call.substringBefore('(')
    return call
  }

  /** `min`/`max` are both optional upstream, and a bound the design did not set is not written. */
  private fun JsonObject.boundsArguments(): String =
    listOfNotNull(
        this["minDp"]?.numberValue()?.let { "min = ${it.dpLiteral()}" },
        this["maxDp"]?.numberValue()?.let { "max = ${it.dpLiteral()}" },
      )
      .joinToString()

  /**
   * A dp argument: the authored number, or a computed value the player evaluates, read as dp
   * one-to-one rather than as pixels.
   */
  private fun JsonObject.dp(name: String, fallback: Float = 0f): String {
    val value = this[name]
    if (UiExpressions.isComputed(value)) {
      computed(value!!, UiValueKind.FLOAT, "modifiers.${plainString("type")}.$name")?.let {
        usedComponentImports += "$REMOTE_STATE_PACKAGE.asRemoteDp"
        return "$it.asRemoteDp()"
      }
    }
    return (value?.numberValue() ?: fallback).dpLiteral()
  }

  /** A `RemoteFloat` argument: the authored number, or a computed value. */
  private fun JsonObject.float(name: String, fallback: Float = 0f): String {
    val value = this[name]
    if (UiExpressions.isComputed(value)) {
      computed(value!!, UiValueKind.FLOAT, "modifiers.${plainString("type")}.$name")?.let {
        return it
      }
    }
    return (value?.numberValue() ?: fallback).floatLiteral()
  }

  /**
   * The colour inside a modifier (a `UiValueV1`): a theme role needs the theme wrapper; a
   * `#AARRGGBB` literal is `Color(0x…).rc`.
   */
  private fun JsonObject.colorExpression(): String {
    val declared = (this["color"] as? JsonObject)?.get("value")?.stringValue().orEmpty()
    return if (declared.startsWith("#")) {
      usesColorLiteral = true
      "${declared.argbLiteral()}.rc"
    } else {
      usesTheme = true
      "RemoteMaterialTheme.colorScheme.${declared.ifEmpty { "surface" }}"
    }
  }

  /** `small`/`medium`/`large` are the catalog's names for the radii the Compose exporter uses. */
  private fun JsonObject.shapeExpression(): String {
    usesRoundedCornerShape = true
    val declared = this["shape"]?.stringValue()
    val radius =
      when (declared) {
        "large" -> 16f
        "medium" -> 12f
        "small" -> 8f
        else -> declared?.toFloatOrNull() ?: 0f
      }
    return "RemoteRoundedCornerShape(${radius.dpLiteral()})"
  }

  /** Every dp literal needs `rdp`, which is why the flag is set here and not per call site. */
  private fun Float.dpLiteral(): String {
    usesDp = true
    return if (this % 1f == 0f) "${toInt()}.rdp" else "${this}f.rdp"
  }

  /** Every remote float literal needs `rf`, the same way a dp literal needs `rdp`. */
  private fun Float.floatLiteral(): String {
    usesRemoteFloat = true
    return if (this % 1f == 0f) "${toInt()}.rf" else "${this}f.rf"
  }

  /**
   * A size as Remote Compose spells one. There is no `Float.rsp`, so fractional sizes go through
   * `TextUnit`. Both spellings are compiled in the catalog's value vocabulary probe.
   */
  private fun Float.spLiteral(): String =
    if (this % 1f == 0f) "${toInt()}.rsp".also { usesSp = true }
    else "${this}f.sp.asRemoteTextUnit()".also { usesFractionalSp = true }

  private companion object {
    const val INDENT = "    "

    /** ktfmt's own default, so pasted output survives the formatter unchanged. */
    const val MAX_LINE = 100

    /** The argument a modifier chain is written as, whose width the chain has to allow for. */
    const val MODIFIER_ARGUMENT = "modifier = "

    /** What [container] appends after a call that takes children. */
    const val OPENING_BRACE = " {"

    /** The first Lottie animation's constant; a second one is suffixed. */
    const val LOTTIE_CONSTANT = "LOTTIE_ANIMATION"

    /** A JVM string constant's cap, in modified-UTF-8 bytes. */
    const val MAX_STRING_CONSTANT_BYTES = 65535

    /**
     * The directory a bundle's pictures sit in, under the module's `assets/`, so unpacking does not
     * mix into existing assets.
     */
    const val BUNDLE_DIRECTORY = "uibuilder"
  }
}

/**
 * The design id as one path segment. Server ids are already safe; anything else is folded into the
 * segment alphabet. Asset keys keep archive paths distinct regardless.
 */
private fun String.bundleSegment(): String {
  val folded = map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
  return folded.joinToString("").trimStart('-').ifEmpty { "design" }
}

/** The file extension for a picture's media type. Cosmetic: `BitmapFactory` sniffs the bytes. */
private fun String.pictureExtension(): String =
  when (substringBefore(';').trim().lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "bin"
  }

/**
 * Where a box puts a child that neither it nor the child aligns: Compose's `Alignment.TopStart`.
 */
private const val DEFAULT_BOX_ALIGNMENT = "topStart"

/** `RemoteText`'s `overflow`, by the catalog's word. */
private val TEXT_OVERFLOW_MEMBERS =
  mapOf(
    "clip" to "Clip",
    "ellipsis" to "Ellipsis",
    "visible" to "Visible",
    "startEllipsis" to "StartEllipsis",
    "middleEllipsis" to "MiddleEllipsis",
  )

/**
 * Text settings the catalog offers on `m3/text` that [RemoteContentEmitter] does not write, each
 * with the value that changes nothing (null when only absence does). Any other value is a warning.
 */
private val UNWRITTEN_TEXT_SETTINGS: Map<String, String?> =
  mapOf(
    "fontWeight" to "normal",
    "fontStyle" to "normal",
    "textDecoration" to "none",
    "lineHeightSp" to null,
    "letterSpacingSp" to null,
    "minLines" to "1",
    "softWrap" to "true",
  )

private fun String.remoteAlignment(): String =
  when (this) {
    "topCenter" -> "TopCenter"
    "topEnd" -> "TopEnd"
    "centerStart" -> "CenterStart"
    "center" -> "Center"
    "centerEnd" -> "CenterEnd"
    "bottomStart" -> "BottomStart"
    "bottomCenter" -> "BottomCenter"
    "bottomEnd" -> "BottomEnd"
    else -> "TopStart"
  }

private fun String.remoteHorizontal(): String =
  when (this) {
    "centerHorizontally",
    "center" -> "CenterHorizontally"
    "end" -> "End"
    else -> "Start"
  }

private fun String.remoteVertical(): String =
  when (this) {
    "centerVertically",
    "center" -> "CenterVertically"
    "bottom" -> "Bottom"
    else -> "Top"
  }

/**
 * `#FF2196F3` and `#2196F3` both become `Color(0xFF2196F3)`; six digits mean opaque, as on the
 * canvas. Anything else passes through, since the validator refuses it first.
 */
internal fun String.argbLiteral(): String {
  val digits = removePrefix("#").uppercase()
  val argb = if (SIX_DIGIT_HEX.matches(digits)) "FF$digits" else digits
  return "Color(0x$argb)"
}

private val SIX_DIGIT_HEX = Regex("[0-9A-F]{6}")

/**
 * A state variable the host owns (`"persistence": "host"`): the design reads it and never writes
 * it, and the export names it so a host can push values into the running document.
 */
internal fun JsonObject.isHostInput(): Boolean =
  (this["persistence"] as? JsonPrimitive)?.contentOrNull == HOST_PERSISTENCE

internal const val HOST_PERSISTENCE = "host"

/** The types a host input's declaration names (`RemoteString.createNamedRemoteString(…)`). */
private val NAMED_STATE_TYPES =
  setOf("RemoteState", "RemoteString", "RemoteInt", "RemoteFloat", "RemoteBoolean")

/** Escaped for a Kotlin `"…"` literal. `$` matters for Lottie JSON, which is arbitrary text. */
private fun String.escaped(): String =
  replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("$", "\\$")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? =
  (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.numberValue(): Float? =
  (this as? JsonPrimitive)?.floatOrNull

/** The parameter names and types the record-driven fallback in [RemoteContentEmitter] knows. */
private const val MODIFIER_PARAMETER = "modifier"

private const val ACTION_FQN = "androidx.compose.remote.creation.compose.action.Action"
private const val REMOTE_STRING_FQN = "androidx.compose.remote.creation.compose.state.RemoteString"
private const val REMOTE_BOOLEAN_FQN =
  "androidx.compose.remote.creation.compose.state.RemoteBoolean"
private const val REMOTE_FLOAT_FQN = "androidx.compose.remote.creation.compose.state.RemoteFloat"
private const val REMOTE_INT_FQN = "androidx.compose.remote.creation.compose.state.RemoteInt"
private const val REMOTE_STATE_PACKAGE = "androidx.compose.remote.creation.compose.state"

private const val REMOTE_MODIFIER_PACKAGE = "androidx.compose.remote.creation.compose.modifier"

/** The fields a motion modifier (`sharedElement`, `animateEnterExit`) may carry past its key. */
private val MOTION_FIELDS = listOf("durationMs", "easing", "enter", "exit")

/** `RemoteEnterTransition`'s named transitions, as the design stores them. */
public val MOTION_ENTERS: List<String> =
  listOf("fadeIn", "slideInLeft", "slideInRight", "slideInTop", "slideInBottom", "rotate")

/** `RemoteExitTransition`'s named transitions, as the design stores them. */
public val MOTION_EXITS: List<String> =
  listOf("fadeOut", "slideOutLeft", "slideOutRight", "slideOutTop", "slideOutBottom", "rotate")

/** The colour editor's `transparent`: not a Material scheme role, so written as a literal. */
private const val TRANSPARENT_TOKEN = "transparent"

/** The events a layout node takes: `combinedClickable`'s three and the three touch modifiers. */
internal val LAYOUT_EVENTS: List<String> =
  listOf("click", "longClick", "doubleClick", "touchDown", "touchUp", "touchCancel")

/** The touch events and the `RemoteModifier` call each is written as. */
private val TOUCH_EVENTS: List<Pair<String, String>> =
  listOf("touchDown" to "onTouchDown", "touchUp" to "onTouchUp", "touchCancel" to "onTouchCancel")

/** An Int literal in written Remote source, which needs the `ri` import. */
private val INT_LITERAL = Regex("""\b\d+\.ri\b""")
private const val REMOTE_COLOR_FQN = "androidx.compose.remote.creation.compose.state.RemoteColor"
private const val REMOTE_DP_FQN = "androidx.compose.remote.creation.compose.state.RemoteDp"
private const val REMOTE_CIRCULAR_PROGRESS_FQN =
  "androidx.wear.compose.remote.material3.RemoteCircularProgressIndicator"
private const val INDETERMINATE_PROPERTY = "indeterminate"
/** The parameters the indeterminate `RemoteCircularProgressIndicator` shares with the record's. */
private val INDETERMINATE_CIRCULAR_PARAMETERS =
  setOf("modifier", "colors", "strokeWidth", "gapSize")
private const val REMOTE_BORDER_STROKE_FQN =
  "androidx.wear.compose.remote.material3.RemoteBorderStroke"
private const val BORDER_COLOR_PROPERTY = "borderColor"
private const val BORDER_WIDTH_PROPERTY = "borderWidthDp"

/**
 * A `remote-material3` colours type and the factory that builds one from theme roles: the factory
 * lives on [defaultsFqn], and [roles] are its parameter names, which a node spells as properties.
 */
private class RemoteColorBundle(
  val defaultsFqn: String,
  val factory: String,
  val roles: List<String>,
)

private const val REMOTE_M3_PACKAGE = "androidx.wear.compose.remote.material3"

private val REMOTE_COLOR_BUNDLES: Map<String, RemoteColorBundle> =
  mapOf(
    "$REMOTE_M3_PACKAGE.RemoteButtonColors" to
      RemoteColorBundle(
        "$REMOTE_M3_PACKAGE.RemoteButtonDefaults",
        "buttonColors",
        listOf(
          "containerColor",
          "contentColor",
          "secondaryContentColor",
          "iconColor",
          "disabledContainerColor",
          "disabledContentColor",
          "disabledSecondaryContentColor",
          "disabledIconColor",
        ),
      ),
    "$REMOTE_M3_PACKAGE.RemoteTextButtonColors" to
      RemoteColorBundle(
        "$REMOTE_M3_PACKAGE.RemoteTextButtonDefaults",
        "textButtonColors",
        listOf("containerColor", "contentColor", "disabledContainerColor", "disabledContentColor"),
      ),
    "$REMOTE_M3_PACKAGE.RemoteIconButtonColors" to
      RemoteColorBundle(
        "$REMOTE_M3_PACKAGE.RemoteIconButtonDefaults",
        "iconButtonColors",
        listOf("containerColor", "contentColor", "disabledContainerColor", "disabledContentColor"),
      ),
    "$REMOTE_M3_PACKAGE.RemoteCardColors" to
      RemoteColorBundle(
        "$REMOTE_M3_PACKAGE.RemoteCardDefaults",
        "cardColors",
        listOf(
          "containerColor",
          "contentColor",
          "appNameColor",
          "timeColor",
          "titleColor",
          "subtitleColor",
        ),
      ),
  )

/** A colour scheme role's name, as `RemoteMaterialTheme.colorScheme` spells its members. */
private val COLOR_ROLE = Regex("[a-z][A-Za-z0-9]*")

/** A colour literal as a draw property spells one: `#RRGGBB` or `#AARRGGBB`. */
private val COLOR_HEX = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
/**
 * The published Remote Compose text component, served by the same writer as the borrowed `m3/text`,
 * so a design moving between the ids keeps its text properties.
 */
public const val REMOTE_TEXT_COMPONENT_ID: String = "remote-m3/remote-text"

/**
 * `remote-creation-compose`'s `RemoteText` as the `remote-widgets` catalog publishes it: the same
 * properties as [REMOTE_TEXT_COMPONENT_ID] less the theme roles, written under
 * [RemoteTextVocabulary.CREATION].
 */
public const val LAUNCHER_WIDGET_TEXT_COMPONENT_ID: String = "remote-widgets/remote-text"

/** Which library a text node's `RemoteText` comes from. */
internal enum class RemoteTextVocabulary {
  /** `androidx.wear.compose.remote.material3.RemoteText`, under `RemoteMaterialTheme`. */
  MATERIAL3,

  /** `androidx.compose.remote.creation.compose.layout.RemoteText`, with no theme at all. */
  CREATION,
}

private const val REMOTE_TEXT_UNIT_FQN =
  "androidx.compose.remote.creation.compose.state.RemoteTextUnit"

/** A plain string field of an action or a state declaration — not the `{type, value}` wrapper. */
private fun JsonObject.plainString(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A state variable's name as a Kotlin identifier the generated body can declare. */
private fun String.remoteIdentifier(): String = buildString {
  this@remoteIdentifier.forEachIndexed { index, character ->
    append(
      when {
        character.isLetter() || character == '_' -> character
        character.isDigit() && index > 0 -> character
        else -> '_'
      }
    )
  }
}
  .ifEmpty { "state" }

internal fun kotlinx.serialization.json.JsonElement.boolOrNull(): Boolean? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.booleanOrNull

internal fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull

internal fun kotlinx.serialization.json.JsonElement.numberOrNull(): Float? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull

internal fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull

/**
 * SVG path data as the `RemotePath` `RemoteDrawScope` draws:
 * `androidx.compose.remote.creation.compose.path`'s, which has no string constructor, not
 * `androidx.compose.remote.creation.RemotePath`, the core writer's, which does but which no draw
 * call takes. Compose's `PathParser` reads the data, arcs and smooth curves included, and its
 * segments are replayed, as the catalog's device preview does.
 */
internal val REMOTE_PATH_HELPER: String =
  """
  private fun remotePathOf(pathData: String): RemotePath {
    val path = RemotePath()
    val segments = PathParser().parsePathString(pathData).toPath().iterator()
    while (segments.hasNext()) {
      val segment = segments.next()
      val p = segment.points
      when (segment.type) {
        PathSegment.Type.Move -> path.moveTo(p[0], p[1])
        PathSegment.Type.Line -> path.lineTo(p[2], p[3])
        PathSegment.Type.Quadratic -> path.quadTo(p[2], p[3], p[4], p[5])
        PathSegment.Type.Conic -> path.conicTo(p[2], p[3], p[4], p[5], segment.weight)
        PathSegment.Type.Cubic -> path.cubicTo(p[2], p[3], p[4], p[5], p[6], p[7])
        PathSegment.Type.Close -> path.close()
        PathSegment.Type.Done -> Unit
      }
    }
    return path
  }
  """
    .trimIndent()

internal val REMOTE_PATH_HELPER_IMPORTS: List<String> =
  listOf(
    "androidx.compose.remote.creation.compose.path.RemotePath",
    "androidx.compose.ui.graphics.PathSegment",
    "androidx.compose.ui.graphics.vector.PathParser",
  )
