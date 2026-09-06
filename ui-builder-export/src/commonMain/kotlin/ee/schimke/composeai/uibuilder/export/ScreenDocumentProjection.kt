package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.discovery.SlotItem
import ee.schimke.composeai.uibuilder.cardContentFill
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
import ee.schimke.composeai.uibuilder.toUiBuilderNode
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
    // The lazy family names its slot `items`, which reads as a list of children and is a
    // `content` lambda on every one of the three Compose signatures.
    "layout/lazy-column" to mapOf("items" to "content"),
    "layout/lazy-row" to mapOf("items" to "content"),
    "layout/lazy-grid" to mapOf("items" to "content"),
    // `ListItem` names each region by what it holds — `headlineContent` — where the catalog names
    // the region alone. Three of its six slots; `overlineContent` and `leadingContent` are not in
    // the catalog's vocabulary, so nothing here can name them.
    "m3/list-item" to
      mapOf(
        "headline" to "headlineContent",
        "supporting" to "supportingContent",
        "trailing" to "trailingContent",
      ),
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
 * names is still refused by name, under a reason authored for it in [VARIANT_PROPERTIES]:
 * `expandedTwoPane` is a mode of one adaptive component and `uncontained` names which carousel to
 * call, and neither is a member of anything a table could hold.
 */
object ScreenDocumentProjection {

  /**
   * The Kotlin parameter a catalog slot fills. Public only so `M3CatalogSlotScopeTest` can walk the
   * same alias the projection does when it checks [SLOT_SCOPES] against the shipped record.
   */
  fun parameterForSlotName(componentId: String, slot: String): String =
    parameterForSlot(componentId, slot)

  sealed interface Outcome {
    data class Projected(
      val document: ScreenDocument,
      /**
       * Every `asset/image` whose picture the source stands in for with a placeholder painter.
       *
       * Not a refusal, and not silent either: the generated call is the right `Image(...)` with the
       * design's own description, scale and modifiers, and the one argument this projection cannot
       * write — a `Painter` for bytes that live in the design's asset store, not in Kotlin — is a
       * theme-coloured `ColorPainter`. The caller says so beside the source, naming the key and the
       * digest, so the person bundling the picture knows exactly which line to replace.
       */
      val assetPlaceholders: List<AssetPlaceholder> = emptyList(),
    ) : Outcome

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
    return Outcome.Projected(
      ScreenDocument(name = screenName, root = checkNotNull(root)),
      assetPlaceholders = pass.assetPlaceholders.toList(),
    )
  }

