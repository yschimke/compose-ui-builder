package ee.schimke.composeai.uibuilder.canvas.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.LocalContentColor as WearLocalContentColor
import androidx.wear.compose.material3.Text as WearText
import ee.schimke.composeai.uibuilder.canvas.BuilderIcon
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderSurfaceDocument
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderUnrolled
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.canvas.NativeOnlyPlaceholder
import ee.schimke.composeai.uibuilder.canvas.UiBuilderCanvasAddon
import ee.schimke.composeai.uibuilder.canvas.WearWidgetContainerScaffold
import ee.schimke.composeai.uibuilder.canvas.bool
import ee.schimke.composeai.uibuilder.canvas.color
import ee.schimke.composeai.uibuilder.canvas.float
import ee.schimke.composeai.uibuilder.canvas.fontStyle
import ee.schimke.composeai.uibuilder.canvas.fontWeight
import ee.schimke.composeai.uibuilder.canvas.integer
import ee.schimke.composeai.uibuilder.canvas.layoutWeightValue
import ee.schimke.composeai.uibuilder.canvas.lineCount
import ee.schimke.composeai.uibuilder.canvas.string
import ee.schimke.composeai.uibuilder.canvas.textAlign
import ee.schimke.composeai.uibuilder.canvas.textDecoration
import ee.schimke.composeai.uibuilder.canvas.textOverflow
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapter
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasNodeScope
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import ee.schimke.wearcmp.port.LocalWearDeviceConfiguration

/**
 * The Wear and Remote Compose Material 3 canvas, drawn in-process by hosts that cannot load a
 * catalog's Wasm renderer runtime.
 *
 * `:ui-builder` draws Material 3 and nothing else. `wear-m3` and `remote-m3` are published by
 * `wear-m3-catalog` with their own renderer runtimes, which is how the browser editor draws them;
 * the desktop app, the IntelliJ plugin and the server's render bundle have no browser to host one,
 * and draw them with this instead. It is found on the JVM classpath through `ServiceLoader`
 * (`META-INF/services`), so adding the module is the whole of enabling it.
 *
 * Every adapter here is the branch `UiBuilderRenderer` used to carry for the same id, moved onto
 * the SDK's `CanvasAdapterRegistry` unchanged: the node is resolved and the modifier carries the
 * authored modifiers, bounds and click action before an adapter is called, exactly as the branch
 * received them.
 */
class WearCanvasAddon : UiBuilderCanvasAddon {

  override val adapters: CanvasAdapterRegistry = WEAR_CANVAS_ADAPTERS

  override fun rootColorScheme(rootAdapterId: String): ColorScheme? = WearDarkColorScheme.takeIf {
    rootAdapterId == ROUND_SCREEN_FRAME
  }

  // 26dp on a Wear screen: measured off the reference card's corner, where the first drawn row is
  // inset 26dp from each side and reaches full width 26dp down. Material 3's 16dp default draws a
  // recognisably different card, and the card is most of what a Wear list is.
  override fun rootCornerRadiusDp(rootAdapterId: String): Float? =
    WEAR_CARD_CORNER_RADIUS_DP.takeIf {
      rootAdapterId == ROUND_SCREEN_FRAME
    }

  override fun rootAlignment(adapterId: String): Alignment? =
    when {
      adapterId.startsWith(WIDGET_CONTAINER_PREFIX) -> Alignment.Center
      // A screen is taller than its frame by design — the stadium IS the scroll extent — so it is
      // pinned to the top and centred across, the way a long screenshot reads.
      adapterId == ROUND_SCREEN_FRAME -> Alignment.TopCenter
      else -> null
    }

  // The watch the components inside this design are laid out against — see
  // [wearDeviceConfiguration] for what the browser answers when nobody says. The trigger is the
  // PLATFORM the catalog declares: a board holding one Wear card needs a watch as much as a whole
  // screen does, and so does a shelf thumbnail of one picker.
  @Composable
  override fun surfaceLocals(
    document: UiBuilderDocument,
    platform: String,
  ): Array<ProvidedValue<*>> =
    if (platform == UiBuilderCatalogPlatform.WEAR.wireValue) {
      arrayOf(
        LocalWearDeviceConfiguration provides
          document.wearDeviceConfiguration(LocalUiBuilderFrameGeometry.current)
      )
    } else {
      emptyArray()
    }
}

private const val WIDGET_CONTAINER_PREFIX = "remote-m3/widget-container-"

