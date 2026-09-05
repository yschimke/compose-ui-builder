package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.protocol.AdaptiveGridValueV1
import ee.schimke.composeai.uibuilder.protocol.AlignHorizontalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignVerticalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignmentV1
import ee.schimke.composeai.uibuilder.protocol.AlphaModifierV1
import ee.schimke.composeai.uibuilder.protocol.AspectRatioModifierV1
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.BorderModifierV1
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
import ee.schimke.composeai.uibuilder.protocol.FillMaxHeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightInModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.InsetsValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.ListValueV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.ObjectValueV1
import ee.schimke.composeai.uibuilder.protocol.OffsetModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingValueV1
import ee.schimke.composeai.uibuilder.protocol.ResourceValueV1
import ee.schimke.composeai.uibuilder.protocol.RotateModifierV1
import ee.schimke.composeai.uibuilder.protocol.ScaleModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShadowModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StateEqualsValueV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.TestTagModifierV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.VerticalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.VerticalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.WeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthInModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.WrapContentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.ZIndexModifierV1
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
 * ## Enum values are the seventh refusal, for the values nothing names
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
 * So the derivation stayed refused and the mapping is **authored**: [ENUM_MEMBERS] for the values
 * that are members of a type, [ICON_MEMBERS] for the icon keys, which are extension properties on
 * `Icons.Filled` and therefore a [ScreenValue.Chain] rather than a path. A value neither table
 * names is still refused by name — `expandedTwoPane` and `uncontained` select a *component* rather
 * than an argument, and no table of members can express that.
 */
object ScreenDocumentProjection {

  /**
   * The Kotlin parameter a catalog slot fills. Public only so `M3CatalogSlotScopeTest` can walk the
   * same alias the projection does when it checks [SLOT_SCOPES] against the shipped record.
   */
  fun parameterForSlotName(componentId: String, slot: String): String =
    parameterForSlot(componentId, slot)

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

