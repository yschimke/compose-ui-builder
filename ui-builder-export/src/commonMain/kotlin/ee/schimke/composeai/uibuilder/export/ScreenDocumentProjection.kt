package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenAction
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenState
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.discovery.SlotItem
import ee.schimke.composeai.uibuilder.protocol.AdaptiveGridValueV1
import ee.schimke.composeai.uibuilder.protocol.AlignHorizontalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignVerticalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlignmentV1
import ee.schimke.composeai.uibuilder.protocol.AlphaModifierV1
import ee.schimke.composeai.uibuilder.protocol.AspectRatioModifierV1
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BindingValueV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.BorderModifierV1
import ee.schimke.composeai.uibuilder.protocol.ClipModifierV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DimensionUnitV1
import ee.schimke.composeai.uibuilder.protocol.DimensionValueV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExpressionValueV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxHeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightInModifierV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.HorizontalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.IncrementActionV1
import ee.schimke.composeai.uibuilder.protocol.InsetsValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.ListValueV1
import ee.schimke.composeai.uibuilder.protocol.MatchParentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.ObjectValueV1
import ee.schimke.composeai.uibuilder.protocol.OffsetModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.PaddingValueV1
import ee.schimke.composeai.uibuilder.protocol.RemoteCallModifierV1
import ee.schimke.composeai.uibuilder.protocol.ResourceValueV1
import ee.schimke.composeai.uibuilder.protocol.RotateModifierV1
import ee.schimke.composeai.uibuilder.protocol.ScaleModifierV1
import ee.schimke.composeai.uibuilder.protocol.SelectActionV1
import ee.schimke.composeai.uibuilder.protocol.SetTextActionV1
import ee.schimke.composeai.uibuilder.protocol.SetValueActionV1
import ee.schimke.composeai.uibuilder.protocol.ShadowModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StateEqualsValueV1
import ee.schimke.composeai.uibuilder.protocol.StateValueTypeV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StateVariableV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.SystemValueV1
import ee.schimke.composeai.uibuilder.protocol.TestTagModifierV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.ToggleActionV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.VerticalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.VerticalScrollModifierV1
import ee.schimke.composeai.uibuilder.protocol.WeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthInModifierV1
import ee.schimke.composeai.uibuilder.protocol.WidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.WrapContentSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.ZIndexModifierV1
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull

/**
 * The Kotlin parameter a catalog slot fills, where the two are spelled differently
 * (`layout/column`'s `children` is `Column(content = …)`).
 *
 * Authored here rather than by renaming the record's parameter, which would make the record lie
 * about the signature it attests. Unlisted slots pass through unchanged.
 */
private val SLOT_PARAMETERS: Map<String, Map<String, String>> =
  mapOf(
    "layout/box" to mapOf("children" to "content"),
    "layout/column" to mapOf("children" to "content"),
    "layout/row" to mapOf("children" to "content"),
    // A flow row's slot is `content` like every other layout primitive's. Named here because
    // without it the generator is handed the catalog's `children` against `FlowRow`'s signature
    // and refuses with "`FlowRow` has no slot `children`" — a true statement about a component
    // that is perfectly writable.
    "layout/flow-row" to mapOf("children" to "content"),
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
    // What shows once the bar expands is `SearchBar`'s trailing `content` lambda.
    "m3/search-bar" to mapOf("expandedContent" to "content"),
    // The navigation suite's floating action slot. `navigationItems` and `content` already match.
    "m3/navigation-suite-scaffold" to mapOf("primaryAction" to "primaryActionContent"),
  )

private fun parameterForSlot(componentId: String, slot: String): String =
  SLOT_PARAMETERS[componentId]?.get(slot) ?: slot