/**
 * A slot drawn inside a Wear container. The container sets Wear's content colour — `onPrimary`
 * inside a filled button — and a child drawn with Material 3's `Text` reads Material 3's, which the
 * surface pins to the design's `onBackground`. Without this bridge a widget button's label was
 * light on light.
 */
@Composable
private fun CanvasNodeScope.WearSlot(name: String, modifier: Modifier = Modifier) {
  CompositionLocalProvider(LocalContentColor provides WearLocalContentColor.current) {
    Slot(name, modifier)
  }
}

private fun CanvasNodeScope.has(slot: String): Boolean = itemCount(slot) > 0

/** A label that is either a slot's children or, when the slot is empty, the property's text. */
@Composable
private fun CanvasNodeScope.Label(slot: String) {
  if (!has(slot)) Text(node.string(slot)) else WearSlot(slot)
}

/** The optional secondary label: the slot, else the property's text, else none at all. */
private fun CanvasNodeScope.secondaryLabel(): (@Composable RowScope.() -> Unit)? =
  when {
    has("secondaryLabel") -> ({ WearSlot("secondaryLabel") })
    node.string("secondaryLabel").isNotEmpty() -> ({ Text(node.string("secondaryLabel")) })
    else -> null
  }

/**
 * The ids given a real adapter — not a native-only placeholder. Filled by [WEAR_CANVAS_ADAPTERS].
 */
private val drawnAdapterIds = mutableSetOf<String>()

/**
 * The adapter ids this canvas draws as the real component, for the test that holds the catalog's
 * `supported` declarations to them.
 */
internal val WEAR_DRAWN_ADAPTER_IDS: Set<String>
  get() = WEAR_CANVAS_ADAPTERS.let { drawnAdapterIds.toSet() }