    /**
     * @param scope the receiver of the slot this node sits in, or null at the root.
     *
     * Threaded down rather than looked up, because a node has no parent pointer and the answer is
     * about placement rather than about the node. It is what `weight` and `matchParentSize` need:
     * both are declared on a slot's receiver, so whether either compiles is decided here and
     * nowhere else.
     */
    fun node(id: String, scope: String? = null): ScreenNode? {
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
        val variant = variantOf(node)
        return ScreenNode(
          // A variant names a **component**, so it is spent here rather than emitted as an
          // argument: `m3/card` with `variant = elevated` is `ElevatedCard`, which is a different
          // callable with its own record. The catalog says as much itself — its `code.imports`
          // lists all three — and this is the projection acting on it.
          componentId = variant?.canonicalId ?: node.componentId,
          arguments = arguments(node, scope, variant),
          slots =
            node.slots.entries.associate { (slot, children) ->
              val childScope = slotScope(node.componentId, variant, slot)
              parameterForSlot(node.componentId, slot) to
                children.mapNotNull { child -> node(child, childScope) }
            },
        )
      } finally {
        visiting.remove(id)
      }
    }

    /**
     * Whether a progress indicator's determinacy property was handled here, refusal included.
     *
     * Both Compose indicators have two overloads: an indeterminate one whose parameters all
     * default, and a determinate one taking `progress: () -> Float`. Only the first is recorded,
     * because **no [ScreenValue] is a lambda** — the vocabulary has references, calls and chains,
     * and a value-returning `{ 0.4f }` is none of them. The overload the record names is chosen by
     * the argument list, so an omitted `progress` really does resolve to the indeterminate one.
     *
     * So `indeterminate = true` describes the component already being emitted and is spent, while
     * `progress`, or an explicit `indeterminate = false`, asks for the overload that cannot be
     * written and says so. Neither is a parameter of anything, so left alone both would refuse as
     * "`LinearProgressIndicator` has no parameter `progress`" — true, and pointing at the wrong
     * thing.
     */
    private fun determinacy(property: String, value: UiValueV1, node: DesignNodeV1): Boolean {
      val determinate =
        "a determinate indicator takes `progress: () -> Float`, and no value in this vocabulary " +
          "is a lambda; the indeterminate form is what exports"
      when (property) {
        PROGRESS -> refuse("node `${node.id}` sets `progress`, but $determinate")
        INDETERMINATE ->
          if ((value as? BooleanValueV1)?.value == false) {
            refuse("node `${node.id}` is not indeterminate, and $determinate")
          }
        else -> return false
      }
      return true
    }

    /**
     * The receiver a slot's children are composed under.
     *
     * The variant answers when there is one, because it is the component actually being emitted;
     * [SLOT_SCOPES] answers otherwise. Reading the table in both cases would give a `fab`'s content
     * the `RowScope` that `m3/button`'s other three values have.
     */
    private fun slotScope(componentId: String, variant: ComponentVariant?, slot: String): String? =
      if (variant != null) variant.slotScopes[slot] else SLOT_SCOPES[componentId]?.get(slot)

    /**
     * The component a node's variant property selects, or null when it selects nothing.
     *
     * Null covers two different cases and both are right. A component with no variant property has
     * no entry, and one whose variant is simply unset falls back to the record the catalog id
     * already resolves to — `m3/card` is `Card`, which is what `filled` means anyway. A variant the
     * table does not know refuses, because guessing which of three components a designer meant is
     * the failure this whole file exists to avoid.
     */
    private fun variantOf(node: DesignNodeV1): ComponentVariant? {
      val property = VARIANT_SELECTORS[node.componentId] ?: return null
      val choices = COMPONENT_VARIANTS[node.componentId] ?: return null
      val authored =
        when (val value = node.properties[property]) {
          // Either wrapper, for the same reason `value` reads both: `enum` is canonical and
          // documents committed before that rule holds `string` (#339).
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          null -> return null
          else -> {
            refuse(
              "node `${node.id}`.`$property` selects a component, so it has to be one of " +
                choices.keys.sorted().joinToString(", ")
            )
            return null
          }
        }
      return choices[authored]
        ?: run {
          refuse(
            "node `${node.id}`.`$property` is `$authored`, which is not one of " +
              choices.keys.sorted().joinToString(", ")
          )
          null
        }
    }

    private fun arguments(
      node: DesignNodeV1,
      scope: String?,
      variant: ComponentVariant?,
    ): Map<String, ScreenValue> {
      unexpressible(node)
      val arguments = mutableMapOf<String, ScreenValue>()
      // Properties that are not arguments at all — `m3/icon`'s `sizeDp` is `Modifier.size(24.dp)`,
      // which `Icon` has no parameter for. Collected here rather than emitted where they are read,
      // so a node with an authored modifier list and a modifier-shaped property produces one chain
      // in a fixed order instead of two arguments the generator would reject the second of.
      val fromProperties = mutableListOf<ChainLink>()
      for ((property, value) in node.properties) {
        // Spent on the call site above; emitting it as well would hand the component a parameter
        // it does not declare.
        if (variant != null && property == VARIANT_SELECTORS[node.componentId]) continue
        if (property == WEIGHT) {
          weightLink(value, node, scope)?.let { fromProperties += it }
          continue
        }
        if (node.componentId == PROGRESS_INDICATOR && determinacy(property, value, node)) continue
        val link = MODIFIER_PROPERTIES[node.componentId]?.get(property)
        if (link != null) {
          modifierLink(link, value, node, property)?.let { fromProperties += it }
          continue
        }
        val target = PROPERTY_PARAMETERS[node.componentId]?.get(property)
        if (target == null) {
          arguments[property] = value(value, node, property) ?: continue
          continue
        }
        arguments[target.parameter] = retarget(target, value, node, property, variant) ?: continue
      }
      if (node.modifiers.isNotEmpty() || fromProperties.isNotEmpty() || tagNodes) {
        modifiers(node, fromProperties, scope)?.let { arguments["modifier"] = it }
      }
      return arguments
    }

    /**
     * The `Modifier.weight(…)` a layout weight becomes, or null having said why there isn't one.
     *
     * Two things had to change upstream before this could exist, and both were about spelling
     * rather than about the value. `Modifier.weight` is declared on `RowScope` and `ColumnScope`,
     * so it is legal only in the slot the node was placed in — [ChainLink.receiverScopeFqn] states
     * that and `ScreenGenerator` checks it against the slot it emits into. And it takes a `Float`,
     * which a nested `Fractional` could not be: nested, a fraction renders as a `Double` and
     * `weight(1.0)` does not compile, which is what [ScreenValue.Fractional32] exists for.
     *
     * Outside a row or a column it stays refused, and the refusal now says where the node actually
     * is — a weight on a `Box` child is a design mistake worth reading rather than a gap in a
     * table.
     */
    private fun weightLink(value: UiValueV1, node: DesignNodeV1, scope: String?): ChainLink? {
      val where = "node `${node.id}`.`weight`"
      val number =
        when (value) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> {
            refuse("$where becomes `Modifier.weight`, which needs a number")
            return null
          }
        }
      return weightLink(number, fill = null, where = where, scope = scope)
    }

    /**
     * The same link, for a `weight` authored as a **modifier** rather than as a property.
     *
     * Both spellings reach the builder — the catalog declares `weight` in `modifierCapabilities`
     * and m3-catalog components also carry it as a property — and they mean one thing, so they
     * produce one link rather than two nearly-identical ones that could disagree about the
     * narrowing rule or about which scopes are legal. `fill` exists only on the modifier form;
     * omitted, Compose's own default of `true` stands, which is what the property form has always
     * meant.
     */
    private fun weightLink(
      number: Double?,
      fill: Boolean?,
      where: String,
      scope: String?,
    ): ChainLink? {
      if (scope != ROW_SCOPE && scope != COLUMN_SCOPE) {
        refuse(
          "$where is a layout weight, which `Modifier.weight` supplies from a row's or column's " +
            "scope; this node sits " +
            (scope?.let { "in a `$it` slot" } ?: "at the root, which has no receiver")
        )
        return null
      }
      if (number == null) {
        refuse("$where becomes `Modifier.weight`, which needs a number")
        return null
      }
      val weight = number.toFloat()
      // The same narrowing rule `Dp` gets: a weight that does not survive `Float` would be emitted
      // as `Infinity` or collapse to zero, which is a number the design never contained.
      if (!weight.isFinite() || (weight == 0f && number != 0.0)) {
        refuse("$where is $number, which does not survive `Float`")
        return null
      }
      return ChainLink(
        "$scope.weight",
        positional = listOf(ScreenValue.Fractional32(weight)),
        named = fill?.let { mapOf("fill" to ScreenValue.Bool(it)) } ?: emptyMap(),
        receiverScopeFqn = scope,
      )
    }

    /**
     * One chain link for a property whose Compose spelling is a modifier, or null having said why
     * not.
     */
    private fun modifierLink(
      callableFqn: String,
      value: UiValueV1,
      node: DesignNodeV1,
      property: String,
    ): ChainLink? {
      val where = "node `${node.id}`.`$property`"
      val number =
        when (value) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> {
            refuse("$where becomes a modifier taking a `Dp`, which needs a number")
            return null
          }
        }
      val dp = dp(number)
      if (dp == null) {
        refuse("$where is $number, which does not survive `Dp`")
        return null
      }
      return ChainLink(callableFqn, positional = listOf(dp))
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
    private fun modifiers(
      node: DesignNodeV1,
      fromProperties: List<ChainLink>,
      scope: String?,
    ): ScreenValue? {
      // Every modifier is visited even after one fails. A non-local `return` out of the map stopped
      // at the first, which quietly broke this projection's one promise: `Outcome.Refused` carries
      // *every* unexpressible thing so a document can be fixed in one pass, not one per export.
      val links = node.modifiers.map { link(it, node.id, scope) }
      if (links.any { it == null }) return null
      // Last in the chain, so a tagged preview and its untagged export differ by exactly one
      // appended link and nothing about the modifiers a designer wrote moves.
      val tag =
        if (!tagNodes) emptyList()
        else listOf(ChainLink(TEST_TAG, positional = listOf(ScreenValue.Text(node.id))))
      return ScreenValue.Chain(
        receiver = ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER),
        // The authored chain first, then the links a property implied, then the tag. A designer's
        // own order is the one thing here that carries intent, so nothing is interleaved with it.
        links = links.filterNotNull() + fromProperties + tag,
        typeFqn = MODIFIER,
      )
    }

    /**
     * The chain link one authored modifier becomes, or null having said why there isn't one.
     *
     * Every subtype of `DesignModifierV1` is answered here — twenty-eight of them — because the
     * catalog admits a modifier onto a component by *type*, so anything this `when` does not name
     * refuses a document the builder was happy to author. That was the state this replaces: six
     * kinds were expressible and the other twenty-two came back as "which this projection has no
     * expression for", including `background`, `border`, `width` and `align`, which the m3-catalog
     * palette offers on almost every component.
     *
     * Two are refused **deliberately** and stay refused: `verticalScroll` and `horizontalScroll`
     * take a `ScrollState` from `rememberScrollState()`, and no [ScreenValue] is a remembered
     * value. The `else` branch below is therefore not a list of things left undone; it is what a
     * *newer* `ui-builder-protocol` than the one this compiled against would fall into, and it
     * keeps that arriving as a named refusal rather than as a silently dropped modifier.
     */
    private fun link(modifier: DesignModifierV1, nodeId: String, scope: String?): ChainLink? {
      return when (modifier) {
        FillMaxWidthModifierV1 -> ChainLink("$LAYOUT.fillMaxWidth")
        FillMaxHeightModifierV1 -> ChainLink("$LAYOUT.fillMaxHeight")
        FillMaxSizeModifierV1 -> ChainLink("$LAYOUT.fillMaxSize")
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
            ChainLink("$LAYOUT.padding", named = axes)
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
              ChainLink("$LAYOUT.size", named = mapOf("width" to width, "height" to height))
            width != null -> ChainLink("$LAYOUT.width", positional = listOf(width))
            height != null -> ChainLink("$LAYOUT.height", positional = listOf(height))
            else -> {
              reasons += "node `$nodeId` sizes to neither a width nor a height"
              null
            }
          }
        }
        is WidthModifierV1 -> dpLink("$LAYOUT.width", modifier.widthDp, nodeId, "width")
        is HeightModifierV1 -> dpLink("$LAYOUT.height", modifier.heightDp, nodeId, "height")
        is WidthInModifierV1 ->
          boundsLink("$LAYOUT.widthIn", modifier.minDp, modifier.maxDp, nodeId, "widthIn")
        is HeightInModifierV1 ->
          boundsLink("$LAYOUT.heightIn", modifier.minDp, modifier.maxDp, nodeId, "heightIn")
        is OffsetModifierV1 -> {
          // Both axes default, so `offset()` compiles — as a no-op, which is not what a document
          // holding two unusable numbers meant. Refused for the reason `padding` is.
          val axes = buildMap {
            dp(modifier.xDp)?.let { put("x", it) }
            dp(modifier.yDp)?.let { put("y", it) }
          }
          if (axes.isEmpty()) {
            reasons += "node `$nodeId` offsets by neither an x nor a y that is a number"
            null
          } else {
            ChainLink("$LAYOUT.offset", named = axes)
          }
        }
        is AspectRatioModifierV1 ->
          floatLink("$LAYOUT.aspectRatio", modifier.ratio, nodeId, "aspectRatio")
        is WrapContentSizeModifierV1 ->
          // Positional, because the parameter is named `align` while every other alignment in this
          // file is called `alignment` — a name worth not restating from memory. An unset
          // alignment writes no argument at all rather than an invented `Center`, which is what
          // Compose's own default already is.
          ChainLink(
            "$LAYOUT.wrapContentSize",
            positional = modifier.alignment?.let { listOf(alignment(it)) } ?: emptyList(),
          )
        is AlphaModifierV1 -> floatLink("$DRAW.alpha", modifier.alpha, nodeId, "alpha")
        is RotateModifierV1 -> floatLink("$DRAW.rotate", modifier.degrees, nodeId, "rotate")
        is ScaleModifierV1 -> {
          // Both axes or neither. `scale(scaleX, scaleY)` is the two-axis overload and there is a
          // one-argument `scale(scale: Float)` that means both at once — naming one axis of the
          // pair would silently scale the other by its default of 1, which the document did not
          // say.
          val x = float(modifier.scaleX, nodeId, "scaleX")
          val y = float(modifier.scaleY, nodeId, "scaleY")
          if (x == null || y == null) null
          else ChainLink("$DRAW.scale", named = mapOf("scaleX" to x, "scaleY" to y))
        }
        is ZIndexModifierV1 ->
          floatLink("androidx.compose.ui.zIndex", modifier.zIndex, nodeId, "zIndex")
        is TestTagModifierV1 ->
          ChainLink(TEST_TAG, positional = listOf(ScreenValue.Text(modifier.tag)))
        is ClipModifierV1 ->
          // A theme shape first, then the two constants. `medium` and `large` are what real
          // documents clip to and they are `MaterialTheme.shapes` roles, not constants — refusing
          // them lost a clip the previous exporter rendered correctly.
          shapeOf(modifier.shape)?.let { ChainLink("$DRAW.clip", positional = listOf(it)) }
            ?: refuseShape(nodeId, "clips to", modifier.shape)
        is BackgroundModifierV1 -> {
          val color = colour(modifier.color, "node `$nodeId`'s `background`") ?: return null
          ChainLink(
            "androidx.compose.foundation.background",
            named =
              buildMap {
                put("color", color)
                modifier.shape?.let {
                  put("shape", shapeOf(it) ?: return refuseShape(nodeId, "fills with", it))
                }
              },
          )
        }
        is BorderModifierV1 -> {
          val width =
            dp(modifier.widthDp)
              ?: run {
                reasons += "node `$nodeId` borders itself with a width that is not a number"
                return null
              }
          val color = colour(modifier.color, "node `$nodeId`'s `border`") ?: return null
          ChainLink(
            "androidx.compose.foundation.border",
            named =
              buildMap {
                put("width", width)
                put("color", color)
                modifier.shape?.let {
                  put("shape", shapeOf(it) ?: return refuseShape(nodeId, "borders with", it))
                }
              },
          )
        }
        is ShadowModifierV1 -> {
          val elevation =
            dp(modifier.elevationDp)
              ?: run {
                reasons += "node `$nodeId` casts a shadow at an elevation that is not a number"
                return null
              }
          ChainLink(
            "$DRAW.shadow",
            named =
              buildMap {
                put("elevation", elevation)
                modifier.shape?.let {
                  put("shape", shapeOf(it) ?: return refuseShape(nodeId, "shadows with", it))
                }
                // `clip` defaults to `elevation > 0.dp`, so it is written only when the document
                // said something — an explicit `false` on a raised node is the case that matters.
                modifier.clip?.let { put("clip", ScreenValue.Bool(it)) }
              },
          )
        }
        is WeightModifierV1 ->
          weightLink(
            (modifier.weight as? JsonPrimitive)?.doubleOrNull,
            modifier.fill,
            "node `$nodeId`'s `weight` modifier",
            scope,
          )
        // The three `align`s and `matchParentSize` are the same fact four times: each is declared
        // on a slot's receiver, so which one compiles is decided by where the node sits and by
        // nothing about the node itself. A `Column` child aligns horizontally, a `Row` child
        // vertically, and only a `Box` child names a two-axis `Alignment`.
        is AlignModifierV1 ->
          alignLink(nodeId, scope, BOX_SCOPE, "box", alignment(modifier.alignment))
        is AlignHorizontalModifierV1 ->
          alignLink(nodeId, scope, COLUMN_SCOPE, "column", horizontal(modifier.alignment))
        is AlignVerticalModifierV1 ->
          alignLink(nodeId, scope, ROW_SCOPE, "row", vertical(modifier.alignment))
        // `matchParentSize` is declared on `BoxScope`, so it compiles inside a `Box` slot and
        // nowhere else. This projection now knows which slot a node was placed in, so the answer
        // is a lookup rather than the refusal it used to be — and outside a `Box` it is still a
        // refusal, because emitting it there is an unresolved reference.
        MatchParentSizeModifierV1 ->
          if (scope == BOX_SCOPE)
            ChainLink("$BOX_SCOPE.matchParentSize", receiverScopeFqn = BOX_SCOPE)
          else
            null.also {
              reasons +=
                "node `$nodeId` uses `matchParentSize`, which is declared on `BoxScope` and is in " +
                  "scope only inside a `layout/box` slot; this node sits " +
                  (scope?.let { "in a `$it` slot" } ?: "at the root, which has no receiver")
            }
        VerticalScrollModifierV1 -> scrolls(nodeId, "verticalScroll")
        HorizontalScrollModifierV1 -> scrolls(nodeId, "horizontalScroll")
        else ->
          null.also {
            reasons +=
              "node `$nodeId` uses the modifier ${modifier::class.simpleName}, which this " +
                "projection has no expression for"
          }
      }
    }

    /** A modifier link taking one `Dp`, or null having said why the number does not survive one. */
    private fun dpLink(
      callableFqn: String,
      value: JsonElement?,
      nodeId: String,
      name: String,
    ): ChainLink? {
      val dp = dp(value)
      if (dp == null) {
        reasons += "node `$nodeId` sets `$name` to something that is not a number surviving `Dp`"
        return null
      }
      return ChainLink(callableFqn, positional = listOf(dp))
    }

    /** `widthIn` / `heightIn`, whose two bounds are each optional and not both absent. */
    private fun boundsLink(
      callableFqn: String,
      minDp: JsonElement?,
      maxDp: JsonElement?,
      nodeId: String,
      name: String,
    ): ChainLink? {
      val bounds = buildMap {
        dp(minDp)?.let { put("min", it) }
        dp(maxDp)?.let { put("max", it) }
      }
      if (bounds.isEmpty()) {
        reasons += "node `$nodeId` constrains `$name` with neither a min nor a max that is a number"
        return null
      }
      return ChainLink(callableFqn, named = bounds)
    }

    private fun floatLink(
      callableFqn: String,
      value: JsonElement?,
      nodeId: String,
      name: String,
    ): ChainLink? =
      float(value, nodeId, name)?.let { ChainLink(callableFqn, positional = listOf(it)) }

    /**
     * A `Float` for a JSON number, or null having said why there isn't one.
     *
     * The narrowing rule `Dp` gets, for the same reason and with the same answer. `alpha`,
     * `rotate`, `scale`, `zIndex` and `aspectRatio` all take a `Float`, and a `Double` that does
     * not survive the narrowing would be emitted as `Infinity` or collapse to zero — a number the
     * design never contained, returned as a success.
     */
    private fun float(value: JsonElement?, nodeId: String, name: String): ScreenValue? {
      val number = (value as? JsonPrimitive)?.doubleOrNull
      if (number == null) {
        reasons += "node `$nodeId` sets `$name` to something that is not a number"
        return null
      }
      val narrowed = number.toFloat()
      if (!narrowed.isFinite() || (narrowed == 0f && number != 0.0)) {
        reasons += "node `$nodeId` sets `$name` to $number, which does not survive `Float`"
        return null
      }
      return ScreenValue.Fractional32(narrowed)
    }

    /** The colour a modifier paints with — a literal or a theme role, and nothing else. */
    private fun colour(value: UiValueV1, where: String): ScreenValue? =
      when (value) {
        is ColorValueV1 -> color(value.value, where)
        is ColorTokenValueV1 -> token(value.value, COLOR_TOKENS, COLOR, "colour", where)
        else ->
          refuse(
            "$where is a colour, which is written as a `#RRGGBB` literal or as a theme role and " +
              "not as ${value::class.simpleName}"
          )
      }

    /** The `Shape` a shape name resolves to — theme role first, then the two constants. */
    private fun shapeOf(name: String): ScreenValue? =
      SHAPE_TOKENS[name]?.let { path ->
        ScreenValue.Reference(path.first(), path.drop(1), typeFqn = SHAPE)
      } ?: SHAPE_CONSTANTS[name]?.let { ScreenValue.Reference(it, typeFqn = SHAPE) }

    /** Records a shape nothing resolves, naming both sets a document may choose from. */
    private fun refuseShape(nodeId: String, verb: String, name: String): ChainLink? {
      reasons +=
        "node `$nodeId` $verb shape `$name`, which is neither a theme shape " +
          "(${SHAPE_TOKENS.keys.sorted().joinToString(", ")}) nor one of " +
          SHAPE_CONSTANTS.keys.sorted().joinToString(", ")
      return null
    }

    private fun scrolls(nodeId: String, name: String): ChainLink? {
      reasons +=
        "node `$nodeId` uses `$name`, which takes a `ScrollState` from " +
          "`rememberScrollState()` — a `remember { … }` preamble this projection does not emit"
      return null
    }

    /**
     * A `<Scope>.align(…)`, or null having said where the node actually is.
     *
     * `align` is three different members with three different parameter types, one per scope, and a
     * document names which by the modifier it authored. Emitting the wrong one is not a wrong
     * picture but an unresolved reference, so the scope is checked here as well as by the generator
     * against the record — see [ChainLink.receiverScopeFqn].
     */
    private fun alignLink(
      nodeId: String,
      scope: String?,
      required: String,
      container: String,
      alignment: ScreenValue,
    ): ChainLink? {
      if (scope != required) {
        reasons +=
          "node `$nodeId` aligns itself, which `Modifier.align` supplies from a $container's " +
            "scope; this node sits " +
            (scope?.let { "in a `$it` slot" } ?: "at the root, which has no receiver")
        return null
      }
      return ChainLink(
        "$required.align",
        positional = listOf(alignment),
        receiverScopeFqn = required,
      )
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
      variant: ComponentVariant?,
    ): ScreenValue? {
      val where = "node `${node.id}`.`$property`"
      if (target.kind == TargetKind.RENAME) return value(value, node, property)
      if (target.kind == TargetKind.CARD_COLORS) {
        // `CardDefaults.cardColors` is `@Composable`, which is why this is expressible at all: the
        // generated screen body is one, so the call site is legal exactly where the argument goes.
        //
        // The factory follows the variant. All three return a `CardColors`, so `cardColors` would
        // compile on an `ElevatedCard` — and would quietly give it the *filled* card's content and
        // disabled colours for every role the designer did not set. A wrong colour that compiles is
        // the failure mode this projection is built to refuse, so the defaults match the component.
        val color = value(value, node, property) ?: return null
        return ScreenValue.Construct(
          callableFqn = "$CARD_DEFAULTS.${variant?.defaults ?: "card"}Colors",
          named = mapOf("containerColor" to color),
          typeFqn = "androidx.compose.material3.CardColors",
        )
      }
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
        TargetKind.CARD_ELEVATION ->
          ScreenValue.Construct(
            callableFqn = "$CARD_DEFAULTS.${variant?.defaults ?: "card"}Elevation",
            named = mapOf("defaultElevation" to dp),
            typeFqn = "androidx.compose.material3.CardElevation",
          )
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
        TargetKind.CARD_COLORS,
        TargetKind.SHAPE_TOKEN -> error("handled above")
      }
    }

    /** The Kotlin value for one property, or null having said why there isn't one. */
    private fun value(value: UiValueV1, node: DesignNodeV1, property: String): ScreenValue? {
      val where = "node `${node.id}`.`$property`"
      return when (value) {
        // A `string` wrapper on a property whose values are an enumeration is read through the same
        // table the `enum` wrapper is. The reducer now rejects that spelling on a write (#339), so
        // nothing new arrives this way — but documents committed before it did already render, and
        // refusing them here with "`Text`.`style` is a TextStyle, which Text is not" names the
        // wrong problem in a message that cannot be acted on from the builder.
        is StringValueV1 ->
          if (enumerated(node.componentId, property))
            enum(value.value, node.componentId, property, where)
          else ScreenValue.Text(value.value)
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
      if (componentId to property in ICON_PROPERTIES) {
        return icon(entry)
          ?: refuse(
            "$where is the icon key `$entry`, which is not one of " +
              ICON_MEMBERS.keys.sorted().joinToString(", ")
          )
      }
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

    /**
     * The `ImageVector` an icon key names, or null when [ICON_MEMBERS] has no entry for it.
     *
     * A [ScreenValue.Chain] rather than the [ScreenValue.Reference] every other enum value gets,
     * and that is forced rather than chosen: an icon is an **extension property** on `Icons.Filled`
     * declared in `androidx.compose.material.icons.filled`, so it resolves through an import of the
     * property and not through a longer qualified path.
     * `androidx.compose.material.icons.Icons.Filled.AccountCircle` written out is not a spelling of
     * anything, which is exactly the case [ScreenValue.Chain]'s KDoc exists for.
     */
    /** Whether this catalog property's values are an enumeration one of the tables names. */
    private fun enumerated(componentId: String, property: String): Boolean =
      ENUM_MEMBERS[componentId]?.containsKey(property) == true ||
        componentId to property in ICON_PROPERTIES

    private fun icon(entry: String): ScreenValue? {
      val path = ICON_MEMBERS[entry]?.split(".") ?: return null
      val pack = path.dropLast(1)
      return ScreenValue.Chain(
        receiver =
          ScreenValue.Reference(
            rootFqn = ICONS,
            members = pack,
            // JVM-spelled, for the reason [ALIGNMENT_VERTICAL] carries: `Icons.Filled` is a nested
            // object, and this claim is compared as a string.
            typeFqn = pack.joinToString("\$", prefix = "$ICONS\$"),
          ),
        links =
          listOf(
            ChainLink(
              "$ICONS_PACKAGE.${pack.joinToString(".") { it.lowercase() }}.${path.last()}",
              property = true,
            )
          ),
        typeFqn = IMAGE_VECTOR,
      )
    }

    private fun refuse(reason: String): ScreenValue? {
      reasons += reason
      return null
    }
  }

  private const val MODIFIER = "androidx.compose.ui.Modifier"

  /**
   * The two packages the authored modifiers come from, named once.
   *
   * Layout modifiers (`padding`, `size`, `offset`, `aspectRatio`, `wrapContentSize`) are
   * `foundation.layout` extensions; the draw ones (`clip`, `alpha`, `rotate`, `scale`, `shadow`)
   * are `ui.draw`. Which package a modifier lives in is not guessable from its name — `zIndex` is
   * in neither and `background` and `border` are in `foundation` itself — so the three that sit
   * outside these two are spelled in full at their branch.
   */
  private const val LAYOUT = "androidx.compose.foundation.layout"

  private const val DRAW = "androidx.compose.ui.draw"

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

  /**
   * The `Alignment` member a protocol alignment names, per axis.
   *
   * Exhaustive `when`s rather than maps, deliberately: an alignment added to `ui-builder-protocol`
   * then fails this module's compile rather than becoming a modifier that silently refuses. These
   * are the same members [ENUM_MEMBERS] maps `layout/box`'s `contentAlignment` onto — restated
   * because that table is keyed by the catalog's own value spellings (`topStart`) and these are
   * keyed by the protocol enum, and neither can be derived from the other.
   */
  private fun alignment(value: AlignmentV1): ScreenValue =
    ScreenValue.Reference(
      ALIGNMENT,
      listOf(
        when (value) {
          AlignmentV1.TOP_START -> "TopStart"
          AlignmentV1.TOP_CENTER -> "TopCenter"
          AlignmentV1.TOP_END -> "TopEnd"
          AlignmentV1.CENTER_START -> "CenterStart"
          AlignmentV1.CENTER -> "Center"
          AlignmentV1.CENTER_END -> "CenterEnd"
          AlignmentV1.BOTTOM_START -> "BottomStart"
          AlignmentV1.BOTTOM_CENTER -> "BottomCenter"
          AlignmentV1.BOTTOM_END -> "BottomEnd"
        }
      ),
      typeFqn = ALIGNMENT,
    )

  private fun horizontal(value: HorizontalAlignmentV1): ScreenValue =
    ScreenValue.Reference(
      ALIGNMENT,
      listOf(
        when (value) {
          HorizontalAlignmentV1.START -> "Start"
          HorizontalAlignmentV1.CENTER_HORIZONTALLY -> "CenterHorizontally"
          HorizontalAlignmentV1.END -> "End"
        }
      ),
      typeFqn = ALIGNMENT_HORIZONTAL,
    )

  private fun vertical(value: VerticalAlignmentV1): ScreenValue =
    ScreenValue.Reference(
      ALIGNMENT,
      listOf(
        when (value) {
          VerticalAlignmentV1.TOP -> "Top"
          VerticalAlignmentV1.CENTER_VERTICALLY -> "CenterVertically"
          VerticalAlignmentV1.BOTTOM -> "Bottom"
        }
      ),
      typeFqn = ALIGNMENT_VERTICAL,
    )

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
    /** A container colour Material 3 takes as a `CardColors` bundle. */
    CARD_COLORS,
    /** A resting elevation in dp, which `Card` takes as a `CardElevation` bundle. */
    CARD_ELEVATION,
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
   * Deliberately not exhaustive, and `weight` is deliberately not here: it is not a parameter under
   * another name, it is a **modifier whose legality depends on the slot the node sits in**. It goes
   * through `weightLink` and [SLOT_SCOPES] instead, which is the route `matchParentSize` takes too.
   */
  private val PROPERTY_PARAMETERS: Map<String, Map<String, ParameterTarget>> =
    mapOf(
      "m3/surface" to
        mapOf(
          "containerColor" to ParameterTarget("color", TargetKind.RENAME),
          "shapeDp" to ParameterTarget("shape", TargetKind.ROUNDED_CORNER_SHAPE),
          "tonalElevationDp" to ParameterTarget("tonalElevation", TargetKind.DP),
        ),
      "m3/card" to
        mapOf(
          "shape" to ParameterTarget("shape", TargetKind.SHAPE_TOKEN),
          "containerColor" to ParameterTarget("colors", TargetKind.CARD_COLORS),
          "elevationDp" to ParameterTarget("elevation", TargetKind.CARD_ELEVATION),
        ),
      "m3/icon" to
        mapOf(
          "iconKey" to ParameterTarget("imageVector", TargetKind.RENAME),
          "color" to ParameterTarget("tint", TargetKind.RENAME),
        ),
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
   * Catalog properties whose Compose spelling is a **modifier link**, not a parameter.
   *
   * The fourth authored table, and the one whose absence reads worst: `m3/icon`'s `sizeDp` refused
   * as "`Icon` has no parameter `sizeDp`", which is true and useless — `Icon` has no such parameter
   * because the size goes on the modifier, and the catalog says so itself by declaring `size` in
   * the component's `modifierCapabilities`.
   *
   * Only unscoped links belong here. `Modifier.weight` looks like the same shape and is not: it is
   * declared on `RowScope`, so whether it compiles depends on the slot the node was placed in — see
   * [PROPERTY_PARAMETERS] for why that stays refused.
   */
  private val MODIFIER_PROPERTIES: Map<String, Map<String, String>> =
    mapOf(
      "m3/icon" to mapOf("sizeDp" to "$LAYOUT.size"),
      "m3/icon-button" to mapOf("sizeDp" to "$LAYOUT.size"),
    )

  private const val CARD_DEFAULTS = "androidx.compose.material3.CardDefaults"

  /**
   * One component a variant property selects.
   *
   * @property canonicalId the record to emit. A canonical id rather than a catalog alias, because
   *   there is no catalog id for `ElevatedCard` — the catalog spells all three as `m3/card` and
   *   distinguishes them by the property, which is precisely the mapping this table is.
   * @property defaults the `CardDefaults` prefix whose factories match this component —
   *   `elevatedCardColors` beside `ElevatedCard`. Carried per variant because all three factories
   *   return the same type, so the wrong one compiles and silently supplies another component's
   *   colours.
   */
  private class ComponentVariant(
    val canonicalId: String,
    val defaults: String,
    /**
     * The receiver each slot of **this** component composes its children under.
     *
     * Carried per variant rather than read from [SLOT_SCOPES], because the variant can change the
     * answer: `m3/button` is a `RowScope` content slot for three of its four values and none at all
     * for `fab`, since `FloatingActionButton` takes a plain `@Composable () -> Unit`. Keyed by the
     * catalog's slot name, and a slot with no entry has no receiver — which is what makes a
     * `weight` inside a floating action button refuse where one inside a `TextButton` does not.
     */
    val slotScopes: Map<String, String> = emptyMap(),
  )

  private val COLUMN_CONTENT = mapOf("content" to COLUMN_SCOPE)
  private val ROW_CONTENT = mapOf("content" to ROW_SCOPE)

  /** The property that selects a component, per catalog id. */
  private val VARIANT_SELECTORS: Map<String, String> =
    mapOf(
      "m3/card" to "variant",
      "m3/button" to "style",
      "m3/icon-button" to "variant",
      "m3/text-field" to "variant",
      "m3/progress-indicator" to "variant",
    )

  /**
   * Which component each variant value names.
   *
   * `m3/card` is `Card`, `ElevatedCard` or `OutlinedCard` — three Compose components behind one
   * catalog id, which is why `variant` was refused as "a call-site decision this projection cannot
   * make from a parameter". It can make it from *here*: the decision is a lookup, and it was only
   * ever unmakeable while there was nothing to look up in.
   *
   * The catalog agrees, and said so before this table existed: `m3/card`'s `code.imports` already
   * lists all three, while its `code.symbol` names one. This is that intent, written where the
   * export can act on it.
   *
   * **`fab` is the entry that is not a rename.** The other eleven are the same signature under
   * another name, so the only thing that changes is which callable is written. `fab` is
   * `FloatingActionButton`: it has no `enabled`, it takes a `containerColor` directly rather than
   * through a `ButtonDefaults` bundle, and its content slot has **no receiver** where `Button`'s is
   * a `RowScope`. That last one is why [ComponentVariant.slotScopes] exists — without it a `weight`
   * inside a floating action button would be emitted against a receiver that is not there.
   */
  private val COMPONENT_VARIANTS: Map<String, Map<String, ComponentVariant>> =
    mapOf(
      "m3/card" to
        mapOf(
          "filled" to ComponentVariant(CARD_ID, "card", COLUMN_CONTENT),
          "elevated" to ComponentVariant(ELEVATED_CARD_ID, "elevatedCard", COLUMN_CONTENT),
          "outlined" to ComponentVariant(OUTLINED_CARD_ID, "outlinedCard", COLUMN_CONTENT),
        ),
      "m3/button" to
        mapOf(
          "filled" to ComponentVariant(BUTTON_ID, "button", ROW_CONTENT),
          "filledTonal" to
            ComponentVariant(FILLED_TONAL_BUTTON_ID, "filledTonalButton", ROW_CONTENT),
          "text" to ComponentVariant(TEXT_BUTTON_ID, "textButton", ROW_CONTENT),
          // No slot scopes: `FloatingActionButton`'s content is a plain `@Composable () -> Unit`.
          "fab" to ComponentVariant(FAB_ID, "floatingActionButton"),
        ),
      "m3/icon-button" to
        mapOf(
          "standard" to ComponentVariant(ICON_BUTTON_ID, "iconButton"),
          "filled" to ComponentVariant(FILLED_ICON_BUTTON_ID, "filledIconButton"),
          "tonal" to ComponentVariant(FILLED_TONAL_ICON_BUTTON_ID, "filledTonalIconButton"),
          "outlined" to ComponentVariant(OUTLINED_ICON_BUTTON_ID, "outlinedIconButton"),
        ),
      "m3/text-field" to
        mapOf(
          "filled" to ComponentVariant(TEXT_FIELD_ID, "textField"),
          "outlined" to ComponentVariant(OUTLINED_TEXT_FIELD_ID, "outlinedTextField"),
        ),
      // The two indicators, both **indeterminate**. Each name has a determinate overload taking
      // `progress: () -> Float`, and no `ScreenValue` is a lambda, so a progress value is refused
      // by `unexpressible` rather than recorded here — see [PROGRESS].
      "m3/progress-indicator" to
        mapOf(
          "linear" to ComponentVariant(LINEAR_INDICATOR_ID, "linearProgressIndicator"),
          "circular" to ComponentVariant(CIRCULAR_INDICATOR_ID, "circularProgressIndicator"),
        ),
    )

  private const val CARD_ID = "m3-catalog/androidx.compose.material3.CardKt.Card"
  private const val ELEVATED_CARD_ID = "m3-catalog/androidx.compose.material3.CardKt.ElevatedCard"
  private const val OUTLINED_CARD_ID = "m3-catalog/androidx.compose.material3.CardKt.OutlinedCard"
  private const val BUTTON_ID = "m3-catalog/androidx.compose.material3.ButtonKt.Button"
  private const val FILLED_TONAL_BUTTON_ID =
    "m3-catalog/androidx.compose.material3.ButtonKt.FilledTonalButton"
  private const val TEXT_BUTTON_ID = "m3-catalog/androidx.compose.material3.ButtonKt.TextButton"
  private const val FAB_ID =
    "m3-catalog/androidx.compose.material3.FloatingActionButtonKt.FloatingActionButton"
  private const val ICON_BUTTON_ID = "m3-catalog/androidx.compose.material3.IconButtonKt.IconButton"
  private const val FILLED_ICON_BUTTON_ID =
    "m3-catalog/androidx.compose.material3.IconButtonKt.FilledIconButton"
  private const val FILLED_TONAL_ICON_BUTTON_ID =
    "m3-catalog/androidx.compose.material3.IconButtonKt.FilledTonalIconButton"
  private const val OUTLINED_ICON_BUTTON_ID =
    "m3-catalog/androidx.compose.material3.IconButtonKt.OutlinedIconButton"
  private const val TEXT_FIELD_ID = "m3-catalog/androidx.compose.material3.TextFieldKt.TextField"
  private const val OUTLINED_TEXT_FIELD_ID =
    "m3-catalog/androidx.compose.material3.TextFieldKt.OutlinedTextField"
  private const val LINEAR_INDICATOR_ID =
    "m3-catalog/androidx.compose.material3.ProgressIndicatorKt.LinearProgressIndicator"
  private const val CIRCULAR_INDICATOR_ID =
    "m3-catalog/androidx.compose.material3.ProgressIndicatorKt.CircularProgressIndicator"

  private const val PROGRESS_INDICATOR = "m3/progress-indicator"
  private const val PROGRESS = "progress"
  private const val INDETERMINATE = "indeterminate"
  private const val WEIGHT = "weight"
  private const val ROW_SCOPE = "androidx.compose.foundation.layout.RowScope"
  private const val COLUMN_SCOPE = "androidx.compose.foundation.layout.ColumnScope"
  private const val BOX_SCOPE = "androidx.compose.foundation.layout.BoxScope"

  /**
   * The receiver each catalog slot's children are composed under, where it has one.
   *
   * The fifth authored table, and the only one that describes **placement** rather than a value.
   * Keyed by the catalog's own slot name, like [SLOT_PARAMETERS] — `children` here, `content` on
   * the record's side — because that is what the document holds.
   *
   * It has to agree with the record's `composableSlotReceiver`, since that is what the generator
   * compares a scoped link's claim against, and `M3CatalogSlotScopeTest` is what keeps the two from
   * drifting. A slot with no entry composes its children under no receiver, which is correct for
   * `m3/surface`'s `content` and every `layout/scaffold` slot, and is why a `weight` there refuses.
   */
  val SLOT_SCOPES: Map<String, Map<String, String>> =
    mapOf(
      "layout/column" to mapOf("children" to COLUMN_SCOPE),
      "layout/row" to mapOf("children" to ROW_SCOPE),
      "layout/box" to mapOf("children" to BOX_SCOPE),
      "m3/card" to mapOf("content" to COLUMN_SCOPE),
      "m3/button" to mapOf("content" to ROW_SCOPE),
    )

  private const val ICONS_PACKAGE = "androidx.compose.material.icons"
  private const val ICONS = "$ICONS_PACKAGE.Icons"
  private const val IMAGE_VECTOR = "androidx.compose.ui.graphics.vector.ImageVector"

  /** The catalog properties whose enum values are icon keys rather than members of a type. */
  private val ICON_PROPERTIES: Set<Pair<String, String>> = setOf("m3/icon" to "iconKey")

  /**
   * Which icon each catalog `iconKey` names, as the member path under `Icons`.
   *
   * The same 46 keys `GoogleMaterialIconCatalog` renders with, in the same spellings, and that is
   * the point: the builder's canvas already holds a wire-to-Kotlin mapping for every key it can
   * draw — `GoogleMaterialIcon.composeExpression` — and a second one written from the catalog's
   * `allowedValues` would be a second chance to disagree about which vector `genres` is (it is
   * `Category`, which no derivation from the key would ever produce). `:ui-builder-export` cannot
   * read that catalog, because it holds real `ImageVector`s and this module deliberately has no
   * Compose dependency, so the mapping is restated here and `GoogleMaterialIconExportMappingTest`
   * fails if the two ever drift.
   *
   * **These need `material-icons-extended` on the consumer's classpath.** Only a minority of the 46
   * are in `material-icons-core`, and nothing in a generated file can add a dependency to the
   * project it lands in. Stated here rather than discovered at compile time because it is the one
   * thing about this table a reader has to know: an export that names `Icons.Filled.Coffee` is
   * correct Kotlin and does not compile against `-core` alone.
   */
  val ICON_MEMBERS: Map<String, String> =
    mapOf(
      "accessTime" to "Filled.AccessTime",
      "accountCircle" to "Filled.AccountCircle",
      "add" to "Filled.Add",
      "addCircle" to "Filled.AddCircle",
      "arrowBack" to "AutoMirrored.Filled.ArrowBack",
      "arrowForward" to "AutoMirrored.Filled.ArrowForward",
      "bookmark" to "Filled.Bookmark",
      "bookmarkBorder" to "Outlined.BookmarkBorder",
      "calendarMonth" to "Filled.CalendarMonth",
      "cameraAlt" to "Filled.CameraAlt",
      "check" to "Filled.Check",
      "checkCircle" to "Filled.CheckCircle",
      "chevronRight" to "Filled.ChevronRight",
      "close" to "Filled.Close",
      "coffee" to "Filled.Coffee",
      "delete" to "Filled.Delete",
      "download" to "Filled.Download",
      "edit" to "Filled.Edit",
      "email" to "Filled.Email",
      "expandMore" to "Filled.ExpandMore",
      "favorite" to "Filled.Favorite",
      "genres" to "Filled.Category",
      "home" to "Filled.Home",
      "image" to "Filled.Image",
      "info" to "Filled.Info",
      "locationOn" to "Filled.LocationOn",
      "lock" to "Filled.Lock",
      "menu" to "Filled.Menu",
      "moreVert" to "Filled.MoreVert",
      "notifications" to "Filled.Notifications",
      "pauseCircle" to "Filled.PauseCircle",
      "person" to "Filled.Person",
      "phone" to "Filled.Phone",
      "playCircle" to "Filled.PlayCircle",
      "playlistAdd" to "AutoMirrored.Filled.PlaylistAdd",
      "refresh" to "Filled.Refresh",
      "remove" to "Filled.Remove",
      "search" to "Filled.Search",
      "settings" to "Filled.Settings",
      "share" to "Filled.Share",
      "star" to "Filled.Star",
      "stopCircle" to "Filled.StopCircle",
      "upload" to "Filled.Upload",
      "videoLibrary" to "Filled.VideoLibrary",
      "visibility" to "Filled.Visibility",
      "warning" to "Filled.Warning",
    )

  /**
   * Properties whose values pick a **component**, not an argument.
   *
   * No table of *members* can express these, because the value names a component rather than an
   * argument. [COMPONENT_VARIANTS] is where that becomes expressible — `m3/card` went through it
   * and is no longer here — so what is left is the ones nothing selects yet, and the refusal says
   * so rather than reading like a missing member.
   */
  private val VARIANT_PROPERTIES: Set<Pair<String, String>> =
    setOf(
      "layout/supporting-pane-scaffold" to "layoutMode",
      "layout/horizontal-carousel" to "kind",
    )
}
