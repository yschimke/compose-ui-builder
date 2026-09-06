package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.Json
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
 * This constant exists because the two lists drifted: the catalog offered 28 modifiers on a widget
 * node, the canvas drew them, and the generator could write three — so `size`, `background` and
 * `weight`, which is how anyone builds a fixed-size coloured button beside a text column that
 * truncates, made a design that exported nowhere (yschimke/compose-preview-server#508). Two tests
 * hold the halves together: one in `:ui-builder` walks every name here through the emitter and
 * fails on a refusal, and one in `:server` fails when a `remote-m3` component advertises a modifier
 * this set does not carry.
 *
 * What is deliberately *absent* is as load-bearing as what is here. `matchParentSize`,
 * `aspectRatio`, `shadow` and `testTag` have no `RemoteModifier` counterpart at
 * `remote-creation-compose` 1.0.0-alpha18; each is refused by name with the reason and the route
 * that does work, rather than being dropped from the chain. The three `align*` modifiers are in
 * here but write no call of their own: a played document aligns content from the container, so they
 * become an argument of the row, column or box above — and are refused anywhere else.
 */
public val REMOTE_CONTENT_MODIFIERS: Set<String> =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
    "background",
    "border",
    "clip",
    "fillMaxHeight",
    "fillMaxSize",
    "fillMaxWidth",
    "height",
    "heightIn",
    "horizontalScroll",
    "offset",
    "padding",
    "rotate",
    "scale",
    "size",
    "verticalScroll",
    "weight",
    "width",
    "widthIn",
    "wrapContentSize",
    "zIndex",
  )

/**
 * The component ids [RemoteContentEmitter] has an authored answer for — a call it writes, or a
 * refusal that states the reason and the way round it.
 *
 * The other half of the same drift: `asset/image` was in the `remote-m3` palette, the canvas drew
 * it, and the generator sent it to the catch-all `else` branch, so a widget with album art in it
 * refused to export with the same sentence an unknown component gets. A `remote-m3` catalog may
 * only offer ids in here, which the `:server` test asserts.
 */
public val REMOTE_CONTENT_COMPONENT_IDS: Set<String> =
  setOf(
    "asset/image",
    "layout/box",
    "layout/column",
    "layout/row",
    "m3/surface",
    "m3/text",
    "remote-m3/lottie",
    "shape/linear-gradient",
    "remote-compose/document",
    REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
    REMOTE_COMPOSE_INLINE_COMPONENT_ID,
  )

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
          elements +=
            when (node.properties["direction"]?.stringOrNull()) {
              "leftToRight" -> horizontal("horizontalGradient($stops)")
              "rightToLeft" -> horizontal("horizontalGradient($reversed)")
              "bottomToTop" -> vertical("verticalGradient($reversed)")
              else -> vertical("verticalGradient($stops)")
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
      "layout/box" -> container(node, depth, "RemoteBox", boxArguments(node, pad))
      "layout/column" -> container(node, depth, "RemoteColumn", columnArguments(node, pad))
      "layout/row" -> container(node, depth, "RemoteRow", rowArguments(node, pad))
      "remote-m3/lottie" -> lottie(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      "asset/image" -> image(node, pad)?.let { (pad + it).split("\n") } ?: emptyList()
      // Authored refusals, not the catch-all below. `m3/surface` and `shape/linear-gradient` are
      // both in the `remote-m3` palette and neither has a body counterpart, so each says what to
      // reach for instead — which is the whole difference between "the generator has not been
      // taught this" and "this vocabulary does not have it".
      "m3/surface" ->
        emptyList<String>().also {
          refusals +=
            "the surface `${node.id}` is a Material container — tonal elevation and a content " +
              "colour its children inherit — and a widget body has neither; a coloured, rounded " +
              "container here is a `layout/box` with `background` and `clip` modifiers"
        }
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
    val head =
      if (arguments.isEmpty()) "$symbol {"
      // `call` measures the call alone; the ` {` this appends is two more columns, and without
      // counting them a call landing on 99 or 100 columns is emitted one or two over the budget.
      else "${call(symbol, arguments, pad, trailing = OPENING_BRACE.length)}$OPENING_BRACE"
    // The children are emitted inside this container's scope, which is what decides whether a
    // `weight` modifier on one of them has a counterpart at all: `weight` is a member of
    // `RemoteColumnScope`/`RemoteRowScope` upstream, so it is legal in the lambda below and
    // nowhere else. Saved and restored rather than assigned, because emission is depth-first and
    // a row inside a column has to hand the column's scope back on the way out.
    val enclosing = scope
    scope = symbol
    val body = children.flatMap { emit(it, depth + 1) }
    scope = enclosing
    return (pad + head).split("\n") + body + listOf("$pad}")
  }

  /** The container symbol whose lambda the node being emitted sits in, or null at the top. */
  private var scope: String? = null

  private fun boxArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // `layout/box` aligns each child by that child's own `alignment`, while `RemoteBox` aligns all
    // of them together. One child is the case both samples write and the case the two models agree
    // on; more than one, each wanting a different corner, is a design this cannot write.
    val children = node.slots["children"].orEmpty().mapNotNull(document.nodes::get)
    val alignments = children.map { it.declaredAlignment("align") }.distinct()
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

  /**
   * What a child asks to be aligned to: its scoped modifier if it has one, else its property.
   *
   * Both spellings reach a document — the modifier chain is what the inspector writes now and the
   * property is what older designs carry — and they mean the same thing, so the modifier wins and
   * the property is the fallback. Reading only the property is what made a `size` + `background` +
   * `align` button, which is most of what a widget is, refuse to export
   * (yschimke/compose-preview-server#508).
   */
  private fun UiBuilderNode.declaredAlignment(modifierType: String): String =
    modifiers
      .mapNotNull { it as? JsonObject }
      .firstOrNull { it["type"]?.stringValue() == modifierType }
      ?.get("alignment")
      ?.stringValue() ?: properties["alignment"]?.stringOrNull().orEmpty()

  /**
   * The one alignment a row or column lays every child out on, or null when nothing asked for one.
   *
   * `RemoteColumn` takes a `horizontalAlignment` and `RemoteRow` a `verticalAlignment`, both for
   * the whole content — the same group semantics `RemoteBox` has — so children that disagree are
   * refused here rather than aligned to whichever came first.
   *
   * @param modifierType the scoped modifier this axis is written from, and the only place it is
   *   read from.
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
    // Only the modifier, never the node's own `horizontalAlignment`/`verticalAlignment` property:
    // the canvas does not read those, so writing them here would generate a widget that lays its
    // content out differently from the design the author approved.
    return scoped.singleOrNull()?.ifEmpty { null }
  }

  private fun columnArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    node.properties["verticalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "verticalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    crossAxisAlignment(node, "alignHorizontal")
      ?.takeIf { it != "start" }
      ?.let {
        usesAlignment = true
        arguments += "horizontalAlignment = RemoteAlignment.${it.remoteHorizontal()}"
      }
    return arguments
  }

  private fun rowArguments(node: UiBuilderNode, pad: String): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    node.properties["horizontalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "horizontalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    crossAxisAlignment(node, "alignVertical")
      ?.takeIf { it != "top" }
      ?.let {
        usesAlignment = true
        arguments += "verticalAlignment = RemoteAlignment.${it.remoteVertical()}"
      }
    return arguments
  }

  /**
   * `Symbol(a, b)` on one line, or one argument per line once that would run long.
   *
   * Generated or not, this is source somebody reads and pastes into a file their formatter will
   * check. A 150-column call is a diff nobody wants on their first commit after using the builder.
   */
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
    usesMaterialText = true
    val arguments =
      mutableListOf("text = \"${node.properties["text"]?.stringOrNull().orEmpty().escaped()}\".rs")
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
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
   * `RemoteCustomComponent(name = "field", …)` — the way host content gets back inside a document.
   *
   * A custom component is a `LAYOUT_CUSTOM` operation naming a renderer the *host* registers, so
   * what the body writes is the hole and its reserved bounds, and never the content filling it. The
   * node's `content` slot is therefore deliberately not walked: those children are ordinary Compose
   * the application draws under this name, and emitting them here would put host composables inside
   * a `@RemoteComposable` body, which is the one thing the vocabulary cannot do.
   *
   * The size comes from the node's `widthDp`/`heightDp` rather than from the content, for the
   * reason the capability states: a player lays a custom component out from the document, which
   * cannot measure content it does not have. Both absent is legal and emits no size — the component
   * then takes whatever its parent gives it, which is what an author who set neither asked for.
   *
   * `RemoteCustomComponent` is `@RestrictTo(LIBRARY_GROUP)`, which is why both generated files open
   * with `@file:Suppress("RestrictedApi")`: the call compiles, and the annotation is a lint opinion
   * about who upstream expects to call it rather than a guarantee it will keep working.
   */
  private fun customComponent(node: UiBuilderNode, pad: String): String? {
    // `name` is what the operation carries and what the host looks the renderer up by, so a blank
    // one is not a component with a default — it is a hole nothing can ever fill. Refused by name
    // rather than emitted as `""`, which would compile and draw nothing on every player.
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
    // Reserved bounds first, then whatever the author put on the node: a `padding` after a `size`
    // insets the content of a box that size, which is what an author dragging a padding onto a
    // sized component means, and the reverse would silently grow the hole.
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
   * `LottieAnimation(json = …)` — Horologist's Lottie **compiler**, called with the animation this
   * element carries.
   *
   * The animation does not travel beside the widget: `LottieAnimation` is a `@RemoteComposable`
   * that parses the JSON while the document is being built and re-emits it as Remote Compose
   * operations, so what reaches the watch is a document that draws the animation and nothing else.
   * That is also why a URL cannot be written here — the generated widget has no network at the
   * moment it needs the bytes, so the builder resolves the URL into `json` at authoring time and
   * this refuses the element that still carries only one.
   *
   * The JSON goes into a top-level constant rather than inline. A minified animation is a few
   * thousand columns on one line; put in the body it buries the design in a file somebody has to
   * read, and put in a constant it sits at the bottom where a reader can skip it.
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
    // Reparsed rather than pasted through, for two reasons: an animation that is not JSON is
    // caught here instead of by the reader's compiler, and the round trip drops whatever
    // indentation the source had — which is most of the bytes of a pretty-printed Lottie, and all
    // of them wasted in a string literal.
    val compact =
      try {
        Json.parseToJsonElement(json).toString()
      } catch (failure: Exception) {
        refusals +=
          "the Lottie element `${node.id}` does not hold valid JSON: ${failure.message ?: "it could not be parsed"}"
        return null
      }
    val literal = compact.escaped()
    // A Kotlin string literal is a JVM constant, and a JVM constant is capped at 65535 *bytes* of
    // modified UTF-8. Past that the generated file does not compile — which the author would
    // discover after pasting it — so it is refused here, with the route that does work.
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
    // Absent means "run": `LottieAnimation` drives the frame off the document's own animation
    // clock when it is given no progress. A value pins the animation to one frame, which is what a
    // widget that must not animate wants — so 0f is emitted and an unset property is not.
    node.properties["progress"]?.numberOrNull()?.let {
      usesRemoteFloat = true
      arguments += "progress = ${if (it % 1f == 0f) "${it.toInt()}.rf" else "${it}f.rf"}"
    }
    return call("LottieAnimation", arguments, pad)
  }

  /**
   * `RemoteImage(remoteBitmap = albumArt, …)` — a picture in the widget's content, drawn from a
   * bitmap the **application** supplies.
   *
   * The design carries an asset *key* and the builder's registry carries the bytes behind it.
   * Neither travels into generated source: a widget's picture is application data — album art, an
   * avatar, a logo — that changes long after this file is written, and baking today's bytes in as a
   * constant would generate a widget that draws the picture the design was built with forever. So
   * the key becomes a **parameter**: [imageParameters] names one per distinct key, the content
   * function takes it, and `provideWidgetData` hands it on. That is the same split
   * `docs/UI_BUILDER_GETTING_STARTED.md` already describes for an image *background*, applied to
   * the content slot rather than to the brush chain.
   *
   * `contentDescription` is not optional in the call: upstream declares it `RemoteString?` with no
   * default, so a node without one passes `null` explicitly rather than leaving it out.
   */
  private fun image(node: UiBuilderNode, pad: String): String? {
    val key = node.properties["assetKey"]?.stringOrNull().orEmpty()
    if (key.isBlank()) {
      refusals +=
        "the image `${node.id}` names no asset, and a picture with no key is one nothing can " +
          "resolve — pick an asset for it in the inspector"
      return null
    }
    usesRemoteImage = true
    val arguments = mutableListOf("remoteBitmap = ${imageParameter(key)}")
    val description = node.properties["contentDescription"]?.stringOrNull().orEmpty()
    arguments +=
      if (description.isEmpty()) "contentDescription = null"
      else {
        usesRemoteString = true
        "contentDescription = \"${description.escaped()}\".rs"
      }
    node.modifierExpression(pad)?.let { arguments += "modifier = $it" }
    // Written even when it is upstream's own default: `Fit` is the library's default and `crop` is
    // the builder's, so a design that says nothing means Crop here and leaving the argument out
    // would quietly letterbox every picture the canvas fills.
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
   * The parameter name an asset key becomes, allocated once per key.
   *
   * Per *key* rather than per node, because two nodes drawing the same asset are one picture the
   * application supplies once. Collisions after identifier-ing (`album-art` and `album art`) are
   * suffixed, since two parameters of one name do not compile.
   */
  private fun imageParameter(key: String): String {
    imageAssets[key]?.let {
      return it
    }
    val base = exportedStateIdentifier(key)
    val taken = imageAssets.values.toSet()
    val name =
      if (base !in taken) base
      else generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
    imageAssets[key] = name
    return name
  }

  private val imageAssets = linkedMapOf<String, String>()

  /**
   * The bitmaps this body was written against, in the order they were first drawn.
   *
   * [WearWidgetCodeExporter] turns these into the content function's parameters and the widget
   * class's; [InlineRemoteContentExporter] into the fragment's. The exporters differ over the
   * default, and deliberately — see each.
   */
  val imageParameters: List<ImageParameter>
    get() = imageAssets.map { (key, identifier) -> ImageParameter(identifier, key) }

  /** @property assetKey the design's own key, which the parameter's doc comment names. */
  data class ImageParameter(val identifier: String, val assetKey: String)

  /**
   * Top-level declarations the body refers to, in emission order.
   *
   * Separate from the body because they belong at the *file's* level, not inside the content
   * function: [WearWidgetCodeExporter] appends them after the preview, which is where a reader
   * expects a wall of generated bytes to be rather than in the middle of the design.
   */
  val declarations: List<String>
    get() = lottieDeclarations.toList()

  private val lottieDeclarations = mutableListOf<String>()

  /**
   * The imports the emitted file needs, sorted the way Kotlin style orders them.
   *
   * Gated on what was actually written rather than emitted wholesale: an unused import is a warning
   * in the reader's IDE the moment they paste this in, and "generated" is not a licence to hand
   * someone code they have to tidy.
   */
  fun imports(previewParamsProvider: String?): List<String> {
    val imports = mutableSetOf<String>()
    if (previewParamsProvider != null) imports += "android.content.Context"
    if (usesBox) imports += "androidx.compose.remote.creation.compose.layout.RemoteBox"
    if (usesColumn) imports += "androidx.compose.remote.creation.compose.layout.RemoteColumn"
    imports += "androidx.compose.remote.creation.compose.layout.RemoteComposable"
    if (usesCustomComponent) {
      imports += "androidx.compose.remote.creation.compose.layout.RemoteCustomComponent"
    }
    if (usesRemoteImage) imports += "androidx.compose.remote.creation.compose.layout.RemoteImage"
    if (usesRow) imports += "androidx.compose.remote.creation.compose.layout.RemoteRow"
    if (usesLottie) imports += "com.google.android.horologist.remotecompose.lottie.LottieAnimation"
    if (usesRemoteFloat) imports += "androidx.compose.remote.creation.compose.state.rf"
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
    if (usesRemoteImage)
      imports += "androidx.compose.remote.creation.compose.state.RemoteImageBitmap"
    if (usesMaterialText || usesRemoteString) {
      imports += "androidx.compose.remote.creation.compose.state.rs"
    }
    if (usesSp) imports += "androidx.compose.remote.creation.compose.state.rsp"
    imports += "androidx.compose.runtime.Composable"
    if (usesColorLiteral) imports += "androidx.compose.ui.graphics.Color"
    if (usesContentScale) imports += "androidx.compose.ui.layout.ContentScale"
    if (usesTextAlign) imports += "androidx.compose.ui.text.style.TextAlign"
    // The widget half. A Wear widget is delivered as a `WearWidgetDocument` and previewed through
    // the Glance host tooling; inline remote content inside a phone or watch *screen* is neither,
    // so it takes the vocabulary above and none of this. Gated rather than always-on for the reason
    // every other import here is: an unused import is a warning in the reader's IDE on their first
    // paste.
    if (previewParamsProvider != null) {
      imports += "androidx.compose.ui.tooling.preview.Preview"
      imports += "androidx.compose.ui.tooling.preview.PreviewParameter"
      imports += "androidx.glance.wear.GlanceWearWidget"
      imports += "androidx.glance.wear.WearWidgetBrush"
      imports += "androidx.glance.wear.WearWidgetData"
      imports += "androidx.glance.wear.WearWidgetDocument"
      if (usesBrushColor) imports += "androidx.glance.wear.color"
      if (usesHorizontalGradient) imports += "androidx.glance.wear.horizontalGradient"
      if (usesVerticalGradient) imports += "androidx.glance.wear.verticalGradient"
      // The blank placeholder the generated widget class defaults an image parameter to, so the
      // `@Preview` beside it compiles without a bitmap only the application has.
      if (usesRemoteImage) {
        imports += "androidx.compose.remote.creation.compose.state.rb"
        imports += "androidx.compose.ui.graphics.ImageBitmap"
      }
      imports += "androidx.glance.wear.core.WearWidgetParams"
      imports += "androidx.glance.wear.tooling.preview.$previewParamsProvider"
      imports += "androidx.glance.wear.tooling.preview.WearWidgetPreview"
    }
    if (usesRemoteColorScheme) {
      imports += "androidx.wear.compose.remote.material3.RemoteColorScheme"
    }
    if (usesTheme) imports += "androidx.wear.compose.remote.material3.RemoteMaterialTheme"
    if (usesMaterialText) imports += "androidx.wear.compose.remote.material3.RemoteText"
    return imports.sorted()
  }

  /**
   * A gradient call, recorded so [imports] names the direction it actually wrote.
   *
   * `horizontalGradient` and `verticalGradient` are separate top-level functions, and importing
   * both because a gradient exists hands the reader an unused import on their first paste — the
   * thing every other import here is gated to avoid.
   */
  private fun horizontal(call: String): String = call.also { usesHorizontalGradient = true }

  private fun vertical(call: String): String = call.also { usesVerticalGradient = true }

  private var usesModifier = false
  private var usesCustomComponent = false
  private var usesLottie = false
  private var usesRemoteFloat = false
  private var usesSp = false
  private var usesHorizontalGradient = false
  private var usesVerticalGradient = false
  private var usesRemoteScrollState = false
  private var usesRoundedCornerShape = false
  private var usesRemoteImage = false
  private var usesRemoteString = false
  private var usesContentScale = false
  private val usedModifierImports = mutableSetOf<String>()

  /**
   * @param leading modifier calls this emitter derived from the node's own properties, applied
   *   before the authored chain.
   */
  private fun UiBuilderNode.modifierExpression(
    pad: String,
    leading: List<String> = emptyList(),
  ): String? {
    val parts = leading + modifiers.flatMap { element -> modifierCalls(element) }
    if (parts.isEmpty()) return null
    usesModifier = true
    val single = parts.joinToString(".", prefix = "RemoteModifier.")
    // A chain of four modifiers is past the column budget on its own, and [call] can only break
    // between *arguments* — so a long chain is broken here, at its dots, the way the formatter the
    // budget exists to satisfy would break it. The continuation indent is two levels because a
    // call carrying an argument this long has already wrapped one argument per line.
    if (pad.length + INDENT.length + MODIFIER_ARGUMENT.length + single.length <= MAX_LINE) {
      return single
    }
    val continuation = "\n$pad$INDENT$INDENT."
    return parts.joinToString(continuation, prefix = "RemoteModifier.")
  }

  /**
   * One authored modifier as the calls it becomes — none, one, or two.
   *
   * Two exists for `background(colour, shape)`, which upstream does not have: `RemoteModifier`
   * takes a colour, a brush or a painter and no shape, and `clip(shape).background(colour)` is what
   * Compose's own two-argument overload does. Nothing here approximates: a modifier the vocabulary
   * has no counterpart for is refused **by name and with the reason**, never dropped, because a
   * widget that silently loses its `size` draws something the canvas never showed.
   */
  private fun UiBuilderNode.modifierCalls(element: JsonElement): List<String> {
    val modifier = element as? JsonObject ?: return emptyList()
    return when (val type = modifier["type"]?.stringValue()) {
      "fillMaxSize" -> listOf(modifierCall("fillMaxSize()"))
      "fillMaxWidth" -> listOf(modifierCall("fillMaxWidth()"))
      "fillMaxHeight" -> listOf(modifierCall("fillMaxHeight()"))
      "wrapContentSize" -> {
        // Upstream's `wrapContentSize()` takes no alignment, and Compose's default is `Center`.
        // A design that named a corner asked for something this cannot write, so it is refused
        // rather than centred silently.
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
      // The scroll position belongs to the played document rather than to the design, exactly as
      // the Compose exporter's `rememberScrollState()` does: the body is a composable, so the
      // state is remembered at the call site.
      "verticalScroll",
      "horizontalScroll" -> {
        usesRemoteScrollState = true
        listOf(modifierCall("$type(rememberRemoteScrollState())"))
      }
      "weight" -> weightCall(modifier)
      // Everything below is in the catalog's modifier vocabulary and has no `RemoteModifier`
      // counterpart at `remote-creation-compose` 1.0.0-alpha18. Each says which, and what to
      // reach for instead, rather than sharing one "no counterpart" sentence: an author who is
      // told `matchParentSize` is missing still has to guess that `fillMaxSize` is the answer.
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
      // Read by the parent rather than written here: `RemoteBox`, `RemoteColumn` and `RemoteRow`
      // each align their content as a group, so a child's alignment becomes an argument of the
      // container above it — see [boxArguments] and [crossAxisAlignment], which also refuse when
      // two children ask for different ones. In the wrong container it is a refusal, because the
      // axis a column aligns on is not the axis the design named.
      "align" -> alignmentConsumedBy("RemoteBox", type, "a box")
      "alignHorizontal" -> alignmentConsumedBy("RemoteColumn", type, "a column")
      "alignVertical" -> alignmentConsumedBy("RemoteRow", type, "a row")
      null -> emptyList()
      else -> {
        refusals += "the `$type` modifier on `$id` has no RemoteModifier counterpart here"
        emptyList()
      }
    }
  }

  /**
   * Nothing, when the container above this node has already written the alignment as an argument.
   *
   * @param container the symbol whose lambda consumes it, and the only scope it means anything in.
   */
  private fun UiBuilderNode.alignmentConsumedBy(
    container: String,
    type: String,
    article: String,
  ): List<String> {
    if (scope == container) return emptyList()
    refusals +=
      "the `$type` modifier on `$id` aligns a child of $article, and this node is in " +
        "${scope?.let { "a $it" } ?: "the widget's content slot"}; a played document has no " +
        "per-child alignment outside the container that lays it out"
    return emptyList()
  }

  /**
   * `weight`, which is legal only in the lambda of the row or column it is measured against.
   *
   * Upstream puts it on `RemoteColumnScope` and `RemoteRowScope` rather than on `RemoteModifier`,
   * so a weight anywhere else does not compile — and the node that carries one outside a row or
   * column is a design mistake worth naming rather than a call worth writing.
   */
  private fun UiBuilderNode.weightCall(modifier: JsonObject): List<String> {
    if (scope != "RemoteColumn" && scope != "RemoteRow") {
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
    val weight = modifier["weight"]?.numberValue() ?: 1f
    return listOf("weight(${weight.floatLiteral()})")
  }

  /**
   * A modifier call, and the import its symbol needs.
   *
   * Every modifier upstream is an extension function in its own file, so the import is the call's
   * name — which is why this pairs the two rather than letting [imports] guess from the chain.
   */
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

  private fun JsonObject.dp(name: String, fallback: Float = 0f): String =
    (this[name]?.numberValue() ?: fallback).dpLiteral()

  private fun JsonObject.float(name: String, fallback: Float = 0f): String =
    (this[name]?.numberValue() ?: fallback).floatLiteral()

  /**
   * The colour inside a modifier, which is a `UiValueV1` rather than a bare string.
   *
   * The same two cases the rest of this emitter reads a colour through: a theme role is
   * `RemoteMaterialTheme.colorScheme.<role>` and needs the theme wrapper, and a `#AARRGGBB` literal
   * is a `Color(0x…).rc`.
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

  private fun Float.spLiteral(): String =
    if (this % 1f == 0f) "${toInt()}.rsp".also { usesSp = true }
    else "${this}f.rsp".also { usesSp = true }

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

/** `#FF2196F3` becomes `Color(0xFF2196F3)`. */
private fun String.argbLiteral(): String = "Color(0x${removePrefix("#").uppercase()})"

/**
 * Escaped for a Kotlin `"…"` literal.
 *
 * `$` is in here because of the Lottie path: a template expansion is not something a text property
 * ever contained by accident, but an animation's JSON is arbitrary text somebody else wrote, and a
 * layer named `$1` would otherwise generate a file that does not compile.
 */
private fun String.escaped(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

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