private val WEAR_CANVAS_ADAPTERS: CanvasAdapterRegistry = canvasAdapterRegistry {
  val drawn = drawnAdapterIds
  fun draw(id: String, adapter: CanvasAdapter) {
    drawn += id
    register(id, adapter)
  }
  // Both container sizes, framed in whichever host shape is being viewed. The footprint is read
  // from `hostSpec` rather than written here, so this canvas and the native render beside it cannot
  // disagree about what the host reserves — see `WearWidgetHostSpec`.
  for (size in WearWidgetScaffoldSize.entries) {
    draw(size.componentId) {
      WearWidgetContainerScaffold(
        node = node,
        modifier = modifier,
        spec = size.hostSpec(LocalWearWidgetHostShape.current),
        brushes = { next -> Slot("background", next) },
        hasBrushes = has("background"),
      ) {
        Slot("content", Modifier.fillMaxSize())
      }
    }
  }
  // **Experimental.** The adaptive widget, edited at Large because Large is the size where every
  // slot shows. This is `AdaptiveWearWidget.resolve`'s Large arrangement drawn over the authored
  // slots rather than over the resolved design, so each node the designer placed stays the node
  // they select; the preview panes beside it draw the resolved design at both sizes.
  draw(AdaptiveWearWidget.COMPONENT_ID) {
    WearWidgetContainerScaffold(
      node = node,
      modifier = modifier,
      spec = WearWidgetScaffoldSize.Large.hostSpec(LocalWearWidgetHostShape.current),
      brushes = { next -> Slot(AdaptiveWearWidget.BACKGROUND, next) },
      hasBrushes = has(AdaptiveWearWidget.BACKGROUND),
    ) {
      Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AdaptiveWearWidget.ACTION_SPACING_DP.dp),
      ) {
        Column(
          Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(AdaptiveWearWidget.TEXT_SPACING_DP.dp),
        ) {
          Slot(AdaptiveWearWidget.HEADLINE)
          Slot(AdaptiveWearWidget.SUPPORTING)
        }
        Slot(AdaptiveWearWidget.ACTION)
      }
    }
  }
  // The Wear screen. Unlike the widget container above, this stand-in is EMITTED rather than
  // erased: `ScreenScaffold` is a composable the author calls, so `WearScreenCodeExporter` names
  // it.
  draw(ROUND_SCREEN_FRAME) {
    val frame = LocalUiBuilderFrameGeometry.current
    // Always provided: `UiBuilderSurface` is the only caller of a registered adapter.
    val document = checkNotNull(LocalUiBuilderSurfaceDocument.current)
    WearScreenScaffold(
      node = node,
      modifier = modifier,
      frame = frame,
      screenWidthDp = document.wearScreenWidthDp(frame),
      edgeButton = { next -> Slot("edgeButton", next) },
      edgeButtonSize =
        node.slots["edgeButton"]?.firstOrNull()?.let { document.nodes[it]?.string("size") ?: "" },
    ) { next ->
      Slot("content", next)
    }
  }
  draw("wear-m3/list-header") {
    WearCanvasListHeader(
      text = node.string("text"),
      modifier = modifier,
      // The label's truncation, which upstream's `ListHeader` cannot take — it takes a content
      // lambda — so it belongs on the `Text` inside.
      maxLines = node.lineCount("maxLines"),
      overflow = node.textOverflow(),
    )
  }
  draw("wear-m3/list-sub-header") {
    WearCanvasListSubHeader(
      text = node.string("text"),
      modifier = modifier,
      maxLines = node.lineCount("maxLines"),
      overflow = node.textOverflow(),
    )
  }
  draw("wear-m3/switch-button") {
    WearCanvasSwitchButton(
      checked = node.bool("checked"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
      label = { Label("label") },
      secondaryLabel = secondaryLabel(),
    )
  }
  draw("wear-m3/slider") {
    WearCanvasSlider(
      value = node.float("value"),
      valueFrom = node.float("valueFrom"),
      valueTo = node.float("valueTo", 1f),
      steps = node.integer("steps"),
      segmented = node.bool("segmented"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
    )
  }
  draw("wear-m3/checkbox-button") {
    WearCanvasCheckboxButton(
      checked = node.bool("checked"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
      label = { Label("label") },
      secondaryLabel = secondaryLabel(),
    )
  }
  draw("wear-m3/radio-button") {
    WearCanvasRadioButton(
      selected = node.bool("selected"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
      label = { Label("label") },
      secondaryLabel = secondaryLabel(),
    )
  }
  draw("wear-m3/stepper") {
    WearCanvasStepper(
      value = node.float("value"),
      valueFrom = node.float("valueFrom"),
      valueTo = node.float("valueTo", 1f),
      steps = node.integer("steps"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
    ) {
      WearSlot("content")
    }
  }
  draw("wear-m3/progress-indicator") {
    WearCanvasProgressIndicator(
      variant = node.string("variant"),
      progress = node.float("progress"),
      segments = node.integer("segments", 1),
      enabled = node.bool("enabled", true),
      modifier = modifier,
    )
  }
  draw("wear-m3/page-indicator") {
    WearCanvasPageIndicator(vertical = node.string("variant") == "vertical", modifier = modifier)
  }
  draw("wear-m3/edge-button") {
    WearCanvasEdgeButton(
      size = node.string("size"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
    ) {
      WearSlot("content")
    }
  }
  draw("wear-m3/button-group") {
    val document = checkNotNull(LocalUiBuilderSurfaceDocument.current)
    WearCanvasButtonGroup(
      weights = node.slots["children"].orEmpty().map { document.nodes[it]?.layoutWeightValue() },
      modifier = modifier,
    ) { index, weighted ->
      Item("children", index, weighted)
    }
  }
  draw("wear-m3/icon-button") {
    WearCanvasIconButton(
      variant = node.string("variant"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
      containerColor = node.wearColor("containerColor"),
      contentColor = node.wearColor("contentColor"),
    ) {
      WearSlot("content")
    }
  }
  draw("wear-m3/text-button") {
    WearCanvasTextButton(
      variant = node.string("variant"),
      enabled = node.bool("enabled", true),
      modifier = modifier,
    ) {
      WearSlot("content")
    }
  }
  // Routed to the canvas's own icon drawer rather than to Wear's `Icon`: an icon is a tinted vector
  // at a size on both platforms, and `BuilderIcon` owns the key table, the tint resolution and the
  // structured-path export the SVG lane needs.
  draw("wear-m3/icon") { BuilderIcon(node, modifier) }
  // Wear's own `Text`, reading every property the mobile text reads; the style resolves the role
  // names against Wear's own type scale.
  draw("wear-m3/text") {
    WearText(
      node.string("text"),
      modifier,
      color = node.color("color", Color.Unspecified),
      style = wearTextStyle(node.string("style")),
      fontWeight = node.fontWeight(),
      fontStyle = node.fontStyle(),
      fontSize = node.float("fontSizeSp").takeIf { it > 0f }?.sp ?: TextUnit.Unspecified,
      lineHeight = node.float("lineHeightSp").takeIf { it > 0f }?.sp ?: TextUnit.Unspecified,
      letterSpacing =
        node.float("letterSpacingSp").takeIf { "letterSpacingSp" in node.properties }?.sp
          ?: TextUnit.Unspecified,
      textDecoration = node.textDecoration(),
      minLines = node.integer("minLines", 1),
      maxLines = node.integer("maxLines", Int.MAX_VALUE),
      softWrap = node.bool("softWrap", true),
      overflow = node.textOverflow(),
      textAlign = node.textAlign(),
      onTextLayout = { recordTextLayout(it) },
    )
  }
  draw("wear-m3/card") { WearCanvasCard(node.string("variant"), modifier) { WearSlot("content") } }
  draw("wear-m3/button") {
    WearCanvasButton(
      node.string("variant"),
      node.bool("enabled", true),
      modifier,
      containerColor = node.wearColor("containerColor"),
      contentColor = node.wearColor("contentColor"),
    ) {
      WearSlot("content")
    }
  }
  // The dialogs, drawn only when the document says they are showing: `visible` is the flag the
  // generated screen hangs them on, and a canvas that drew every dialog at once would describe a
  // screen nobody can reach.
  draw("wear-m3/alert-dialog") {
    if (node.bool("visible", true)) {
      WearCanvasAlertDialog(
        title = node.string("title"),
        text = node.string("text"),
        modifier = modifier,
        hasConfirm = has("confirmButton"),
      ) {
        Slot("content")
      }
    }
  }
  draw("wear-m3/confirmation-dialog") {
    if (node.bool("visible", true)) {
      WearCanvasConfirmationDialog(
        text = node.string("text"),
        variant = node.string("variant"),
        modifier = modifier,
      )
    }
  }
  draw("wear-m3/open-on-phone-dialog") {
    if (node.bool("visible", true)) {
      WearCanvasOpenOnPhoneDialog(text = node.string("text"), modifier = modifier)
    }
  }
  draw("wear-m3/date-picker") {
    WearCanvasDatePicker(
      initialDate = node.string("initialDate"),
      type = node.string("type"),
      modifier = modifier,
    )
  }
  draw("wear-m3/time-picker") {
    WearCanvasTimePicker(
      initialTime = node.string("initialTime"),
      type = node.string("type"),
      modifier = modifier,
    )
  }
  draw("wear-m3/transforming-lazy-column") {
    if (LocalUiBuilderUnrolled.current) {
      // At the extent the list is a Column: no viewport, no row transformation, and the rows are
      // the list's unscaled layout — the `ScrollMode.LONG` reference, whose stitch turns the
      // transformation off. The real lazy layout cannot be measured against an unbounded height.
      Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp", 4f).dp),
      ) {
        Slot("items")
      }
    } else {
      // The real lazy column, scaling and fading its rows through the library's own
      // `transformedHeight`.
      WearCanvasTransformingLazyColumn(
        itemCount = itemCount("items"),
        verticalSpacingDp = node.float("verticalSpacingDp", 4f),
        modifier = modifier,
        transformation = node.string("transformation") != "none",
      ) { index, itemModifier ->
        Item("items", index, itemModifier)
      }
    }
  }
  // The Lottie element, drawn as its identity and place: the animation is compiled into the
  // document's operations by Horologist's `LottieAnimation` at export time, and no canvas can run
  // that Android-only creation API.
  draw("remote-m3/lottie") { LottiePlaceholder(node, modifier) }
  // A Wear component with no Material 3 counterpart, drawn as a named placeholder and not as a
  // lookalike. See `NativeOnlyPlaceholder` for why this is the honest shape.
  // Only the ids nothing above draws: the branch this replaces sat after the drawn ones in one
  // `when`, so a component with a real adapter never reached it.
  for (id in WEAR_NATIVE_ONLY - drawn) {
    register(id) {
      NativeOnlyPlaceholder(node, modifier) { node.slots.keys.forEach { name -> Slot(name) } }
    }
  }
}

/** A Wear property's colour: a literal as itself, a role through Wear's own scheme. */
@Composable
internal fun UiBuilderNode.wearColor(name: String): Color {
  val value = string(name)
  if (value.startsWith("#")) return color(name, Color.Unspecified)
  return wearThemeColor(value)
}
