package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Writes a widget's designed content as Remote Compose source, and its background as a
 * `WearWidgetBrush`.
 *
 * Separate from [WearWidgetCodeExporter] because the two answer different questions: that one owns
 * the widget's *shape* — the class, the document, the preview — and this one owns the vocabulary
 * the body is written in. The split is also where the refusals collect: an unsupported node is
 * appended to [refusals] and emits nothing, so one pass reports every reason rather than the first.
 */
internal class RemoteContentEmitter(
  private val document: UiBuilderDocument,
  private val refusals: MutableList<String>,
) {
  /** True once a colour or type token has been written, which only reads inside a theme. */
  var usesTheme: Boolean = false
    private set

  private var usesMaterialText = false
  private var usesColumn = false
  private var usesRow = false
  private var usesBox = false
  private var usesArrangement = false
  private var usesAlignment = false
  private var usesDp = false
  private var usesTextAlign = false

  /** The `WearWidgetBrush` chain a container's background declares. */
  data class Background(val expression: String, val locals: List<String>)

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
          usesGradient = true
          elements +=
            when (node.properties["direction"]?.stringOrNull()) {
              "leftToRight" -> "horizontalGradient($stops)"
              "rightToLeft" -> "horizontalGradient($reversed)"
              "bottomToTop" -> "verticalGradient($reversed)"
              else -> "verticalGradient($stops)"
            }
        }
        // `WearWidgetBrush.image` takes a `RemoteImageBitmap`, which is a bitmap this generator has
        // no way to name: the design carries an asset KEY, and resolving one to a bitmap is the
        // builder's asset registry rather than anything source can say. Refused rather than
        // emitted as a TODO that would not compile.
        "asset/image" ->
          refusals +=
            "the image background `$id` needs a RemoteImageBitmap, which a generated file cannot " +
              "name from an asset key — supply the bitmap in provideWidgetData and add " +
              "`WearWidgetBrush.image(bitmap)` by hand"
        else -> refusals += "`${node.componentId}` is not a widget background brush"
      }
    }
    // The empty chain is a real value upstream: `WearWidgetBrush` alone is what a widget passes to
    // accept the host's default fill.
    val expression =
      if (elements.isEmpty()) "WearWidgetBrush"
      else elements.joinToString(".", prefix = "WearWidgetBrush.")
    return Background(expression, locals)
  }

  private var usesRemoteColorScheme = false
  private var usesColorLiteral = false
  private var usesBrushColor = false

  /** The node and its subtree, as indented source lines. */
  fun emit(nodeId: String, depth: Int): List<String> {
    val node = document.nodes[nodeId] ?: return emptyList()
    val pad = INDENT.repeat(depth)
    return when (node.componentId) {
      "m3/text" -> (pad + text(node, pad)).split("\n")
      "layout/box" -> container(node, depth, "RemoteBox", boxArguments(node))
      "layout/column" -> container(node, depth, "RemoteColumn", columnArguments(node))
      "layout/row" -> container(node, depth, "RemoteRow", rowArguments(node))
      else -> {
        refusals +=
          "`${node.componentId}` has no Remote Compose counterpart this generator can write"
        emptyList()
      }
    }
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
    }
    val pad = INDENT.repeat(depth)
    val children = node.slots["children"].orEmpty()
    if (children.isEmpty()) {
      return (pad + if (arguments.isEmpty()) "$symbol()" else call(symbol, arguments, pad)).split(
        "\n"
      )
    }
    val head = if (arguments.isEmpty()) "$symbol {" else "${call(symbol, arguments, pad)} {"
    return (pad + head).split("\n") + children.flatMap { emit(it, depth + 1) } + listOf("$pad}")
  }

  private fun boxArguments(node: UiBuilderNode): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression()?.let { arguments += "modifier = $it" }
    // `layout/box` aligns each child by that child's own `alignment`, while `RemoteBox` aligns all
    // of them together. One child is the case both samples write and the case the two models agree
    // on; more than one, each wanting a different corner, is a design this cannot write.
    val children = node.slots["children"].orEmpty().mapNotNull(document.nodes::get)
    val alignments =
      children.map { it.properties["alignment"]?.stringOrNull().orEmpty() }.distinct()
    when {
      alignments.size > 1 ->
        refusals +=
          "the box `${node.id}` aligns its children differently from one another, which " +
            "RemoteBox aligns as a group"
      alignments.singleOrNull().isNullOrEmpty() -> Unit
      else -> {
        usesAlignment = true
        arguments += "contentAlignment = RemoteAlignment.${alignments.single().remoteAlignment()}"
      }
    }
    return arguments
  }

  private fun columnArguments(node: UiBuilderNode): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression()?.let { arguments += "modifier = $it" }
    node.properties["verticalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "verticalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    return arguments
  }

  private fun rowArguments(node: UiBuilderNode): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression()?.let { arguments += "modifier = $it" }
    node.properties["horizontalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "horizontalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    return arguments
  }

  /**
   * `Symbol(a, b)` on one line, or one argument per line once that would run long.
   *
   * Generated or not, this is source somebody reads and pastes into a file their formatter will
   * check. A 150-column call is a diff nobody wants on their first commit after using the builder.
   */
  private fun call(symbol: String, arguments: List<String>, pad: String = ""): String {
    val single = "$symbol(${arguments.joinToString(", ")})"
    if (pad.length + single.length <= MAX_LINE) return single
    return buildString {
      appendLine("$symbol(")
      arguments.forEach { appendLine("$pad$INDENT$it,") }
      append("$pad)")
    }
  }

  private fun text(node: UiBuilderNode, pad: String = ""): String {
    usesMaterialText = true
    val arguments =
      mutableListOf("text = \"${node.properties["text"]?.stringOrNull().orEmpty().escaped()}\".rs")
    node.modifierExpression()?.let { arguments += "modifier = $it" }
    node.properties["color"]
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
    node.properties["fontSizeSp"]
      ?.numberOrNull()
      ?.takeIf { it > 0f }
      ?.let { arguments += "fontSize = ${it.spLiteral()}" }
    node.properties["style"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        usesTheme = true
        arguments += "style = RemoteMaterialTheme.typography.$it"
      }
    node.properties["textAlign"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        usesTextAlign = true
        arguments += "textAlign = TextAlign.${it.replaceFirstChar(Char::uppercaseChar)}"
      }
    node.properties["maxLines"]?.intOrNull()?.let { arguments += "maxLines = $it" }
    return call("RemoteText", arguments, pad)
  }

  /**
   * The imports the emitted file needs, sorted the way Kotlin style orders them.
   *
   * Gated on what was actually written rather than emitted wholesale: an unused import is a warning
   * in the reader's IDE the moment they paste this in, and "generated" is not a licence to hand
   * someone code they have to tidy.
   */
  fun imports(previewParamsProvider: String): List<String> {
    val imports = mutableSetOf<String>()
    imports += "android.content.Context"
    if (usesBox) imports += "androidx.compose.remote.creation.compose.layout.RemoteBox"
    if (usesColumn) imports += "androidx.compose.remote.creation.compose.layout.RemoteColumn"
    imports += "androidx.compose.remote.creation.compose.layout.RemoteComposable"
    if (usesRow) imports += "androidx.compose.remote.creation.compose.layout.RemoteRow"
    if (usesAlignment) imports += "androidx.compose.remote.creation.compose.layout.RemoteAlignment"
    if (usesArrangement) {
      imports += "androidx.compose.remote.creation.compose.layout.RemoteArrangement"
    }
    if (usesModifier) imports += "androidx.compose.remote.creation.compose.modifier.RemoteModifier"
    usedModifierImports.forEach {
      imports += "androidx.compose.remote.creation.compose.modifier.$it"
    }
    if (usesDp) imports += "androidx.compose.remote.creation.compose.state.rdp"
    if (usesColorLiteral) imports += "androidx.compose.remote.creation.compose.state.rc"
    if (usesMaterialText) imports += "androidx.compose.remote.creation.compose.state.rs"
    if (usesSp) imports += "androidx.compose.remote.creation.compose.state.rsp"
    imports += "androidx.compose.runtime.Composable"
    if (usesColorLiteral) imports += "androidx.compose.ui.graphics.Color"
    if (usesTextAlign) imports += "androidx.compose.ui.text.style.TextAlign"
    imports += "androidx.compose.ui.tooling.preview.Preview"
    imports += "androidx.compose.ui.tooling.preview.PreviewParameter"
    imports += "androidx.glance.wear.GlanceWearWidget"
    imports += "androidx.glance.wear.WearWidgetBrush"
    imports += "androidx.glance.wear.WearWidgetData"
    imports += "androidx.glance.wear.WearWidgetDocument"
    if (usesBrushColor) imports += "androidx.glance.wear.color"
    if (usesGradient) {
      imports += "androidx.glance.wear.horizontalGradient"
      imports += "androidx.glance.wear.verticalGradient"
    }
    imports += "androidx.glance.wear.core.WearWidgetParams"
    imports += "androidx.glance.wear.tooling.preview.$previewParamsProvider"
    imports += "androidx.glance.wear.tooling.preview.WearWidgetPreview"
    if (usesRemoteColorScheme) {
      imports += "androidx.wear.compose.remote.material3.RemoteColorScheme"
    }
    if (usesTheme) imports += "androidx.wear.compose.remote.material3.RemoteMaterialTheme"
    if (usesMaterialText) imports += "androidx.wear.compose.remote.material3.RemoteText"
    return imports.sorted()
  }

  private var usesModifier = false
  private var usesSp = false
  private var usesGradient = false
  private val usedModifierImports = mutableSetOf<String>()

  private fun UiBuilderNode.modifierExpression(): String? {
    val parts = modifiers.mapNotNull { element ->
      val modifier = element as? JsonObject ?: return@mapNotNull null
      when (val type = modifier["type"]?.stringValue()) {
        "fillMaxSize" -> {
          usedModifierImports += "fillMaxSize"
          "fillMaxSize()"
        }
        "fillMaxWidth" -> {
          usedModifierImports += "fillMaxWidth"
          "fillMaxWidth()"
        }
        "padding" -> {
          usedModifierImports += "padding"
          val start = modifier["startDp"]?.numberValue() ?: 0f
          val top = modifier["topDp"]?.numberValue() ?: 0f
          val end = modifier["endDp"]?.numberValue() ?: 0f
          val bottom = modifier["bottomDp"]?.numberValue() ?: 0f
          "padding(${start.dpLiteral()}, ${top.dpLiteral()}, ${end.dpLiteral()}, ${bottom.dpLiteral()})"
        }
        null -> null
        else -> {
          refusals += "the `$type` modifier on `$id` has no RemoteModifier counterpart here"
          null
        }
      }
    }
    if (parts.isEmpty()) return null
    usesModifier = true
    return parts.joinToString(".", prefix = "RemoteModifier.")
  }

  /** Every dp literal needs `rdp`, which is why the flag is set here and not per call site. */
  private fun Float.dpLiteral(): String {
    usesDp = true
    return if (this % 1f == 0f) "${toInt()}.rdp" else "${this}f.rdp"
  }

  private fun Float.spLiteral(): String =
    if (this % 1f == 0f) "${toInt()}.rsp".also { usesSp = true }
    else "${this}f.rsp".also { usesSp = true }

  private companion object {
    const val INDENT = "    "

    /** ktfmt's own default, so pasted output survives the formatter unchanged. */
    const val MAX_LINE = 100
  }
}

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

/** `#FF2196F3` becomes `Color(0xFF2196F3)`. */
private fun String.argbLiteral(): String = "Color(0x${removePrefix("#").uppercase()})"

private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? =
  (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.numberValue(): Float? =
  (this as? JsonPrimitive)?.floatOrNull

internal fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull

internal fun kotlinx.serialization.json.JsonElement.numberOrNull(): Float? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull

internal fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull
