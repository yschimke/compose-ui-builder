package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.protocol.AdaptiveGridValueV1
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.ClipModifierV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DimensionUnitV1
import ee.schimke.composeai.uibuilder.protocol.DimensionValueV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.InsetsValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.ListValueV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.ObjectValueV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingValueV1
import ee.schimke.composeai.uibuilder.protocol.ResourceValueV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StateEqualsValueV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The Kotlin parameter a catalog slot fills, when the two are not spelled the same.
 *
 * A capability catalog names slots for designers — `layout/column` has `children` — and a Compose
 * signature names them for the compiler: `Column(content = …)`. The generator emits `<parameter> =
 * { … }` from the record's own parameter list, so a document slot that keeps the catalog's spelling
 * either fails to match the record or emits `children = { … }`, which does not compile. Neither is
 * recoverable from data: the record describes the real signature and the document describes the
 * design, and the fact that these two names mean the same region is knowledge that lives in
 * neither.
 *
 * So it is authored, here, per component — and deliberately **not** by renaming the parameter in
 * the record, which would make the record lie about the signature it exists to attest.
 *
 * An id with no entry passes its slot names through unchanged, which is right for the majority:
 * `layout/scaffold`'s `topBar`, `snackbarHost` and `content` already match `Scaffold`'s, and so do
 * `m3/filter-chip`'s `label` and `leadingIcon`.
 */
private val SLOT_PARAMETERS: Map<String, Map<String, String>> =
  mapOf(
    "layout/box" to mapOf("children" to "content"),
    "layout/column" to mapOf("children" to "content"),
    "layout/row" to mapOf("children" to "content"),
  )

private fun parameterForSlot(componentId: String, slot: String): String =
  SLOT_PARAMETERS[componentId]?.get(slot) ?: slot

/**
 * Projects a saved [DesignDocumentV1] onto the [ScreenDocument] `ScreenGenerator` consumes.
 *
 * ## Why this is the interesting half
 *
 * The generator's guarantee is narrow and load-bearing: it emits a call site only when the
 * discovered component record proves one can be written, and refuses otherwise. That guarantee is
 * worth exactly as much as this projection's honesty, because everything the generator trusts —
 * which component a node is, what type a value has, which Kotlin expression a design token means —
 * arrives from here.
 *
 * So the rule here is the same one: **express it or refuse it by name.** The projection this
 * replaced (`ComposeSourceProjection`, which shipped a self-declared `ALMOST_COMPILING_PROJECTION`
 * warning on every export) guessed instead. It put `@file:OptIn(ExperimentalMaterial3Api::class)`
 * on every file whether or not anything needed it, `modifier = …` on every node whether or not the
 * component had such a parameter, and `checkNotNull(components[componentId])` where a stale
 * document deserved an error. The output looked like Kotlin and did not compile, and nothing in the
 * artifact said which of the two it was.
 *
 * ## What it does not express, deliberately
 *
 * Seven kinds of document content have no expression here, and each refuses under its own name
 * rather than being dropped — the seventh, enum values, has its own section below because this file
 * claimed the opposite for a round:
 *
 * - **State.** `StateValueV1` and `StateEqualsValueV1` read a state variable, which needs a
 *   `remember { mutableStateOf(…) }` preamble and a hoisting decision. That is the
 *   "project-specific state adapter" the old warning gestured at; naming it is the first step to
 *   having one.
 * - **Events.** `eventBindings` needs the same for the other direction.
 * - **Conditional nodes.** A `predicate` is a state read in disguise.
 * - **Assets.** `assetBindings` resolves to project-owned artwork through a caller-supplied
 *   adapter, which this projection has no channel for.
 * - **Insets.** `WindowInsets` is read through a composable-scope call, not a value.
 * - **Accessibility.** A `semantics {}` block is a modifier chain the record cannot type-check.
 *
 * ## The one claim it makes
 *
 * A design token (`colorToken`, `typographyToken`, `shapeToken`) is resolved through a **table of
 * Material 3's own accessors** below. That table is the design-system knowledge the generator
 * deliberately does not hold, and it lives here because this is the layer that knows the catalog is
 * Material 3.
 *
 * ## Enum values are the seventh refusal
 *
 * An earlier revision of this file resolved an `enum` against the parameter's own recorded type,
 * appending the document's entry name to `TargetParameter.typeFqn` and calling that a claim the
 * compile gate would check. It is not a claim, and this documented it as one for a round:
 *
 * - the wire values are lower-camel (`center`, `semiBold`), so `TextAlign.center` never compiled;
 * - and capitalising fixes only the half of them that are members of that type at all. Jetcaster's
 *   `accountCircle`, `moreVert` and `playCircle` sit on an `ImageVector` parameter whose entries
 *   live under `Icons`, while `expandedTwoPane`, `fab` and `uncontained` name authored variants
 *   with no single Kotlin type behind them.
 *
 * Nothing in the catalog maps an enum **value** to a Kotlin member — the `code` capability carries
 * a symbol per component and nothing per entry — so `enum()` refuses every `EnumValueV1` by name,
 * and the refusal says that is what is missing. A wire-to-Kotlin mapping is where this becomes
 * expressible, and it belongs in the catalog rather than in a projection.
 */
object ScreenDocumentProjection {

  sealed interface Outcome {
    data class Projected(val document: ScreenDocument) : Outcome

    /** Every unexpressible thing found, not the first — a builder wants the whole list. */
    data class Refused(val reasons: List<String>) : Outcome
  }

  fun project(
    document: DesignDocumentV1,
    screenName: String = screenNameFor(document),
    /**
     * Whether every node carries `Modifier.testTag("<nodeId>")`.
     *
     * Off for an export, because a test tag is not something a designer asked for and the artifact
     * is source somebody keeps. On for the **native preview** lane, where it is the only thing that
     * ties a rendered rectangle back to the node that drew it: the server's semantics observation
     * already reports each authored tag with its bounds in render pixels, so tagging here is what
     * lets a streamed Android or desktop frame carry selectable regions instead of being a picture.
     */
    tagNodes: Boolean = false,
  ): Outcome {
    // No component record parameter. It was here only so an enum value could be qualified with
    // its parameter's recorded type, and `enum` refuses instead — see its KDoc. A parameter kept
    // "in case" is how a reader starts believing this projection type-checks against the record,
    // which it does not: `ScreenGenerator` does that, once, with the record it is handed.
    val pass = Pass(document, tagNodes)
    val roots = document.roots
    if (roots.size != 1) {
      // One root is not a limitation of the generator; it is what a `@Composable fun Screen()`
      // body is. Two roots need a container around them, and choosing `Column` over `Box` is a
      // layout decision the document did not make and this projection must not invent.
      //
      // Every root is still visited before returning. Refusing on the count alone hid whatever
      // else was wrong inside those roots until after someone had wrapped them and exported
      // again — one export per problem, which is the thing `Outcome.Refused` exists to avoid.
      val counted =
        "the document has ${roots.size} roots; a generated screen body needs exactly one, so " +
          "wrap them in a layout component in the builder"
      roots.forEach { pass.node(it) }
      return Outcome.Refused((listOf(counted) + pass.reasons).distinct())
    }
    val root = pass.node(roots.single())
    if (pass.reasons.isNotEmpty()) return Outcome.Refused(pass.reasons.distinct())
    return Outcome.Projected(ScreenDocument(name = screenName, root = checkNotNull(root)))
  }