/**
 * Projects a saved [DesignDocumentV1] onto the [ScreenDocument] `ScreenGenerator` consumes.
 *
 * The generator only emits call sites the component record proves, so this projection must express
 * each piece of content or refuse it by name — never guess. Refused today, each under its own
 * reason: computed state beyond reads and scalar declarations, events beyond assignments,
 * conditional nodes, assets, insets, accessibility semantics, and enum values no table names.
 *
 * Design tokens resolve through a table of Material 3's own accessors below; this is the layer that
 * knows the catalog is Material 3. Enum values are mapped by authored tables ([ENUM_MEMBERS],
 * [ICON_MEMBERS]) because wire values are lower-camel and many are not members of the parameter's
 * type; [VARIANT_PROPERTIES] carries the reasons for the rest.
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
      /** Whether the root is wrapped in the design's theme; see [ScreenTheme]. */
      val themed: Boolean = false,
      /**
       * Every typeface family written as a desktop `SystemFont` lookup, in the order the theme
       * names them — empty unless the projection was asked for [TypefaceTarget.DESKTOP] and the
       * theme names one. Not a refusal: the caller reports each with [SystemFontLookups], because a
       * desktop render draws the family only where it is installed.
       */
      val systemFontFamilies: List<String> = emptyList(),
      /**
       * The declarations the screen's variable font texts call, one per distinct text: generated by
       * flexpress at export and joined into the screen's file. See [VariableFontText].
       */
      val variableFontTexts: List<VariableFontText.Request> = emptyList(),
    ) : Outcome {
      /**
       * [record] with what this projection's own calls resolve through: the `MaterialTheme` a
       * themed root is wrapped in, which no catalog records.
       *
       * Added only to a themed screen. The generator reserves the name of every component the
       * record holds, so a record carrying `MaterialTheme` refuses any screen that reads
       * `MaterialTheme.typography` — which an unthemed screen does for every text style.
       */
      fun resolvable(record: ComponentRecordFile): ComponentRecordFile =
        VariableFontTextRecord.with(
          if (themed) ScreenTheme.withThemeRecord(record) else record,
          variableFontTexts,
        )
    }

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
    /**
     * Which font API a theme's typefaces are written with: [TypefaceTarget.ANDROID]'s `GoogleFont`
     * by default, or [TypefaceTarget.DESKTOP]'s `SystemFont` for a file compiled against a Compose
     * Multiplatform Desktop classpath, which has no `GoogleFont`. See [TypefaceTarget].
     */
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
  ): Outcome =
    projectInternal(document, screenName, tagNodes, false, emptyMap(), typefaces = typefaces)

  /**
   * Opt-in projection for build generation, with declared input reads and checked component calls.
   */
  fun projectProduction(
    document: DesignDocumentV1,
    nodeOverrides: Map<String, ScreenNode> = emptyMap(),
  ): Outcome = projectProduction(document, nodeOverrides, emptyMap())

  /** Production-only callback parameters, checked against the catalog by the shared generator. */
  fun projectProduction(
    document: DesignDocumentV1,
    nodeOverrides: Map<String, ScreenNode>,
    callbackArguments: Map<String, Map<String, ScreenValue>>,
  ): Outcome =
    projectInternal(
      document,
      screenNameFor(document),
      false,
      true,
      nodeOverrides,
      callbackArguments,
    )

  private fun projectInternal(
    document: DesignDocumentV1,
    screenName: String,
    tagNodes: Boolean,
    rootBindings: Boolean,
    nodeOverrides: Map<String, ScreenNode>,
    callbackArguments: Map<String, Map<String, ScreenValue>> = emptyMap(),
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
  ): Outcome {
    // No component record parameter. It was here only so an enum value could be qualified with
    // its parameter's recorded type, and `enum` refuses instead — see its KDoc. A parameter kept
    // "in case" is how a reader starts believing this projection type-checks against the record,
    // which it does not: `ScreenGenerator` does that, once, with the record it is handed.
    val pass = Pass(document, tagNodes, rootBindings, nodeOverrides, callbackArguments, typefaces)
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
    // The theme host the canvas draws the design under: the first surface among the top-level
    // nodes — the root, or the items of the board an Add beside wrapped it in (`topLevelNodes` in
    // `UiBuilderBoard`). One level, as there. The theme still wraps the whole root, because the
    // canvas draws every item of the board under it.
    val rootNode = document.nodes[roots.single()]
    val topLevel =
      if (rootNode?.componentId == BOARD_CATALOG_ID)
        rootNode.slots[BOARD_SLOT].orEmpty().mapNotNull(document.nodes::get)
      else listOfNotNull(rootNode)
    val theme =
      topLevel
        .firstOrNull { it.componentId == SURFACE_CATALOG_ID }
        ?.let { ScreenTheme.of(it, dark = document.environment.theme == ThemeV1.DARK) }
    if (theme != null && document.environment.theme == ThemeV1.SYSTEM) {
      // The canvas picks the baseline scheme with `isSystemInDarkTheme()`, and the generated screen
      // has no way to make that choice: a value here is a call, never a branch. Writing either one
      // would change every role the theme leaves unset whenever the viewer's mode is the other.
      pass.reasons +=
        "the design sets a theme on its surface and its environment theme is `system`, which " +
          "the export cannot follow at runtime; set the environment theme to light or dark"
    }
    pass.themeParameters = theme != null
    val root =
      pass.node(roots.single())?.let { content ->
        if (theme == null) content else pass.themed(content, theme, screenName)
      }
    val projected = root?.let {
      pass.finish(ScreenDocument(screenName, it, pass.state.values.toList()))
    }
    if (pass.reasons.isNotEmpty()) return Outcome.Refused(pass.reasons.distinct())
    // Components are not behind this flag: a placement exports as a call to a private composable
    // of the same file, which is ordinary Compose with no runtime of its own — the reusable part
    // of a design that local components exist to give somebody.
    if (!UiBuilderBuildFeatures.remoteCompose) {
      document.nodes.values
        .filter {
          SHOW_BY_STATE in it.properties ||
            it.componentId == "layout/for-each" ||
            it.eventBindings.isNotEmpty() ||
            it.properties.values.any { value ->
              value is StateValueV1 || value is StateEqualsValueV1
            }
        }
        .forEach { pass.reasons += "node `${it.id}`: stateful authoring is disabled in this build" }
    }
    if (pass.reasons.isNotEmpty()) return Outcome.Refused(pass.reasons.distinct())
    return Outcome.Projected(
      checkNotNull(projected),
      assetPlaceholders = pass.assetPlaceholders.toList(),
      themed = theme != null,
      systemFontFamilies = pass.systemFontFamilies.toList(),
      variableFontTexts = VariableFontText.requests(pass.variableFontSpecs),
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

  private class Pass(
    val document: DesignDocumentV1,
    val tagNodes: Boolean = false,
    rootBindings: Boolean = false,
    val nodeOverrides: Map<String, ScreenNode> = emptyMap(),
    val productionCallbacks: Map<String, Map<String, ScreenValue>> = emptyMap(),
    val typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
  ) {
    val reasons = mutableListOf<String>()
    val assetPlaceholders = mutableListOf<AssetPlaceholder>()
    /** The families written as `SystemFont` lookups; see [Outcome.Projected.systemFontFamilies]. */
    val systemFontFamilies = linkedSetOf<String>()
    /** The variable font texts reached, in order; see [Outcome.Projected.variableFontTexts]. */
    val variableFontSpecs = mutableListOf<VariableFontText.Spec>()
    val state: Map<String, ScreenState> =
      document.stateVariables
        .mapNotNull { (name, declaration) ->
          val type = stateType(declaration)
          val initial = stateLiteral(declaration.initialValue, "state `$name` initial value", type)
          if (type == null) refuse("state `$name` has no supported value type")
          if (initial == null || type == null) null
          else name to ScreenState(exportedStateIdentifier(name), type, initial)
        }
        .toMap()

    private fun stateType(declaration: StateVariableV1): String? {
      val initial = declaration.initialValue as? JsonPrimitive
      val type =
        when (declaration.valueType) {
          StateValueTypeV1.BOOLEAN -> "kotlin.Boolean"
          StateValueTypeV1.INTEGER -> "kotlin.Int"
          StateValueTypeV1.DECIMAL -> "kotlin.Float"
          StateValueTypeV1.STRING -> "kotlin.String"
          null ->
            when {
              initial == null || initial is JsonNull -> null
              initial.isString -> "kotlin.String"
              initial.booleanOrNull != null -> "kotlin.Boolean"
              initial.longOrNull != null -> "kotlin.Int"
              initial.doubleOrNull != null -> "kotlin.Float"
              else -> null
            }
        } ?: return null
      return type + if (declaration.nullable == true) "?" else ""
    }

    private fun stateLiteral(
      value: JsonElement?,
      where: String,
      type: String? = null,
    ): ScreenValue? {
      val primitive = value as? JsonPrimitive
      return when {
        primitive == null || primitive is JsonNull ->
          refuse("$where is null or structured; the shared generator needs a typed value")
        primitive.isString -> ScreenValue.Text(primitive.content)
        primitive.booleanOrNull != null -> ScreenValue.Bool(primitive.booleanOrNull!!)
        type?.removeSuffix("?") == "kotlin.Float" && primitive.doubleOrNull?.isFinite() == true ->
          ScreenValue.Fractional(primitive.doubleOrNull!!)
        primitive.longOrNull != null -> ScreenValue.Whole(primitive.longOrNull!!)
        primitive.doubleOrNull?.isFinite() == true ->
          ScreenValue.Fractional(primitive.doubleOrNull!!)
        else -> refuse("$where is not a finite scalar")
      }
    }

    private fun stateRead(variable: String, where: String): ScreenValue? {
      val declared =
        state[variable] ?: return refuse("$where reads undeclared state variable `$variable`")
      functionScope?.let { function ->
        return function.capture("state:$variable", declared.typeFqn) { stateRead(variable, where) }
      }
      return ScreenValue.StateRead(declared.name, declared.typeFqn)
    }

    private fun handlers(node: DesignNodeV1): Map<String, List<ScreenAction>> =
      node.eventBindings.entries
        .mapNotNull { (event, actions) ->
          if (actions.isEmpty()) return@mapNotNull null
          if (functionScope != null) return@mapNotNull null
          if (event == "click" && node.componentId in MODIFIER_CLICK_COMPONENTS) {
            return@mapNotNull null
          }
          // The generator checks the recovered parameter's type. Unknown events and callbacks with
          // parameters stay explicit refusals; a composable content slot can never become a
          // handler.
          val parameter = "on" + event.replaceFirstChar { it.uppercaseChar() }
          val projected = actions.mapNotNull { action -> action(action, node.id, event) }
          parameter to projected
        }
        .toMap()

    private fun modifierClick(node: DesignNodeV1): ChainLink? {
      if (node.componentId !in MODIFIER_CLICK_COMPONENTS) return null
      val actions = node.eventBindings["click"].orEmpty()
      if (actions.isEmpty()) return null
      val callback = callback(node, "click") ?: return null
      return ChainLink(
        "androidx.compose.foundation.clickable",
        named = mapOf("onClick" to callback),
      )
    }

    private fun callback(node: DesignNodeV1, event: String): ScreenValue? {
      functionScope?.let { function ->
        return function.capture("event:${node.id}:$event", null) { callback(node, event) }
      }
      val projected = node.eventBindings[event].orEmpty().mapNotNull { action(it, node.id, event) }
      val json = Json
      // Strict decoding keeps the released generator buildable and refuses the new vocabulary
      // there. Local generator publications can prove this path before an upstream release.
      return try {
        json.decodeFromJsonElement<ScreenValue>(
          buildJsonObject {
            put("type", "ee.schimke.composeai.discovery.ScreenValue.ActionLambda")
            put("actions", json.encodeToJsonElement(projected))
          }
        )
      } catch (_: kotlinx.serialization.SerializationException) {
        return refuse(
          "node `${node.id}`: layout clicks need action-lambda support in the shared generator; use a local dependency build or a release containing it"
        )
      }
    }

    private fun action(action: DesignActionV1, nodeId: String, event: String): ScreenAction? {
      val where = "node `$nodeId`.`eventBindings.$event`"
      fun target(variable: String): String? =
        state[variable]?.name
          ?: run {
            refuse("$where writes undeclared state variable `$variable`")
            null
          }
      fun assignment(variable: String, value: JsonElement): ScreenAction? {
        val name = target(variable) ?: return null
        val literal =
          stateLiteral(value, "$where assignment", state.getValue(variable).typeFqn) ?: return null
        return ScreenAction.Set(name, literal)
      }
      return when (action) {
        is ToggleActionV1 -> target(action.variable)?.let(ScreenAction::Toggle)
        // The shared generator writes a handler's `Set` only from a literal: `count.plus(1)` is
        // refused there as "an expression that names an API", since a callback is not a
        // composable scope. Counting needs its own shared action lowering first.
        is IncrementActionV1 ->
          refuse(
            "$where increments `${action.variable}`, which needs a shared increment lowering in " +
              "the screen generator; the Remote Compose export writes it today"
          )
        is SetValueActionV1 -> assignment(action.variable, action.value)
        is SelectActionV1 -> assignment(action.variable, action.value)
        is SetTextActionV1 ->
          refuse(
            "$where consumes the callback's text value, which needs a parameter-aware shared handler"
          )
        else -> {
          refuse("$where uses `${action::class.simpleName}`, which needs a shared action lowering")
          null
        }
      }
    }

    private val visiting = mutableSetOf<String>()

    /**
     * `preferredWidth` links a pane scaffold hands to the node in each pane, keyed by that node.
     *
     * The width is authored on the scaffold (`mainPanePreferredWidthDp`) and applied by the pane's
     * content (`Modifier.preferredWidth` is `PaneScaffoldScope`'s, parent data the scaffold's
     * measure policy reads), so it crosses from one node to the other here — set when the
     * scaffold's slots are visited, spent when the child's own modifier chain is built.
     */
    private val paneWidths = mutableMapOf<String, ChainLink>()

    /**
     * `Modifier.padding(contentPadding)` for each child of a `Scaffold`'s `content`, led with as
     * the canvas does so bodies sit below the bars. Set when the `content` slot is visited and
     * spent when the child's chain is built; children that build no chain drop it unpadded.
     */
    private val scaffoldPaddings = mutableMapOf<String, ChainLink>()

    private class BoundField(
      val name: String,
      val type: String,
      val convert: (UiValueV1) -> ScreenValue?,
    )

    private class BindingScope(val row: Boolean) {
      val fields = linkedMapOf<String, BoundField>()
    }

    private var bindingScope: BindingScope? = if (rootBindings) BindingScope(row = false) else null
    private var functionScope: FunctionBody? = null

    /**
     * Whether the root is being projected inside the theme function [themed] writes, where theme
     * roles read that function's parameters rather than `MaterialTheme`. See [pathValue].
     */
    var themeParameters = false

    /** The functions [themed] writes around the root, outermost first. */
    private val themeFunctions = mutableListOf<JsonObject>()
    private val functions = linkedMapOf<String, FunctionBody>()
    private val defining = mutableSetOf<String>()

    private inner class FunctionBody(val name: String) {
      val bindings = BindingScope(row = false)
      val captures = linkedMapOf<String, Pair<JsonObject, () -> ScreenValue?>>()
      var root: ScreenNode? = null

      fun capture(key: String, type: String?, supply: () -> ScreenValue?): ScreenValue? {
        val parameter =
          captures.getOrPut(key) { parameter("capture${captures.size}", type) to supply }.first
        return scopedRead(
          "ParameterRead",
          "parameter",
          parameter.getValue("name").jsonPrimitive.content,
          type ?: "kotlin.Function0",
        )
      }

      fun encode(): JsonObject = buildJsonObject {
        put("name", name)
        put(
          "parameters",
          JsonArray(
            listOf(parameter("modifier", MODIFIER)) +
              bindings.fields.values.map { parameter(it.name, it.type) } +
              captures.values.map { it.first }
          ),
        )
        put("root", Json.encodeToJsonElement(checkNotNull(root)))
      }
    }

    private fun parameter(name: String, type: String?): JsonObject = buildJsonObject {
      put(
        "type",
        "ee.schimke.composeai.discovery.ScreenParameter.${if (type == null) "Callback" else "Value"}",
      )
      put("name", name)
      type?.let { put("typeFqn", it) }
    }

    // The additive shapes are decoded strictly: released generators refuse them while explicitly
    // staged local publications can execute them. No generated source is assembled in this bridge.
    private inline fun <reified T> shared(value: JsonElement, feature: String): T? =
      try {
        Json.decodeFromJsonElement<T>(value)
      } catch (_: kotlinx.serialization.SerializationException) {
        refuse(
          "$feature needs shared generator support; use a local dependency build or a release containing it"
        )
      }

    fun finish(screen: ScreenDocument): ScreenDocument? {
      if (reasons.isNotEmpty()) return null
      if (functions.isEmpty() && themeFunctions.isEmpty()) return screen
      return shared(
        JsonObject(
          Json.encodeToJsonElement(screen).jsonObject +
            ("functions" to JsonArray(themeFunctions + functions.values.map { it.encode() }))
        ),
        if (functions.isEmpty()) "the design's theme" else "reusable components",
      )
    }

    private fun scopedRead(kind: String, key: String, name: String, type: String): ScreenValue? =
      shared(
        buildJsonObject {
          put("type", "ee.schimke.composeai.discovery.ScreenValue.$kind")
          put(key, name)
          put("typeFqn", type)
        },
        "scoped $kind",
      )

    private fun binding(
      value: BindingValueV1,
      type: String,
      where: String,
      convert: (UiValueV1) -> ScreenValue?,
    ): ScreenValue? {
      val scope =
        bindingScope ?: return refuse("$where reads `${value.value}` outside a component or loop")
      val field =
        scope.fields.getOrPut(value.value) {
          BoundField(parameterName(scope, value.value), type, convert)
        }
      if (field.type != type)
        return refuse("$where: binding `${value.value}` is used as both ${field.type} and $type")
      return if (scope.row) scopedRead("RowRead", "field", value.value, type)
      else scopedRead("ParameterRead", "parameter", field.name, type)
    }

    /**
     * What a component's parameter is called: the key its body reads, when that is a plain Kotlin
     * name nothing else in the function claims — so `InboxEmail(sender = …, subject = …)` reads as
     * somebody would have written it. Anything else (a keyword, `modifier`, a name the generated
     * `argumentN`/`captureN` series could also produce, one already taken) keeps the opaque
     * positional name, which cannot collide. A loop's row fields are read by key and never named
     * here.
     */
    private fun parameterName(scope: BindingScope, key: String): String {
      val positional = "argument${scope.fields.size}"
      if (scope.row) return positional
      val plain =
        key.isNotEmpty() &&
          key.first().isLowerCase() &&
          key.all { it.isLetterOrDigit() } &&
          key !in KOTLIN_HARD_KEYWORDS &&
          key != "modifier" &&
          !Regex("(argument|capture)\\d+").matches(key)
      return if (plain && scope.fields.values.none { it.name == key }) key else positional
    }

    private fun supplied(value: UiValueV1, field: BoundField, where: String): ScreenValue? =
      if (value is BindingValueV1) binding(value, field.type, where, field.convert)
      else field.convert(value)

    private fun callbackArguments(node: DesignNodeV1): Map<String, ScreenValue> {
      productionCallbacks[node.id]?.let {
        return it
      }
      if (functionScope == null) return emptyMap()
      return node.eventBindings
        .filterValues { it.isNotEmpty() }
        .mapNotNull { (event, _) ->
          if (event == "click" && node.componentId in MODIFIER_CLICK_COMPONENTS) null
          else
            callback(node, event)?.let {
              ("on" + event.replaceFirstChar { it.uppercaseChar() }) to it
            }
        }
        .toMap()
    }

    private fun placementNode(placement: DesignNodeV1, scope: String?): ScreenNode? {
      val instance = checkNotNull(placement.component)
      val key = instance.componentKey
      if (
        placement.componentId != "design/component-instance" ||
          placement.properties.isNotEmpty() ||
          placement.slots.isNotEmpty()
      )
        return refuse(
          "node `${placement.id}`: component placement needs design/component-instance with no properties or slots"
        )
      val definition =
        document.components[key]
          ?: return refuse("node `${placement.id}`: unknown component `$key`")
      if (key in defining) return refuse("node `${placement.id}`: recursive component `$key`")
      val function =
        functions[key]
          ?: run {
            // The authored composable name is preserved; the generator validates identifiers and
            // collisions against state, imports and other definitions before emitting any source.
            val body = FunctionBody(definition.name)
            functions[key] = body
            defining += key
            val previousBindings = bindingScope
            val previousFunction = functionScope
            bindingScope = body.bindings
            functionScope = body
            try {
              // The canvas gives each placement a Box. Its modifiers belong at the call site; the
              // definition's root receives BoxScope here and cannot inherit the caller's receiver.
              val modifier =
                scopedRead("ParameterRead", "parameter", "modifier", MODIFIER) ?: return null
              body.root =
                ScreenNode(
                  BOX_CATALOG_ID,
                  arguments = mapOf("modifier" to modifier),
                  slots = mapOf("content" to listOfNotNull(node(definition.root, BOX_SCOPE))),
                )
            } finally {
              bindingScope = previousBindings
              functionScope = previousFunction
              defining -= key
            }
            body
          }
      val arguments = linkedMapOf<String, ScreenValue>()
      (instance.arguments.keys - function.bindings.fields.keys).forEach {
        refuse("node `${placement.id}`: component `$key` has no argument `$it`")
      }
      function.bindings.fields.forEach { (key, field) ->
        val raw = instance.arguments[key]
        if (raw == null) refuse("node `${placement.id}`: missing component argument `$key`")
        else
          supplied(raw, field, "node `${placement.id}` argument `$key`")?.let {
            arguments[field.name] = it
          }
      }
      function.captures.values.forEach { (parameter, supply) ->
        supply()?.let { arguments[parameter.getValue("name").jsonPrimitive.content] = it }
      }
      val wrapper = placement.copy(componentId = BOX_CATALOG_ID, component = null)
      val wrapperArguments = arguments(wrapper, scope, null)
      if (
        wrapper.eventBindings.any { (event, actions) -> event != "click" && actions.isNotEmpty() }
      )
        refuse("node `${placement.id}`: only click events can be applied to a component placement")
      arguments["modifier"] =
        wrapperArguments["modifier"] ?: ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER)
      return shared<ScreenNode>(
        JsonObject(
          Json.encodeToJsonElement(ScreenNode("", arguments)).jsonObject +
            ("function" to JsonPrimitive(function.name))
        ),
        "node `${placement.id}` component call",
      )
    }

    private fun repetitionNode(loop: DesignNodeV1, scope: String?): ScreenNode? {
      val where = "node `${loop.id}`"
      loop.properties.keys
        .filter { it !in setOf("data", "verticalSpacingDp") }
        .forEach { refuse("$where: unsupported loop property `$it`") }
      val template = loop.slots["template"]?.singleOrNull()
      if (template == null || loop.slots.keys != setOf("template"))
        return refuse("$where: a loop needs exactly one template child")
      val data =
        loop.properties["data"] as? ListValueV1
          ?: return refuse("$where.data: expected an authored list of row objects")
      if (data.values.size > 10_000) return refuse("$where.data: exceeds 10000 rows")
      val rowScope = BindingScope(row = true)
      val previous = bindingScope
      bindingScope = rowScope
      val body =
        try {
          node(template, COLUMN_SCOPE)
        } finally {
          bindingScope = previous
        }
      // Fields read by the template provide types even for an empty list. Preserve unused authored
      // scalar fields too, without inventing a type from an absent first row.
      data.values.forEachIndexed { index, value ->
        val fields = (value as? ObjectValueV1)?.fields
        if (fields == null) refuse("$where.data[$index]: expected a row object")
        else
          fields.forEach { (key, raw) ->
            if (key !in rowScope.fields) {
              val type = scalarType(raw)
              if (type == null)
                refuse("$where.data[$index].$key: cannot infer an unused field's type")
              else
                rowScope.fields[key] =
                  BoundField(key, type) { scalar(it, type, "$where.data.$key") }
            }
          }
      }
      val rows =
        data.values.mapIndexed { index, value ->
          val fields = (value as? ObjectValueV1)?.fields.orEmpty()
          rowScope.fields
            .mapNotNull { (key, field) ->
              val raw = fields[key]
              if (raw == null) {
                refuse("$where.data[$index]: missing row field `$key`")
                null
              } else supplied(raw, field, "$where.data[$index].$key")?.let { key to it }
            }
            .toMap()
        }
      val repeated =
        shared<ScreenNode>(
          JsonObject(
            Json.encodeToJsonElement(ScreenNode("", slots = mapOf("body" to listOfNotNull(body))))
              .jsonObject +
              ("repetition" to
                buildJsonObject {
                  put(
                    "fields",
                    Json.encodeToJsonElement(rowScope.fields.mapValues { it.value.type }),
                  )
                  put("rows", Json.encodeToJsonElement(rows))
                })
          ),
          "$where repetition",
        ) ?: return null
      val wrapper =
        loop.copy(
          componentId = "layout/column",
          properties = loop.properties - "data",
          slots = emptyMap(),
        )
      return ScreenNode(
        "layout/column",
        arguments(wrapper, scope, null) + callbackArguments(wrapper),
        slots = mapOf("content" to listOf(repeated)),
        handlers = handlers(wrapper),
      )
    }

    private fun scalarType(value: UiValueV1): String? =
      when (value) {
        is StringValueV1 -> "kotlin.String"
        is BooleanValueV1 -> "kotlin.Boolean"
        is IntegerValueV1 -> "kotlin.Int"
        is DecimalValueV1 -> "kotlin.Float"
        is ColorValueV1,
        is ColorTokenValueV1 -> COLOR
        is StateValueV1 -> state[value.variable]?.typeFqn
        else -> null
      }

    private fun scalar(value: UiValueV1, type: String, where: String): ScreenValue? {
      if (value is StateValueV1) return stateRead(value.variable, where)
      return when (type) {
        "kotlin.String" -> (value as? StringValueV1)?.let { ScreenValue.Text(it.value) }
        "kotlin.Boolean" -> (value as? BooleanValueV1)?.let { ScreenValue.Bool(it.value) }
        "kotlin.Int" -> (value as? IntegerValueV1)?.let { ScreenValue.Whole(it.value) }
        "kotlin.Float" -> {
          val number =
            when (value) {
              is IntegerValueV1 -> value.value.toDouble()
              is DecimalValueV1 -> value.value
              else -> null
            }
          number
            ?.takeIf { it.toFloat().isFinite() && (it == 0.0 || it.toFloat() != 0f) }
            ?.let { ScreenValue.Fractional32(it.toFloat()) }
        }
        COLOR -> colour(value, where)
        else -> null
      } ?: refuse("$where: expected $type")
    }

    private fun propertyBinding(
      value: BindingValueV1,
      node: DesignNodeV1,
      property: String,
    ): ScreenValue? {
      val type =
        when {
          PropertyValueKinds.isColour(property) -> COLOR
          property in setOf("text", "contentDescription", "label", "placeholder", "value") ->
            "kotlin.String"
          property in
            setOf("enabled", "checked", "selected", "expanded", "singleLine", "softWrap") ->
            "kotlin.Boolean"
          property in setOf("maxLines", "minLines", "maxValue", "minValue") -> "kotlin.Int"
          property in setOf("progress", "fraction", "alpha") -> "kotlin.Float"
          else -> ENUM_MEMBERS[node.componentId]?.get(property)?.typeFqn
        } ?: return refuse("node `${node.id}`.$property: no binding type mapping")
      return binding(value, type, "node `${node.id}`.$property") { raw ->
        if (type.startsWith("kotlin.") || type == COLOR)
          scalar(raw, type, "node `${node.id}`.$property")
        else value(raw, node, property)
      }
    }

    private fun boundDp(value: BindingValueV1, where: String): ScreenValue? =
      binding(value, "kotlin.Float", where) { scalar(it, "kotlin.Float", where) }
        ?.let {
          ScreenValue.Chain(
            it,
            listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
            DP,
          )
        }

    /**
     * @param scope the receiver of the slot this node sits in, or null at the root.
     *
     * Threaded down rather than looked up, because a node has no parent pointer and the answer is
     * about placement rather than about the node. It is what `weight` and `matchParentSize` need:
     * both are declared on a slot's receiver, so whether either compiles is decided here and
     * nowhere else.
     */
    fun node(id: String, scope: String? = null): ScreenNode? {
      nodeOverrides[id]?.let {
        return it
      }
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
        if (visiting.size > 128) return refuse("node `$id`: nesting exceeds 128 levels")
        if (node.componentId == VariableFontText.M3_ID) return variableFontTextNode(node, scope)
        if (node.component != null) return placementNode(node, scope)
        if (node.componentId == "layout/for-each") return repetitionNode(node, scope)
        if (SHOW_BY_STATE in node.properties) return selectionNode(node, scope)
        val variant = variantOf(node)
        return ScreenNode(
          // A variant names a **component**, so it is spent here rather than emitted as an
          // argument: `m3/card` with `variant = elevated` is `ElevatedCard`, which is a different
          // callable with its own record. The catalog says as much itself — its `code.imports`
          // lists all three — and this is the projection acting on it.
          componentId = variant?.canonicalId ?: node.componentId,
          arguments = arguments(node, scope, variant) + callbackArguments(node),
          handlers = handlers(node),
          slots =
            node.slots.entries.associate { (slot, children) ->
              val childScope = slotScope(node.componentId, variant, slot)
              if (node.componentId in setOf(SUPPORTING_PANE_SCAFFOLD, LIST_DETAIL_PANE_SCAFFOLD)) {
                paneWidthLink(node, slot)?.let { link ->
                  children.forEach { paneWidths[it] = link }
                }
              }
              val padsContent = node.componentId == SCAFFOLD && slot == SCAFFOLD_CONTENT
              if (padsContent) children.forEach { scaffoldPaddings[it] = SCAFFOLD_PADDING_LINK }
              parameterForSlot(node.componentId, slot) to
                (if (node.componentId == CARD_CATALOG_ID && slot == CARD_CONTENT_SLOT)
                    listOf(cardContentBox(node, children))
                  else children.mapNotNull { child -> node(child, childScope) })
                  .also { if (padsContent) children.forEach { scaffoldPaddings.remove(it) } }
            },
          // The parameter those padding links read: `Scaffold(…) { contentPadding -> … }`.
          slotParameters =
            if (node.componentId == SCAFFOLD && !node.slots[SCAFFOLD_CONTENT].isNullOrEmpty())
              mapOf(SCAFFOLD_CONTENT to CONTENT_PADDING)
            else emptyMap(),
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
     * A variable font text: a call to the declaration flexpress generates for its font, text and
     * held axes (see [VariableFontTextRecord]), passing each animated axis as a `() -> Float` and
     * the size, colour and modifiers the design sets. An axis bound to state is animated and one
     * with a number is held; a computed one is the Remote catalog's, and text has to be literal,
     * because the outline is worked out from it ahead of time.
     */
    private fun variableFontTextNode(node: DesignNodeV1, scope: String?): ScreenNode? {
      val where = "node `${node.id}`"
      val fontName =
        when (val value = node.properties["font"]) {
          null -> null
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          else -> return refuse("$where.`font` names a font by a literal")
        }
      val font =
        if (fontName.isNullOrEmpty()) VariableFontText.Font.RobotoFlex
        else
          VariableFontText.Font.fromWire(fontName)
            ?: return refuse("$where.`font` is `$fontName`, which is not a font it draws in")
      val text =
        (node.properties["text"] as? StringValueV1)?.value
          ?: return refuse(
            "$where.`text` is not a literal, and a variable font text's outline is worked out " +
              "from its text when the design exports"
          )
      val animated = mutableListOf<String>()
      val location = mutableMapOf<String, Float>()
      val arguments = mutableMapOf<String, ScreenValue>()
      for (property in VariableFontText.AXIS_PROPERTIES) {
        val value = node.properties[property] ?: continue
        val tag = font.axis(property)?.tag ?: property
        when (value) {
          is DecimalValueV1 -> location[tag] = value.value.toFloat()
          is IntegerValueV1 -> location[tag] = value.value.toFloat()
          is StateValueV1 -> {
            animated += tag
            arguments[property] =
              retarget(
                ParameterTarget(property, TargetKind.FLOAT_LAMBDA),
                value,
                node,
                property,
                null,
              ) ?: return null
          }
          else ->
            return refuse(
              "$where.`$property` is computed; a Compose screen animates an axis from a state " +
                "variable, and a computed one is a Remote widget's"
            )
        }
      }
      val spec =
        VariableFontText.Spec(font, text, animated, location, VariableFontText.Target.COMPOSE_UI)
      val problems = VariableFontText.problems(spec)
      if (problems.isNotEmpty()) {
        problems.forEach { reasons += "$where: $it" }
        return null
      }
      variableFontSpecs += spec
      val name = with(VariableFontText) { requests(variableFontSpecs).of(spec).functionName }
      // A size of zero or less is the default, as the canvas and the Wear lane read it, so the
      // export draws what the preview did.
      val size =
        when (val value = node.properties["fontSizeSp"]) {
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          null -> VariableFontTextRecord.DEFAULT_SIZE_SP
          else -> return refuse("$where.`fontSizeSp` is a literal number")
        }.takeIf { it > 0.0 } ?: VariableFontTextRecord.DEFAULT_SIZE_SP
      arguments["fontSize"] =
        ScreenValue.Chain(
          receiver = unitReceiver(size) ?: return refuse("$where.`fontSizeSp` is $size"),
          links = listOf(ChainLink("androidx.compose.ui.unit.sp", property = true)),
          typeFqn = TEXT_UNIT,
        )
      node.properties["color"]?.let { value ->
        arguments["color"] = value(value, node, "color") ?: return null
      }
      val leading = listOfNotNull(scaffoldPaddings.remove(node.id))
      if (node.modifiers.isNotEmpty() || leading.isNotEmpty() || tagNodes) {
        modifiers(node, emptyList(), scope, leading)?.let { arguments["modifier"] = it }
      }
      return ScreenNode(
        componentId = VariableFontTextRecord.componentId(name),
        arguments = arguments,
      )
    }

    private fun selectionNode(node: DesignNodeV1, scope: String?): ScreenNode? {
      val json = Json
      val authored = node.toUiBuilderNode()
      stateSelectionIssue(authored, json.encodeToJsonElement(document.stateVariables).jsonObject)
        ?.let {
          return refuse("node `${node.id}`.$SHOW_BY_STATE: $it")
        }
      val selection = requireNotNull(authored.stateSelection())
      val variable = (selection.selector["variable"] as? JsonPrimitive)?.contentOrNull
      val declared = variable?.let(state::get)
      val type =
        declared?.typeFqn
          ?: when (selection.selector["type"]?.jsonPrimitive?.content) {
            "string" -> "kotlin.String"
            "bool" -> "kotlin.Boolean"
            "int" -> "kotlin.Int"
            "float" -> "kotlin.Float"
            else -> return refuse("node `${node.id}` has an invalid selector")
          }
      val subject =
        if (declared != null)
          stateRead(requireNotNull(variable), "node `${node.id}` selector") ?: return null
        else
          stateLiteral(selection.selector["value"], "node `${node.id}` selector", type)
            ?: return null
      val cases =
        selection.cases.mapValues { (id, value) ->
          stateLiteral(value, "node `${node.id}` case `$id`", type) ?: return null
        }
      val branches =
        node.slots["children"].orEmpty().associateWith { child ->
          listOfNotNull(node(child, BOX_SCOPE))
        }
      // This additive model shape is decoded strictly so the released dependency floor remains
      // buildable. A local staged generator understands it; an older generator must refuse it,
      // never silently drop selection and emit all branches. No Kotlin source is interpolated.
      val encoded =
        JsonObject(
          json.encodeToJsonElement(ScreenNode("", slots = branches)).jsonObject +
            ("selection" to
              buildJsonObject {
                put("subject", json.encodeToJsonElement<ScreenValue>(subject))
                put("cases", json.encodeToJsonElement(cases))
                selection.fallback?.let { put("elseSlot", JsonPrimitive(it)) }
              })
        )
      val selected =
        try {
          json.decodeFromJsonElement<ScreenNode>(encoded)
        } catch (_: kotlinx.serialization.SerializationException) {
          return refuse(
            "node `${node.id}`: the shared generator needs state-selection support; use a local dependency build or a release containing it"
          )
        }
      val container = node.copy(properties = node.properties - SHOW_BY_STATE)
      return ScreenNode(
        componentId = container.componentId,
        arguments = arguments(container, scope, null) + callbackArguments(container),
        handlers = handlers(container),
        slots = mapOf("content" to listOf(selected)),
      )
    }

    /**
     * A card's content inside the `layout/box` the canvas and capability exporter put it in, so
     * children get `BoxScope` alignment rather than `Card`'s `ColumnScope`. Sized by
     * [cardContentFill] like the other lanes (#483).
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
     * Whether a progress indicator's `indeterminate` property was handled here. The overload is
     * chosen by whether `progress` is present (see [PROPERTY_PARAMETERS]), so `indeterminate` is
     * always spent; only "not indeterminate, but no progress" is refused.
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
     * Whether this property asks for something about the node's placement in its parent that no
     * argument can carry, refusing by name if so. `span = full` needs a per-child `item(span = {
     * GridItemSpan(maxLineSpan) })`, which a per-slot [SlotItem] cannot express; dropping it would
     * silently export a single cell.
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
      DIRECT_COMPONENTS[node.componentId]?.let {
        return it
      }
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
        // The design's theme, which [themed] writes around the root; not `Surface` arguments.
        if (node.componentId == SURFACE_CATALOG_ID && property in ScreenTheme.PROPERTIES) continue
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
            ?: ICON_KEY_TARGET.takeIf { property == ICON_KEY }
        if (target == null) {
          arguments[property] = value(value, node, property) ?: continue
          continue
        }
        arguments[target.parameter] = retarget(target, value, node, property, variant) ?: continue
      }
      modifierClick(node)?.let { fromProperties += it }
      val leading = listOfNotNull(scaffoldPaddings.remove(node.id))
      if (
        node.modifiers.isNotEmpty() ||
          fromProperties.isNotEmpty() ||
          leading.isNotEmpty() ||
          tagNodes
      ) {
        modifiers(node, fromProperties, scope, leading)?.let { arguments["modifier"] = it }
      }
      return arguments
    }

    /**
     * Arguments built from more than one property, and the properties they consumed: arrangement +
     * spacing, top-bar colour pairs, time-picker hour + minute, and colour-dot chains. Read one at
     * a time, the second would overwrite the first.
     */
    private fun composite(
      node: DesignNodeV1,
      arguments: MutableMap<String, ScreenValue>,
      fromProperties: MutableList<ChainLink>,
    ): Set<String> {
      val spent = mutableSetOf<String>()
      // Every axis the component has, not one: a flow row arranges along its lines AND down them,
      // and reading only the first would drop whichever of the two the document listed second.
      ARRANGEMENTS[node.componentId].orEmpty().forEach { axis ->
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
                  (bundle.factoryNames[role] ?: role) to it
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
      STATE_BUNDLES[node.componentId]?.let { bundle ->
        val present = bundle.arguments.filterKeys { it in node.properties }
        if (present.isNotEmpty()) {
          spent += present.keys
          val named =
            present
              .mapNotNull { (property, argument) ->
                stateArgument(argument, node, property)?.let { argument.parameter to it }
              }
              .toMap()
          // The same all-or-nothing rule the colour bundles keep, for the same reason: a state
          // built from two of a component's three settings is a component configured differently
          // from the one on the canvas, and it compiles.
          if (named.size == present.size) {
            // The canvas's defaults for what the design left unset, in the table's own order, so
            // the call reads the way the factory declares it.
            val complete =
              bundle.arguments
                .mapNotNull { (property, argument) ->
                  val supplied = named[argument.parameter]
                  val fallback = if (property in node.properties) null else argument.whenAbsent
                  (supplied ?: fallback)?.let { argument.parameter to it }
                }
                .toMap()
            arguments[bundle.parameter] =
              ScreenValue.Construct(
                callableFqn = bundle.factoryFqn,
                named = complete,
                typeFqn = bundle.typeFqn,
                requiredOptIns = bundle.optIns,
              )
          }
        }
      }
      if (node.componentId == COLOUR_DOT) spent += colourDot(node, fromProperties)
      if (node.componentId == LINEAR_GRADIENT) spent += linearGradient(node, fromProperties)
      if (node.componentId in setOf(SUPPORTING_PANE_SCAFFOLD, LIST_DETAIL_PANE_SCAFFOLD))
        spent += supportingPanes(node, arguments)
      paneWidths.remove(node.id)?.let { fromProperties += it }
      return spent
    }

    /**
     * One state-factory argument, clamped exactly where the canvas clamps (e.g. an hour of 25
     * becomes 23), so the export matches the picture instead of refusing a document the canvas
     * rendered.
     */
    private fun stateArgument(
      argument: StateArgument,
      node: DesignNodeV1,
      property: String,
    ): ScreenValue? {
      val raw = value(node.properties.getValue(property), node, property) ?: return null
      val range = argument.range ?: return raw
      if (raw !is ScreenValue.Whole) return raw
      return if (raw.value in range.first.toLong()..range.last.toLong()) raw
      else ScreenValue.Whole(raw.value.coerceIn(range.first.toLong(), range.last.toLong()))
    }

    /**
     * The one `Arrangement` a node's arrangement and spacing name together, following the canvas's
     * rule: aligned arrangements take a gap via `spacedBy(gap, alignment)`, `space*` arrangements
     * spend it, and a zero gap writes the bare member.
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
      val spacing = node.properties.getValue(axis.spacing)
      if (spacing is BindingValueV1) {
        val number =
          binding(spacing, "kotlin.Float", spacingWhere) {
            scalar(it, "kotlin.Float", spacingWhere)
          } ?: return null
        val alignment = axis.aligned[entry] ?: return member
        // The canvas treats non-positive spacing as the chosen alignment without a gap.
        val nonnegative =
          ScreenValue.Construct(
            "kotlin.math.max",
            positional = listOf(ScreenValue.Fractional32(0f), number),
            typeFqn = "kotlin.Float",
          )
        val dp =
          ScreenValue.Chain(
            nonnegative,
            listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
            DP,
          )
        return ScreenValue.Construct(
          "$ARRANGEMENT.spacedBy",
          positional =
            listOf(
              dp,
              ScreenValue.Reference(ALIGNMENT, listOf(alignment), typeFqn = axis.alignment),
            ),
          typeFqn = axis.typeFqn,
        )
      }
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
     * A colour dot's modifier chain — `size(d.dp).clip(CircleShape).background(colour)` — and the
     * properties it spent. Exported through `Box`'s record by alias; the diameter defaults to the
     * canvas's 8dp so the dot does not vanish.
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
     * `SupportingPaneScaffold`'s computed `directive` and `value` arguments, written as the window
     * computation an app (and the canvas) performs.
     *
     * `layoutMode` is spent: every mode but `singlePane` means "what the directive decides", and
     * `singlePane` / pane spacing become a `directive.copy(…)`. A hidden pane is refused; preferred
     * pane widths go on the pane content via [paneWidthLink].
     */
    private fun supportingPanes(
      node: DesignNodeV1,
      arguments: MutableMap<String, ScreenValue>,
    ): Set<String> {
      val listDetail = node.componentId == LIST_DETAIL_PANE_SCAFFOLD
      val where = "node `${node.id}`"
      val mode =
        when (val value = node.properties[PANE_LAYOUT_MODE]) {
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          else -> null
        }
      val adjustments = mutableMapOf<String, ScreenValue>()
      if (mode == "singlePane") adjustments["maxHorizontalPartitions"] = ScreenValue.Whole(1)
      for (flag in
        if (listDetail) listOf("listPaneVisible", "detailPaneVisible")
        else listOf(MAIN_PANE_VISIBLE, SUPPORTING_PANE_VISIBLE)) {
        val visible = (node.properties[flag] as? BooleanValueV1)?.value ?: true
        if (!visible) {
          refuse(
            "$where.`$flag` hides a pane, which means building a `ThreePaneScaffoldValue` by " +
              "hand rather than computing it; show both panes to export the adaptive one"
          )
        }
      }
      val spacing =
        when (val value = node.properties[PANE_SPACING_DP]) {
          null -> null
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else -> refuse("$where.`$PANE_SPACING_DP` is a spacing in dp, which needs a number")
        }
      if (spacing != null) {
        dp(spacing)?.let { adjustments["horizontalPartitionSpacerSize"] = it }
          ?: refuse("$where.`$PANE_SPACING_DP` is $spacing, which does not survive `Dp`")
      }
      val sizingPolicy =
        (node.properties["paneSizing"] as? EnumValueV1)?.value
          ?: (node.properties["paneSizing"] as? StringValueV1)?.value
      if (spacing == null && sizingPolicy in setOf("fixedStart", "fixedEnd"))
        adjustments["horizontalPartitionSpacerSize"] = dp(24.0)!!
      val computed =
        ScreenValue.Construct(
          callableFqn = "$ADAPTIVE_LAYOUT.calculatePaneScaffoldDirective",
          positional =
            listOf(
              ScreenValue.Construct(
                callableFqn = "$ADAPTIVE.currentWindowAdaptiveInfo",
                typeFqn = "$ADAPTIVE.WindowAdaptiveInfo",
              )
            ),
          typeFqn = "$ADAPTIVE_LAYOUT.PaneScaffoldDirective",
        )
      val directive =
        if (adjustments.isEmpty()) computed
        else
          ScreenValue.Chain(
            receiver = computed,
            links =
              listOf(
                ChainLink(
                  "$ADAPTIVE_LAYOUT.PaneScaffoldDirective.copy",
                  named = adjustments,
                  member = true,
                )
              ),
            typeFqn = "$ADAPTIVE_LAYOUT.PaneScaffoldDirective",
          )
      arguments["directive"] = directive
      fun destinationRole(): ScreenValue {
        val index = node.properties["activePaneIndex"]
        if (index is IntegerValueV1)
          return ScreenValue.Reference(
            "$ADAPTIVE_LAYOUT.ListDetailPaneScaffoldRole",
            listOf(
              when (index.value.toInt().coerceIn(0, 2)) {
                1 -> "Detail"
                2 -> if (node.slots["extraPane"].isNullOrEmpty()) "List" else "Extra"
                else -> "List"
              }
            ),
            typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldRole",
          )
        if (index != null) {
          val raw =
            when (index) {
              is IntegerValueV1 -> ScreenValue.Whole(index.value.toLong().coerceIn(0, 2))
              is StateValueV1 ->
                if (state[index.variable]?.typeFqn == "kotlin.Int")
                  stateRead(index.variable, "$where.activePaneIndex") ?: ScreenValue.Whole(0)
                else {
                  refuse("$where.activePaneIndex requires integer state")
                  ScreenValue.Whole(0)
                }
              else -> {
                refuse("$where.activePaneIndex needs an integer or integer state")
                ScreenValue.Whole(0)
              }
            }
          val clamped =
            ScreenValue.Chain(
              raw,
              listOf(
                ChainLink(
                  "kotlin.ranges.coerceIn",
                  positional = listOf(ScreenValue.Whole(0), ScreenValue.Whole(2)),
                )
              ),
              typeFqn = "kotlin.Int",
            )
          // A closed mapping of the three destinations. Keeping the intermediate value a String
          // avoids a raw generic List receiver in the screen-model's typed chain locals.
          val name =
            ScreenValue.Chain(
              clamped,
              listOf(
                ChainLink("kotlin.Int.toString", member = true),
                ChainLink(
                  "kotlin.text.replace",
                  positional = listOf(ScreenValue.Text("0"), ScreenValue.Text("Secondary")),
                ),
                ChainLink(
                  "kotlin.text.replace",
                  positional = listOf(ScreenValue.Text("1"), ScreenValue.Text("Primary")),
                ),
                ChainLink(
                  "kotlin.text.replace",
                  positional =
                    listOf(
                      ScreenValue.Text("2"),
                      ScreenValue.Text(
                        if (node.slots["extraPane"].isNullOrEmpty()) "Secondary" else "Tertiary"
                      ),
                    ),
                ),
              ),
              typeFqn = "kotlin.String",
            )
          return ScreenValue.Construct(
            "$ADAPTIVE_LAYOUT.ThreePaneScaffoldRole.valueOf",
            positional = listOf(name),
            typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldRole",
          )
        }
        return ScreenValue.Reference(
          "$ADAPTIVE_LAYOUT.ListDetailPaneScaffoldRole",
          listOf(
            when (
              (node.properties["activePane"] as? EnumValueV1)?.value
                ?: (node.properties["activePane"] as? StringValueV1)?.value
            ) {
              "detail" -> "Detail"
              "extra" -> if (node.slots["extraPane"].isNullOrEmpty()) "List" else "Extra"
              else -> "List"
            }
          ),
          typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldRole",
        )
      }
      arguments["value"] =
        ScreenValue.Construct(
          callableFqn = "$ADAPTIVE_LAYOUT.calculateThreePaneScaffoldValue",
          positional =
            listOf(
              ScreenValue.Chain(
                receiver = directive,
                links =
                  listOf(
                    // A member of the directive, not an importable extension: the generator
                    // declares the directive as a typed local and reads the count off it.
                    ChainLink(
                      "$ADAPTIVE_LAYOUT.PaneScaffoldDirective.maxHorizontalPartitions",
                      property = true,
                      member = true,
                    )
                  ),
                typeFqn = "kotlin.Int",
              )
            ),
          // Both named, although adaptive-layout 1.2 defaults them: 1.3 drops those defaults and
          // offers two overloads, one taking a `currentDestination` and one a `destinationHistory`,
          // so a call that leaves them out does not compile against a catalog built on 1.3 — which
          // is what m3-catalog's native lane compiles the export against. An explicit destination
          // names the overload both versions have; a String content key also lets Kotlin infer
          // the destination item's generic parameter.
          named =
            mapOf(
              "adaptStrategies" to
                ScreenValue.Construct(
                  callableFqn =
                    "$ADAPTIVE_LAYOUT.${if (listDetail) "ListDetailPaneScaffoldDefaults" else "SupportingPaneScaffoldDefaults"}.adaptStrategies",
                  typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldAdaptStrategies",
                ),
              "currentDestination" to
                if (listDetail)
                  ScreenValue.Construct(
                    callableFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldDestinationItem",
                    positional = listOf(destinationRole(), ScreenValue.Text("")),
                    typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldDestinationItem",
                  )
                else
                  ScreenValue.Construct(
                    callableFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldDestinationItem",
                    positional =
                      listOf(
                        ScreenValue.Reference(
                          "$ADAPTIVE_LAYOUT.SupportingPaneScaffoldRole",
                          listOf("Main"),
                          typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldRole",
                        ),
                        ScreenValue.Text(""),
                      ),
                    typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldDestinationItem",
                  ),
            ),
          typeFqn = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldValue",
        )
      val sizing =
        (node.properties["paneSizing"] as? EnumValueV1)?.value
          ?: (node.properties["paneSizing"] as? StringValueV1)?.value
      fun number(property: String, fallback: Double): Double =
        when (val v = node.properties[property]) {
          is DecimalValueV1 -> v.value
          is IntegerValueV1 -> v.value.toDouble()
          null -> fallback
          else -> {
            refuse("$where.`$property` needs a literal number")
            fallback
          }
        }
      val anchor =
        when (sizing) {
          "fixedStart",
          "fixedEnd" ->
            ScreenValue.Construct(
              callableFqn =
                "$ADAPTIVE_LAYOUT.PaneExpansionAnchor.Offset.${if (sizing == "fixedStart") "fromStart" else "fromEnd"}",
              // Offset anchors describe the gutter centre. The authored gutter, or Material's
              // 24dp default, is pinned on the directive below so both lanes measure this width.
              positional =
                listOf(
                  dp(number("fixedPaneWidthDp", 360.0).coerceAtLeast(0.0) + (spacing ?: 24.0) / 2)!!
                ),
              typeFqn = "$ADAPTIVE_LAYOUT.PaneExpansionAnchor",
            )
          "split" ->
            ScreenValue.Construct(
              callableFqn = "$ADAPTIVE_LAYOUT.PaneExpansionAnchor.Proportion",
              positional =
                listOf(
                  ScreenValue.Fractional32(number("splitFraction", .5).coerceIn(.1, .9).toFloat())
                ),
              typeFqn = "$ADAPTIVE_LAYOUT.PaneExpansionAnchor",
            )
          else -> null
        }
      if (anchor != null)
        arguments["paneExpansionState"] =
          ScreenValue.Construct(
            callableFqn = "$ADAPTIVE_LAYOUT.rememberPaneExpansionState",
            named =
              mapOf(
                "anchors" to
                  ScreenValue.Construct(
                    callableFqn = "kotlin.collections.listOf",
                    positional = listOf(anchor),
                    typeFqn = "kotlin.collections.List",
                  ),
                "initialAnchoredIndex" to ScreenValue.Whole(0),
              ),
            typeFqn = "$ADAPTIVE_LAYOUT.PaneExpansionState",
          )
      return setOf(
        "paneSizing",
        "fixedPaneWidthDp",
        "splitFraction",
        "activePane",
        "activePaneIndex",
        "listPaneVisible",
        "detailPaneVisible",
        "listPanePreferredWidthDp",
        "detailPanePreferredWidthDp",
        "extraPanePreferredWidthDp",
      ) +
        setOf(
          PANE_LAYOUT_MODE,
          MAIN_PANE_VISIBLE,
          SUPPORTING_PANE_VISIBLE,
          PANE_SPACING_DP,
          MAIN_PANE_WIDTH_DP,
          SUPPORTING_PANE_WIDTH_DP,
        )
    }

    /** `Modifier.preferredWidth(…)` for the content of one pane slot, or null where none is set. */
    private fun paneWidthLink(node: DesignNodeV1, slot: String): ChainLink? {
      val property =
        when (slot) {
          "listPane" -> "listPanePreferredWidthDp"
          "detailPane" -> "detailPanePreferredWidthDp"
          "extraPane" -> "extraPanePreferredWidthDp"
          "mainPane" -> MAIN_PANE_WIDTH_DP
          "supportingPane" -> SUPPORTING_PANE_WIDTH_DP
          else -> return null
        }
      val number =
        when (val value = node.properties[property]) {
          null -> return null
          is DecimalValueV1 -> value.value
          is IntegerValueV1 -> value.value.toDouble()
          else ->
            return refuse(
              "node `${node.id}`.`$property` is a pane width in dp, which needs a number"
            )
        }
      val width =
        dp(number)
          ?: return refuse("node `${node.id}`.`$property` is $number, which does not survive `Dp`")
      return ChainLink(
        "$THREE_PANE_SCOPE.preferredWidth",
        positional = listOf(width),
        receiverScopeFqn = THREE_PANE_SCOPE,
      )
    }

    /**
     * A linear gradient's `background(brush = …)` link and the properties it spent. Exported
     * through `Box` by alias like [colourDot]; `direction` follows the canvas's table so vertical
     * never exports as anything else.
     */
    private fun linearGradient(
      node: DesignNodeV1,
      fromProperties: MutableList<ChainLink>,
    ): Set<String> {
      val where = "node `${node.id}`"
      fun end(property: String): ScreenValue? =
        when (val value = node.properties[property]) {
          null ->
            refuse("$where sets no `$property`, and a gradient is nothing but its two colours")
          is StringValueV1 -> color(value.value, "$where.`$property`")
          else -> colour(value, "$where.`$property`")
        }
      val start = end(GRADIENT_START)
      val finish = end(GRADIENT_END)
      val direction =
        when (val value = node.properties[GRADIENT_DIRECTION]) {
          is EnumValueV1 -> value.value
          is StringValueV1 -> value.value
          else -> null
        }
      if (start != null && finish != null) {
        val (factory, colours) =
          when (direction) {
            "leftToRight",
            "horizontal" -> "horizontalGradient" to listOf(start, finish)
            "rightToLeft" -> "horizontalGradient" to listOf(finish, start)
            "bottomToTop" -> "verticalGradient" to listOf(finish, start)
            else -> "verticalGradient" to listOf(start, finish)
          }
        val brush =
          ScreenValue.Construct(
            callableFqn = "$BRUSH.$factory",
            positional =
              listOf(
                ScreenValue.Construct(
                  callableFqn = "kotlin.collections.listOf",
                  positional = colours,
                  typeFqn = "kotlin.collections.List",
                )
              ),
            typeFqn = BRUSH,
          )
        fromProperties +=
          ChainLink("androidx.compose.foundation.background", named = mapOf("brush" to brush))
      }
      return setOf(GRADIENT_START, GRADIENT_END, GRADIENT_DIRECTION)
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
     * A list item's leading accent bar. The canvas draws it in a `drawBehind` lambda this
     * vocabulary cannot express, so an empty value is spent and a colour is refused by name.
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
     * The `Modifier.weight(…)` a layout weight becomes, or null having said why. Scoped to
     * `RowScope` / `ColumnScope` via [ChainLink.receiverScopeFqn] and written as
     * [ScreenValue.Fractional32] because `weight` takes a `Float`.
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
     * The same link for `weight` authored as a modifier, so both spellings share one narrowing
     * rule. `fill` exists only on the modifier form.
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
      leading: List<ChainLink> = emptyList(),
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
        // Only a parent's padding precedes it — [scaffoldPaddings] — because that is the canvas's
        // order: the scaffold pads its content, and the content's own modifiers apply inside.
        links = leading + links.filterNotNull() + fromProperties + tag,
        typeFqn = MODIFIER,
      )
    }

    /**
     * The chain link one authored modifier becomes, or null having said why. Every
     * `DesignModifierV1` subtype is answered; the `else` branch only catches a newer protocol,
     * keeping that a named refusal instead of a dropped modifier.
     */
    private fun link(modifier: DesignModifierV1, nodeId: String, scope: String?): ChainLink? {
      return when (modifier) {
        FillMaxWidthModifierV1 -> ChainLink("$LAYOUT.fillMaxWidth")
        FillMaxHeightModifierV1 -> ChainLink("$LAYOUT.fillMaxHeight")
        FillMaxSizeModifierV1 -> ChainLink("$LAYOUT.fillMaxSize")
        is PaddingModifierV1 -> {
          // No usable axis emits `Modifier.padding()`, which is ambiguous between Compose's two
          // fully-defaulted overloads and compiles as neither. Catalog validation checks that the
          // modifier *type* is allowed and not that its axes are numbers, and the renderer reads a
          // bad number as zero, so such a document reaches here rather than being stopped earlier.
          val insets =
            insets(modifier.startDp, modifier.topDp, modifier.endDp, modifier.bottomDp)
              ?: run {
                reasons += "node `$nodeId` pads with no axis that is a number"
                return null
              }
          ChainLink("$LAYOUT.padding", positional = insets.first, named = insets.second)
        }
        is SizeModifierV1 -> {
          // `size` has two overloads and neither accepts one named axis: `size(size: Dp)` names
          // its parameter `size`, and `size(width: Dp, height: Dp)` requires both. So a modifier
          // carrying one axis has to become `width(…)` or `height(…)`, which the renderer treats
          // the same way and the compiler accepts.
          val width = dp(modifier.widthDp)
          val height = dp(modifier.heightDp)
          when {
            // `size(40.dp)` for a square, as it is written by hand.
            width != null && width == height ->
              ChainLink("$LAYOUT.size", positional = listOf(width))
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
        // A `RemoteModifier` call: only a Remote Compose catalog's exporter writes one.
        is RemoteCallModifierV1 -> {
          reasons +=
            "node `$nodeId` carries the Remote Compose call `${modifier.name}`, which a Compose " +
              "screen has no Modifier for; export it as a Remote widget"
          null
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
        is BindingValueV1 -> binding(value, COLOR, where) { colour(it, where) }
        is ColorValueV1 -> color(value.value, where)
        is ColorTokenValueV1 -> colourToken(value.value, where)
        else ->
          refuse(
            "$where is a colour, which is written as a `#RRGGBB` literal or as a theme role and " +
              "not as ${value::class.simpleName}"
          )
      }

    /**
     * The `Shape` a shape name resolves to: theme role, then the two constants, then a numeric
     * corner radius (`"16"` is 16dp, as the canvas draws it). Named roles stay roles so a re-themed
     * catalog is followed.
     */
    private fun shapeOf(name: String): ScreenValue? =
      SHAPE_TOKENS[name]?.let { path -> pathValue(path, SHAPE) }
        ?: SHAPE_CONSTANTS[name]?.let { ScreenValue.Reference(it, typeFqn = SHAPE) }
        ?: name.toDoubleOrNull()?.let { radius ->
          dp(radius)?.let { corner ->
            ScreenValue.Construct(
              callableFqn = ROUNDED_CORNER_SHAPE_FQN,
              positional = listOf(corner),
              // `Shape`, not `RoundedCornerShape`: the generator compares this claim against the
              // parameter's own `typeFqn` as a string, exactly as the property-side
              // `ROUNDED_CORNER_SHAPE` target kind does one screen over.
              typeFqn = SHAPE,
            )
          }
        }

    /** Records a shape nothing resolves, naming both sets a document may choose from. */
    private fun refuseShape(nodeId: String, verb: String, name: String): ChainLink? {
      reasons +=
        "node `$nodeId` $verb shape `$name`, which is neither a theme shape " +
          "(${SHAPE_TOKENS.keys.sorted().joinToString(", ")}), one of " +
          SHAPE_CONSTANTS.keys.sorted().joinToString(", ") +
          ", nor a corner radius in dp"
      return null
    }

    /**
     * `.verticalScroll(rememberScrollState())`, with the state remembered inline at the call —
     * legal because the generated body is composable
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
     * The receiver for a `.dp` or `.sp` chain, or null. Whole numbers are written as `Int` only
     * within `Int` range (there is no `Long.dp`); fractional values must survive narrowing to
     * `Float`, since `Dp` wraps a `Float` and `1e100.dp` would silently become infinity.
     */
    private fun unitReceiver(number: Double): ScreenValue? {
      if (!number.isFinite() || !number.toFloat().isFinite()) return null
      val whole = number.toLong()
      return if (number == whole.toDouble() && whole in Int.MIN_VALUE..Int.MAX_VALUE)
        ScreenValue.Whole(whole)
      else ScreenValue.Fractional(number)
    }

    /** A `Dp` for a JSON number, or null when the field was absent or not a number. */
    /**
     * Four insets as hand-written `padding` / `PaddingValues` arguments: `(all)`, `(horizontal,
     * vertical)`, or the non-zero sides by name. Non-numeric sides count as zero, as the renderer
     * reads them.
     */
    private fun insets(
      start: JsonElement?,
      top: JsonElement?,
      end: JsonElement?,
      bottom: JsonElement?,
    ): Pair<List<ScreenValue>, Map<String, ScreenValue>>? {
      fun number(value: JsonElement?) = (value as? JsonPrimitive)?.doubleOrNull
      if (listOf(start, top, end, bottom).all { number(it) == null }) return null
      val s = number(start) ?: 0.0
      val t = number(top) ?: 0.0
      val e = number(end) ?: 0.0
      val b = number(bottom) ?: 0.0
      fun named(vararg sides: Pair<String, Double>): Map<String, ScreenValue>? = buildMap {
        for ((name, amount) in sides) {
          if (amount != 0.0) put(name, dp(amount) ?: return null)
        }
      }
        .ifEmpty { null }
      return when {
        s == t && t == e && e == b -> listOf(dp(s) ?: return null) to emptyMap()
        s == e && t == b ->
          emptyList<ScreenValue>() to (named("horizontal" to s, "vertical" to t) ?: return null)
        else ->
          emptyList<ScreenValue>() to
            (named("start" to s, "top" to t, "end" to e, "bottom" to b) ?: return null)
      }
    }

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
      if (target.kind == TargetKind.INT) {
        // The wire's numbers are floats unless a writer said `int`, and a palette insert or an
        // older document says `0.0` for an index. A whole float is that index; a fractional one is
        // not an index at all.
        return when (value) {
          is IntegerValueV1 -> ScreenValue.Whole(value.value)
          is DecimalValueV1 ->
            if (value.value % 1.0 == 0.0 && kotlin.math.abs(value.value) <= Int.MAX_VALUE)
              ScreenValue.Whole(value.value.toLong())
            else refuse("$where is ${value.value}, and an index is a whole number")
          else -> value(value, node, property)
        }
      }
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
            listOfNotNull(pathValue(listOf(THEME, "colorScheme", "surfaceVariant"), COLOR)),
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
        if (value is BindingValueV1) {
          val projected =
            binding(value, "kotlin.Float", where) { scalar(it, "kotlin.Float", where) }
              ?: return null
          return if (target.kind == TargetKind.FLOAT_LAMBDA) ScreenValue.Lambda(projected)
          else projected
        }
        val fraction =
          when (value) {
            is DecimalValueV1 -> value.value
            is IntegerValueV1 -> value.value.toDouble()
            // The state read belongs inside the progress callback so recomposition observes the
            // current value, just as the numeric spelling belongs inside a constant callback.
            else -> {
              val projected = value(value, node, property) ?: return null
              return if (target.kind == TargetKind.FLOAT_LAMBDA) ScreenValue.Lambda(projected)
              else projected
            }
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
        if (value is BindingValueV1)
          return binding(value, SHAPE, where) { retarget(target, it, node, property, variant) }
        // Only the text spelling needs help. A `shapeToken` wrapper already resolves through the
        // same table in `value`, and the checked-in fixtures use it — narrowing this to strings
        // refused a document that exported before, which is the one thing a widening must not do.
        val name = (value as? StringValueV1)?.value ?: return value(value, node, property)
        return token(name, SHAPE_TOKENS, SHAPE, "shape", where)
      }
      val dp =
        when (value) {
          is BindingValueV1 -> boundDp(value, where)
          is DecimalValueV1 -> dp(value.value)
          is IntegerValueV1 -> dp(value.value.toDouble())
          else -> return refuse("$where becomes `${target.parameter}`, which needs a number")
        } ?: return refuse("$where does not survive `Dp`")
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
            callableFqn = ROUNDED_CORNER_SHAPE_FQN,
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
        is ColorTokenValueV1 -> colourToken(value.value, where)
        is TypographyTokenValueV1 ->
          token(value.value, TYPOGRAPHY_TOKENS, TEXT_STYLE, "typography", where)
        // Through `shapeOf` rather than the table alone, so a corner radius written as a number
        // reads the same on a property as it does in a `clip` modifier. `token` still writes the
        // refusal for a name that is neither.
        is ShapeTokenValueV1 ->
          shapeOf(value.value) ?: token(value.value, SHAPE_TOKENS, SHAPE, "shape", where)
        is DimensionValueV1 -> dimension(value, where)
        is PaddingValueV1 -> {
          // `PaddingValues()` is ambiguous for the same reason `Modifier.padding()` is: every
          // overload is fully defaulted, so an argument list with nothing in it picks none of them.
          val insets =
            insets(value.startDp, value.topDp, value.endDp, value.bottomDp)
              ?: return refuse("$where has no axis that is a number")
          ScreenValue.Construct(
            callableFqn = "androidx.compose.foundation.layout.PaddingValues",
            positional = insets.first,
            named = insets.second,
            typeFqn = "androidx.compose.foundation.layout.PaddingValues",
          )
        }
        is EnumValueV1 -> enum(value.value, node.componentId, property, where)
        is BindingValueV1 -> propertyBinding(value, node, property)
        is StateValueV1 -> stateRead(value.variable, where)
        is StateEqualsValueV1 ->
          refuse(
            "$where compares the state variable `${value.variable}`, which needs a shared " +
              "comparison expression"
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
        // Values the Remote player computes. A Compose screen has no player to compute them.
        is ExpressionValueV1,
        is SystemValueV1 ->
          refuse(
            "$where is a value the Remote Compose player computes; export it as a Remote widget"
          )
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
      }
    }

    /**
     * A `colorToken` wrapper's value, which may be a theme role or a `#AARRGGBB` literal — the
     * canvas checks `startsWith("#")` first, and older designs use that spelling.
     */
    private fun colourToken(name: String, where: String): ScreenValue? =
      if (name.startsWith("#")) color(name, where)
      else token(name, COLOR_TOKENS, COLOR, "colour", where)

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
      return pathValue(path, typeFqn)
    }

    /**
     * A qualified path as a value: `MaterialTheme.colorScheme.primary`, `Alignment.Center`.
     *
     * Theme roles are the exception once the root is themed. A screen that calls `MaterialTheme(…)`
     * cannot also read `MaterialTheme.colorScheme` — the generator refuses an import whose name the
     * file already spends on a call — so inside a themed root a role is read off the theme
     * function's own parameter instead: `colorScheme.primary`, the same value the `MaterialTheme`
     * around it provides. See [themed].
     */
    private fun pathValue(path: List<String>, typeFqn: String): ScreenValue? {
      if (!themeParameters || path.first() != THEME) {
        return ScreenValue.Reference(
          rootFqn = path.first(),
          members = path.drop(1),
          typeFqn = typeFqn,
        )
      }
      val (holder, role) = path[1] to path.drop(2)
      if (functionScope != null) {
        return refuse(
          "a reusable component inside the themed surface reads the theme role " +
            "`${(listOf(holder) + role).joinToString(".")}`, which this export cannot write " +
            "inside a component yet; read it outside the component or set the role directly"
        )
      }
      val holderType = THEME_HOLDERS.getValue(holder)
      val read = scopedRead("ParameterRead", "parameter", holder, holderType) ?: return null
      return ScreenValue.Chain(
        receiver = read,
        links = role.map { ChainLink("$holderType.$it", property = true, member = true) },
        typeFqn = typeFqn,
      )
    }

    /**
     * [content] inside the `MaterialTheme` [theme] describes, written as up to three private
     * functions so each value is a parameter of the next:
     * - `<Screen>Fonts(provider)`: the Google Fonts provider, built once (only when a typeface is
     *   named, and only for [TypefaceTarget.ANDROID]: the desktop form has no provider, and passes
     *   each family's `SystemFont` lookups straight to the typography function).
     * - `<Screen>Typography(base, display, …)`: the themed type scale (only when type is scaled or
     *   a typeface named).
     * - `<Screen>Theme(colorScheme, typography, shapes)`: `MaterialTheme` around the surface; theme
     *   roles inside read these parameters (see [pathValue]).
     *
     * Baselines match the canvas: light/dark color scheme by environment, `Typography()` and
     * `Shapes()`.
     */
    fun themed(content: ScreenNode, theme: ScreenTheme, screenName: String): ScreenNode? {
      val themeName = "${screenName}Theme"
      val typographyName = "${screenName}Typography"
      val fontsName = "${screenName}Fonts"
      fun read(name: String, type: String) = scopedRead("ParameterRead", "parameter", name, type)
      val holders = THEME_HOLDERS.keys.toList()
      define(
        themeName,
        holders.map { it to THEME_HOLDERS.getValue(it) },
        ScreenNode(
          ScreenTheme.COMPONENT_ID,
          arguments = holders.associateWith { read(it, THEME_HOLDERS.getValue(it)) ?: return null },
          slots = mapOf("content" to listOf(textStyled(content, theme.textRole) ?: return null)),
        ),
      )
      val colorScheme =
        ScreenValue.Construct(
          callableFqn = if (theme.dark) DARK_COLOR_SCHEME else LIGHT_COLOR_SCHEME,
          named =
            theme.colors.mapValues { (role, literal) ->
              color(literal, "the theme's `$role`") ?: return null
            },
          typeFqn = COLOR_SCHEME,
        )
      val shapes = themeShapes(theme.cornerRadiusDp) ?: return null
      val themeCall = { typography: ScreenValue ->
        functionCall(
          themeName,
          mapOf("colorScheme" to colorScheme, "typography" to typography, "shapes" to shapes),
        )
      }
      val baseline = ScreenValue.Construct(TYPOGRAPHY, typeFqn = TYPOGRAPHY)
      if (!theme.scalesType && theme.families.isEmpty()) return themeCall(baseline)
      // In the order the groups are declared, so a design's parameters read display to label.
      val groups = ThemeTypefaces.GROUPS.filter { it in theme.families }
      define(
        typographyName,
        listOf("base" to TYPOGRAPHY) + groups.map { it.name to FONT_FAMILY },
        themeCall(themedTypography(theme, groups) ?: return null) ?: return null,
      )
      if (groups.isEmpty()) return functionCall(typographyName, mapOf("base" to baseline))
      if (typefaces == TypefaceTarget.DESKTOP) {
        return functionCall(
          typographyName,
          mapOf("base" to baseline) +
            groups.associate { it.name to systemFontFamily(theme.families.getValue(it)) },
        )
      }
      val provider = read("provider", GOOGLE_FONT_PROVIDER) ?: return null
      define(
        fontsName,
        listOf("provider" to GOOGLE_FONT_PROVIDER),
        functionCall(
          typographyName,
          mapOf("base" to baseline) +
            groups.associate { it.name to googleFontFamily(theme.families.getValue(it), provider) },
        ) ?: return null,
      )
      return functionCall(fontsName, mapOf("provider" to googleFontProvider()))
    }

    /**
     * [content] under the host's default text role — `ProvideTextStyle(typography.<role>)`, over
     * the theme's own `bodyLarge` (see [ThemeTextStyle]) — or [content] itself when [role] is null.
     * The role is read off the theme function's parameter, as every role inside it is.
     */
    private fun textStyled(content: ScreenNode, role: String?): ScreenNode? {
      if (role == null) return content
      val style = pathValue(listOf(THEME, "typography", role), TEXT_STYLE) ?: return null
      return ScreenNode(
        ScreenTheme.TEXT_STYLE_COMPONENT_ID,
        arguments = mapOf("value" to style),
        slots = mapOf("content" to listOf(content)),
      )
    }

    /** A private composable [themed] writes, added ahead of those already written. */
    private fun define(name: String, parameters: List<Pair<String, String>>, root: ScreenNode) {
      themeFunctions.add(
        0,
        buildJsonObject {
          put("name", name)
          put("parameters", JsonArray(parameters.map { (it, type) -> parameter(it, type) }))
          put("root", Json.encodeToJsonElement(root))
        },
      )
    }

    private fun functionCall(name: String, arguments: Map<String, ScreenValue>): ScreenNode? =
      shared<ScreenNode>(
        JsonObject(
          Json.encodeToJsonElement(ScreenNode("", arguments)).jsonObject +
            ("function" to JsonPrimitive(name))
        ),
        "the design's theme",
      )

    /**
     * `base.copy(…)` with each role [theme] changes: scaled by its type scale, the way
     * `UiBuilderRenderer` scales a text's style, and set in the family of the group it belongs to.
     */
    private fun themedTypography(
      theme: ScreenTheme,
      groups: List<ThemeTypefaces.Group>,
    ): ScreenValue? {
      val base = scopedRead("ParameterRead", "parameter", "base", TYPOGRAPHY) ?: return null
      fun role(name: String) = ChainLink("$TYPOGRAPHY.$name", property = true, member = true)
      fun scaled(role: String, unit: String) =
        ScreenValue.Chain(
          receiver = base,
          links =
            listOf(
              role(role),
              ChainLink("$TEXT_STYLE.$unit", property = true, member = true),
              ChainLink(
                "$TEXT_UNIT.times",
                positional = listOf(ScreenValue.Fractional32(theme.typeScale)),
                member = true,
              ),
            ),
          typeFqn = TEXT_UNIT,
        )
      val familyOf = groups.flatMap { group -> group.m3Roles.map { it to group.name } }.toMap()
      val roles = TYPOGRAPHY_TOKENS.keys.filter { theme.scalesType || it in familyOf }
      val changed = roles.associateWith { name ->
        val named = buildMap {
          familyOf[name]?.let { group ->
            put(
              "fontFamily",
              scopedRead("ParameterRead", "parameter", group, FONT_FAMILY) ?: return null,
            )
          }
          if (theme.scalesType) {
            put("fontSize", scaled(name, "fontSize"))
            put("lineHeight", scaled(name, "lineHeight"))
          }
        }
        ScreenValue.Chain(
          receiver = base,
          links = listOf(role(name), ChainLink("$TEXT_STYLE.copy", named = named, member = true)),
          typeFqn = TEXT_STYLE,
        )
      }
      return ScreenValue.Chain(
        receiver = base,
        links = listOf(ChainLink("$TYPOGRAPHY.copy", named = changed, member = true)),
        typeFqn = TYPOGRAPHY,
      )
    }

    /**
     * The three shape roles the canvas derives from one corner radius — `large` at the radius,
     * `medium` at three quarters, `small` at half — or the baseline `Shapes()` when the theme sets
     * none, which is the same 16dp the canvas defaults to.
     */
    private fun themeShapes(radius: Float?): ScreenValue? {
      if (radius == null) return ScreenValue.Construct(SHAPES, typeFqn = SHAPES)
      fun corner(factor: Double) =
        dp(radius * factor)?.let {
          ScreenValue.Construct(
            callableFqn = ROUNDED_CORNER_SHAPE_FQN,
            positional = listOf(it),
            typeFqn = ROUNDED_CORNER_SHAPE_FQN,
          )
        }
      return ScreenValue.Construct(
        callableFqn = SHAPES,
        named =
          mapOf(
            "small" to (corner(0.5) ?: return null),
            "medium" to (corner(0.75) ?: return null),
            "large" to (corner(1.0) ?: return null),
          ),
        typeFqn = SHAPES,
      )
    }

    /**
     * `FontFamily(Font(GoogleFont("Michroma"), provider, FontWeight.Normal), …)`: the weights a
     * type scale uses, each fetched from Google Fonts on first use, as `AndroidGoogleFonts` writes
     * them for the other exporters.
     */
    private fun googleFontFamily(family: String, provider: ScreenValue): ScreenValue =
      ScreenValue.Construct(
        callableFqn = FONT_FAMILY,
        positional =
          listOf("Normal", "Medium", "Bold").map { weight ->
            ScreenValue.Construct(
              callableFqn = "$GOOGLE_FONTS.Font",
              positional =
                listOf(
                  ScreenValue.Construct(
                    callableFqn = GOOGLE_FONT,
                    positional = listOf(ScreenValue.Text(ThemeTypefaces.familyName(family))),
                    typeFqn = GOOGLE_FONT,
                  ),
                  provider,
                  ScreenValue.Reference(FONT_WEIGHT, listOf(weight), typeFqn = FONT_WEIGHT),
                ),
              typeFqn = FONT,
            )
          },
        typeFqn = FONT_FAMILY,
      )

    /**
     * `FontFamily(SystemFont("Lobster", FontWeight.Normal), …)`: the same three weights as
     * [googleFontFamily], each looked up by family name in the desktop font manager — Compose
     * Multiplatform's `ui-text` on Skiko, which has no `GoogleFont`. An uninstalled family falls
     * back to the default face rather than failing, and the family is recorded so the caller can
     * say so ([SystemFontLookups]).
     *
     * `SystemFont` is `@ExperimentalTextApi`, an error-level opt-in, so each construct carries the
     * marker and the generator writes the `@OptIn` on the function that builds it.
     */
    private fun systemFontFamily(family: String): ScreenValue {
      val name = ThemeTypefaces.familyName(family)
      systemFontFamilies += name
      return ScreenValue.Construct(
        callableFqn = FONT_FAMILY,
        positional =
          listOf("Normal", "Medium", "Bold").map { weight ->
            ScreenValue.Construct(
              callableFqn = SYSTEM_FONT,
              positional =
                listOf(
                  ScreenValue.Text(name),
                  ScreenValue.Reference(FONT_WEIGHT, listOf(weight), typeFqn = FONT_WEIGHT),
                ),
              typeFqn = SYSTEM_FONT,
              requiredOptIns = listOf(EXPERIMENTAL_TEXT_API),
            )
          },
        typeFqn = FONT_FAMILY,
      )
    }

    /**
     * Google Play services' font provider, with its two published certificates decoded by
     * `kotlin.io.encoding.Base64` — the standard library's, so the generated file names nothing
     * outside Compose and Kotlin. See `AndroidGoogleFonts` for why a generated file carries them.
     */
    private fun googleFontProvider(): ScreenValue {
      fun list(items: List<ScreenValue>) =
        ScreenValue.Construct(LIST_OF, positional = items, typeFqn = LIST)
      val certificates =
        AndroidGoogleFonts.GMS_FONTS_CERTIFICATES.map { chunks ->
          list(
            listOf(
              ScreenValue.Chain(
                receiver =
                  ScreenValue.Reference(BASE64, listOf("Default"), typeFqn = "$BASE64.Default"),
                links =
                  listOf(
                    ChainLink(
                      "$BASE64.decode",
                      positional = listOf(ScreenValue.Text(chunks.joinToString(""))),
                      member = true,
                    )
                  ),
                typeFqn = "kotlin.ByteArray",
              )
            )
          )
        }
      return ScreenValue.Construct(
        callableFqn = GOOGLE_FONT_PROVIDER,
        named =
          mapOf(
            "providerAuthority" to ScreenValue.Text("com.google.android.gms.fonts"),
            "providerPackage" to ScreenValue.Text("com.google.android.gms"),
            "certificates" to list(certificates),
          ),
        typeFqn = GOOGLE_FONT_PROVIDER,
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
     * The Kotlin member a catalog enum value names, from [ENUM_MEMBERS], or a refusal by name.
     * Keyed per component because `style` means a typography role on `m3/text` and a variant on
     * `m3/button`; deriving members from the parameter type never compiled.
     */
    private fun enum(
      entry: String,
      componentId: String,
      property: String,
      where: String,
    ): ScreenValue? {
      if (property == ICON_KEY) {
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
      return pathValue(path, mapping.typeFqn)
    }

    /** Whether this catalog property's values are an enumeration one of the tables names. */
    private fun enumerated(componentId: String, property: String): Boolean =
      ENUM_MEMBERS[componentId]?.containsKey(property) == true ||
        property == ICON_KEY ||
        componentId to property in FACTORY_MEMBERS

    /**
     * The `ImageVector` an icon key names, or null when [ICON_MEMBERS] has no entry. A
     * [ScreenValue.Chain] because icons are extension properties on `Icons.Filled`, resolved
     * through an import rather than a qualified path.
     */
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

    private fun refuse(reason: String): Nothing? {
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

  /** Named once: a corner radius reaches it from a modifier and from a property alike. */
  private const val ROUNDED_CORNER_SHAPE_FQN =
    "androidx.compose.foundation.shape.RoundedCornerShape"
  private const val TEXT_STYLE = "androidx.compose.ui.text.TextStyle"
  private const val SURFACE_CATALOG_ID = "m3/surface"
  /** A board: the single root an Add beside wraps a design's items in. See `UiBuilderBoard`. */
  private const val BOARD_CATALOG_ID = "layout/column"
  private const val BOARD_SLOT = "children"
  private const val THEME = "androidx.compose.material3.MaterialTheme"
  private const val COLOR_SCHEME = "androidx.compose.material3.ColorScheme"
  private const val TYPOGRAPHY = "androidx.compose.material3.Typography"
  private const val SHAPES = "androidx.compose.material3.Shapes"
  private const val LIGHT_COLOR_SCHEME = "androidx.compose.material3.lightColorScheme"
  private const val DARK_COLOR_SCHEME = "androidx.compose.material3.darkColorScheme"
  private const val TEXT_UNIT = "androidx.compose.ui.unit.TextUnit"
  private const val FONT_FAMILY = "androidx.compose.ui.text.font.FontFamily"
  private const val FONT = "androidx.compose.ui.text.font.Font"
  private const val GOOGLE_FONTS = "androidx.compose.ui.text.googlefonts"
  private const val GOOGLE_FONT = "$GOOGLE_FONTS.GoogleFont"
  private const val GOOGLE_FONT_PROVIDER = "$GOOGLE_FONT.Provider"
  private const val SYSTEM_FONT = "androidx.compose.ui.text.platform.SystemFont"
  private const val EXPERIMENTAL_TEXT_API = "androidx.compose.ui.text.ExperimentalTextApi"
  private const val BASE64 = "kotlin.io.encoding.Base64"
  private const val LIST_OF = "kotlin.collections.listOf"
  private const val LIST = "kotlin.collections.List"

  /**
   * The three `MaterialTheme` accessors a theme role reads, by name, and their types — the
   * parameters of the theme function a themed root is projected inside. See `Pass.pathValue`.
   */
  private val THEME_HOLDERS: Map<String, String> =
    linkedMapOf("colorScheme" to COLOR_SCHEME, "typography" to TYPOGRAPHY, "shapes" to SHAPES)

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
    /** A whole number the parameter takes as an `Int` — a tab row's selected index. */
    INT,
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
   * The Compose parameter each catalog property sets, where the names differ (`color` →
   * `containerColor`). The generator refuses unknown parameter names, so without this many
   * builder-authored components would refuse.
   *
   * `weight` is deliberately absent: its legality depends on the slot, so it goes through
   * `weightLink` and [SLOT_SCOPES].
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
      "m3/elevated-card" to
        mapOf(
          "shape" to ParameterTarget("shape", TargetKind.SHAPE_TOKEN),
          "containerColor" to ParameterTarget("colors", TargetKind.CARD_COLORS),
          "elevationDp" to ParameterTarget("elevation", TargetKind.CARD_ELEVATION),
        ),
      "m3/outlined-card" to
        mapOf(
          "shape" to ParameterTarget("shape", TargetKind.SHAPE_TOKEN),
          "containerColor" to ParameterTarget("colors", TargetKind.CARD_COLORS),
          "elevationDp" to ParameterTarget("elevation", TargetKind.CARD_ELEVATION),
        ),
      // `AlertDialog` takes the surface's two, under the surface's names.
      "m3/dialog" to
        mapOf(
          "shapeDp" to ParameterTarget("shape", TargetKind.ROUNDED_CORNER_SHAPE),
          "tonalElevationDp" to ParameterTarget("tonalElevation", TargetKind.DP),
        ),
      "m3/filter-chip" to mapOf("shape" to ParameterTarget("shape", TargetKind.SHAPE_TOKEN)),
      "m3/horizontal-divider" to
        mapOf("thicknessDp" to ParameterTarget("thickness", TargetKind.DP)),
      "m3/search-bar" to
        mapOf(
          "shapeDp" to ParameterTarget("shape", TargetKind.ROUNDED_CORNER_SHAPE),
          "tonalElevationDp" to ParameterTarget("tonalElevation", TargetKind.DP),
        ),
      // The field's text is `query`. A literal is a field that only shows it; a state read is
      // refused elsewhere while stateful authoring is off, exactly as any other state read is.
      "m3/search-input-field" to mapOf("value" to ParameterTarget("query", TargetKind.RENAME)),
      "m3/icon" to
        mapOf(
          "iconKey" to ParameterTarget("imageVector", TargetKind.RENAME),
          "color" to ParameterTarget("tint", TargetKind.RENAME),
        ),
      // The builder has always called this `selectedIndex` — the frozen catalog names it that,
      // the inspector edits it under that name, and `CapabilityComposeCodeExporter` has written
      // `selectedTabIndex = …` from it since it was added. This lane had no such rename, so a
      // published `m3/primary-tab-row` refused with "`PrimaryTabRow` has no parameter
      // `selectedIndex`" — the property is right and the two exporters disagreed about it.
      "m3/primary-tab-row" to
        mapOf("selectedIndex" to ParameterTarget("selectedTabIndex", TargetKind.INT)),
      "m3/primary-scrollable-tab-row" to
        mapOf("selectedIndex" to ParameterTarget("selectedTabIndex", TargetKind.INT)),
      "asset/image" to mapOf("assetKey" to ParameterTarget("painter", TargetKind.ASSET_PAINTER)),
      // Three of the four styles; `fab` overrides this in `COMPONENT_VARIANTS` because it takes a
      // bare `Color` on a different parameter.
      "m3/button" to mapOf("containerColor" to ParameterTarget("colors", TargetKind.BUTTON_COLORS)),
      // Same name on both sides, so this entry exists for the KIND rather than for a rename: the
      // catalog carries a number and Compose takes `() -> Float`.
      "m3/progress-indicator" to
        mapOf("progress" to ParameterTarget("progress", TargetKind.FLOAT_LAMBDA)),
      "m3/linear-progress-indicator" to
        mapOf("progress" to ParameterTarget("progress", TargetKind.FLOAT_LAMBDA)),
      "m3/circular-progress-indicator" to
        mapOf("progress" to ParameterTarget("progress", TargetKind.FLOAT_LAMBDA)),
      // Same name on both sides again, and again for the kind: the catalog holds a number and
      // `Slider` takes a `Float`, which a whole number in the document would not render as.
      SLIDER to mapOf("value" to ParameterTarget("value", TargetKind.FLOAT)),
      "layout/row" to
        mapOf(
          "horizontalSpacingDp" to
            ParameterTarget("horizontalArrangement", TargetKind.SPACED_BY_HORIZONTAL)
        ),
      // Both axes, because a flow row has both: along a line it arranges like a row, and down the
      // lines it arranges like a column.
      "layout/flow-row" to
        mapOf(
          "horizontalSpacingDp" to
            ParameterTarget("horizontalArrangement", TargetKind.SPACED_BY_HORIZONTAL),
          "verticalSpacingDp" to
            ParameterTarget("verticalArrangement", TargetKind.SPACED_BY_VERTICAL),
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
   * The nine two-axis alignments a catalog spells, as the `Alignment` members they name.
   *
   * Named once and read by three callers: `layout/box`'s `contentAlignment`, the `alignment`
   * property a box's children carry (see [BOX_ALIGNED]), and `asset/image`'s own `alignment` — in
   * this lane through [BOX_ALIGNMENT_MEMBERS], and in the Wear screen lane, which writes the same
   * foundation component from the same catalog and would otherwise hold a second copy of this
   * vocabulary to disagree with.
   */
  val ALIGNMENT_MEMBERS: Map<String, String> =
    mapOf(
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

  /** `asset/image`'s four content scales, named once for the same reason as [ALIGNMENT_MEMBERS]. */
  val CONTENT_SCALE_MEMBERS: Map<String, String> =
    mapOf("crop" to "Crop", "fit" to "Fit", "fillBounds" to "FillBounds", "inside" to "Inside")

  private val BOX_ALIGNMENT_MEMBERS =
    members(ALIGNMENT, ALIGNMENT, *ALIGNMENT_MEMBERS.toList().toTypedArray())

  /**
   * The six ways a row distributes its children, and the six a column does.
   *
   * Named once for the reason [ALIGNMENT_MEMBERS] is: `layout/flow-row` reads BOTH of them — a flow
   * row arranges like a row along a line and like a column down the lines — and a third spelling of
   * the same twelve members is a third chance for them to disagree.
   */
  private val ROW_ARRANGEMENT_MEMBERS =
    members(
      ARRANGEMENT_HORIZONTAL,
      ARRANGEMENT,
      "start" to "Start",
      "center" to "Center",
      "end" to "End",
      "spaceBetween" to "SpaceBetween",
      "spaceAround" to "SpaceAround",
      "spaceEvenly" to "SpaceEvenly",
    )

  private val COLUMN_ARRANGEMENT_MEMBERS =
    members(
      ARRANGEMENT_VERTICAL,
      ARRANGEMENT,
      "top" to "Top",
      "center" to "Center",
      "bottom" to "Bottom",
      "spaceBetween" to "SpaceBetween",
      "spaceAround" to "SpaceAround",
      "spaceEvenly" to "SpaceEvenly",
    )

  /**
   * Which Kotlin member each catalog enum value names, per component and property (`semiBold` →
   * `FontWeight.SemiBold`). `m3/text`.`style` reuses [TYPOGRAPHY_TOKENS] so the token and enum
   * spellings (#339) cannot disagree.
   */
  private val ENUM_MEMBERS: Map<String, Map<String, EnumMembers>> =
    mapOf(
      "asset/image" to
        mapOf(
          "contentScale" to
            members(CONTENT_SCALE, CONTENT_SCALE, *CONTENT_SCALE_MEMBERS.toList().toTypedArray()),
          // The same nine as a box's, which is what they were before they were spelled out twice.
          "alignment" to BOX_ALIGNMENT_MEMBERS,
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
          "horizontalArrangement" to ROW_ARRANGEMENT_MEMBERS,
          "verticalAlignment" to
            members(
              ALIGNMENT_VERTICAL,
              ALIGNMENT,
              "top" to "Top",
              "center" to "CenterVertically",
              "bottom" to "Bottom",
            ),
        ),
      "layout/flow-row" to
        mapOf(
          "horizontalArrangement" to ROW_ARRANGEMENT_MEMBERS,
          "verticalArrangement" to COLUMN_ARRANGEMENT_MEMBERS,
        ),
      "layout/column" to
        mapOf(
          "verticalArrangement" to COLUMN_ARRANGEMENT_MEMBERS,
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

  private val ROW_AXIS =
    ArrangementAxis(
      property = "horizontalArrangement",
      spacing = "horizontalSpacingDp",
      parameter = "horizontalArrangement",
      typeFqn = ARRANGEMENT_HORIZONTAL,
      alignment = ALIGNMENT_HORIZONTAL,
      aligned = mapOf("start" to "Start", "center" to "CenterHorizontally", "end" to "End"),
    )

  private val COLUMN_AXIS =
    ArrangementAxis(
      property = "verticalArrangement",
      spacing = "verticalSpacingDp",
      parameter = "verticalArrangement",
      typeFqn = ARRANGEMENT_VERTICAL,
      alignment = ALIGNMENT_VERTICAL,
      aligned = mapOf("top" to "Top", "center" to "CenterVertically", "bottom" to "Bottom"),
    )

  /**
   * The axes each container arranges on, by component.
   *
   * A list rather than one axis because `layout/flow-row` has two of them and they are the same two
   * a row and a column already have — along a line it is a row, down the lines it is a column.
   */
  private val ARRANGEMENTS: Map<String, List<ArrangementAxis>> =
    mapOf(
      "layout/row" to listOf(ROW_AXIS),
      "layout/column" to listOf(COLUMN_AXIS),
      "layout/flow-row" to listOf(ROW_AXIS, COLUMN_AXIS),
    )

  /**
   * Catalog enum values that name a no-argument `@Composable` factory call rather than a member
   * (`pinned` → `TopAppBarDefaults.pinnedScrollBehavior()`), written as a [ScreenValue.Construct].
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
   * Colour roles that fill one `…Colors` bundle between them, so they are read together rather than
   * overwriting each other.
   *
   * @property roles the catalog properties, which are also the factory's parameter names unless
   *   [factoryNames] says otherwise. @property factoryNames the factory parameter a role fills,
   *   where the two are spelled apart.
   */
  private class ColorBundle(
    val parameter: String,
    val factoryFqn: String,
    val typeFqn: String,
    val roles: List<String>,
    val optIns: List<String>,
    val factoryNames: Map<String, String> = emptyMap(),
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
        ),
      "m3/horizontal-floating-toolbar" to
        ColorBundle(
          parameter = "colors",
          factoryFqn =
            "androidx.compose.material3.FloatingToolbarDefaults.standardFloatingToolbarColors",
          typeFqn = "androidx.compose.material3.FloatingToolbarColors",
          roles = listOf("containerColor"),
          optIns = listOf(EXPERIMENTAL_MATERIAL3_EXPRESSIVE),
          factoryNames = mapOf("containerColor" to "toolbarContainerColor"),
        ),
    )

  /**
   * Properties that configure a component's remembered state rather than its call (`TimePicker`'s
   * hour is `rememberTimePickerState(initialHour = …)`). Filled into the record's `noArgFactory`
   * placeholder. `m3/date-picker` is absent: its date string needs a conversion this projection
   * cannot express.
   *
   * @property arguments the catalog property, in the factory's parameter order, mapped to the
   *   factory parameter it fills.
   */
  private class StateBundle(
    val parameter: String,
    val factoryFqn: String,
    val typeFqn: String,
    val arguments: Map<String, StateArgument>,
    val optIns: List<String>,
  )

  /**
   * One factory parameter, and what the canvas does to the property before passing it, so the
   * export draws what the author saw.
   *
   * @property range the bounds the canvas clamps to, or null where the value is not a number.
   *     @property whenAbsent the value the canvas uses for an unset optional property; without it
   *       Material's default applies (for `is24Hour`, the device locale).
   */
  private class StateArgument(
    val parameter: String,
    val range: IntRange? = null,
    val whenAbsent: ScreenValue? = null,
  )

  private val STATE_BUNDLES: Map<String, StateBundle> =
    mapOf(
      "m3/time-picker" to
        StateBundle(
          parameter = "state",
          factoryFqn = "androidx.compose.material3.rememberTimePickerState",
          typeFqn = "androidx.compose.material3.TimePickerState",
          arguments =
            mapOf(
              "hour" to StateArgument("initialHour", range = 0..23),
              "minute" to StateArgument("initialMinute", range = 0..59),
              "is24Hour" to StateArgument("is24Hour", whenAbsent = ScreenValue.Bool(true)),
            ),
          // None: discovery records `TimePicker(state = rememberTimePickerState())` with an empty
          // `requiredOptIns`, so both halves of that call are stable API and claiming an opt-in
          // here would write an `@OptIn` the file does not need.
          optIns = emptyList(),
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
  private const val EXPERIMENTAL_MATERIAL3_EXPRESSIVE =
    "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
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

  private const val LIST_DETAIL_PANE_SCAFFOLD = "layout/list-detail-pane-scaffold"
  private const val SUPPORTING_PANE_SCAFFOLD = "layout/supporting-pane-scaffold"
  private const val SCAFFOLD = "layout/scaffold"
  private const val SCAFFOLD_CONTENT = "content"

  /** The document key a `Scaffold`'s content lambda parameter is read by. */
  private const val CONTENT_PADDING = "contentPadding"

  private val SCAFFOLD_PADDING_LINK =
    ChainLink(
      "androidx.compose.foundation.layout.padding",
      positional =
        listOf(
          ScreenValue.SlotParameterRead(
            CONTENT_PADDING,
            "androidx.compose.foundation.layout.PaddingValues",
          )
        ),
    )
  private const val PANE_LAYOUT_MODE = "layoutMode"
  private const val MAIN_PANE_VISIBLE = "mainPaneVisible"
  private const val SUPPORTING_PANE_VISIBLE = "supportingPaneVisible"
  private const val PANE_SPACING_DP = "paneSpacingDp"
  private const val MAIN_PANE_WIDTH_DP = "mainPanePreferredWidthDp"
  private const val SUPPORTING_PANE_WIDTH_DP = "supportingPanePreferredWidthDp"
  private const val ADAPTIVE = "androidx.compose.material3.adaptive"
  private const val ADAPTIVE_LAYOUT = "androidx.compose.material3.adaptive.layout"
  private const val THREE_PANE_SCOPE = "$ADAPTIVE_LAYOUT.ThreePaneScaffoldPaneScope"
  private const val LINEAR_GRADIENT = "shape/linear-gradient"
  private const val GRADIENT_START = "startColor"
  private const val GRADIENT_END = "endColor"
  private const val GRADIENT_DIRECTION = "direction"
  private const val BRUSH = "androidx.compose.ui.graphics.Brush"

  /** What the canvas draws when a dot sets no diameter, and so what the export writes. */
  private const val DEFAULT_DOT_DIAMETER = 8.0

  private const val REMEMBER_SCROLL_STATE = "androidx.compose.foundation.rememberScrollState"
  private const val SCROLL_STATE = "androidx.compose.foundation.ScrollState"

  /**
   * Catalog properties whose Compose spelling is an unscoped modifier link rather than a parameter
   * (`m3/icon`'s `sizeDp` → `Modifier.size`). Scoped links like `weight` do not belong here.
   */
  private val MODIFIER_PROPERTIES: Map<String, Map<String, String>> =
    mapOf(
      "m3/icon" to mapOf("sizeDp" to "$LAYOUT.size"),
      "m3/icon-button" to mapOf("sizeDp" to "$LAYOUT.size"),
      "m3/filled-icon-button" to mapOf("sizeDp" to "$LAYOUT.size"),
      "m3/filled-tonal-icon-button" to mapOf("sizeDp" to "$LAYOUT.size"),
      "m3/outlined-icon-button" to mapOf("sizeDp" to "$LAYOUT.size"),
    )

  private const val CARD_DEFAULTS = "androidx.compose.material3.CardDefaults"

  /**
   * One component a variant property selects.
   *
   * @property canonicalId the record to emit; there is no catalog id for e.g. `ElevatedCard`.
   *     @property defaults the `CardDefaults` prefix whose factories match this component. Carried
   *       per variant because all three return the same type, so the wrong one would compile with
   *       another component's colours.
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
     * The [ParameterTarget] this component takes for a property where it differs from the catalog
     * id's table — e.g. `FloatingActionButton` takes `containerColor: Color` directly rather than a
     * `ButtonColors` bundle
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
      // The catalog's own note names the precedent: `TimePicker` and `TimeInput` are "two
      // composables over one state, spelled as one component the way `m3/progress-indicator`
      // spells linear and circular". Identical parameter lists, so the only decision is which
      // one to call.
      "m3/time-picker" to "mode",
    )

  /**
   * Which component each variant value names (`m3/card` is `Card`, `ElevatedCard` or
   * `OutlinedCard`), matching the catalog's `code.imports`.
   *
   * `fab` is the one entry that is not a rename: `FloatingActionButton` has no `enabled`, takes
   * `containerColor` directly, and its content slot has no receiver — hence
   * [ComponentVariant.slotScopes].
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
      "m3/time-picker" to
        mapOf(
          "dial" to ComponentVariant(TIME_PICKER_ID, "timePicker"),
          "input" to ComponentVariant(TIME_INPUT_ID, "timePicker"),
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

  /** Concrete catalog ids replacing the legacy property-selected component families. */
  private val DIRECT_COMPONENTS: Map<String, ComponentVariant> =
    mapOf(
      "m3/elevated-card" to ComponentVariant(ELEVATED_CARD_ID, "elevatedCard", COLUMN_CONTENT),
      "m3/outlined-card" to ComponentVariant(OUTLINED_CARD_ID, "outlinedCard", COLUMN_CONTENT),
      "m3/filled-icon-button" to ComponentVariant(FILLED_ICON_BUTTON_ID, "filledIconButton"),
      "m3/filled-tonal-icon-button" to
        ComponentVariant(FILLED_TONAL_ICON_BUTTON_ID, "filledTonalIconButton"),
      "m3/outlined-icon-button" to ComponentVariant(OUTLINED_ICON_BUTTON_ID, "outlinedIconButton"),
      "m3/linear-progress-indicator" to
        ComponentVariant(LINEAR_INDICATOR_ID, "linearProgressIndicator"),
      "m3/circular-progress-indicator" to
        ComponentVariant(CIRCULAR_INDICATOR_ID, "circularProgressIndicator"),
      "m3/outlined-text-field" to ComponentVariant(OUTLINED_TEXT_FIELD_ID, "outlinedTextField"),
    )

  // The variant table names CALLABLES, not catalog components: a `style` of `elevated` on
  // `m3/card` means the design calls `ElevatedCard` instead of `Card`. They were written as
  // canonical ids — `m3-catalog/<fqn>` — and a canonical id carries the MODULE it was discovered
  // from. m3-catalog's shipped record was discovered from a module called `m3-catalog`; its own
  // repository discovers from `:catalog`, so every one of these missed on the catalog's own
  // record and three components refused with "no component `m3-catalog/…` in this catalog".
  //
  // Named by the callable alone, which is the part that is actually true of both. The record is
  // aliased with the same form by [callableAliases] so the generator can resolve it.
  private const val CARD_ID = "androidx.compose.material3.CardKt.Card"
  private const val ELEVATED_CARD_ID = "androidx.compose.material3.CardKt.ElevatedCard"
  private const val OUTLINED_CARD_ID = "androidx.compose.material3.CardKt.OutlinedCard"
  private const val BUTTON_ID = "androidx.compose.material3.ButtonKt.Button"
  private const val FILLED_TONAL_BUTTON_ID = "androidx.compose.material3.ButtonKt.FilledTonalButton"
  private const val TEXT_BUTTON_ID = "androidx.compose.material3.ButtonKt.TextButton"
  private const val FAB_ID =
    "androidx.compose.material3.FloatingActionButtonKt.FloatingActionButton"
  private const val ICON_BUTTON_ID = "androidx.compose.material3.IconButtonKt.IconButton"
  private const val FILLED_ICON_BUTTON_ID =
    "androidx.compose.material3.IconButtonKt.FilledIconButton"
  private const val FILLED_TONAL_ICON_BUTTON_ID =
    "androidx.compose.material3.IconButtonKt.FilledTonalIconButton"
  private const val OUTLINED_ICON_BUTTON_ID =
    "androidx.compose.material3.IconButtonKt.OutlinedIconButton"
  private const val TIME_PICKER_ID = "androidx.compose.material3.TimePickerKt.TimePicker"
  private const val TIME_INPUT_ID = "androidx.compose.material3.TimePickerKt.TimeInput"
  private const val TEXT_FIELD_ID = "androidx.compose.material3.TextFieldKt.TextField"
  // `OutlinedTextFieldKt`, not `TextFieldKt`: Material declares the outlined field in its own
  // file. The alias `callableAliases()` adds is the record's canonical id minus the module, so an
  // id naming the wrong file resolves to nothing and the variant refuses — which is what this one
  // did, on every authored outlined field, until the parity test below started authoring every
  // variant rather than the first.
  private const val OUTLINED_TEXT_FIELD_ID =
    "androidx.compose.material3.OutlinedTextFieldKt.OutlinedTextField"
  private const val LINEAR_INDICATOR_ID =
    "androidx.compose.material3.ProgressIndicatorKt.LinearProgressIndicator"
  private const val CIRCULAR_INDICATOR_ID =
    "androidx.compose.material3.ProgressIndicatorKt.CircularProgressIndicator"

  /**
   * Properties that are builder bookkeeping rather than design values, spent rather than refused.
   *
   * `scrollStateKey` is required on lazy containers, so refusing would make them unexportable;
   * dropping is safe because the old exporter only used these for a `key("…")` wrapper that changes
   * no pixel. `span` is deliberately not here — see `unplaceable`.
   */
  private val IDENTITY_PROPERTIES: Set<String> = setOf("scrollStateKey", "stableKey")

  private const val SPAN = "span"

  private const val BUTTON_DEFAULTS = "androidx.compose.material3.ButtonDefaults"

  private const val PROGRESS_INDICATOR = "m3/progress-indicator"
  private const val PROGRESS = "progress"
  private const val INDETERMINATE = "indeterminate"
  private const val WEIGHT = "weight"
  private const val ROW_SCOPE = "androidx.compose.foundation.layout.RowScope"
  // `FlowRowScope` extends `RowScope`, so a weighted child inside a flow row is legal for the
  // same reason it is inside a row — but the generator checks the scope it was told, not its
  // supertypes, so the real one has to be named.
  private const val FLOW_ROW_SCOPE = "androidx.compose.foundation.layout.FlowRowScope"
  /** The card whose content is a box (see `cardContentBox`), and the box it becomes. */
  private const val CARD_CATALOG_ID = "m3/card"
  private const val CARD_CONTENT_SLOT = "content"
  private const val BOX_CATALOG_ID = "layout/box"

  // These layout composables have a modifier parameter and no onClick parameter. Controls keep
  // their declared callbacks, including their own enabled state and interaction behavior.
  private val MODIFIER_CLICK_COMPONENTS = setOf("layout/box", "layout/row", "layout/column")
  private const val BOX_CHILDREN_SLOT = "children"

  private const val COLUMN_SCOPE = "androidx.compose.foundation.layout.ColumnScope"
  private const val BOX_SCOPE = "androidx.compose.foundation.layout.BoxScope"

  /**
   * The receiver member each of a DSL slot's children is wrapped in (`LazyColumn`'s children are
   * declared with `item { … }`, #394). `ScreenGenerator` checks the scope against the record's
   * `TargetParameter.scopeDslReceiver`.
   *
   * No per-child `key`: a [SlotItem] wraps the whole slot, which costs only reorder identity in a
   * static screen. Public so `M3CatalogSlotScopeTest` can check it against the shipped record.
   */
  val SLOT_ITEMS: Map<String, Map<String, SlotItem>> =
    mapOf(
      "layout/lazy-column" to mapOf("items" to SlotItem("item", LAZY_LIST_SCOPE)),
      "layout/lazy-row" to mapOf("items" to SlotItem("item", LAZY_LIST_SCOPE)),
      "layout/lazy-grid" to mapOf("items" to SlotItem("item", LAZY_GRID_SCOPE)),
    )

  private const val LAZY_LIST_SCOPE = "androidx.compose.foundation.lazy.LazyListScope"
  private const val LAZY_GRID_SCOPE = "androidx.compose.foundation.lazy.grid.LazyGridScope"

  /**
   * The receiver each catalog slot's children are composed under, keyed by catalog slot name. Must
   * agree with the record's `composableSlotReceiver` (pinned by `M3CatalogSlotScopeTest`); a slot
   * with no entry composes under no receiver, which is why a `weight` there refuses.
   */
  val SLOT_SCOPES: Map<String, Map<String, String>> =
    mapOf(
      "layout/column" to mapOf("children" to COLUMN_SCOPE),
      "layout/row" to mapOf("children" to ROW_SCOPE),
      "layout/flow-row" to mapOf("children" to FLOW_ROW_SCOPE),
      "layout/box" to mapOf("children" to BOX_SCOPE),
      // A colour dot is `Box` by alias (see `colourDot`), so the record attests `Box`'s
      // `content` slot for it too. Keyed by the parameter name because the catalog gives the dot
      // no slot at all — nothing ever fills this, and the claim exists so the drift check that
      // compares this table with the record stays exact.
      COLOUR_DOT to mapOf("content" to BOX_SCOPE),
      // The same claim for the same reason: a linear gradient is `Box` by alias too (see
      // `linearGradient`), and the catalog gives it no slot either.
      LINEAR_GRADIENT to mapOf("content" to BOX_SCOPE),
      // `Card`'s own slot, which the record attests as a `ColumnScope` — and what sits in it is
      // the one `layout/box` `cardContentBox` emits, never a design's node. A card's children are
      // composed inside that box, under `BoxScope`, which is why this row is not what they get.
      "m3/card" to mapOf("content" to COLUMN_SCOPE),
      "m3/button" to mapOf("content" to ROW_SCOPE),
      "m3/horizontal-floating-toolbar" to mapOf("content" to ROW_SCOPE),
      LIST_DETAIL_PANE_SCAFFOLD to
        mapOf(
          "listPane" to THREE_PANE_SCOPE,
          "detailPane" to THREE_PANE_SCOPE,
          "extraPane" to THREE_PANE_SCOPE,
          "paneExpansionDragHandle" to "$ADAPTIVE_LAYOUT.ThreePaneScaffoldScope",
        ),
      SUPPORTING_PANE_SCAFFOLD to
        mapOf(
          "mainPane" to THREE_PANE_SCOPE,
          "supportingPane" to THREE_PANE_SCOPE,
          "extraPane" to THREE_PANE_SCOPE,
          "paneExpansionDragHandle" to "$ADAPTIVE_LAYOUT.ThreePaneScaffoldScope",
        ),
      // Keyed by the parameter, like the colour dot's: the catalog's slot is `expandedContent`, and
      // SLOT_PARAMETERS renames it to `content` before anything reads this.
      "m3/search-bar" to mapOf("content" to COLUMN_SCOPE),
    )

  private const val ICONS_PACKAGE = "androidx.compose.material.icons"
  private const val ICONS = "$ICONS_PACKAGE.Icons"
  private const val IMAGE_VECTOR = "androidx.compose.ui.graphics.vector.ImageVector"

  /**
   * The catalog property whose values are icon keys rather than members of a type, on ANY
   * component: the builder's icon picker writes `iconKey` wherever a component draws an icon, and
   * the keys are one vocabulary ([ICON_MEMBERS]). Keyed by property, not by component, because a
   * table naming `m3/icon` left every other catalog's icon — `glimmer/icon`, whose keys are the
   * same `filled/info` — refused as "`ImageVector` has no literal", with no way for the catalog to
   * say otherwise.
   */
  private const val ICON_KEY = "iconKey"

  /**
   * Where [ICON_KEY] goes when [PROPERTY_PARAMETERS] says nothing for the component: an icon
   * composable's `imageVector`, as Material 3's and Glimmer's `Icon` both name it. A component
   * whose icon parameter is called something else keeps an explicit entry there.
   */
  private val ICON_KEY_TARGET = ParameterTarget("imageVector", TargetKind.RENAME)

  /**
   * Which icon each catalog `iconKey` names, as the member path under `Icons`, generated from the
   * Material Icons artifact the canvas uses. Exports need `material-icons-extended` on the
   * consumer's classpath.
   */
  val ICON_MEMBERS: Map<String, String> = GeneratedMaterialIconMembers

  /**
   * Properties whose values name no member of anything, each with its own refusal reason
   * ([compose-preview-server#394](https://github.com/yschimke/compose-preview-server/issues/394)).
   * Ids that [COMPONENT_VARIANTS] or `supportingPanes` can express have left this table.
   *
   * Each value completes "…`$entry`, which …".
   */
  private val VARIANT_PROPERTIES: Map<Pair<String, String>, String> =
    mapOf(
      ("layout/horizontal-carousel" to "kind") to
        "names which carousel function to call rather than an argument to one, and no record " +
          "selects a carousel yet: its `items` is a `CarouselScope` DSL, which is a slot shape " +
          "this projection cannot emit"
    )
}