  /** One `asset/image` the source draws with a placeholder painter in place of its picture. */
  data class AssetPlaceholder(
    val nodeId: String,
    val assetKey: String,
    /**
     * The registry binding's media type and digest, or null for a key the design has no entry for.
     */
    val mediaType: String?,
    val contentDigest: String?,
  )

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
    val assetPlaceholders = mutableListOf<AssetPlaceholder>()
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
                if (node.componentId == CARD_CATALOG_ID && slot == CARD_CONTENT_SLOT)
                  listOf(cardContentBox(node, children))
                else children.mapNotNull { child -> node(child, childScope) }
            },
          slotItems =
            node.slots.keys
              .mapNotNull { slot ->
                SLOT_ITEMS[node.componentId]?.get(slot)?.let {
                  parameterForSlot(node.componentId, slot) to it
                }
              }
              .toMap(),
        )
      } finally {
        visiting.remove(id)
      }
    }

    /**
     * A card's content, inside the `Box` this catalog says a card's content is.
     *
     * The canvas stacks a card's children with box alignments, the editor offers a card child
     * exactly what a `layout/box` child gets, and the capability exporter writes `Card { Box { … }
     * }` — so a card's children live in a `BoxScope`, and a design that lays an image, a gradient
     * and an aligned title over each other in one card (the Jetcaster podcast cards do) means
     * exactly that. This projection used to compose them straight under `Card`'s own `ColumnScope`,
     * which stacked the same children top to bottom and refused every `matchParentSize` among them:
     * the one lane whose whole claim is fidelity drew a different card from the two it exists to
     * check.
     *
     * The box is emitted as the catalog's `layout/box`, which the record attests like any other
     * node, and sized by [cardContentFill] — the rule the canvas and the capability exporter read —
     * so a card with no height wraps here exactly as it does there (#483).
     */
    private fun cardContentBox(card: DesignNodeV1, children: List<String>): ScreenNode {
      val fill = card.toUiBuilderNode().cardContentFill()
      val links =
        when {
          fill.width && fill.height -> listOf(ChainLink("$LAYOUT.fillMaxSize"))
          fill.width -> listOf(ChainLink("$LAYOUT.fillMaxWidth"))
          fill.height -> listOf(ChainLink("$LAYOUT.fillMaxHeight"))
          else -> emptyList()
        }
      val arguments =
        if (links.isEmpty()) emptyMap()
        else
          mapOf(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER),
                links = links,
                typeFqn = MODIFIER,
              )
          )
      return ScreenNode(
        componentId = BOX_CATALOG_ID,
        arguments = arguments,
        slots =
          mapOf(
            parameterForSlot(BOX_CATALOG_ID, BOX_CHILDREN_SLOT) to
              children.mapNotNull { child -> node(child, BOX_SCOPE) }
          ),
      )
    }

    /**
     * Whether a progress indicator's `indeterminate` property was handled here, refusal included.
     *
     * Both Compose indicators have two overloads — an indeterminate one whose parameters all
     * default, and a determinate one taking `progress: () -> Float` — and the argument list is what
     * picks between them. The catalog says the same thing in its own words: "absent means the
     * indeterminate indicator, Material's own distinction between a progress you know and one you
     * do not". So `progress` is now an ordinary argument (see [PROPERTY_PARAMETERS]) and this is
     * left with the boolean that restates the choice rather than making it.
     *
     * It used to refuse both. `progress` was unwritable while no [ScreenValue] was a lambda, and
     * that reason was true of the vocabulary rather than of the component — `ScreenValue.Lambda`
     * (compose-ai-tools#5219) is the narrow kind that ended it.
     *
     * `indeterminate` is **spent either way**, because it never adds anything the argument list has
     * not already said: `true` describes the overload an absent `progress` selects, and `false`
     * describes the one a present `progress` selects. The single case worth a refusal is the
     * contradiction — not indeterminate, and no progress to be determinate with — which asks for
     * the determinate overload without the one argument it requires.
     */
    private fun determinacy(property: String, value: UiValueV1, node: DesignNodeV1): Boolean {
      if (property != INDETERMINATE) return false
      if ((value as? BooleanValueV1)?.value == false && PROGRESS !in node.properties) {
        refuse(
          "node `${node.id}` is not indeterminate and sets no `progress`; the determinate " +
            "indicator is chosen by passing one, and there is nothing here to pass"
        )
      }
      return true
    }

    /**
     * Whether this property asks for something about the node's **placement in its parent** that no
     * argument to the node itself could carry — refusing by name if so.
     *
     * `span` is the case, and it is worth refusing loudly rather than dropping. A child of a lazy
     * grid with `span = full` is a row that crosses every column, and the old exporter wrote it as
     * `item(span = { GridItemSpan(maxLineSpan) })` — an argument to the **wrapper**, computed from
     * a lambda whose receiver supplies `maxLineSpan`. Two separate things put that out of reach: a
     * [SlotItem] is one wrapper for a whole slot rather than one per child, and
     * `ScreenValue.Lambda` returns a value the document already holds — it cannot read a receiver,
     * which is the whole of what `maxLineSpan` is. Dropped instead, a full-width row would silently
     * export as a single cell — a different design that compiles, which is the failure this
     * projection exists to prevent.
     */
    private fun unplaceable(property: String, node: DesignNodeV1): Boolean {
      if (property != SPAN) return false
      refuse(
        "node `${node.id}`.`$property` is the span this node takes in its parent grid, which is " +
          "`item(span = { GridItemSpan(…) })` on the wrapper around it — an argument to another " +
          "node, computed from the grid's own scope, and this vocabulary has neither"
      )
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
      when {
        // A DSL slot's children are not composed under the slot's own receiver — they sit inside
        // the wrapper, whose receiver (`LazyItemScope`) nothing in the record attests. So they get
        // none, and a `weight` inside a lazy list refuses by name exactly as one at the root does.
        SLOT_ITEMS[componentId]?.containsKey(slot) == true -> null
        variant != null -> variant.slotScopes[slot]
        else -> SLOT_SCOPES[componentId]?.get(slot)
      }

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
      // Properties that are read together rather than one at a time, spent here before the loop
      // below reaches them: an arrangement and the spacing it composes with, the two colour roles
      // one `colors` bundle carries, a colour dot's whole modifier chain.
      val spent = composite(node, arguments, fromProperties)
      for ((property, value) in node.properties) {
        if (property in spent) continue
        // Spent on the call site above; emitting it as well would hand the component a parameter
        // it does not declare.
        if (variant != null && property == VARIANT_SELECTORS[node.componentId]) continue
        if (property == WEIGHT) {
          weightLink(value, node, scope)?.let { fromProperties += it }
          continue
        }
        if (node.componentId == PROGRESS_INDICATOR && determinacy(property, value, node)) continue
        // Recomposition identity rather than design. Spent, like a variant selector is, and for a
        // reason that is written down: see [IDENTITY_PROPERTIES].
        if (property in IDENTITY_PROPERTIES) continue
        if (unplaceable(property, node)) continue
        // How a parent box aligns this node — `BoxScope.align`, so a scoped link decided by the
        // slot the node sits in, exactly as the authored `align` modifier is.
        if (property == BOX_ALIGNMENT && node.componentId in BOX_ALIGNED) {
          boxAlignmentLink(value, node, property, scope)?.let { fromProperties += it }
          continue
        }
        if (node.componentId == LIST_ITEM && property == START_ACCENT_COLOR) {
          accent(value, node, property)
          continue
        }
        if (node.componentId == SLIDER && property in SLIDER_BOUNDS) {
          sliderBound(value, node, property)
          continue
        }
        val link = MODIFIER_PROPERTIES[node.componentId]?.get(property)
        if (link != null) {
          modifierLink(link, value, node, property)?.let { fromProperties += it }
          continue
        }
        // The variant's own target wins, because it describes the component actually being
        // emitted; the catalog id's table is what the other values fall back to.
        val target =
          variant?.propertyTargets?.get(property)
            ?: PROPERTY_PARAMETERS[node.componentId]?.get(property)
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
     * The arguments built from **more than one** property, and the properties they consumed.
     *
     * Every other property is one value on one parameter, which the loop in [arguments] handles a
     * row at a time. Three shapes are not: an arrangement and a spacing name **one** `Arrangement`
     * between them, two colour roles fill **one** `TopAppBarColors` bundle, and a colour dot is a
     * `Box` whose entire meaning is a modifier chain built from both its properties. Read one at a
     * time, the second of each pair would silently overwrite the first in the argument map — a row
     * with `spaceBetween` and an 8dp gap exporting as whichever the document happened to list last.
     */
    private fun composite(
      node: DesignNodeV1,
      arguments: MutableMap<String, ScreenValue>,
      fromProperties: MutableList<ChainLink>,
    ): Set<String> {
      val spent = mutableSetOf<String>()
      ARRANGEMENTS[node.componentId]?.let { axis ->
        if (axis.property in node.properties && axis.spacing in node.properties) {
          spent += axis.property
          spent += axis.spacing
          arranged(axis, node)?.let { arguments[axis.parameter] = it }
        }
      }
      COLOR_BUNDLES[node.componentId]?.let { bundle ->
        val roles = bundle.roles.filter { it in node.properties }
        if (roles.isNotEmpty()) {
          spent += roles
          val named =
            roles
              .mapNotNull { role ->
                colour(node.properties.getValue(role), "node `${node.id}`.`$role`")?.let {
                  role to it
                }
              }
              .toMap()
          // Only when every role resolved: a bundle missing one would export a bar whose colour
          // silently fell back to Material's, which is the wrong-picture-that-compiles case.
          if (named.size == roles.size) {
            arguments[bundle.parameter] =
              ScreenValue.Construct(
                callableFqn = bundle.factoryFqn,
                named = named,
                typeFqn = bundle.typeFqn,
                requiredOptIns = bundle.optIns,
              )
          }
        }
      }
      if (node.componentId == COLOUR_DOT) spent += colourDot(node, fromProperties)
      return spent
    }

    /**
     * The one `Arrangement` a node's arrangement **and** spacing name together, or null having said
     * why there isn't one.
     *
     * Compose has two families here and the catalog documents which composes with what: the three
     * *aligned* arrangements take a gap through `Arrangement.spacedBy(space, alignment)`, and the
     * three `space*` arrangements "distribute the free space themselves, and Compose has no form
     * that also inserts a fixed gap" — the catalog's own note on `verticalArrangement`. The canvas
     * renders exactly that rule, so this writes exactly that rule: `spacedBy(8.dp, Alignment.End)`
     * for an aligned value with a gap, the bare member for a `space*` value with the gap spent, and
     * the bare member again for an aligned value whose gap is zero — `spacedBy(0.dp, Top)` is
     * `Top`, and a person writes the shorter one.
     */
    private fun arranged(axis: ArrangementAxis, node: DesignNodeV1): ScreenValue? {
      val where = "node `${node.id}`.`${axis.property}`"
      val entry =
        when (val value = node.properties.getValue(axis.property)) {
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          else ->
            return refuse(
              "$where is a ${value::class.simpleName}, and an arrangement is one of " +
                ENUM_MEMBERS.getValue(node.componentId)
                  .getValue(axis.property)
                  .members
                  .keys
                  .sorted()
                  .joinToString(", ")
            )
        }
      // Through the same table a lone arrangement reads, so an unknown value refuses with the
      // same list it would have refused with alone.
      val member = enum(entry, node.componentId, axis.property, where) ?: return null
      val spacingWhere = "node `${node.id}`.`${axis.spacing}`"
      val number =
        when (val value = node.properties.getValue(axis.spacing)) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> return refuse("$spacingWhere becomes `${axis.parameter}`, which needs a number")
        }
      val alignment = axis.aligned[entry] ?: return member
      if (number <= 0.0) return member
      val dp = dp(number) ?: return refuse("$spacingWhere is $number, which does not survive `Dp`")
      return ScreenValue.Construct(
        callableFqn = "$ARRANGEMENT.spacedBy",
        positional =
          listOf(dp, ScreenValue.Reference(ALIGNMENT, listOf(alignment), typeFqn = axis.alignment)),
        typeFqn = axis.typeFqn,
      )
    }

    /**
     * A colour dot's whole modifier chain — `size(d.dp).clip(CircleShape).background(colour)` — and
     * the two properties it spent.
     *
     * `shape/colour-dot` is a `Box` and nothing else: the catalog's own `code.symbol` says so, and
     * the canvas draws exactly this chain. So the record it exports through is `Box`'s, reached by
     * alias, and the component's identity is entirely the links appended here. The diameter
     * defaults to the canvas's 8dp rather than to nothing, because a `Box` with no size is 0dp and
     * a dot that vanished on export is a different design.
     */
    private fun colourDot(node: DesignNodeV1, fromProperties: MutableList<ChainLink>): Set<String> {
      val where = "node `${node.id}`"
      val size =
        when (val value = node.properties[DIAMETER_DP]) {
          null -> dp(DEFAULT_DOT_DIAMETER)
          is DecimalValueV1 -> dp(value.value)
          is IntegerValueV1 -> dp(value.value.toDouble())
          else -> null
        }
      if (size == null) {
        refuse("$where.`$DIAMETER_DP` is a diameter in dp, which needs a number that survives `Dp`")
      }
      val fill =
        when (val value = node.properties[DOT_COLOR]) {
          null -> refuse("$where sets no `$DOT_COLOR`, and a colour dot is nothing but its colour")
          // The canvas reads this property as a literal string, so the text spelling is the one
          // real documents hold; the wrappers are accepted because the reducer canonicalises to
          // them.
          is StringValueV1 -> color(value.value, "$where.`$DOT_COLOR`")
          else -> colour(value, "$where.`$DOT_COLOR`")
        }
      if (size != null && fill != null) {
        fromProperties += ChainLink("$LAYOUT.size", positional = listOf(size))
        fromProperties +=
          ChainLink(
            "$DRAW.clip",
            positional =
              listOf(ScreenValue.Reference(SHAPE_CONSTANTS.getValue("circle"), typeFqn = SHAPE)),
          )
        fromProperties +=
          ChainLink("androidx.compose.foundation.background", named = mapOf("color" to fill))
      }
      return setOf(DIAMETER_DP, DOT_COLOR)
    }

    /**
     * `BoxScope.align(…)` for the `alignment` **property**, or null having said where the node is.
     *
     * The catalog declares `alignment` on `m3/text` and `layout/column` as "how a parent Box aligns
     * this" node, and the canvas reads it off any child of a box. It is the authored `align`
     * modifier under another spelling, so it takes the same route: a scoped link, legal only inside
     * a `layout/box` slot, refused by name anywhere else rather than dropped.
     */
    private fun boxAlignmentLink(
      value: UiValueV1,
      node: DesignNodeV1,
      property: String,
      scope: String?,
    ): ChainLink? {
      val where = "node `${node.id}`.`$property`"
      val choices = BOX_ALIGNMENT_MEMBERS.members.keys.sorted().joinToString(", ")
      val entry =
        when (value) {
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          else -> {
            refuse("$where is how a parent box aligns this node, which is one of $choices")
            return null
          }
        }
      val path =
        BOX_ALIGNMENT_MEMBERS.members[entry]
          ?: run {
            refuse("$where is the enum value `$entry`, which is not one of $choices")
            return null
          }
      return alignLink(
        node.id,
        scope,
        BOX_SCOPE,
        "box",
        ScreenValue.Reference(path.first(), path.drop(1), typeFqn = ALIGNMENT),
      )
    }

    /**
     * A list item's leading accent bar, which is the one part of the component nothing here can
     * write.
     *
     * The canvas draws it with `Modifier.drawBehind { drawRect(colour, size = Size(3.dp.toPx(),
     * size.height)) }` — a draw lambda with a statement in it, which is precisely the shape this
     * vocabulary refuses to grow into. An empty value is the catalog's own "draws none" and is
     * spent, so a plain list item exports; a colour refuses by name, so a schedule whose track
     * colours are the point does not come back as a plain list that compiles.
     */
    private fun accent(value: UiValueV1, node: DesignNodeV1, property: String) {
      if (value is StringValueV1 && value.value.isEmpty()) return
      refuse(
        "node `${node.id}`.`$property` is a 3dp bar drawn down the item's leading edge with " +
          "`Modifier.drawBehind { … }` — a draw lambda this vocabulary has no form for; leave it " +
          "empty to export the item without the bar"
      )
    }

    /**
     * One end of a slider's range, which exports only when it is the end Material already has.
     *
     * `Slider` takes `valueRange: ClosedFloatingPointRange<Float>`, written `0f..100f`, and a range
     * expression is not one of this vocabulary's shapes — `rangeTo` is a member of `Float` in the
     * standard library, outside the packages a generated screen may name. So a bound equal to the
     * default is spent, because omitting it leaves the parameter at exactly that value, and any
     * other bound refuses by name rather than exporting a 0-to-100 slider as a 0-to-1 one.
     */
    private fun sliderBound(value: UiValueV1, node: DesignNodeV1, property: String) {
      val where = "node `${node.id}`.`$property`"
      val number =
        when (value) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> {
            refuse("$where becomes one end of `valueRange`, which needs a number")
            return
          }
        }
      if (number == SLIDER_BOUNDS.getValue(property)) return
      refuse(
        "$where is $number, which reaches `Slider` as `valueRange = valueFrom..valueTo` — a range " +
          "expression this vocabulary has no form for; only Material's default range " +
          "(`valueFrom` 0, `valueTo` 1) exports"
      )
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
     * None is refused on principle any more. `verticalScroll` and `horizontalScroll` were, as "a
     * `remember { … }` preamble this projection does not emit", and that was a wrong diagnosis —
     * see [scrolls]. The `else` branch below is therefore not a list of things left undone; it is
     * what a *newer* `ui-builder-protocol` than the one this compiled against would fall into, and
     * it keeps that arriving as a named refusal rather than as a silently dropped modifier.
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
        VerticalScrollModifierV1 -> scrolls("androidx.compose.foundation.verticalScroll")
        HorizontalScrollModifierV1 -> scrolls("androidx.compose.foundation.horizontalScroll")
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

    /**
     * `.verticalScroll(rememberScrollState())` — the state remembered **inline**, at the call.
     *
     * This refused for several rounds, as taking "a `ScrollState` from `rememberScrollState()` — a
     * `remember { … }` preamble this projection does not emit", and the diagnosis mislocated the
     * state. `rememberScrollState()` is a `@Composable` function whose parameters all default, and
     * the generated screen body is composable, so the call is legal exactly where the modifier is
     * written — which is where a person writes it, and where `rememberCarouselState { n }` already
     * goes one component over. A `ScrollState` never needed a declaration line above the tree; it
     * needed a [ScreenValue.Construct] in the link's argument
     * ([#481](https://github.com/yschimke/compose-preview-server/issues/481)).
     */
    private fun scrolls(callableFqn: String): ChainLink =
      ChainLink(
        callableFqn,
        positional = listOf(ScreenValue.Construct(REMEMBER_SCROLL_STATE, typeFqn = SCROLL_STATE)),
      )

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
      if (target.kind == TargetKind.ASSET_PAINTER) {
        // The one argument no `ScreenValue` can carry: the picture is bytes in the design's asset
        // store, and the host convention for a bundled resource — `R.drawable` here, `Res.drawable`
        // there — names a symbol nothing on the generator's classpath declares. What compiles on
        // every host and draws *something* in the picture's frame is a `ColorPainter` in the
        // theme's surface-variant, the same ground the canvas paints under an unresolved key. The
        // substitution is recorded on the outcome rather than hidden in it, so the export can say
        // beside the source which line to replace and with which bytes.
        val assetKey =
          when (value) {
            is AssetKeyValueV1 -> value.value
            is StringValueV1 -> value.value
            else -> return refuse("$where must be an asset key")
          }
        val binding = document.assets[assetKey]
        assetPlaceholders +=
          AssetPlaceholder(
            nodeId = node.id,
            assetKey = assetKey,
            mediaType = binding?.mediaType,
            contentDigest = binding?.contentDigest,
          )
        return ScreenValue.Construct(
          callableFqn = COLOR_PAINTER,
          positional =
            listOf(
              ScreenValue.Reference(
                rootFqn = THEME,
                members = listOf("colorScheme", "surfaceVariant"),
                typeFqn = COLOR,
              )
            ),
          typeFqn = PAINTER,
        )
      }
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
      if (target.kind == TargetKind.BUTTON_COLORS) {
        // The card's argument one component family over, and it needs the same care for the same
        // reason: all four `ButtonDefaults` factories return a `ButtonColors`, so `buttonColors`
        // would compile on a `TextButton` and hand it the *filled* button's content and disabled
        // colours for every role the designer did not set.
        //
        // `fab` never reaches here — `FloatingActionButton` takes a bare `Color` and says so
        // through `ComponentVariant.propertyTargets`, which is the axis #393 added.
        val color = value(value, node, property) ?: return null
        return ScreenValue.Construct(
          callableFqn = "$BUTTON_DEFAULTS.${variant?.defaults ?: "button"}Colors",
          named = mapOf("containerColor" to color),
          typeFqn = "androidx.compose.material3.ButtonColors",
        )
      }
      if (target.kind == TargetKind.FLOAT_LAMBDA || target.kind == TargetKind.FLOAT) {
        val fraction =
          when (value) {
            is DecimalValueV1 -> value.value
            is IntegerValueV1 -> value.value.toDouble()
            // A `state` read is the catalog's other spelling for `progress` and for a slider's
            // `value`, and it refuses under its own name rather than this one: the lambda is
            // expressible now, and the state variable inside it still needs the `remember`
            // preamble this projection does not emit.
            else -> return value(value, node, property)
          }
        val narrowed = fraction.toFloat()
        // The same narrowing every other number here gets. A value past `Float` becomes `Infinity`
        // and one below it collapses to zero, and a progress bar drawn from either is not the one
        // anybody designed.
        if (!narrowed.isFinite() || (narrowed == 0f && fraction != 0.0)) {
          return refuse("$where is $fraction, which does not survive `Float`")
        }
        val float = ScreenValue.Fractional32(narrowed)
        return if (target.kind == TargetKind.FLOAT) float else ScreenValue.Lambda(float)
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
        TargetKind.BUTTON_COLORS,
        TargetKind.FLOAT,
        TargetKind.FLOAT_LAMBDA,
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
          when {
            enumerated(node.componentId, property) ->
              enum(value.value, node.componentId, property, where)
            // The modifier's sentence for the property's mistake. Left to the generator, a
            // `string` on `m3/text.color` was refused as "`Text`.`color` is Color, which Text is
            // not" — a message about a type the author never wrote, with no hint that the wrapper
            // was the problem (#476). The reducers refuse the spelling at commit now; a document
            // that already holds it gets the same words here.
            PropertyValueKinds.isColour(property) -> colour(value, where)
            else -> ScreenValue.Text(value.value)
          }
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
        is AdaptiveGridValueV1 -> {
          // `LazyVerticalGrid(columns = …)` takes exactly this as a value, so the refusal this
          // replaces was true only while no grid had a record to be an argument of. A cell width
          // that is absent or does not survive `Dp` still refuses, through the same `JsonElement`
          // narrowing every other dimension in this file goes through.
          val minimum =
            dp(value.minimumCellWidthDp)
              ?: return refuse(
                "$where has a minimum cell width of `${value.minimumCellWidthDp}`, which is not " +
                  "a number that survives `Dp`"
              )
          ScreenValue.Construct(
            callableFqn = "androidx.compose.foundation.lazy.grid.GridCells.Adaptive",
            positional = listOf(minimum),
            // The parameter's own type, not the expression's: `GridCells.Adaptive` is a
            // `GridCells`, and the generator compares this claim to `TargetParameter.typeFqn` as a
            // string.
            typeFqn = "androidx.compose.foundation.lazy.grid.GridCells",
          )
        }
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
      FACTORY_MEMBERS[componentId to property]?.let { factory ->
        val callable =
          factory.members[entry]
            ?: return refuse(
              "$where is the enum value `$entry`, which is not one of " +
                factory.members.keys.sorted().joinToString(", ")
            )
        return ScreenValue.Construct(
          callableFqn = callable,
          typeFqn = factory.typeFqn,
          requiredOptIns = factory.optIns,
        )
      }
      val mapping =
        ENUM_MEMBERS[componentId]?.get(property)
          ?: return refuse(
            VARIANT_PROPERTIES[componentId to property]?.let { "$where is `$entry`, which $it" }
              ?: "$where is the enum value `$entry`, and nothing maps this catalog property's " +
                "values to Kotlin members"
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
        componentId to property in ICON_PROPERTIES ||
        componentId to property in FACTORY_MEMBERS

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
  private const val CONTENT_SCALE = "androidx.compose.ui.layout.ContentScale"
  private const val PAINTER = "androidx.compose.ui.graphics.painter.Painter"
  private const val COLOR_PAINTER = "androidx.compose.ui.graphics.painter.ColorPainter"

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
    /** A number the parameter takes as a `Float` — a slider's `value`. */
    FLOAT,
    /** A theme shape role named as text — `large`. */
    SHAPE_TOKEN,
    /** A container colour Material 3 takes as a `CardColors` bundle. */
    CARD_COLORS,
    /** The same, for the buttons whose colours live in a `ButtonColors` bundle. */
    BUTTON_COLORS,
    /** A number the component takes as a lambda returning a `Float` — `progress = { 0.4f }`. */
    FLOAT_LAMBDA,
    /** A resting elevation in dp, which `Card` takes as a `CardElevation` bundle. */
    CARD_ELEVATION,
    /** An asset key, which `Image` takes as a `Painter` this projection stands in for. */
    ASSET_PAINTER,
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
      "asset/image" to mapOf("assetKey" to ParameterTarget("painter", TargetKind.ASSET_PAINTER)),
      // Three of the four styles; `fab` overrides this in `COMPONENT_VARIANTS` because it takes a
      // bare `Color` on a different parameter.
      "m3/button" to mapOf("containerColor" to ParameterTarget("colors", TargetKind.BUTTON_COLORS)),
      // Same name on both sides, so this entry exists for the KIND rather than for a rename: the
      // catalog carries a number and Compose takes `() -> Float`.
      "m3/progress-indicator" to
        mapOf("progress" to ParameterTarget("progress", TargetKind.FLOAT_LAMBDA)),
      // Same name on both sides again, and again for the kind: the catalog holds a number and
      // `Slider` takes a `Float`, which a whole number in the document would not render as.
      SLIDER to mapOf("value" to ParameterTarget("value", TargetKind.FLOAT)),
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
      // The lazy three spell spacing the way their non-lazy counterparts do, and reach the same
      // `Arrangement.spacedBy` this table already builds for `layout/row` and `layout/column`.
      // `contentPadding` and `reverseLayout` are parameters under their own names and need no
      // entry.
      "layout/lazy-column" to
        mapOf(
          "verticalSpacingDp" to
            ParameterTarget("verticalArrangement", TargetKind.SPACED_BY_VERTICAL)
        ),
      "layout/lazy-row" to
        mapOf(
          "horizontalSpacingDp" to
            ParameterTarget("horizontalArrangement", TargetKind.SPACED_BY_HORIZONTAL)
        ),
      "layout/lazy-grid" to
        mapOf(
          "verticalSpacingDp" to
            ParameterTarget("verticalArrangement", TargetKind.SPACED_BY_VERTICAL),
          "horizontalSpacingDp" to
            ParameterTarget("horizontalArrangement", TargetKind.SPACED_BY_HORIZONTAL),
        ),
    )

  /** One catalog property's values, as the Kotlin members they name. */
  private class EnumMembers(val typeFqn: String, val members: Map<String, List<String>>)

  private fun members(typeFqn: String, root: String, vararg pairs: Pair<String, String>) =
    EnumMembers(typeFqn, pairs.toMap().mapValues { (_, member) -> listOf(root, member) })

  /**
   * The nine two-axis alignments, named once: `layout/box`'s `contentAlignment` reads them, and so
   * does the `alignment` property a box's children carry (see [BOX_ALIGNED]).
   */
  private val BOX_ALIGNMENT_MEMBERS =
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
      "asset/image" to
        mapOf(
          "contentScale" to
            members(
              CONTENT_SCALE,
              CONTENT_SCALE,
              "crop" to "Crop",
              "fit" to "Fit",
              "fillBounds" to "FillBounds",
              "inside" to "Inside",
            ),
          "alignment" to
            members(
              ALIGNMENT,
              ALIGNMENT,
              "center" to "Center",
              "topStart" to "TopStart",
              "topCenter" to "TopCenter",
              "topEnd" to "TopEnd",
              "centerStart" to "CenterStart",
              "centerEnd" to "CenterEnd",
              "bottomStart" to "BottomStart",
              "bottomCenter" to "BottomCenter",
              "bottomEnd" to "BottomEnd",
            ),
        ),
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
      // The arrangements sat beside the alignments in the catalog and nowhere in this table, so a
      // `spaceBetween` top bar or a `spaceEvenly` navigation row refused as "nothing maps this
      // catalog property's values" while the `verticalAlignment: center` on the same node went
      // through ([#475](https://github.com/yschimke/compose-preview-server/issues/475)). Six
      // members of the `Arrangement` object each; when a spacing is set on the same node the two
      // are read together instead — see `arranged`.
      "layout/row" to
        mapOf(
          "horizontalArrangement" to
            members(
              ARRANGEMENT_HORIZONTAL,
              ARRANGEMENT,
              "start" to "Start",
              "center" to "Center",
              "end" to "End",
              "spaceBetween" to "SpaceBetween",
              "spaceAround" to "SpaceAround",
              "spaceEvenly" to "SpaceEvenly",
            ),
          "verticalAlignment" to
            members(
              ALIGNMENT_VERTICAL,
              ALIGNMENT,
              "top" to "Top",
              "center" to "CenterVertically",
              "bottom" to "Bottom",
            ),
        ),
      "layout/column" to
        mapOf(
          "verticalArrangement" to
            members(
              ARRANGEMENT_VERTICAL,
              ARRANGEMENT,
              "top" to "Top",
              "center" to "Center",
              "bottom" to "Bottom",
              "spaceBetween" to "SpaceBetween",
              "spaceAround" to "SpaceAround",
              "spaceEvenly" to "SpaceEvenly",
            ),
          "horizontalAlignment" to
            members(
              ALIGNMENT_HORIZONTAL,
              ALIGNMENT,
              "start" to "Start",
              "center" to "CenterHorizontally",
              "end" to "End",
            ),
        ),
      "layout/box" to mapOf("contentAlignment" to BOX_ALIGNMENT_MEMBERS),
    )

  /**
   * The arrangement property each layout pairs with a spacing, and how the two compose.
   *
   * @property aligned the arrangements that take a gap — as the `Alignment` member
   *   `Arrangement.spacedBy(space, alignment)` wants for each, which is a **different** member from
   *   the one the arrangement alone names: `center` is `Arrangement.Center` on its own and
   *   `Alignment.CenterHorizontally` beside a gap. The three `space*` values are absent because no
   *   form of them takes a gap.
   */
  private class ArrangementAxis(
    val property: String,
    val spacing: String,
    val parameter: String,
    val typeFqn: String,
    val alignment: String,
    val aligned: Map<String, String>,
  )

  private val ARRANGEMENTS: Map<String, ArrangementAxis> =
    mapOf(
      "layout/row" to
        ArrangementAxis(
          property = "horizontalArrangement",
          spacing = "horizontalSpacingDp",
          parameter = "horizontalArrangement",
          typeFqn = ARRANGEMENT_HORIZONTAL,
          alignment = ALIGNMENT_HORIZONTAL,
          aligned = mapOf("start" to "Start", "center" to "CenterHorizontally", "end" to "End"),
        ),
      "layout/column" to
        ArrangementAxis(
          property = "verticalArrangement",
          spacing = "verticalSpacingDp",
          parameter = "verticalArrangement",
          typeFqn = ARRANGEMENT_VERTICAL,
          alignment = ALIGNMENT_VERTICAL,
          aligned = mapOf("top" to "Top", "center" to "CenterVertically", "bottom" to "Bottom"),
        ),
    )

  /**
   * Catalog enum values that name a **factory call** rather than a member — `pinned` is
   * `TopAppBarDefaults.pinnedScrollBehavior()`, a `@Composable` function with every parameter
   * defaulted, called where the argument goes.
   *
   * A third table beside [ENUM_MEMBERS] and [ICON_MEMBERS] because the value's shape is a third
   * one: a [ScreenValue.Construct] with no arguments, where those two produce a reference and a
   * chain. The catalog says of `scrollBehavior` that it "reaches the generated Kotlin as the
   * matching `TopAppBarDefaults` behavior", and that the canvas draws the bar pinned whatever it
   * says — so this is the one place the export can say more than the canvas shows, and it says
   * exactly what the catalog promised.
   */
  private class FactoryMembers(
    val typeFqn: String,
    val optIns: List<String>,
    val members: Map<String, String>,
  )

  private val FACTORY_MEMBERS: Map<Pair<String, String>, FactoryMembers> =
    mapOf(
      (TOP_APP_BAR to "scrollBehavior") to
        FactoryMembers(
          typeFqn = "androidx.compose.material3.TopAppBarScrollBehavior",
          optIns = listOf(EXPERIMENTAL_MATERIAL3),
          members =
            mapOf(
              "pinned" to "$TOP_APP_BAR_DEFAULTS.pinnedScrollBehavior",
              "enterAlways" to "$TOP_APP_BAR_DEFAULTS.enterAlwaysScrollBehavior",
              "exitUntilCollapsed" to "$TOP_APP_BAR_DEFAULTS.exitUntilCollapsedScrollBehavior",
            ),
        )
    )

  /**
   * Colour roles that fill one `…Colors` bundle between them, per component.
   *
   * [TargetKind.CARD_COLORS] and [TargetKind.BUTTON_COLORS] are this shape for one role each. A top
   * app bar carries two — `containerColor` and `scrolledContainerColor` — and they are the same
   * `colors` argument, so they have to be read together or the second overwrites the first.
   *
   * @property roles the catalog properties, which are also the factory's parameter names.
   */
  private class ColorBundle(
    val parameter: String,
    val factoryFqn: String,
    val typeFqn: String,
    val roles: List<String>,
    val optIns: List<String>,
  )

  private val COLOR_BUNDLES: Map<String, ColorBundle> =
    mapOf(
      TOP_APP_BAR to
        ColorBundle(
          parameter = "colors",
          factoryFqn = "$TOP_APP_BAR_DEFAULTS.centerAlignedTopAppBarColors",
          typeFqn = "androidx.compose.material3.TopAppBarColors",
          roles = listOf("containerColor", "scrolledContainerColor"),
          optIns = listOf(EXPERIMENTAL_MATERIAL3),
        )
    )

  /**
   * The components whose `alignment` property is "how a parent Box aligns this" node — the
   * catalog's words on `layout/column`, and `m3/text` declares the same nine values. A modifier in
   * a property's clothing, routed through `alignLink` like the authored one.
   */
  private val BOX_ALIGNED: Set<String> = setOf("m3/text", "layout/column")

  private const val BOX_ALIGNMENT = "alignment"

  private const val ARRANGEMENT = "androidx.compose.foundation.layout.Arrangement"
  private const val EXPERIMENTAL_MATERIAL3 = "androidx.compose.material3.ExperimentalMaterial3Api"
  private const val TOP_APP_BAR = "m3/center-aligned-top-app-bar"
  private const val TOP_APP_BAR_DEFAULTS = "androidx.compose.material3.TopAppBarDefaults"
  private const val LIST_ITEM = "m3/list-item"
  private const val START_ACCENT_COLOR = "startAccentColor"
  private const val SLIDER = "m3/slider"

  /** A slider's two range bounds, with the value each has when Material is left to default it. */
  private val SLIDER_BOUNDS: Map<String, Double> = mapOf("valueFrom" to 0.0, "valueTo" to 1.0)

  private const val COLOUR_DOT = "shape/colour-dot"
  private const val DIAMETER_DP = "diameterDp"
  private const val DOT_COLOR = "color"

  /** What the canvas draws when a dot sets no diameter, and so what the export writes. */
  private const val DEFAULT_DOT_DIAMETER = 8.0

  private const val REMEMBER_SCROLL_STATE = "androidx.compose.foundation.rememberScrollState"
  private const val SCROLL_STATE = "androidx.compose.foundation.ScrollState"

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
    /**
     * The [ParameterTarget] **this** component takes for a property, where it differs from the one
     * the catalog id's own table names.
     *
     * [defaults] already made a *factory* vary with the variant — that is how `m3/card` picks
     * `elevatedCardColors` over `cardColors` — and for three of `m3/button`'s four values that is
     * the whole difference: the same `colors` parameter, a different `ButtonDefaults` function.
     *
     * `fab` is the one it could not express. `FloatingActionButton` takes `containerColor: Color`
     * directly, so the property lands on a **different parameter** holding a **different shape** —
     * a bare `Color` rather than a `ButtonColors` bundle — and no choice of factory says that. An
     * entry here replaces the table's target outright, which is why the value is a whole
     * [ParameterTarget] rather than a parameter name
     * ([#393](https://github.com/yschimke/compose-preview-server/issues/393)).
     */
    val propertyTargets: Map<String, ParameterTarget> = emptyMap(),
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
          // And the one property target that does not follow the others: a bare `Color` on
          // `containerColor`, where the other three build a `ButtonColors` bundle for `colors`.
          "fab" to
            ComponentVariant(
              FAB_ID,
              "floatingActionButton",
              propertyTargets =
                mapOf("containerColor" to ParameterTarget("containerColor", TargetKind.RENAME)),
            ),
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
      // The two indicators. Each name is TWO Compose overloads — a determinate one taking
      // `progress: () -> Float` and an indeterminate one whose parameters all default — and the
      // argument list picks between them, so both live under one record and one entry here. Which
      // one a design gets is decided by whether it sets `progress`; see `determinacy`.
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

  /**
   * Properties that name a node's **identity to the builder**, not a value in the design.
   *
   * `scrollStateKey` says which scroll position the canvas restores when it re-renders a design —
   * the catalog's own note is "independent pane scroll requires distinct stable scrollStateKey
   * values" — and `stableKey` is the same idea for a child in a list. Neither is a parameter of
   * anything, and neither describes what the screen looks like.
   *
   * Spent rather than refused, which is the one place this file drops something on purpose, so the
   * reason is written here. Refusing would be the safer reflex and the wrong answer:
   * `scrollStateKey` is **required** on `layout/lazy-column` and `layout/lazy-grid`, so every real
   * lazy container carries one and a refusal would make covering them worth nothing. Dropping is
   * safe only because what is lost is not in the design: `CapabilityComposeCodeExporter` spent
   * these on a `key("…") { }` wrapper around the node, which changes recomposition identity and
   * changes no pixel.
   *
   * Keyed by property name rather than by component, because the meaning does not vary: the two are
   * catalog-wide bookkeeping wherever they appear. That reaches one **already covered** id,
   * `layout/scaffold`, which declares `scrollStateKey` too — deliberately. Before this it refused
   * as "`Scaffold` has no parameter `scrollStateKey`", so a scaffold carrying one could not export
   * at all; the widening fixes that rather than causing it.
   *
   * That argument is exactly why `span` is **not** here — see `unplaceable`. It reads like another
   * bookkeeping string and is a layout instruction, and dropping it would export a full-width row
   * as one cell.
   */
  private val IDENTITY_PROPERTIES: Set<String> = setOf("scrollStateKey", "stableKey")

  private const val SPAN = "span"

  private const val BUTTON_DEFAULTS = "androidx.compose.material3.ButtonDefaults"

  private const val PROGRESS_INDICATOR = "m3/progress-indicator"
  private const val PROGRESS = "progress"
  private const val INDETERMINATE = "indeterminate"
  private const val WEIGHT = "weight"
  private const val ROW_SCOPE = "androidx.compose.foundation.layout.RowScope"
  /** The card whose content is a box (see `cardContentBox`), and the box it becomes. */
  private const val CARD_CATALOG_ID = "m3/card"
  private const val CARD_CONTENT_SLOT = "content"
  private const val BOX_CATALOG_ID = "layout/box"
  private const val BOX_CHILDREN_SLOT = "children"

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
  /**
   * The receiver member each of a DSL slot's children is wrapped in.
   *
   * The sixth authored table, and the one that made a list exportable at all. A lazy container's
   * `content` is not a composable slot: `LazyColumn` takes a `LazyListScope.() -> Unit`, and its
   * children are **declared** with `item { … }` rather than composed into it. Emitting them
   * directly produces `LazyColumn(content = { Text(…) })`, which satisfies the lambda's type and
   * does not compile, because `Text` is not a member of `LazyListScope` — which is why all three
   * lazy ids had no component record at all rather than a wrong one (#394).
   *
   * `ScreenNode.slotItems` is the shape that expresses it, and `ScreenGenerator` **checks** the
   * scope named here against the record's own `TargetParameter.scopeDslReceiver` rather than
   * trusting it — the same bargain `ChainLink.receiverScopeFqn` makes one level in. So a wrong
   * entry here is a refusal naming both scopes, not a file that fails to compile in someone else's
   * project.
   *
   * Keyed by the catalog's slot name (`items`), like [SLOT_PARAMETERS], because that is what the
   * document holds.
   *
   * **No `key`, deliberately.** `CapabilityComposeCodeExporter` emits `item(key = "…")` from each
   * *child's* `stableKey`, and a [SlotItem] is one wrapper for the whole slot — so a per-child key
   * is not expressible here. An unkeyed `item` is correct Kotlin and correct layout; what it costs
   * is list-item identity across a reorder, which a generated static screen does not do. Stated
   * rather than discovered, and the narrowing worth revisiting first if `slotItems` ever becomes
   * per-child.
   *
   * Public for the same reason [SLOT_SCOPES] is: `M3CatalogSlotScopeTest` walks it against the
   * shipped record so the two halves of one claim cannot drift apart silently.
   */
  val SLOT_ITEMS: Map<String, Map<String, SlotItem>> =
    mapOf(
      "layout/lazy-column" to mapOf("items" to SlotItem("item", LAZY_LIST_SCOPE)),
      "layout/lazy-row" to mapOf("items" to SlotItem("item", LAZY_LIST_SCOPE)),
      "layout/lazy-grid" to mapOf("items" to SlotItem("item", LAZY_GRID_SCOPE)),
    )

  private const val LAZY_LIST_SCOPE = "androidx.compose.foundation.lazy.LazyListScope"
  private const val LAZY_GRID_SCOPE = "androidx.compose.foundation.lazy.grid.LazyGridScope"

  val SLOT_SCOPES: Map<String, Map<String, String>> =
    mapOf(
      "layout/column" to mapOf("children" to COLUMN_SCOPE),
      "layout/row" to mapOf("children" to ROW_SCOPE),
      "layout/box" to mapOf("children" to BOX_SCOPE),
      // A colour dot is `Box` by alias (see `colourDot`), so the record attests `Box`'s
      // `content` slot for it too. Keyed by the parameter name because the catalog gives the dot
      // no slot at all — nothing ever fills this, and the claim exists so the drift check that
      // compares this table with the record stays exact.
      COLOUR_DOT to mapOf("content" to BOX_SCOPE),
      // `Card`'s own slot, which the record attests as a `ColumnScope` — and what sits in it is
      // the one `layout/box` `cardContentBox` emits, never a design's node. A card's children are
      // composed inside that box, under `BoxScope`, which is why this row is not what they get.
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
   * Properties whose values do not name a member of anything, and **why**, per entry.
   *
   * A set with one shared sentence was enough while every entry was a card: `m3/card`'s `variant`
   * really did spell three Compose components as one id, and the refusal said so. Both entries left
   * moved on from that and the sentence did not, so it went on telling an operator the catalog
   * spells three components as one id about a property where that is simply untrue
   * ([compose-preview-server#394](https://github.com/yschimke/compose-preview-server/issues/394)).
   *
   * The two remaining are not even the same kind of wrong as each other — one names a **mode** of a
   * single adaptive component and the other names **which** carousel to call — which is why the
   * reason is authored per entry rather than shared. A refusal an operator cannot act on is worth
   * about as much as no refusal, and one that describes a different component is worth less.
   *
   * [COMPONENT_VARIANTS] is where "picks a component" became expressible, and an id that goes
   * through it leaves this table — `m3/card`, `m3/button`, `m3/text-field` and
   * `m3/progress-indicator` all have.
   *
   * Each value completes "…`$entry`, which …".
   */
  private val VARIANT_PROPERTIES: Map<Pair<String, String>, String> =
    mapOf(
      ("layout/supporting-pane-scaffold" to "layoutMode") to
        "names a layout mode of one adaptive component rather than a value: " +
          "`SupportingPaneScaffold` decides how many panes to show from a scaffold directive and " +
          "the window it is measured in, so there is no parameter for a mode to be written to",
      ("layout/horizontal-carousel" to "kind") to
        "names which carousel function to call rather than an argument to one, and no record " +
          "selects a carousel yet: its `items` is a `CarouselScope` DSL, which is a slot shape " +
          "this projection cannot emit",
    )
}