  /**
   * A Kotlin function name for the design.
   *
   * Derived from the title rather than the id because a title is what a human named the screen and
   * an id is a uuid. Non-identifier runs become word boundaries, so `Schedule operations` is
   * `ScheduleOperations`; a title with nothing usable in it falls back to a fixed name rather than
   * to the id, since an id is no more likely to be an identifier. The generator checks the result
   * either way — this only has to produce a plausible candidate.
   */
  fun screenNameFor(document: DesignDocumentV1): String {
    val words =
      document.title
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.isNotEmpty() }
        .map { word -> word.replaceFirstChar { it.uppercaseChar() } }
    val name = words.joinToString("")
    return when {
      name.isEmpty() -> "GeneratedScreen"
      // A leading digit is an identifier's one positional rule, and a title like `2026 review`
      // hits it immediately.
      name.first().isDigit() -> "Screen$name"
      else -> name
    }
  }

  private class Pass(val document: DesignDocumentV1, val tagNodes: Boolean = false) {
    val reasons = mutableListOf<String>()
    private val visiting = mutableSetOf<String>()

    fun node(id: String): ScreenNode? {
      val node = document.nodes[id]
      if (node == null) {
        reasons += "the document references node `$id`, which it does not define"
        return null
      }
      // A slot cycle is expressible in a map of ids and is not expressible in Kotlin. Left
      // unchecked it is an infinite recursion here rather than a refusal, and the document arrives
      // over the wire.
      if (!visiting.add(id)) {
        reasons += "node `$id` contains itself through its slots"
        return null
      }
      try {
        return ScreenNode(
          componentId = node.componentId,
          arguments = arguments(node),
          slots =
            node.slots
              .mapKeys { (slot, _) -> parameterForSlot(node.componentId, slot) }
              .mapValues { (_, children) -> children.mapNotNull { child -> node(child) } },
        )
      } finally {
        visiting.remove(id)
      }
    }

    private fun arguments(node: DesignNodeV1): Map<String, ScreenValue> {
      unexpressible(node)
      val arguments = mutableMapOf<String, ScreenValue>()
      for ((property, value) in node.properties) {
        val target = PROPERTY_PARAMETERS[node.componentId]?.get(property)
        if (target == null) {
          arguments[property] = value(value, node, property) ?: continue
          continue
        }
        arguments[target.parameter] = retarget(target, value, node, property) ?: continue
      }
      if (node.modifiers.isNotEmpty() || tagNodes) {
        modifiers(node)?.let { arguments["modifier"] = it }
      }
      return arguments
    }

    /** Names every part of a node this projection has no expression for. */
    private fun unexpressible(node: DesignNodeV1) {
      val id = node.id
      if (node.eventBindings.isNotEmpty()) {
        reasons +=
          "node `$id` binds the event(s) ${node.eventBindings.keys.sorted().joinToString(", ")}, " +
            "which need an event adapter this projection has no channel for"
      }
      if (node.predicate != null) {
        reasons += "node `$id` is conditional on a predicate, which reads state"
      }
      if (node.assetBindings.isNotEmpty()) {
        reasons +=
          "node `$id` binds the asset(s) ${node.assetBindings.keys.sorted().joinToString(", ")}, " +
            "which need a caller-supplied artwork adapter"
      }
      if (node.tokenBindings.isNotEmpty()) {
        reasons +=
          "node `$id` overrides the token(s) " +
            "${node.tokenBindings.keys.sorted().joinToString(", ")}, which this projection reads " +
            "from the theme rather than from the document"
      }
      if (node.accessibility != null) {
        reasons +=
          "node `$id` sets accessibility, which is a `semantics {}` modifier the component record " +
            "cannot type-check"
      }
    }

    /** The `modifier` argument for a node's modifier list, or null having said why not. */
    private fun modifiers(node: DesignNodeV1): ScreenValue? {
      // Every modifier is visited even after one fails. A non-local `return` out of the map stopped
      // at the first, which quietly broke this projection's one promise: `Outcome.Refused` carries
      // *every* unexpressible thing so a document can be fixed in one pass, not one per export.
      val links = node.modifiers.map { link(it, node.id) }
      if (links.any { it == null }) return null
      // Last in the chain, so a tagged preview and its untagged export differ by exactly one
      // appended link and nothing about the modifiers a designer wrote moves.
      val tag =
        if (!tagNodes) emptyList()
        else listOf(ChainLink(TEST_TAG, positional = listOf(ScreenValue.Text(node.id))))
      return ScreenValue.Chain(
        receiver = ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER),
        links = links.filterNotNull() + tag,
        typeFqn = MODIFIER,
      )
    }

    private fun link(modifier: DesignModifierV1, nodeId: String): ChainLink? =
      when (modifier) {
        FillMaxWidthModifierV1 -> ChainLink("androidx.compose.foundation.layout.fillMaxWidth")
        FillMaxSizeModifierV1 -> ChainLink("androidx.compose.foundation.layout.fillMaxSize")
        is PaddingModifierV1 -> {
          val axes = buildMap {
            dp(modifier.startDp)?.let { put("start", it) }
            dp(modifier.topDp)?.let { put("top", it) }
            dp(modifier.endDp)?.let { put("end", it) }
            dp(modifier.bottomDp)?.let { put("bottom", it) }
          }
          // No usable axis emits `Modifier.padding()`, which is ambiguous between Compose's two
          // fully-defaulted overloads and compiles as neither. Catalog validation checks that the
          // modifier *type* is allowed and not that its axes are numbers, and the renderer reads a
          // bad number as zero, so such a document reaches here rather than being stopped earlier.
          if (axes.isEmpty()) {
            reasons += "node `$nodeId` pads with no axis that is a number"
            null
          } else {
            ChainLink("androidx.compose.foundation.layout.padding", named = axes)
          }
        }
        is SizeModifierV1 -> {
          // `size` has two overloads and neither accepts one named axis: `size(size: Dp)` names
          // its parameter `size`, and `size(width: Dp, height: Dp)` requires both. So a modifier
          // carrying one axis has to become `width(…)` or `height(…)`, which the renderer treats
          // the same way and the compiler accepts.
          val width = dp(modifier.widthDp)
          val height = dp(modifier.heightDp)
          when {
            width != null && height != null ->
              ChainLink(
                "androidx.compose.foundation.layout.size",
                named = mapOf("width" to width, "height" to height),
              )
            width != null ->
              ChainLink("androidx.compose.foundation.layout.width", positional = listOf(width))
            height != null ->
              ChainLink("androidx.compose.foundation.layout.height", positional = listOf(height))
            else -> {
              reasons += "node `$nodeId` sizes to neither a width nor a height"
              null
            }
          }
        }
        is ClipModifierV1 -> {
          // A theme shape first, then the two constants. `medium` and `large` are what real
          // documents clip to and they are `MaterialTheme.shapes` roles, not constants — refusing
          // them lost a clip the previous exporter rendered correctly.
          val shape =
            SHAPE_TOKENS[modifier.shape]?.let { path ->
              ScreenValue.Reference(path.first(), path.drop(1), typeFqn = SHAPE)
            } ?: SHAPE_CONSTANTS[modifier.shape]?.let { ScreenValue.Reference(it, typeFqn = SHAPE) }
          if (shape == null) {
            reasons +=
              "node `$nodeId` clips to shape `${modifier.shape}`, which is neither a theme shape " +
                "(${SHAPE_TOKENS.keys.sorted().joinToString(", ")}) nor one of " +
                SHAPE_CONSTANTS.keys.sorted().joinToString(", ")
            null
          } else {
            ChainLink("androidx.compose.ui.draw.clip", positional = listOf(shape))
          }
        }
        // `matchParentSize` is declared on `BoxScope`, so it compiles inside a `Box` slot and
        // nowhere else. The record does carry each slot's receiver scope, but this projection does
        // not know which slot a node was placed in, and emitting a scoped modifier on that basis
        // would generate an unresolved reference for every node that is not in a `Box`.
        MatchParentSizeModifierV1 ->
          null.also {
            reasons +=
              "node `$nodeId` uses `matchParentSize`, which is declared on `BoxScope` and cannot " +
                "be proven in scope here"
          }
        else ->
          null.also {
            reasons +=
              "node `$nodeId` uses the modifier ${modifier::class.simpleName}, which this " +
                "projection has no expression for"
          }
      }

    /**
     * The receiver for a `.dp` or `.sp` chain, or null when no receiver expresses this number.
     *
     * Whole when the number is one, because `16.dp` reading as `16.0.dp` in generated source is
     * noise a human would not have written — but **only inside the `Int` range**. Compose declares
     * these extensions on `Int`, `Double` and `Float` and not on `Long`, so `2147483648` rendered a
     * `Long` receiver and `2147483648.dp` does not compile, while the export was returned as a
     * clean success.
     *
     * The `Double` overload exists — checked against `ui-unit`'s own bytecode, which carries
     * `getDp(int)`, `getDp(double)` and `getDp(float)` — so the fractional fallback compiles. What
     * it does **not** do is preserve the value: `Dp` is a value class over `Float`, so the `Double`
     * overload narrows, and `1e100.dp` compiles into `Float.POSITIVE_INFINITY`. That is a success
     * carrying a number the design never contained, which is worse than a refusal, so anything that
     * does not survive the narrowing is refused instead.
     */
    private fun unitReceiver(number: Double): ScreenValue? {
      if (!number.isFinite() || !number.toFloat().isFinite()) return null
      val whole = number.toLong()
      return if (number == whole.toDouble() && whole in Int.MIN_VALUE..Int.MAX_VALUE)
        ScreenValue.Whole(whole)
      else ScreenValue.Fractional(number)
    }

    /** A `Dp` for a JSON number, or null when the field was absent or not a number. */
    private fun dp(value: JsonElement?): ScreenValue? {
      val number = (value as? JsonPrimitive)?.doubleOrNull ?: return null
      return dp(number)
    }

    private fun dp(number: Double): ScreenValue? =
      unitReceiver(number)?.let { receiver ->
        ScreenValue.Chain(
          // `16.dp` rather than `Dp(16f)`: the extension is what a human writes, and it reads the
          // same in the generated file as in the file it was copied from. The receiver is a whole
          // number when it is one, because `.dp` is declared on `Int` and on `Float` alike and an
          // `Int` receiver keeps `16.dp` from rendering as `16.0.dp`.
          receiver = receiver,
          links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
          typeFqn = DP,
        )
      }

    /**
     * One property whose Compose parameter is not merely spelled differently but *shaped*
     * differently.
     *
     * A catalog authors what a designer sets — a corner radius in dp, a gap between children — and
     * Compose takes what the API declares: a `Shape`, an `Arrangement.Vertical`. The number in the
     * document is an ingredient of the argument rather than the argument, so a rename alone would
     * hand `Surface` a `Float` where it wants a `Shape`.
     */
    private fun retarget(
      target: ParameterTarget,
      value: UiValueV1,
      node: DesignNodeV1,
      property: String,
    ): ScreenValue? {
      val where = "node `${node.id}`.`$property`"
      if (target.kind == TargetKind.RENAME) return value(value, node, property)
      if (target.kind == TargetKind.SHAPE_TOKEN) {
        // Only the text spelling needs help. A `shapeToken` wrapper already resolves through the
        // same table in `value`, and the checked-in fixtures use it — narrowing this to strings
        // refused a document that exported before, which is the one thing a widening must not do.
        val name = (value as? StringValueV1)?.value ?: return value(value, node, property)
        return token(name, SHAPE_TOKENS, SHAPE, "shape", where)
      }
      val number =
        when (value) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> return refuse("$where becomes `${target.parameter}`, which needs a number")
        }
      val dp = dp(number) ?: return refuse("$where is $number, which does not survive `Dp`")
      return when (target.kind) {
        TargetKind.DP -> dp
        TargetKind.ROUNDED_CORNER_SHAPE ->
          ScreenValue.Construct(
            callableFqn = "androidx.compose.foundation.shape.RoundedCornerShape",
            positional = listOf(dp),
            // The parameter's own type, not the expression's. `RoundedCornerShape` is a `Shape`,
            // and the generator compares this claim to `TargetParameter.typeFqn` as a string — the
            // same reason `clip` claims `Shape` for a theme role rather than the role's own class.
            typeFqn = SHAPE,
          )
        TargetKind.SPACED_BY_VERTICAL,
        TargetKind.SPACED_BY_HORIZONTAL ->
          ScreenValue.Construct(
            // A member of the `Arrangement` object, which reads as an ordinary qualified call.
            callableFqn = "androidx.compose.foundation.layout.Arrangement.spacedBy",
            positional = listOf(dp),
            typeFqn =
              if (target.kind == TargetKind.SPACED_BY_VERTICAL) ARRANGEMENT_VERTICAL
              else ARRANGEMENT_HORIZONTAL,
          )
        TargetKind.RENAME,
        TargetKind.SHAPE_TOKEN -> error("handled above")
      }
    }

    /** The Kotlin value for one property, or null having said why there isn't one. */
    private fun value(value: UiValueV1, node: DesignNodeV1, property: String): ScreenValue? {
      val where = "node `${node.id}`.`$property`"
      return when (value) {
        is StringValueV1 -> ScreenValue.Text(value.value)
        is BooleanValueV1 -> ScreenValue.Bool(value.value)
        is IntegerValueV1 -> ScreenValue.Whole(value.value)
        is DecimalValueV1 -> ScreenValue.Fractional(value.value)
        is ColorValueV1 -> color(value.value, where)
        is ColorTokenValueV1 -> token(value.value, COLOR_TOKENS, COLOR, "colour", where)
        is TypographyTokenValueV1 ->
          token(value.value, TYPOGRAPHY_TOKENS, TEXT_STYLE, "typography", where)
        is ShapeTokenValueV1 -> token(value.value, SHAPE_TOKENS, SHAPE, "shape", where)
        is DimensionValueV1 -> dimension(value, where)
        is PaddingValueV1 -> {
          val axes = buildMap {
            dp(value.startDp)?.let { put("start", it) }
            dp(value.topDp)?.let { put("top", it) }
            dp(value.endDp)?.let { put("end", it) }
            dp(value.bottomDp)?.let { put("bottom", it) }
          }
          // `PaddingValues()` is ambiguous for the same reason `Modifier.padding()` is: every
          // overload is fully defaulted, so an argument list with nothing in it picks none of them.
          if (axes.isEmpty()) refuse("$where has no axis that is a number")
          else
            ScreenValue.Construct(
              callableFqn = "androidx.compose.foundation.layout.PaddingValues",
              named = axes,
              typeFqn = "androidx.compose.foundation.layout.PaddingValues",
            )
        }
        is EnumValueV1 -> enum(value.value, node.componentId, property, where)
        is StateValueV1 ->
          refuse(
            "$where reads the state variable `${value.variable}`, which needs a " +
              "`remember { mutableStateOf(…) }` preamble this projection does not emit"
          )
        is StateEqualsValueV1 ->
          refuse(
            "$where compares the state variable `${value.variable}`, which needs the same state " +
              "preamble"
          )
        is InsetsValueV1 ->
          refuse("$where is window insets, which are read through a composable call, not a value")
        is NullValueV1 ->
          // Not a refusal of principle: `null` is a perfectly good argument. But the generator
          // checks a claimed type against the parameter's, and `null`'s type is whatever the
          // parameter is — there is nothing to claim. A nullable parameter left unset takes its
          // default, which is what the document meant.
          refuse("$where is null; leave the property unset instead so the default applies")
        is ListValueV1 -> refuse("$where is a list, which no component parameter accepts directly")
        is ObjectValueV1 -> refuse("$where is an object, which has no Kotlin literal")
        is ResourceValueV1 ->
          refuse("$where is a resource reference, which needs an Android resource context")
        is AssetKeyValueV1 -> refuse("$where is an asset key, which needs an artwork adapter")
        is AdaptiveGridValueV1 ->
          refuse("$where is an adaptive grid specification, which is a layout rather than a value")
        else -> refuse("$where is a ${value::class.simpleName}, which is not projected")
      }
    }

    private fun color(value: String, where: String): ScreenValue? {
      val digits = value.removePrefix("#")
      // The prefix is required, not optional. `UiBuilderRenderer.color` reads a literal only when
      // the string starts with `#` and sends everything else to a token table whose `else` branch
      // raises, so `6750A4` renders as an error while it exported here as a perfectly good
      // `Color(0xFF6750A4)`. An artifact that disagrees with what the design renders is what this
      // executor exists to stop producing, even when the Kotlin compiles.
      //
      // Only hex digits get this message, though. Suggesting `#rebeccapurple` to someone who wrote
      // a CSS colour name would be worse than the shape refusal below, which is what that is.
      if (
        !value.startsWith("#") && digits.length in setOf(6, 8) && digits.toLongOrNull(16) != null
      ) {
        return refuse(
          "$where is the colour `$value`, which the renderer reads as a token rather than a " +
            "literal; write it as `#$value` if a literal was meant"
        )
      }
      val argb =
        when (if (value.startsWith("#")) digits.length else -1) {
          // `RRGGBB` is opaque by convention everywhere this format appears, so the alpha is
          // supplied rather than left at zero — which would render every six-digit colour
          // invisible.
          6 -> digits.toLongOrNull(16)?.let { 0xFF000000L or it }
          8 -> digits.toLongOrNull(16)
          else -> null
        }
      if (argb == null) {
        return refuse("$where is the colour `$value`, which is not #RRGGBB or #AARRGGBB")
      }
      return ScreenValue.Construct(
        callableFqn = COLOR,
        positional = listOf(ScreenValue.Whole(argb)),
        typeFqn = COLOR,
      )
    }

    private fun token(
      name: String,
      table: Map<String, List<String>>,
      typeFqn: String,
      kind: String,
      where: String,
    ): ScreenValue? {
      val path = table[name]
      if (path == null) {
        return refuse(
          "$where is the $kind token `$name`, which is not one this catalog's theme defines"
        )
      }
      return ScreenValue.Reference(
        rootFqn = path.first(),
        members = path.drop(1),
        typeFqn = typeFqn,
      )
    }

    private fun dimension(value: DimensionValueV1, where: String): ScreenValue? {
      val number = (value.value as? JsonPrimitive)?.doubleOrNull
      if (number == null) {
        return refuse("$where is a dimension whose value is not a number")
      }
      return when (value.unit) {
        DimensionUnitV1.DP ->
          dp(number)
            ?: refuse(
              "$where is $number, which does not survive the narrowing to `Float` that `Dp` " +
                "performs"
            )
        DimensionUnitV1.SP ->
          ScreenValue.Chain(
            receiver =
              unitReceiver(number)
                ?: return refuse(
                  "$where is $number, which does not survive the narrowing to `Float` that " +
                    "`TextUnit` performs"
                ),
            links = listOf(ChainLink("androidx.compose.ui.unit.sp", property = true)),
            typeFqn = "androidx.compose.ui.unit.TextUnit",
          )
        // A raw pixel is density-dependent and a percentage is parent-dependent; both need the
        // composition's density or a layout pass, neither of which is a value.
        DimensionUnitV1.PX ->
          refuse("$where is in pixels, which needs the composition's density to become a `Dp`")
        DimensionUnitV1.PERCENT ->
          refuse("$where is a percentage, which needs a parent size to become a `Dp`")
      }
    }

    /**
     * The Kotlin member a catalog enum value names, or a refusal saying why there isn't one.
     *
     * The first version of this projection appended the document's value to the parameter's
     * recorded type — `TextAlign` + `Center` — and called that a claim the compile gate would
     * check. It is not one, for two reasons that are both still true. The wire values are
     * **lower-camel**, so `TextAlign.center` never compiled; and many of them are not members of
     * the parameter's type at all — `accountCircle` and `moreVert` sit on an `ImageVector`
     * parameter whose entries live under `Icons`, while `expandedTwoPane`, `filled` and
     * `uncontained` name authored *component variants* with no single Kotlin member behind them.
     *
     * So the derivation stayed refused, and this reads a table instead — [ENUM_MEMBERS], keyed by
     * component and property for the same reason [SLOT_PARAMETERS] is: which Kotlin member a
     * catalog value means is knowledge neither side holds, and `style` means a typography role on
     * `m3/text` and a component variant on `m3/button`.
     *
     * A value the table has no entry for is still refused by name. That is the half worth keeping
     * from the previous behaviour: a variant name emitting nonsense with no diagnostic is the
     * failure mode all of this exists to remove.
     */
    private fun enum(
      entry: String,
      componentId: String,
      property: String,
      where: String,
    ): ScreenValue? {
      val mapping =
        ENUM_MEMBERS[componentId]?.get(property)
          ?: return refuse(
            if (componentId to property in VARIANT_PROPERTIES) {
              "$where is `$entry`, which names a component variant rather than a value: the " +
                "catalog spells three Compose components as one id, and choosing between them is " +
                "a call-site decision this projection cannot make from a parameter"
            } else {
              "$where is the enum value `$entry`, and nothing maps this catalog property's " +
                "values to Kotlin members"
            }
          )
      val path =
        mapping.members[entry]
          ?: return refuse(
            "$where is the enum value `$entry`, which is not one of " +
              mapping.members.keys.sorted().joinToString(", ")
          )
      return ScreenValue.Reference(
        rootFqn = path.first(),
        members = path.drop(1),
        typeFqn = mapping.typeFqn,
      )
    }

    private fun refuse(reason: String): ScreenValue? {
      reasons += reason
      return null
    }
  }

  private const val MODIFIER = "androidx.compose.ui.Modifier"

  /**
   * The tag a native preview's nodes carry.
   *
   * `androidx.compose.ui.platform.testTag` and not a semantics block: the server's existing
   * annotation lane reports authored test tags with their bounds in render pixels, so this is the
   * one modifier that makes a streamed frame addressable by design node id without a new data
   * product. It is inside the generator's `androidx.compose` allow-list, so no widening is needed
   * to emit it.
   */
  private const val TEST_TAG = "androidx.compose.ui.platform.testTag"
  private const val COLOR = "androidx.compose.ui.graphics.Color"
  private const val DP = "androidx.compose.ui.unit.Dp"
  private const val SHAPE = "androidx.compose.ui.graphics.Shape"
  private const val TEXT_STYLE = "androidx.compose.ui.text.TextStyle"
  private const val THEME = "androidx.compose.material3.MaterialTheme"

  /**
   * Material 3's colour roles, as the accessor path that reads each one.
   *
   * A table, not a transformation, and that is the point: `MaterialTheme.colorScheme` really does
   * have a `primary` and really does not have a `transparent`, so the two resolve to different
   * roots. A rule like "camel-case the token onto `colorScheme`" would emit an unresolved reference
   * for the second and there would be nothing here saying why.
   */
  private val COLOR_TOKENS: Map<String, List<String>> =
    listOf(
        "primary",
        "onPrimary",
        "primaryContainer",
        "onPrimaryContainer",
        "inversePrimary",
        "secondary",
        "onSecondary",
        "secondaryContainer",
        "onSecondaryContainer",
        "tertiary",
        "onTertiary",
        "tertiaryContainer",
        "onTertiaryContainer",
        "background",
        "onBackground",
        "surface",
        "onSurface",
        "surfaceVariant",
        "onSurfaceVariant",
        "surfaceTint",
        "surfaceBright",
        "surfaceDim",
        "surfaceContainer",
        "surfaceContainerLowest",
        "surfaceContainerLow",
        "surfaceContainerHigh",
        "surfaceContainerHighest",
        "inverseSurface",
        "inverseOnSurface",
        "error",
        "onError",
        "errorContainer",
        "onErrorContainer",
        "outline",
        "outlineVariant",
        "scrim",
        "primaryFixed",
        "primaryFixedDim",
        "onPrimaryFixed",
        "onPrimaryFixedVariant",
        "secondaryFixed",
        "secondaryFixedDim",
        "onSecondaryFixed",
        "onSecondaryFixedVariant",
        "tertiaryFixed",
        "tertiaryFixedDim",
        "onTertiaryFixed",
        "onTertiaryFixedVariant",
      )
      .associateWith { listOf(THEME, "colorScheme", it) } +
      mapOf(
        // Not theme roles at all — `Color`'s own companion constants, which a document reaches for
        // exactly as often and which no `colorScheme` lookup would find.
        "transparent" to listOf(COLOR, "Transparent"),
        "unspecified" to listOf(COLOR, "Unspecified"),
      )

  private val TYPOGRAPHY_TOKENS: Map<String, List<String>> =
    listOf(
        "displayLarge",
        "displayMedium",
        "displaySmall",
        "headlineLarge",
        "headlineMedium",
        "headlineSmall",
        "titleLarge",
        "titleMedium",
        "titleSmall",
        "bodyLarge",
        "bodyMedium",
        "bodySmall",
        "labelLarge",
        "labelMedium",
        "labelSmall",
      )
      .associateWith { listOf(THEME, "typography", it) }

  private val SHAPE_TOKENS: Map<String, List<String>> =
    listOf("extraSmall", "small", "medium", "large", "extraLarge").associateWith {
      listOf(THEME, "shapes", it)
    }

  /**
   * The two shapes a `clip` modifier can name that are **not** theme roles.
   *
   * Theme roles come from [SHAPE_TOKENS], which `clip` consults first — `medium` and `large` are
   * what real documents clip to, and they are `MaterialTheme.shapes` accessors.
   */
  private val SHAPE_CONSTANTS: Map<String, String> =
    mapOf(
      "circle" to "androidx.compose.foundation.shape.CircleShape",
      "rectangle" to "androidx.compose.ui.graphics.RectangleShape",
    )

  private const val FONT_WEIGHT = "androidx.compose.ui.text.font.FontWeight"
  private const val FONT_STYLE = "androidx.compose.ui.text.font.FontStyle"
  private const val TEXT_ALIGN = "androidx.compose.ui.text.style.TextAlign"
  private const val TEXT_OVERFLOW = "androidx.compose.ui.text.style.TextOverflow"
  private const val TEXT_DECORATION = "androidx.compose.ui.text.style.TextDecoration"
  private const val ALIGNMENT = "androidx.compose.ui.Alignment"

  /**
   * A `$`, not a `.`, and this is the whole reason the constant exists rather than being spelled
   * inline. `TargetParameter.typeFqn` is read off `@kotlin.Metadata`, so a nested classifier
   * arrives JVM-spelled — `androidx.compose.ui.Alignment$Vertical` — and the generator compares the
   * claimed type to it as a **string**. `Alignment.Vertical` is the same type and a different
   * string, so it refuses with a mismatch that reads like a real type error.
   */
  private const val ALIGNMENT_VERTICAL = "androidx.compose.ui.Alignment\$Vertical"

  private const val ALIGNMENT_HORIZONTAL = "androidx.compose.ui.Alignment\$Horizontal"
  private const val ARRANGEMENT_VERTICAL =
    "androidx.compose.foundation.layout.Arrangement\$Vertical"
  private const val ARRANGEMENT_HORIZONTAL =
    "androidx.compose.foundation.layout.Arrangement\$Horizontal"

  private enum class TargetKind {
    /** The parameter is the same value under another name. */
    RENAME,
    /** A number of dp — `tonalElevation = 3.dp`. */
    DP,
    /** A corner radius in dp, which Compose takes as a `Shape`. */
    ROUNDED_CORNER_SHAPE,
    /** A gap between children, which Compose takes as an `Arrangement`. */
    SPACED_BY_VERTICAL,
    SPACED_BY_HORIZONTAL,
    /** A theme shape role named as text — `large`. */
    SHAPE_TOKEN,
  }

  private class ParameterTarget(val parameter: String, val kind: TargetKind)

  /**
   * The Compose parameter each catalog property sets, where the two are not the same name.
   *
   * The third authored table, alongside [SLOT_PARAMETERS] and [ENUM_MEMBERS], and the same argument
   * carries it: the record attests a signature and the catalog describes a design, and the fact
   * that `containerColor` and `color` are one parameter is knowledge neither of them holds. The
   * generator keys arguments by **source parameter name** and refuses a name the component does not
   * declare — correctly, since dropping it would generate a screen that compiles and is not the one
   * that was designed — so without this every builder-authored `m3/surface` refused twice over.
   *
   * `CapabilityComposeCodeExporter` already knew all of this. It is not on the export path, which
   * is why the knowledge had to be restated somewhere the export can reach.
   *
   * Deliberately not exhaustive. `weight` is absent because `Modifier.weight` is declared on
   * `RowScope` and `ColumnScope`, so it is legal only in the slot a node was placed in — the same
   * scope question that keeps `matchParentSize` refused, and it wants the same answer rather than a
   * guess. `m3/card`'s `containerColor` and `elevationDp` are absent because `CardDefaults` is not
   * in the record's `Card` signature at all: that is a record gap, not a naming one.
   */
  private val PROPERTY_PARAMETERS: Map<String, Map<String, ParameterTarget>> =
    mapOf(
      "m3/surface" to
        mapOf(
          "containerColor" to ParameterTarget("color", TargetKind.RENAME),
          "shapeDp" to ParameterTarget("shape", TargetKind.ROUNDED_CORNER_SHAPE),
          "tonalElevationDp" to ParameterTarget("tonalElevation", TargetKind.DP),
        ),
      "m3/card" to mapOf("shape" to ParameterTarget("shape", TargetKind.SHAPE_TOKEN)),
      "layout/row" to
        mapOf(
          "horizontalSpacingDp" to
            ParameterTarget("horizontalArrangement", TargetKind.SPACED_BY_HORIZONTAL)
        ),
      "layout/column" to
        mapOf(
          "verticalSpacingDp" to
            ParameterTarget("verticalArrangement", TargetKind.SPACED_BY_VERTICAL)
        ),
    )

  /** One catalog property's values, as the Kotlin members they name. */
  private class EnumMembers(val typeFqn: String, val members: Map<String, List<String>>)

  private fun members(typeFqn: String, root: String, vararg pairs: Pair<String, String>) =
    EnumMembers(typeFqn, pairs.toMap().mapValues { (_, member) -> listOf(root, member) })

  /**
   * Which Kotlin member each catalog enum value names, per component and property.
   *
   * Keyed like [SLOT_PARAMETERS] and for the same reason. The catalog names values for designers
   * (`semiBold`, `centerVertically`) and Kotlin names them for the compiler (`FontWeight.SemiBold`,
   * `Alignment.CenterVertically`); which one means which is knowledge that lives in neither the
   * record — it attests the signature, not the vocabulary — nor the document.
   *
   * Per **component** and not per property name, because one spelling is two vocabularies:
   * `m3/text`.`style` is a `MaterialTheme.typography` role, and `m3/button`.`style` picks between
   * three Compose components. Only the first is a value.
   *
   * `m3/text`.`style` reuses [TYPOGRAPHY_TOKENS] rather than restating those fifteen roles. A
   * document may spell the same intent as either a `typographyToken` or an `enum` — see #339 — and
   * two tables would be two chances to disagree about what `bodyLarge` means.
   */
  private val ENUM_MEMBERS: Map<String, Map<String, EnumMembers>> =
    mapOf(
      "m3/text" to
        mapOf(
          "style" to EnumMembers(TEXT_STYLE, TYPOGRAPHY_TOKENS),
          "fontWeight" to
            members(
              FONT_WEIGHT,
              FONT_WEIGHT,
              "normal" to "Normal",
              "medium" to "Medium",
              "semiBold" to "SemiBold",
              "bold" to "Bold",
            ),
          "fontStyle" to
            members(FONT_STYLE, FONT_STYLE, "normal" to "Normal", "italic" to "Italic"),
          "textAlign" to
            members(
              TEXT_ALIGN,
              TEXT_ALIGN,
              "start" to "Start",
              "center" to "Center",
              "end" to "End",
              "justify" to "Justify",
            ),
          "overflow" to
            members(
              TEXT_OVERFLOW,
              TEXT_OVERFLOW,
              "clip" to "Clip",
              "ellipsis" to "Ellipsis",
              "visible" to "Visible",
            ),
          "textDecoration" to
            members(
              TEXT_DECORATION,
              TEXT_DECORATION,
              "none" to "None",
              "underline" to "Underline",
              "lineThrough" to "LineThrough",
            ),
        ),
      "layout/row" to
        mapOf(
          "verticalAlignment" to
            members(
              ALIGNMENT_VERTICAL,
              ALIGNMENT,
              "top" to "Top",
              "center" to "CenterVertically",
              "bottom" to "Bottom",
            )
        ),
      "layout/column" to
        mapOf(
          "horizontalAlignment" to
            members(
              ALIGNMENT_HORIZONTAL,
              ALIGNMENT,
              "start" to "Start",
              "center" to "CenterHorizontally",
              "end" to "End",
            )
        ),
      "layout/box" to
        mapOf(
          "contentAlignment" to
            members(
              ALIGNMENT,
              ALIGNMENT,
              "topStart" to "TopStart",
              "topCenter" to "TopCenter",
              "topEnd" to "TopEnd",
              "centerStart" to "CenterStart",
              "center" to "Center",
              "centerEnd" to "CenterEnd",
              "bottomStart" to "BottomStart",
              "bottomCenter" to "BottomCenter",
              "bottomEnd" to "BottomEnd",
            )
        ),
    )

  /**
   * Properties whose values pick a **component**, not an argument.
   *
   * `m3/card`.`variant` is `filled`, `elevated` or `outlined`, and Material 3 spells those as
   * `Card`, `ElevatedCard` and `OutlinedCard` — three symbols, three records, one catalog id. No
   * table of members can express that, so they stay refused; naming them separately keeps the
   * refusal from reading like a missing table entry, which is a different piece of work.
   */
  private val VARIANT_PROPERTIES: Set<Pair<String, String>> =
    setOf(
      "m3/card" to "variant",
      "m3/button" to "style",
      "m3/icon-button" to "variant",
      "layout/supporting-pane-scaffold" to "layoutMode",
      "layout/horizontal-carousel" to "kind",
    )
}
