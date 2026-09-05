@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcCustomComponentRegistry
import ee.schimke.composeai.rcplayer.compose.RcCustomContent
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.ProjectOwnedJetcasterArtwork
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class UiBuilderLayer {
  Design,
  EditorOverlay,
}

/**
 * Export-only pinned raster assets; normal Wasm rendering retains its deterministic placeholder.
 */
internal val LocalUiBuilderExportRasterAssets =
  staticCompositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }

/** Export-only path drawing avoids Skia SVG color-filter layers becoming anonymous PNG images. */
internal val LocalUiBuilderExportStructuredIcons = staticCompositionLocalOf { false }

private val LocalUiBuilderTypeScale = staticCompositionLocalOf { 1f }
private val LocalUiBuilderCornerRadius = staticCompositionLocalOf { 16f }

fun uiBuilderLayers(editorOverlay: Boolean): List<UiBuilderLayer> =
  if (editorOverlay) listOf(UiBuilderLayer.Design, UiBuilderLayer.EditorOverlay)
  else listOf(UiBuilderLayer.Design)

sealed interface UiBuilderSemanticActionResult {
  data object Applied : UiBuilderSemanticActionResult

  data class Rejected(val code: String, val message: String) : UiBuilderSemanticActionResult
}

internal data class UiBuilderSemanticActionEntry(
  val enabled: Boolean = true,
  val activate: (() -> Unit)? = null,
  val scrollBy: ((Float) -> Float)? = null,
)

class UiBuilderSemanticActionController {
  private var entries = emptyMap<String, UiBuilderSemanticActionEntry>()
  private var viewportBounds: UiBuilderPixelBounds? = null

  internal fun install(
    value: Map<String, UiBuilderSemanticActionEntry>,
    viewport: UiBuilderPixelBounds?,
  ) {
    entries = value
    viewportBounds = viewport
  }

  fun dispatch(
    action: CatalogRuntimeAction,
    snapshot: UiBuilderInspectionSnapshot?,
  ): UiBuilderSemanticActionResult {
    if (
      snapshot == null ||
        snapshot.documentId != action.documentId ||
        snapshot.documentRevision != action.documentRevision
    ) {
      return UiBuilderSemanticActionResult.Rejected(
        "STALE_DOCUMENT",
        "action does not target the current inspection snapshot",
      )
    }
    val inspected =
      snapshot.nodes.singleOrNull { it.nodeId == action.nodeId }
        ?: return UiBuilderSemanticActionResult.Rejected(
          "UNKNOWN_NODE",
          "semantic node was not found",
        )
    val bounds = inspected.bounds
    val viewport = viewportBounds
    if (bounds == null || viewport == null || !bounds.intersects(viewport)) {
      return UiBuilderSemanticActionResult.Rejected(
        "ACTION_NOT_VISIBLE",
        "semantic node is not measured inside the current Compose viewport",
      )
    }
    val entry =
      entries[action.nodeId]
        ?: return UiBuilderSemanticActionResult.Rejected(
          "ACTION_NOT_AVAILABLE",
          "semantic node does not expose this action in the current composition",
        )
    return when (action.kind) {
      "activate" -> {
        if (!entry.enabled || inspected.semantics.enabled == false) {
          UiBuilderSemanticActionResult.Rejected("ACTION_DISABLED", "semantic node is disabled")
        } else if ("click" !in inspected.semantics.actions || entry.activate == null) {
          UiBuilderSemanticActionResult.Rejected(
            "ACTION_NOT_AVAILABLE",
            "semantic node does not expose activate",
          )
        } else {
          entry.activate.invoke()
          UiBuilderSemanticActionResult.Applied
        }
      }
      "scrollBy" -> {
        val scrollBy =
          entry.scrollBy
            ?: return UiBuilderSemanticActionResult.Rejected(
              "ACTION_NOT_AVAILABLE",
              "semantic node does not expose vertical scrollBy",
            )
        scrollBy(requireNotNull(action.deltaY).toFloat())
        UiBuilderSemanticActionResult.Applied
      }
      else -> UiBuilderSemanticActionResult.Rejected("UNSUPPORTED_ACTION", "unsupported action")
    }
  }
}

private fun UiBuilderPixelBounds.intersects(other: UiBuilderPixelBounds): Boolean =
  width > 0f &&
    height > 0f &&
    x < other.right &&
    right > other.x &&
    y < other.bottom &&
    bottom > other.y

/** Native Compose design pixels plus an optional sibling-only editor overlay. */
@Composable
fun UiBuilderSurface(
  document: UiBuilderDocument,
  editorOverlay: Boolean = false,
  selectedNodeId: String? = null,
  onNodeSelected: ((String) -> Unit)? = null,
  runtimeActionController: UiBuilderSemanticActionController? = null,
  renderSessionId: String = "",
  onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
  onInspectionInvalidated: ((UiBuilderInspectionCollector) -> Unit)? = null,
) {
  val bounds =
    remember(document.id, document.revision, renderSessionId) { mutableStateMapOf<String, Rect>() }
  val overlayBounds =
    remember(document.id, document.revision, renderSessionId) { mutableStateMapOf<String, Rect>() }
  val semanticActions = mutableMapOf<String, UiBuilderSemanticActionEntry>()
  var surfaceCoordinates by
    remember(document.id, document.revision, renderSessionId) {
      mutableStateOf<LayoutCoordinates?>(null)
    }
  val currentInspectionCallback = rememberUpdatedState(onInspectionSnapshot)
  val currentInspectionInvalidated = rememberUpdatedState(onInspectionInvalidated)
  val inspection =
    remember(document.id, document.revision, renderSessionId) {
      UiBuilderInspectionCollector(
        document = document,
        onSnapshot = { snapshot -> currentInspectionCallback.value?.invoke(snapshot) },
        onInvalidated =
          onInspectionInvalidated?.let {
            { collector -> currentInspectionInvalidated.value?.invoke(collector) }
          },
      )
    }
  val state =
    remember(document.id) {
      mutableStateMapOf<String, String?>().also { target ->
        document.stateVariables.forEach { (name, declaration) ->
          target[name] =
            declaration
              .objectOrEmpty()["initialValue"]
              ?.takeUnless { it is JsonNull }
              ?.jsonPrimitive
              ?.contentOrNull
        }
      }
    }
  val theme = document.environment["theme"]?.jsonPrimitive?.contentOrNull
  val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
  val platformDensity = LocalDensity.current
  val density =
    Density(
      density =
        document.environment["density"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf {
          it.isFinite() && it > 0f
        } ?: platformDensity.density,
      fontScale =
        document.environment["fontScale"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf {
          it.isFinite() && it > 0f
        } ?: platformDensity.fontScale,
    )
  val layoutDirection =
    if (document.environment["layoutDirection"]?.jsonPrimitive?.contentOrNull == "rtl")
      LayoutDirection.Rtl
    else LayoutDirection.Ltr
  SideEffect { inspection.updateState(state) }
  SideEffect {
    val size = surfaceCoordinates?.size
    runtimeActionController?.install(
      semanticActions.toMap(),
      size?.let { UiBuilderPixelBounds(0f, 0f, it.width.toFloat(), it.height.toFloat()) },
    )
  }
  // A Wear design gets Wear's colours, whatever the editor theme says. The screen is black, the
  // card is `#332E3C` and a subtitle is the warm `#FFDCC2` that nobody guesses — all three sampled
  // from wear-m3-catalog's own render. Deciding this by root component rather than by a theme host
  // is the same call the scaffold's background makes: `wear-m3` has no `m3/surface` to hang a
  // theme on, and a Wear screen drawn in Material 3 dark is a picture of the wrong watch.
  // The document names a family; [LocalUiBuilderFontFamilies] is what the host managed to load.
  // A name with nothing behind it falls back to the platform default rather than failing the
  // render — a design is still readable in the wrong face, and is not readable at all if the
  // surface refuses to draw.
  val typeface =
    document.environment["typeface"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  val fontFamily = typeface?.let(LocalUiBuilderFontFamilies.current::get)
  val typography =
    MaterialTheme.typography.let { base -> fontFamily?.let(base::withFontFamily) ?: base }
  val wearScreen =
    document.roots.singleOrNull()?.let(document.nodes::get)?.componentId == WEAR_SCREEN_SCAFFOLD
  val baseColorScheme =
    when {
      wearScreen -> WearDarkColorScheme
      dark && document.id.startsWith("fixture-jetcaster-") -> JetcasterDarkColorScheme
      dark -> darkColorScheme()
      else -> lightColorScheme()
    }
  val themeHost =
    document.roots.asSequence().mapNotNull(document.nodes::get).firstOrNull {
      it.componentId == "m3/surface"
    }
  val primaryColor = themeHost?.themeColor(THEME_PRIMARY)
  val backgroundColor = themeHost?.themeColor(THEME_BACKGROUND)
  val surfaceColor = themeHost?.themeColor(THEME_SURFACE)
  val contentColor = themeHost?.themeColor(THEME_CONTENT)
  val colorScheme =
    baseColorScheme.copy(
      primary = primaryColor ?: baseColorScheme.primary,
      background = backgroundColor ?: baseColorScheme.background,
      onBackground = contentColor ?: baseColorScheme.onBackground,
      surface = surfaceColor ?: baseColorScheme.surface,
      surfaceContainer = surfaceColor ?: baseColorScheme.surfaceContainer,
      surfaceContainerLow = surfaceColor ?: baseColorScheme.surfaceContainerLow,
      surfaceContainerHigh = surfaceColor ?: baseColorScheme.surfaceContainerHigh,
      surfaceContainerHighest = surfaceColor ?: baseColorScheme.surfaceContainerHighest,
      onSurface = contentColor ?: baseColorScheme.onSurface,
      onSurfaceVariant = contentColor ?: baseColorScheme.onSurfaceVariant,
    )
  val typeScale = themeHost?.float(THEME_TYPE_SCALE, 1f)?.coerceIn(0.75f, 1.5f) ?: 1f
  // 26dp on a Wear screen: measured off the reference card's corner, where the first drawn row is
  // inset 26dp from each side and reaches full width 26dp down. Material 3's 16dp default draws a
  // recognisably different card, and the card is most of what a Wear list is.
  val cornerRadius =
    themeHost?.float(THEME_CORNER_RADIUS, 16f)?.coerceIn(0f, 48f)
      ?: if (wearScreen) WEAR_CARD_CORNER_RADIUS_DP else 16f
  CompositionLocalProvider(
    LocalDensity provides density,
    LocalLayoutDirection provides layoutDirection,
    LocalUiBuilderTypeScale provides typeScale,
    LocalUiBuilderCornerRadius provides cornerRadius,
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
      Box(
        Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
          surfaceCoordinates = coordinates
          runtimeActionController?.install(
            semanticActions.toMap(),
            UiBuilderPixelBounds(
              0f,
              0f,
              coordinates.size.width.toFloat(),
              coordinates.size.height.toFloat(),
            ),
          )
        }
      ) {
        document.roots.forEach { root ->
          val rootModifier =
            when {
              document.nodes[root]?.componentId?.startsWith("remote-m3/widget-container-") ==
                true -> Modifier.align(Alignment.Center)
              // A screen is taller than its frame by design — the stadium IS the scroll extent —
              // so it is pinned to the top and centred across, the way a long screenshot reads.
              document.nodes[root]?.componentId == "wear-m3/screen-scaffold" ->
                Modifier.align(Alignment.TopCenter)
              else -> Modifier
            }
          RenderNode(
            document = document,
            nodeId = root,
            state = state,
            onState = { key, value ->
              state[key] = value
              inspection.updateState(state)
            },
            onBounds = { id, coordinates ->
              val rootBounds = coordinates.boundsInRoot()
              bounds[id] = rootBounds
              surfaceCoordinates?.let { surface ->
                overlayBounds[id] = surface.localBoundingBoxOf(coordinates, clipBounds = false)
              }
              inspection.recordNodeBounds(
                id,
                rootBounds.left,
                rootBounds.top,
                rootBounds.right,
                rootBounds.bottom,
              )
            },
            onTextLayout = { id, result ->
              inspection.recordTextLayout(
                id,
                result.lineCount,
                result.firstBaseline,
                result.lastBaseline,
                with(density) { document.nodes.getValue(id).textContentTopPaddingDp().dp.toPx() },
              )
            },
            semanticActions = semanticActions,
            modifier = rootModifier,
          )
        }
        if (editorOverlay) {
          val selected = selectedNodeId?.let(overlayBounds::get)
          Canvas(
            Modifier.fillMaxSize().pointerInput(overlayBounds.toMap(), onNodeSelected) {
              detectTapGestures { position ->
                overlayBounds
                  .filterValues { it.contains(position) }
                  .minByOrNull { (_, rect) -> rect.width * rect.height }
                  ?.key
                  ?.let { onNodeSelected?.invoke(it) }
              }
            }
          ) {
            selected?.let { rect ->
              drawRect(
                Color(0xff6750a4),
                rect.topLeft,
                rect.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RenderNode(
  document: UiBuilderDocument,
  nodeId: String,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  onBounds: (String, LayoutCoordinates) -> Unit,
  onTextLayout: (String, TextLayoutResult) -> Unit,
  semanticActions: MutableMap<String, UiBuilderSemanticActionEntry>,
  modifier: Modifier = Modifier,
  ancestors: Set<String> = emptySet(),
) {
  // A reference to a node that is not there, and a reference to one already on this path, are both
  // things the export gate reports — `UNKNOWN_CHILD`, `GRAPH_CYCLE`. `requireNotNull` and an
  // unbounded recursion took the whole composition down instead, which meant the editor could not
  // draw the document its own Issues panel exists to describe. Drawing nothing for the bad
  // reference and everything else as usual is what leaves the diagnostic to the panel.
  val node = document.nodes[nodeId]
  if (node == null || nodeId in ancestors) return
  val enabled = node.bool("enabled", true)
  val activate = { node.dispatch("click", state, onState) }
  if (node.eventBindings["click"] != null) {
    semanticActions[node.id] = UiBuilderSemanticActionEntry(enabled = enabled, activate = activate)
  }
  val themeCornerRadius = LocalUiBuilderCornerRadius.current
  val measured =
    node.modifiers
      .fold(modifier.onGloballyPositioned { onBounds(node.id, it) }) { result, value ->
        result.applyModifier(value.objectOrEmpty(), themeCornerRadius)
      }
      .then(node.actionModifier(activate, enabled))
  fun slot(name: String) = node.slots[name].orEmpty()
  val here = ancestors + nodeId
  val child: @Composable (String, Modifier) -> Unit = { id, next ->
    RenderNode(
      document,
      id,
      state,
      onState,
      onBounds,
      onTextLayout,
      semanticActions,
      next,
      here,
    )
  }

  when (node.componentId.wearScreenStandIn()) {
    // The two container types on a 240dp screen: `CONTAINER_TYPE_SMALL` is 200x60dp of content and
    // `CONTAINER_TYPE_LARGE` 200x108dp, per `SquircleSmallWidgetPreviewParams` /
    // `SquircleLargeWidgetPreviewParams`. The scaffold adds the padding, so these are the content
    // box rather than the canvas.
    "remote-m3/widget-container-small" ->
      WearWidgetContainerScaffold(
        node = node,
        modifier = measured,
        contentWidthDp = 200,
        contentHeightDp = 60,
        brushes = { next -> slot("background").forEach { child(it, next) } },
        hasBrushes = slot("background").isNotEmpty(),
      ) {
        slot("content").forEach { child(it, Modifier.fillMaxSize()) }
      }
    "remote-m3/widget-container-large" ->
      WearWidgetContainerScaffold(
        node = node,
        modifier = measured,
        contentWidthDp = 200,
        contentHeightDp = 108,
        brushes = { next -> slot("background").forEach { child(it, next) } },
        hasBrushes = slot("background").isNotEmpty(),
      ) {
        slot("content").forEach { child(it, Modifier.fillMaxSize()) }
      }
    // The Wear screen. Unlike the widget container above, this stand-in is EMITTED rather than
    // erased: `ScreenScaffold` is a composable the author calls, so `WearScreenCodeExporter` names
    // it. What is faked is only the drawing — the canvas has no Wear Compose to draw with.
    "wear-m3/screen-scaffold" ->
      WearScreenScaffold(
        node = node,
        modifier = measured,
        screenWidthDp = document.wearScreenWidthDp(),
        edgeButton = { next -> slot("edgeButton").forEach { child(it, next) } },
        hasEdgeButton = slot("edgeButton").isNotEmpty(),
      ) { next ->
        slot("content").forEach { child(it, next) }
      }
    // A plain Column, deliberately. `TransformingLazyColumn` scales and fades its rows against the
    // round display through `SurfaceTransformation` and `Modifier.transformedHeight`, and neither
    // exists off Android — approximating the curve with a hand-rolled scale would draw a
    // *different*
    // wrong picture and imply it was the right one. The running order is what this shows.
    // 48dp, and the height is the point. `ListHeader` is what a Wear list puts at the top, the
    // generator emits one, and a design that faked it with a padded Text made the canvas agree
    // with itself while disagreeing with the screen it generates — which the round trip found.
    "wear-m3/list-header" ->
      Box(
        measured.fillMaxWidth().height(WEAR_LIST_HEADER_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          node.string("text"),
          Modifier,
          color = WEAR_SCREEN_ON_SURFACE,
          fontSize = WEAR_LIST_HEADER_SP.sp,
          maxLines = node.integer("maxLines", Int.MAX_VALUE),
        )
      }
    "wear-m3/transforming-lazy-column" ->
      Column(
        modifier = measured.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp", 4f).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // `IntrinsicSize.Min`, because a Wear list row has to wrap. `m3/card` — which is what a
        // borrowed row is — draws its content slot in a `Box(Modifier.fillMaxSize())`, and inside a
        // Column with a bounded parent that makes the first card eat every remaining pixel and the
        // rest of the list vanish. The real `TransformingLazyColumn` measures each item's own
        // height too, through `Modifier.transformedHeight`; this is the same question asked with
        // the tool the canvas has.
        slot("items").forEach { child(it, Modifier.fillMaxWidth().height(IntrinsicSize.Min)) }
      }
    "layout/supporting-pane-scaffold" ->
      DeterministicSupportingPaneScaffold(
        node,
        measured,
        { next -> slot("mainPane").forEach { child(it, next) } },
        { next -> slot("supportingPane").forEach { child(it, next) } },
      )
    "remote-compose/document" ->
      RemoteComposeDocument(
        document = document,
        node = node,
        modifier = measured,
        state = state,
        onEvent = { event -> event.bindingName()?.let { node.dispatch(it, state, onState) } },
        slotContent = { name, next ->
          Box(next) { slot(name).forEach { child(it, Modifier.fillMaxSize()) } }
        },
      )
    "layout/scaffold" ->
      Scaffold(
        modifier = measured,
        containerColor = node.color("containerColor", MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { slot("topBar").forEach { child(it, Modifier) } },
        snackbarHost = { slot("snackbarHost").forEach { child(it, Modifier) } },
      ) { padding ->
        slot("content").forEach { child(it, Modifier.padding(padding)) }
      }
    "layout/box" ->
      Box(measured) {
        slot("children").forEach { id ->
          val item = document.nodes.getValue(id)
          val parentSizing =
            if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
          child(
            id,
            parentSizing.align(item.childAlignment()).zIndex(item.float("zIndex")),
          )
        }
      }
    "layout/column" ->
      Column(
        measured,
        verticalArrangement = node.verticalArrangement(),
        horizontalAlignment = node.horizontalAlignment(),
      ) {
        slot("children").forEach { id ->
          val item = document.nodes.getValue(id)
          val weight = item.float("weight")
          val next =
            when {
              weight > 0f -> Modifier.weight(weight)
              item.componentId == "layout/lazy-column" -> Modifier.fillMaxWidth().weight(1f)
              else -> Modifier
            }
          child(id, next)
        }
      }
    "layout/row" ->
      Row(
        measured,
        horizontalArrangement = node.horizontalArrangement(),
        verticalAlignment = node.verticalAlignment(),
      ) {
        slot("children").forEach { id ->
          val weight = document.nodes.getValue(id).float("weight")
          child(id, if (weight > 0f) Modifier.weight(weight) else Modifier)
        }
      }
    "layout/lazy-row" -> {
      val lazyState = rememberLazyListState()
      LazyRow(
        modifier = measured,
        state = lazyState,
        contentPadding = node.obj("contentPadding").paddingValues(),
        horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
      ) {
        items(slot("items"), key = { it }) { child(it, Modifier) }
      }
    }
    "layout/lazy-column" -> {
      val lazyState = rememberLazyListState()
      semanticActions[node.id] =
        semanticActions[node.id].orEmpty().copy(scrollBy = lazyState::dispatchRawDelta)
      LazyColumn(
        modifier = measured,
        state = lazyState,
        contentPadding = node.obj("contentPadding").paddingValues(),
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
      ) {
        items(slot("items"), key = { it }) { child(it, Modifier) }
      }
    }
    "layout/lazy-grid" -> {
      val minimum = node.obj("columns").number("minimumCellWidthDp", 362f).coerceAtLeast(1f)
      val lazyState = rememberLazyGridState()
      semanticActions[node.id] =
        semanticActions[node.id].orEmpty().copy(scrollBy = lazyState::dispatchRawDelta)
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minimum.dp),
        modifier = measured,
        state = lazyState,
        contentPadding = node.obj("contentPadding").paddingValues(),
        // The catalog declares both on this component and nothing read either, so a grid's
        // spacing was authored, stored, offered in the inspector, and drawn as zero.
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
        horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
      ) {
        items(
          items = slot("items"),
          key = { it },
          span = { id ->
            if (document.nodes.getValue(id).string("span") == "full") GridItemSpan(maxLineSpan)
            else GridItemSpan(1)
          },
        ) {
          child(it, Modifier)
        }
      }
    }
    "layout/horizontal-carousel" -> {
      val lazyState = rememberLazyListState()
      CompatibleHorizontalCarousel(node, measured, lazyState, slot("items")) { id, next ->
        child(id, next)
      }
    }
    "m3/center-aligned-top-app-bar" ->
      CenterAlignedTopAppBar(
        modifier = measured,
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = node.color("containerColor", Color.Transparent),
            scrolledContainerColor = node.color("scrolledContainerColor", Color.Transparent),
          ),
        title = { slot("title").forEach { child(it, Modifier) } },
      )
    "m3/search-bar" ->
      Surface(
        measured.height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = node.float("tonalElevationDp").dp,
      ) {
        Column { slot("inputField").forEach { child(it, Modifier.fillMaxSize()) } }
      }
    "m3/search-input-field" -> {
      val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
      val value = variable?.let(state::get).orEmpty()
      Row(
        measured.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        slot("leadingIcon").forEach { child(it, Modifier) }
        Box(Modifier.weight(1f)) {
          if (value.isEmpty()) slot("placeholder").forEach { child(it, Modifier) }
          BasicTextField(
            value,
            { if (variable != null) onState(variable, it) },
            Modifier.fillMaxWidth(),
            enabled = node.bool("enabled", true),
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
          )
        }
        slot("trailingIcon").forEach { child(it, Modifier) }
      }
    }
    "m3/snackbar-host" ->
      if (node.bool("visible")) Snackbar(measured) { Text(node.string("message")) }
      else Box(measured)
    "m3/filter-chip" ->
      FilterChip(
        selected = node.resolvedBool("selected", state),
        onClick = activate,
        modifier = measured,
        enabled = enabled,
        label = { slot("label").forEach { child(it, Modifier) } },
        leadingIcon =
          slot("leadingIcon").takeIf(List<String>::isNotEmpty)?.let { ids ->
            { ids.forEach { child(it, Modifier) } }
          },
      )
    "m3/primary-tab-row" ->
      PrimaryTabRow(node.integer("selectedIndex"), measured) {
        slot("tabs").forEach { child(it, Modifier) }
      }
    "m3/tab" ->
      Tab(
        node.bool("selected"),
        activate,
        measured,
        enabled = enabled,
        text = { slot("text").forEach { child(it, Modifier) } },
      )
    "m3/list-item" ->
      LegacyListItem(node, measured, slot("headline"), slot("supporting"), slot("trailing"), child)
    "m3/surface" ->
      Surface(
        measured,
        shape = node.shape(themeCornerRadius),
        color = node.color("containerColor", Color.Transparent),
        tonalElevation = node.float("tonalElevationDp").dp,
      ) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/card" ->
      Card(
        measured,
        shape = node.shape(themeCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = node.float("elevationDp").dp),
        colors =
          CardDefaults.cardColors(
            node.color("containerColor", MaterialTheme.colorScheme.surfaceContainer)
          ),
      ) {
        Box(Modifier.fillMaxSize()) {
          slot("content").forEach { id ->
            val item = document.nodes.getValue(id)
            val parentSizing =
              if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
            child(
              id,
              parentSizing.align(item.childAlignment()).zIndex(item.float("zIndex")),
            )
          }
        }
      }
    // `onCheckedChange` rather than the clickable modifier every node gets: a control that reports
    // its own change is what makes the box tickable in the live playground, and Material draws the
    // ripple and the state layer for it.
    "m3/checkbox" ->
      Checkbox(
        checked = node.resolvedBool("checked", state),
        onCheckedChange = { activate() },
        modifier = measured,
        enabled = enabled,
      )
    "m3/switch" ->
      Switch(
        checked = node.resolvedBool("checked", state),
        onCheckedChange = { activate() },
        modifier = measured,
        enabled = enabled,
      )
    "m3/slider" -> {
      // The variable the slider writes, the same seam a text field's `value` uses. A slider with no
      // variable still moves — Material needs a value to draw a thumb — but the movement goes
      // nowhere, which is what an unbound control means everywhere else in this catalog.
      val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
      val from = node.float("valueFrom", 0f)
      val to = node.float("valueTo", 1f).coerceAtLeast(from)
      val bound = variable?.let { state[it]?.toFloatOrNull() }
      Slider(
        value = (bound ?: node.float("value")).coerceIn(from, to),
        onValueChange = { next -> if (variable != null) onState(variable, next.toString()) },
        modifier = measured,
        enabled = enabled,
        valueRange = from..to,
        steps = node.integer("steps"),
      )
    }
    "m3/progress-indicator" -> {
      val variable = node.obj("progress")["variable"]?.jsonPrimitive?.contentOrNull
      val fraction =
        (variable?.let { state[it]?.toFloatOrNull() } ?: node.float("progress")).coerceIn(0f, 1f)
      // Indeterminate is Material's other overload rather than a value, and the document
      // environment freezes animation, so what the canvas shows is its first frame. That is the
      // honest still of a thing that moves, and it is what makes the render diffable.
      val indeterminate = node.bool("indeterminate")
      if (node.string("variant") == "circular") {
        if (indeterminate) CircularProgressIndicator(modifier = measured)
        else CircularProgressIndicator(progress = { fraction }, modifier = measured)
      } else {
        if (indeterminate) LinearProgressIndicator(modifier = measured)
        else LinearProgressIndicator(progress = { fraction }, modifier = measured)
      }
    }
    "m3/radio-button" ->
      RadioButton(
        selected = node.resolvedBool("selected", state),
        onClick = activate,
        modifier = measured,
        enabled = enabled,
      )
    "m3/text-field" -> {
      // The variable the field writes, not a local `remember`. A design's text field is a view of a
      // declared state variable — that is what makes typing in the canvas change the design rather
      // than a field's own private memory, and it is the same seam `m3/search-input-field` uses.
      val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
      val value = variable?.let(state::get) ?: node.string("value")
      val label: (@Composable () -> Unit)? =
        slot("label").takeIf(List<String>::isNotEmpty)?.let { ids ->
          { ids.forEach { child(it, Modifier) } }
        }
      val placeholder: (@Composable () -> Unit)? =
        slot("placeholder").takeIf(List<String>::isNotEmpty)?.let { ids ->
          { ids.forEach { child(it, Modifier) } }
        }
      val supporting: (@Composable () -> Unit)? =
        slot("supportingText").takeIf(List<String>::isNotEmpty)?.let { ids ->
          { ids.forEach { child(it, Modifier) } }
        }
      val leading: (@Composable () -> Unit)? =
        slot("leadingIcon").takeIf(List<String>::isNotEmpty)?.let { ids ->
          { ids.forEach { child(it, Modifier) } }
        }
      val trailing: (@Composable () -> Unit)? =
        slot("trailingIcon").takeIf(List<String>::isNotEmpty)?.let { ids ->
          { ids.forEach { child(it, Modifier) } }
        }
      val onValueChange: (String) -> Unit = { next ->
        if (variable != null) onState(variable, next)
      }
      if (node.string("variant") == "outlined") {
        OutlinedTextField(
          value = value,
          onValueChange = onValueChange,
          modifier = measured,
          enabled = enabled,
          readOnly = node.bool("readOnly"),
          label = label,
          placeholder = placeholder,
          supportingText = supporting,
          leadingIcon = leading,
          trailingIcon = trailing,
          isError = node.bool("isError"),
          singleLine = node.bool("singleLine", true),
        )
      } else {
        TextField(
          value = value,
          onValueChange = onValueChange,
          modifier = measured,
          enabled = enabled,
          readOnly = node.bool("readOnly"),
          label = label,
          placeholder = placeholder,
          supportingText = supporting,
          leadingIcon = leading,
          trailingIcon = trailing,
          isError = node.bool("isError"),
          singleLine = node.bool("singleLine", true),
        )
      }
    }
    "m3/dialog" ->
      BuilderDialogSurface(
        node = node,
        modifier = measured,
        icon = { next -> slot("icon").forEach { child(it, next) } },
        title = { next -> slot("title").forEach { child(it, next) } },
        text = { next -> slot("text").forEach { child(it, next) } },
        hasIcon = slot("icon").isNotEmpty(),
        hasTitle = slot("title").isNotEmpty(),
        hasText = slot("text").isNotEmpty(),
        buttons = { next ->
          slot("dismissButton").forEach { child(it, next) }
          slot("confirmButton").forEach { child(it, next) }
        },
      )
    "m3/date-picker" -> BuilderDatePicker(node, measured)
    "m3/time-picker" -> BuilderTimePicker(node, measured)
    "m3/icon-button" ->
      IconButton(
        onClick = activate,
        modifier =
          measured
            .size(node.float("sizeDp", 48f).dp)
            .then(
              if ("selected" in node.properties) {
                Modifier.background(Color.Black.copy(alpha = 0.46f), CircleShape)
              } else {
                Modifier
              }
            ),
        enabled = enabled,
      ) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/button" ->
      BuilderButton(node, measured, activate, enabled) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/horizontal-floating-toolbar" ->
      CompatibleFloatingToolbar(node, measured) { slot("content").forEach { child(it, Modifier) } }
    "m3/horizontal-divider" ->
      HorizontalDivider(
        measured,
        // Absent means Material's own thickness, not zero — a hairline is what a divider is, and
        // `float(name)`'s zero fallback would have drawn nothing at all.
        thickness = node.dimension("thicknessDp") ?: DividerDefaults.Thickness,
        color = node.color("color", MaterialTheme.colorScheme.outlineVariant),
      )
    "m3/icon" -> BuilderIcon(node, measured)
    "m3/text" ->
      Text(
        node.string("text"),
        measured,
        color = node.color("color", Color.Unspecified),
        style = node.textStyle(),
        fontWeight = node.fontWeight(),
        fontStyle = node.fontStyle(),
        fontSize =
          node.float("fontSizeSp").takeIf { it > 0f }?.sp
            ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        lineHeight =
          node.float("lineHeightSp").takeIf { it > 0f }?.sp
            ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing =
          node.float("letterSpacingSp").takeIf { "letterSpacingSp" in node.properties }?.sp
            ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        textDecoration = node.textDecoration(),
        minLines = node.integer("minLines", 1),
        maxLines = node.integer("maxLines", Int.MAX_VALUE),
        softWrap = node.bool("softWrap", true),
        overflow = node.textOverflow(),
        textAlign = node.textAlign(),
        onTextLayout = { onTextLayout(node.id, it) },
      )
    "asset/image" -> AssetPlaceholder(node, measured)
    "shape/linear-gradient" -> Box(measured.background(node.linearGradientBrush()))
    "shape/radial-gradient" -> {
      val inner =
        node
          .color("innerColor", MaterialTheme.colorScheme.primary)
          .copy(alpha = node.float("innerAlpha", 1f))
      val outer = node.color("outerColor", Color.Transparent)
      Box(
        measured.drawBehind {
          drawRect(
            Brush.radialGradient(
              listOf(inner, outer),
              center = if (node.string("center") == "topStart") Offset.Zero else center,
              radius = size.maxDimension * 0.82f,
            )
          )
        }
      )
    }
    "shape/colour-dot" ->
      Box(
        measured
          .size(node.float("diameterDp", 8f).dp)
          .clip(CircleShape)
          .background(Color(parseArgb(node.string("color"))))
      )
    else -> UnsupportedComponentDiagnostic(node.componentId, measured)
  }
}

/**
 * The brush this gradient layer paints, on the axis its `direction` names.
 *
 * The renderer used to draw every linear gradient top-to-bottom while the Compose exporter read
 * `direction` and emitted all four — so a design with `leftToRight` looked vertical on the canvas
 * and generated horizontal Kotlin. The exporter was right; this now matches it case for case.
 * `WearWidgetBrush` distinguishes the same two axes (`verticalGradient` / `horizontalGradient`),
 * which is what makes the difference reachable from a widget background.
 */
@Composable
private fun UiBuilderNode.linearGradientBrush(): Brush {
  val start = color("startColor", Color.Transparent)
  val end = color("endColor", Color.Transparent)
  return when (string("direction")) {
    "bottomToTop" -> Brush.verticalGradient(listOf(end, start))
    "leftToRight" -> Brush.horizontalGradient(listOf(start, end))
    "rightToLeft" -> Brush.horizontalGradient(listOf(end, start))
    else -> Brush.verticalGradient(listOf(start, end))
  }
}

/**
 * The screen diameter a Wear design is authored against, in dp.
 *
 * Read from the document's own frame rather than from a scaffold property, because the Screen
 * inspector already carries it: `DeviceDimensions` publishes `wearos_small_round` (192dp),
 * `wearos_large_round` (227dp) and `wearos_xl_round` (240dp), and the server serves them to the
 * frame menu. A fifth scaffold property would be a second answer to a question already answered,
 * and the two would disagree the first time somebody changed one.
 *
 * The fallback is the small round size rather than the frame's raw width: a design opened on a
 * phone frame is a design somebody has not picked a watch for yet, and drawing a 411dp-wide watch
 * is a worse answer than drawing the smallest real one.
 */
private fun UiBuilderDocument.wearScreenWidthDp(): Int {
  val width = environment["widthDp"]?.jsonPrimitive?.intOrNull ?: return WEAR_SMALL_ROUND_DP
  return if (width in WEAR_SMALL_ROUND_DP..WEAR_XL_ROUND_DP) width else WEAR_SMALL_ROUND_DP
}

/**
 * The Wear screen as a long screenshot: the frame's width, the content's height, round caps.
 *
 * ## Why a stadium and not a circle
 *
 * Because that is what the real one is. `@ScrollingPreview(modes = [ScrollMode.LONG])` on
 * wear-m3-catalog's `TransformingLazyColumn` component stitches the whole scroll into one tall PNG,
 * and the result is a stadium: the screen's width, the content's height, a round cap at each end.
 * This draws the same shape because the shape is not a metaphor — it is the Wear long-screenshot
 * form, and the extent is what an author is building. A 192dp keyhole shows one screenful and hides
 * the rest of the list behind a scroll position they have to keep re-finding.
 *
 * ## Every number here was measured, not chosen
 *
 * The geometry comes from that render and from `ScreenScaffoldPaddingProbeTest` in wear-m3-catalog,
 * which composes the real `AppScaffold` / `ScreenScaffold` / `TransformingLazyColumn` under
 * Robolectric and reports what the scaffold hands its list. See [wearScreenContentPadding] and
 * [WEAR_TIME_TEXT_TOP_DP]. Guessed fractions is what this used to be, and they were wrong in both
 * axes.
 *
 * ## What it still gets wrong, on purpose
 *
 * The rows are not transformed. `SurfaceTransformation` scales and fades each row by where it sits
 * in the viewport, and on the stitched reference that is visible as rows of *different widths* down
 * the page — each strip carrying the scale it had in the frame it came from. A stand-in cannot have
 * that without inventing a scroll position for a page that has none, so rows here are drawn at the
 * one width the transformation passes through: full content width, which is what a row gets at the
 * centre of the display.
 */
@Composable
private fun WearScreenScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  screenWidthDp: Int,
  edgeButton: @Composable (Modifier) -> Unit,
  hasEdgeButton: Boolean,
  content: @Composable (Modifier) -> Unit,
) {
  val width = screenWidthDp.dp
  val padding = wearScreenContentPadding(screenWidthDp)
  // Wear Material 3 is dark-first and its `background` is pure black — measured off the reference
  // render, not read from the editor theme, which is the bug the widget container's default
  // background comments: reading the theme made the watch go white in a light editor.
  val background = node.color("background", WEAR_SCREEN_BACKGROUND)
  val timeText = node.string("timeText")
  Box(
    modifier =
      modifier
        .width(width)
        // At least one screenful, so an empty scaffold is a watch face rather than a sliver.
        .heightIn(min = width)
        .clip(RoundedCornerShape(percent = 50))
        .background(background)
    // Nothing drawn over the design. An earlier version outlined the first screenful — a
    // circle over the top cap and a line where it ends — to answer "how much of this is above
    // the fold". It reads as an artifact, because it is one: the canvas paints the *design*,
    // and a guide painted into it is editor chrome in the one layer that has to stay
    // comparable, pixel for pixel, with a render that has no such thing. The editor overlay is
    // where that belongs, the way the reference overlay already works.
    //
    // No scroll indicator either. It is a real property of the design and it reaches the
    // generated code; what it has no meaning on is this picture. An indicator shows where a
    // viewport sits within the content, and the extent has no viewport. The real long
    // screenshot agrees: `ScrollMode.LONG` sets `LocalScrollCaptureInProgress`, the emitted
    // scaffold reads it and draws none, and the stitched capture comes back clean.
  ) {
    Column(Modifier.fillMaxWidth().padding(padding)) { content(Modifier.fillMaxWidth()) }
    // Overlaid, not a band above the content. `TimeText` belongs to `AppScaffold` and is drawn
    // over the screen; what makes room for it is the list's own top content padding, which is
    // already applied above. Drawing it as a row that displaced the content — which this did —
    // pushed every row down by the height of a clock the real screen draws on top of nothing.
    if (timeText.isNotEmpty()) {
      WearCurvedTimeText(timeText, Modifier.matchParentSize())
    }
    if (hasEdgeButton) {
      // The edge button hugs the bottom curve, which on the extent is the bottom cap. Placed
      // rather than sized: `EdgeButton` takes its shape from the screen and this cannot draw that.
      edgeButton(
        Modifier.align(Alignment.BottomCenter).padding(bottom = width * WEAR_EDGE_BUTTON_INSET)
      )
    }
  }
}

/**
 * What `ScreenScaffold` hands its `TransformingLazyColumn` as `contentPadding`, by screen diameter.
 *
 * Measured, not derived. `ScreenScaffoldPaddingProbeTest` in yschimke/wear-m3-catalog composes the
 * real thing under Robolectric at each round size and reports the `PaddingValues`; these are its
 * numbers for Wear Compose Material 3 1.7.0-beta02, cross-checked against the stitched
 * `ScrollMode.LONG` render of that repository's `TransformingLazyColumn` component — bottom padding
 * on the reference is 20dp at 192, 23dp at 225 and 24dp at 240, which is this table.
 *
 * Neither axis is a clean fraction of the diameter, which is why guessing failed: horizontal runs
 * 5.21%, 5.29%, 5.42% and vertical 10.42%, 10.13%, 10.00%. Between and beyond the measured sizes
 * this interpolates rather than extrapolating a fraction, because the three points are what is
 * known.
 */
private fun wearScreenContentPadding(screenWidthDp: Int): PaddingValues {
  val horizontal = WEAR_CONTENT_PADDING.interpolate(screenWidthDp) { it.second }
  val vertical = WEAR_CONTENT_PADDING.interpolate(screenWidthDp) { it.third }
  return PaddingValues(horizontal = horizontal.dp, vertical = vertical.dp)
}

/** Measured `(diameterDp, horizontalDp, verticalDp)`, ascending by diameter. */
private val WEAR_CONTENT_PADDING =
  listOf(Triple(192f, 10f, 20f), Triple(227f, 12f, 23f), Triple(240f, 13f, 24f))

private fun List<Triple<Float, Float, Float>>.interpolate(
  widthDp: Int,
  select: (Triple<Float, Float, Float>) -> Float,
): Float {
  val width = widthDp.toFloat()
  first().let { if (width <= it.first) return select(it) }
  last().let { if (width >= it.first) return select(it) }
  val upper = indexOfFirst { it.first >= width }
  val low = this[upper - 1]
  val high = this[upper]
  val t = (width - low.first) / (high.first - low.first)
  return select(low) + t * (select(high) - select(low))
}

/**
 * The clock, drawn along the top of the round viewport the way `TimeText` draws it.
 *
 * ## Why bother curving it
 *
 * Because it is curved, and a straight `10:10` was the one piece of chrome on the canvas that was a
 * different *shape* from the thing it stands for. Everything else here is measured against a real
 * render; this was measured against one too, and then drawn flat, which put the glyphs in the right
 * band and the wrong arc.
 *
 * ## How, without curved-text support
 *
 * Compose Multiplatform has no `drawTextOnPath`. It does not need one for this: each character is
 * measured on its own, placed at the top of the viewport circle, and the whole glyph rotated about
 * the circle's centre by the angle its position along the arc implies. Advance is the character's
 * own measured width over the radius, so the spacing follows the face rather than a guess, and the
 * string is centred by starting half its total angular width anticlockwise of the top.
 *
 * The circle is the *viewport's*, not the extent's — centre at `(width / 2, width / 2)` — which is
 * the circle a watch actually has, whatever the extent below it is doing.
 */
@Composable
private fun WearCurvedTimeText(text: String, modifier: Modifier) {
  val measurer = rememberTextMeasurer()
  val style =
    LocalTextStyle.current.copy(
      color = WEAR_SCREEN_TIME_TEXT,
      fontSize = WEAR_TIME_TEXT_SP.sp,
      fontWeight = FontWeight.Medium,
    )
  val glyphs = remember(text, style) { text.map { measurer.measure(it.toString(), style) } }
  Canvas(modifier) {
    val centre = Offset(size.width / 2f, size.width / 2f)
    // The arc the glyph *centres* ride on: the viewport radius less the measured distance from the
    // top of the screen to the middle of the reference's digits.
    val radius = size.width / 2f - WEAR_TIME_TEXT_CENTRE_DP.dp.toPx()
    if (radius <= 0f) return@Canvas
    val total = glyphs.sumOf { it.size.width.toDouble() }.toFloat()
    var travelled = -total / 2f
    glyphs.forEach { glyph ->
      val width = glyph.size.width.toFloat()
      val height = glyph.size.height.toFloat()
      // Radians along the arc to this glyph's centre, then degrees for the rotation.
      val degrees = ((travelled + width / 2f) / radius) * 180f / PI.toFloat()
      withTransform({ rotate(degrees = degrees, pivot = centre) }) {
        drawText(
          textLayoutResult = glyph,
          topLeft = Offset(centre.x - width / 2f, centre.y - radius - height / 2f),
        )
      }
      travelled += width
    }
  }
}

/** `wearos_small_round` and `wearos_xl_round` from `DeviceDimensions`, as the accepted range. */
private const val WEAR_SMALL_ROUND_DP = 192

private const val WEAR_XL_ROUND_DP = 240

/**
 * Wear Material 3's dark scheme, as the reference render actually draws it.
 *
 * Sampled from wear-m3-catalog's stitched `TransformingLazyColumn` capture rather than copied from
 * a token table: the question the canvas has to answer is what the screen looks like, and these are
 * the pixels it has. `onSurfaceVariant` is the one nobody guesses — Wear's is a warm `#FFDCC2`, not
 * the grey a Material 3 dark scheme puts there, and a subtitle is where it shows.
 */
private val WEAR_SCREEN_BACKGROUND = Color(0xFF000000)

private val WEAR_SCREEN_TIME_TEXT = Color(0xFFC5C5C6)

private val WEAR_SCREEN_SURFACE_CONTAINER = Color(0xFF332E3C)

private val WEAR_SCREEN_ON_SURFACE = Color(0xFFF6EDFF)

private val WEAR_SCREEN_ON_SURFACE_VARIANT = Color(0xFFFFDCC2)

/** The component id the renderer keys the Wear screen's theme and geometry off. */
private const val WEAR_SCREEN_SCAFFOLD = "wear-m3/screen-scaffold"

/** Measured off the reference card: inset 26dp at its top row, full width 26dp down. */
private const val WEAR_CARD_CORNER_RADIUS_DP = 26f

/**
 * The subset of a Material 3 scheme a Wear design actually draws through, in Wear's own values.
 *
 * Only the roles the borrowed components read are replaced. The rest stay Material 3's dark scheme,
 * because a colour this catalog has never drawn is a colour nobody has measured, and inventing one
 * would put a number in the picture that no watch produced.
 */
private val WearDarkColorScheme =
  darkColorScheme(
    background = WEAR_SCREEN_BACKGROUND,
    onBackground = WEAR_SCREEN_ON_SURFACE,
    surface = WEAR_SCREEN_SURFACE_CONTAINER,
    surfaceContainer = WEAR_SCREEN_SURFACE_CONTAINER,
    surfaceContainerLow = WEAR_SCREEN_SURFACE_CONTAINER,
    surfaceContainerHigh = WEAR_SCREEN_SURFACE_CONTAINER,
    surfaceContainerHighest = WEAR_SCREEN_SURFACE_CONTAINER,
    onSurface = WEAR_SCREEN_ON_SURFACE,
    onSurfaceVariant = WEAR_SCREEN_ON_SURFACE_VARIANT,
  )

/**
 * How far below the top of the screen the clock's glyph centres ride, measured.
 *
 * On the reference the digits occupy 5.5..18dp down — at 192, 225 and 240dp alike, a constant,
 * which is the sort of thing only measuring tells you — so their centres sit 11.75dp in. The arc is
 * the viewport radius less a dp under that: a glyph rotated about the circle rides slightly lower
 * than its flat twin, and this is the value that lands the curved box where the reference's is.
 */
private const val WEAR_TIME_TEXT_CENTRE_DP = 10.75f

/** Sized so "10:10" measures the reference's 41.5dp; Wear's clock is bigger than it looks. */
private const val WEAR_TIME_TEXT_SP = 14.5f

/** `ListHeader`'s item height, measured at 192, 225 and 240dp alike. */
private const val WEAR_LIST_HEADER_HEIGHT_DP = 48f

/** Sized so the label measures the reference header's 53.5dp of glyphs. */
private const val WEAR_LIST_HEADER_SP = 14.5f

/** How far the edge button floats off the bottom cap, as a fraction of the diameter. */
private const val WEAR_EDGE_BUTTON_INSET = 0.04f

/**
 * Compose UI counterpart of the stable Glance Wear widget host frame.
 *
 * The outer canvas is the host's padding around the container's content area; the squircle radius
 * and the default fill are host chrome rather than authored widget content, and [brushes] is the
 * widget's own `WearWidgetBrush` chain drawn into that same rounded rect.
 */
@Composable
private fun WearWidgetContainerScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  contentWidthDp: Int,
  contentHeightDp: Int,
  brushes: @Composable (Modifier) -> Unit,
  hasBrushes: Boolean,
  content: @Composable () -> Unit,
) {
  val horizontalPadding = node.float("horizontalPaddingDp", WEAR_WIDGET_PADDING_DP)
  val verticalPadding = node.float("verticalPaddingDp", WEAR_WIDGET_PADDING_DP)
  val cornerRadius = node.float("cornerRadiusDp", WEAR_WIDGET_CORNER_RADIUS_DP)
  val shape = RoundedCornerShape(cornerRadius.dp)
  // The default applies only when the chain is EMPTY, which is what `WearWidgetBrush.isEmpty()`
  // asks upstream. A widget that declares a gradient or an image and no colour has a one-element
  // chain, not an empty one, so painting `#272430` underneath it would add a fill the widget never
  // asked for — visible wherever an image's `Decal` tiling leaves the surface uncovered.
  val declaredColor = node.string("background").isNotEmpty()
  val base =
    if (declaredColor) node.color("background", WEAR_WIDGET_DEFAULT_BACKGROUND)
    else if (hasBrushes) Color.Transparent else WEAR_WIDGET_DEFAULT_BACKGROUND
  Box(
    modifier =
      modifier
        // The canvas the preview wrapper measures: the content box plus padding on all four
        // edges. `WearWidgetPreview` sizes its `RemoteDocumentPreview` exactly this way.
        .size(
          (contentWidthDp + 2f * horizontalPadding).dp,
          (contentHeightDp + 2f * verticalPadding).dp,
        )
        // Drawn behind, not clipped. `WearWidgetContainer` paints the widget's background as a
        // round rect inside `drawWithContent` and then calls `drawContent()` — content that
        // overflows the radius is drawn over the corner rather than cut off, and a scaffold that
        // clipped would hide exactly the overflow an author needs to see.
        .drawBehind {
          drawRoundRect(
            color = base,
            cornerRadius = CornerRadius(cornerRadius.dp.toPx(), cornerRadius.dp.toPx()),
          )
        }
  ) {
    // Each brush element over the whole frame, in chain order, before the content — `foldIn` walks
    // outermost to innermost and every element draws the same round rect. Clipped rather than
    // drawn behind, because a gradient or a bitmap has no radius of its own the way a solid fill
    // does; the round rect IS the shape upstream draws them into.
    brushes(Modifier.matchParentSize().clip(shape))
    Box(Modifier.padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp)) {
      content()
    }
  }
}

/**
 * `androidx.glance.wear.composable.WearWidgetContainer`'s own default background.
 *
 * A literal, because upstream's is: it comments the constant as "Forked from
 * androidx.wear.compose.material3.ColorScheme.surfaceContainerLow" and hard-codes `Color(red = 39,
 * green = 36, blue = 48)`. Reading `MaterialTheme.colorScheme.surfaceContainerLow` here instead —
 * which is what this scaffold used to do — tracks the *editor's* theme, so the frame went pale in a
 * light theme while the real host stayed this colour whatever the widget did.
 */
private val WEAR_WIDGET_DEFAULT_BACKGROUND = Color(red = 39, green = 36, blue = 48)

/**
 * `verticalPaddingDp` / `horizontalPaddingDp` and `cornerRadiusDp` from the shipped
 * `WidgetPreviewParams` providers.
 *
 * Every squircle, round and rectangular spec upstream publishes uses 8dp on both axes; only the
 * corner radius varies by shape (26dp squircle, 999dp round, 0dp rectangular), which is why the
 * radius is authored per design and the padding merely defaults.
 */
private const val WEAR_WIDGET_PADDING_DP = 8f

private const val WEAR_WIDGET_CORNER_RADIUS_DP = 26f

private const val MAX_REMOTE_COMPOSE_BASE64_CHARS = 8 * 1024 * 1024

@Composable
private fun RemoteComposeDocument(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  state: Map<String, String?>,
  onEvent: (RcPlayerEvent) -> Unit,
  slotContent: @Composable (String, Modifier) -> Unit,
) {
  val encoded = node.string("documentBase64")
  val decoded = remember(encoded) { decodeRemoteComposeDocument(encoded) }
  val rcDocument = decoded.getOrNull()
  if (rcDocument == null) {
    RemoteComposeDiagnostic(
      message = decoded.exceptionOrNull()?.message ?: "Remote Compose document is invalid",
      modifier = modifier,
    )
    return
  }

  val namedValues = remember(node.id) { mutableStateMapOf<String, RcNamedValue>() }
  val desiredNamedValues = node.remoteComposeNamedValues(state)
  SideEffect {
    if (namedValues.toMap() != desiredNamedValues) {
      namedValues.clear()
      namedValues.putAll(desiredNamedValues)
    }
  }
  val renderers =
    node.slots.keys.associateWith { slotName ->
      val content: RcCustomContent = { _, next -> slotContent(slotName, next) }
      content
    }
  val customComponents = RcCustomComponentRegistry(renderers)
  val missingCustomComponents =
    remember(rcDocument, customComponents.names) {
      rcDocument
        .composeSupportReport(availableCustomComponents = customComponents.names)
        .issues
        .filter { it.operation == "Custom" }
    }
  if (missingCustomComponents.isNotEmpty()) {
    RemoteComposeDiagnostic(
      message = missingCustomComponents.joinToString("\n") { it.detail },
      modifier = modifier,
    )
    return
  }
  val inheritedTheme =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  val theme =
    when (node.string("theme")) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      "system" -> RcPlayerTheme.System
      else -> inheritedTheme
    }
  RcComposePlayer(
    document = rcDocument,
    modifier = modifier,
    theme = theme,
    namedValues = namedValues,
    onEvent = onEvent,
    customComponents = customComponents,
  )
}

internal fun decodeRemoteComposeDocument(encoded: String): Result<RcDocument> = runCatching {
  require(encoded.isNotBlank()) { "Remote Compose documentBase64 is required" }
  require(encoded.length <= MAX_REMOTE_COMPOSE_BASE64_CHARS) {
    "Remote Compose documentBase64 exceeds the 8 MiB encoded limit"
  }
  RcDocumentCodec.decode(Base64.Default.decode(encoded))
}

private fun UiBuilderNode.remoteComposeNamedValues(
  state: Map<String, String?>
): Map<String, RcNamedValue> {
  val declarations = obj("namedValues")["value"] as? JsonObject ?: return emptyMap()
  return declarations
    .mapNotNull { (name, element) ->
      val declaration = element as? JsonObject ?: return@mapNotNull null
      val type = declaration.optionalString("type") ?: return@mapNotNull null
      val value = declaration["value"]?.jsonPrimitive
      val resolved =
        when (type) {
          "stateText" ->
            declaration.optionalString("variable")?.let(state::get)?.let(RcNamedValue::Text)
          "text" -> value?.contentOrNull?.let(RcNamedValue::Text)
          "float" -> value?.floatOrNull?.let(RcNamedValue::FloatValue)
          "integer" -> value?.intOrNull?.let(RcNamedValue::Integer)
          "long" -> value?.contentOrNull?.toLongOrNull()?.let(RcNamedValue::LongValue)
          "color" ->
            value?.contentOrNull?.let { color ->
              runCatching { RcNamedValue.Color(parseArgb(color).toInt()) }.getOrNull()
            }
          else -> null
        }
      resolved?.let { name to it }
    }
    .toMap()
}

private fun RcPlayerEvent.bindingName(): String? =
  when (this) {
    is RcPlayerEvent.HostNamedAction -> name
    is RcPlayerEvent.HostAction -> "hostAction:$actionId"
    is RcPlayerEvent.HostActionMetadata -> "hostAction:$actionId"
    is RcPlayerEvent.DebugMessage -> null
  }

@Composable
private fun RemoteComposeDiagnostic(message: String, modifier: Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      message,
      Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

/**
 * Material adaptive is not on this module's dependency floor. This explicit compatibility layout
 * implements only deterministic expanded two-pane sizing and single-pane fallback; it does not
 * claim posture, motion, navigation, or predictive-back behaviour.
 */
@Composable
private fun DeterministicSupportingPaneScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  mainPane: @Composable (Modifier) -> Unit,
  supportingPane: @Composable (Modifier) -> Unit,
) {
  val mainVisible = node.bool("mainPaneVisible", true)
  val supportingVisible = node.bool("supportingPaneVisible", true)
  val mainWidth = node.float("mainPanePreferredWidthDp", 744f).coerceAtLeast(1f)
  val supportWidth = node.float("supportingPanePreferredWidthDp", 512f).coerceAtLeast(1f)
  val spacing = node.float("paneSpacingDp").coerceAtLeast(0f)
  BoxWithConstraints(modifier) {
    val expanded =
      node.string("layoutMode") in setOf("expandedTwoPane", "twoPane") &&
        mainVisible &&
        supportingVisible &&
        maxWidth >= (mainWidth + supportWidth + spacing).dp
    if (expanded) {
      Row(Modifier.fillMaxSize()) {
        mainPane(Modifier.width(mainWidth.dp).fillMaxSize())
        Spacer(Modifier.width(spacing.dp))
        supportingPane(Modifier.weight(1f).fillMaxSize())
      }
    } else if (mainVisible) mainPane(Modifier.fillMaxSize())
    else if (supportingVisible) supportingPane(Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize())
  }
}

/**
 * Material's uncontained carousel is not on the dependency floor; this preserves its data/layout
 * contract.
 */
@Composable
private fun CompatibleHorizontalCarousel(
  node: UiBuilderNode,
  modifier: Modifier,
  state: LazyListState,
  ids: List<String>,
  child: @Composable (String, Modifier) -> Unit,
) {
  LazyRow(
    modifier = modifier,
    state = state,
    contentPadding = PaddingValues(start = node.float("contentPaddingStartDp").dp),
    horizontalArrangement = Arrangement.spacedBy(node.float("itemSpacingDp").dp),
  ) {
    items(ids, key = { it }) { child(it, Modifier.width(node.float("itemWidthDp", 128f).dp)) }
  }
}

/** Experimental floating-toolbar identity with deterministic Material surface/row semantics. */
@Composable
private fun CompatibleFloatingToolbar(
  node: UiBuilderNode,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier,
    shape = RoundedCornerShape(32.dp),
    color = node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHighest),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
  ) {
    Row(
      // The catalog exposes four padding edges for this component and nothing read them. Absent
      // stays the 6dp this toolbar has always drawn rather than becoming zero.
      Modifier.padding(
        (node.properties["contentPadding"] as? JsonObject)?.paddingValues() ?: PaddingValues(6.dp)
      ),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      content()
    }
  }
}

@Composable
private fun BuilderButton(
  node: UiBuilderNode,
  modifier: Modifier,
  click: () -> Unit,
  enabled: Boolean,
  content: @Composable () -> Unit,
) {
  when (node.string("style")) {
    "text" -> TextButton(click, modifier, enabled = enabled) { content() }
    "filledTonal" -> FilledTonalButton(click, modifier, enabled = enabled) { content() }
    "fab" ->
      FloatingActionButton(
        click,
        modifier,
        containerColor = node.color("containerColor", MaterialTheme.colorScheme.primary),
      ) {
        Box(Modifier.padding(horizontal = 16.dp)) { content() }
      }
    else ->
      Button(
        click,
        modifier,
        enabled = enabled,
        colors =
          ButtonDefaults.buttonColors(
            node.color("containerColor", MaterialTheme.colorScheme.primary)
          ),
      ) {
        content()
      }
  }
}

@Composable
private fun LegacyListItem(
  node: UiBuilderNode,
  modifier: Modifier,
  headline: List<String>,
  supporting: List<String>,
  trailing: List<String>,
  child: @Composable (String, Modifier) -> Unit,
) {
  val accent = parseArgb(node.string("startAccentColor"))
  ListItem(
    headlineContent = { headline.forEach { child(it, Modifier) } },
    modifier =
      modifier.drawBehind {
        if (node.string("startAccentColor").isNotEmpty()) {
          drawRect(
            Color(accent),
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
          )
        }
      },
    supportingContent =
      supporting.takeIf(List<String>::isNotEmpty)?.let {
        { it.forEach { id -> child(id, Modifier) } }
      },
    trailingContent =
      trailing.takeIf(List<String>::isNotEmpty)?.let {
        { it.forEach { id -> child(id, Modifier) } }
      },
  )
}

@Composable
private fun AssetPlaceholder(node: UiBuilderNode, modifier: Modifier) {
  val exportRaster = LocalUiBuilderExportRasterAssets.current[node.id]
  if (exportRaster != null) {
    Image(
      bitmap = exportRaster,
      contentDescription = node.string("contentDescription").ifEmpty { null },
      modifier = modifier,
      contentScale =
        when (node.string("contentScale")) {
          "fit" -> ContentScale.Fit
          "fillBounds" -> ContentScale.FillBounds
          "inside" -> ContentScale.Inside
          else -> ContentScale.Crop
        },
    )
    return
  }
  val key = node.string("assetKey")
  if (
    key == ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY || key == GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY
  ) {
    ProjectOwnedJetcasterArtwork(
      assetKey = key,
      contentDescription = node.string("contentDescription").ifEmpty { null },
      modifier = modifier,
      contentScale =
        when (node.string("contentScale")) {
          "fit" -> ContentScale.Fit
          "fillBounds" -> ContentScale.FillBounds
          "inside" -> ContentScale.Inside
          else -> ContentScale.Crop
        },
    )
    return
  }
  val palette =
    when (key) {
      "ui-builder.gate0.cover" -> listOf(Color(0xFF6750A4), Color(0xFFB69DF8), Color(0xFF21005D))
      else -> error("unsupported asset '$key' on ${node.id}")
    }
  Canvas(modifier) {
    drawRect(Brush.linearGradient(palette, Offset.Zero, Offset(size.width, size.height)))
    drawCircle(
      Color.White.copy(alpha = 0.18f),
      size.minDimension * 0.34f,
      Offset(size.width * 0.76f, size.height * 0.24f),
    )
    drawCircle(
      Color.Black.copy(alpha = 0.18f),
      size.minDimension * 0.22f,
      Offset(size.width * 0.22f, size.height * 0.72f),
    )
    drawPath(
      Path().apply {
        moveTo(size.width * 0.19f, size.height * 0.32f)
        lineTo(size.width * 0.48f, size.height * 0.18f)
        lineTo(size.width * 0.82f, size.height * 0.58f)
        lineTo(size.width * 0.48f, size.height * 0.78f)
        close()
      },
      Color.White.copy(alpha = 0.27f),
    )
    listOf(
        Triple(0.30f, 0.39f, 0.28f),
        Triple(0.47f, 0.30f, 0.38f),
        Triple(0.64f, 0.43f, 0.24f),
      )
      .forEach { (x, y, height) ->
        drawRect(
          Color.White.copy(alpha = 0.72f),
          Offset(size.width * x, size.height * y),
          androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * height),
        )
      }
    drawCircle(
      Color.White.copy(alpha = 0.72f),
      size.minDimension * 0.28f,
      style = Stroke(size.minDimension * 0.035f),
    )
  }
}

@Composable
private fun UnsupportedComponentDiagnostic(componentId: String, modifier: Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      "Unsupported component: $componentId",
      Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

@Composable
private fun Modifier.applyModifier(value: JsonObject, themeCornerRadius: Float): Modifier =
  when (val plan = uiBuilderModifier(value)) {
    UiBuilderModifierPlan.FillMaxSize -> fillMaxSize()
    UiBuilderModifierPlan.FillMaxWidth -> fillMaxWidth()
    UiBuilderModifierPlan.FillMaxHeight -> fillMaxHeight()
    // Applied by the owning BoxScope so it does not contribute to the parent's measurement.
    UiBuilderModifierPlan.MatchParentSize -> this
    is UiBuilderModifierPlan.Padding ->
      padding(plan.startDp.dp, plan.topDp.dp, plan.endDp.dp, plan.bottomDp.dp)
    is UiBuilderModifierPlan.Size ->
      when {
        plan.widthDp != null && plan.heightDp != null -> size(plan.widthDp.dp, plan.heightDp.dp)
        plan.widthDp != null -> width(plan.widthDp.dp)
        else -> plan.heightDp?.let { height(it.dp) } ?: this
      }
    is UiBuilderModifierPlan.Clip ->
      clip(shapeFor(plan.shape, themeCornerRadius = themeCornerRadius))
    is UiBuilderModifierPlan.Width -> width(plan.dp.dp)
    is UiBuilderModifierPlan.Height -> height(plan.dp.dp)
    is UiBuilderModifierPlan.WidthIn ->
      widthIn(plan.minDp?.dp ?: Dp.Unspecified, plan.maxDp?.dp ?: Dp.Unspecified)
    is UiBuilderModifierPlan.HeightIn ->
      heightIn(plan.minDp?.dp ?: Dp.Unspecified, plan.maxDp?.dp ?: Dp.Unspecified)
    is UiBuilderModifierPlan.AspectRatio -> aspectRatio(plan.ratio)
    is UiBuilderModifierPlan.WrapContentSize -> wrapContentSize(alignmentFor(plan.alignment))
    is UiBuilderModifierPlan.Offset -> offset(plan.xDp.dp, plan.yDp.dp)
    is UiBuilderModifierPlan.ZIndex -> zIndex(plan.value)
    is UiBuilderModifierPlan.Background ->
      background(
        uiBuilderColor(plan.color),
        if (plan.shape == null) RectangleShape
        else shapeFor(plan.shape, themeCornerRadius = themeCornerRadius),
      )
    is UiBuilderModifierPlan.Border ->
      border(
        plan.widthDp.dp,
        uiBuilderColor(plan.color),
        if (plan.shape == null) RectangleShape
        else shapeFor(plan.shape, themeCornerRadius = themeCornerRadius),
      )
    is UiBuilderModifierPlan.Alpha -> alpha(plan.alpha)
    is UiBuilderModifierPlan.Shadow ->
      shadow(
        plan.elevationDp.dp,
        if (plan.shape == null) RectangleShape
        else shapeFor(plan.shape, themeCornerRadius = themeCornerRadius),
        clip = plan.clip ?: (plan.elevationDp > 0f),
      )
    is UiBuilderModifierPlan.Rotate -> rotate(plan.degrees)
    is UiBuilderModifierPlan.Scale -> scale(plan.scaleX, plan.scaleY)
    // The position is the renderer's, not the document's: two people looking at one design scroll
    // independently, so the state is remembered per composition and never persisted.
    UiBuilderModifierPlan.VerticalScroll -> verticalScroll(rememberScrollState())
    UiBuilderModifierPlan.HorizontalScroll -> horizontalScroll(rememberScrollState())
    is UiBuilderModifierPlan.TestTag -> testTag(plan.tag)
    // Not an error, for the reason `uiBuilderStateWrite` gives: one unusable modifier costs one
    // node its layout, and throwing costs the whole screen.
    null -> this
  }

/**
 * What this renderer can make of one authored modifier, or null when it can make nothing of it.
 *
 * Extracted for the reason [uiBuilderStateWrite] was — the reading is pure, and it is the half
 * worth testing — and separating it from application is what makes an unusable modifier skippable.
 * Applying one used to throw on three inputs the wire can carry: a `type` this build does not know,
 * a missing `type`, and a `size` naming neither dimension. Each took the whole preview down
 * mid-composition, so a document authored by a newer client cost every working part of the screen
 * rather than one node's layout.
 */
internal sealed interface UiBuilderModifierPlan {
  data object FillMaxSize : UiBuilderModifierPlan

  data object FillMaxWidth : UiBuilderModifierPlan

  data object MatchParentSize : UiBuilderModifierPlan

  data class Padding(
    val startDp: Float,
    val topDp: Float,
    val endDp: Float,
    val bottomDp: Float,
  ) : UiBuilderModifierPlan

  /** At least one dimension is usable; a `size` naming neither is not a size. */
  data class Size(val widthDp: Float?, val heightDp: Float?) : UiBuilderModifierPlan

  data class Clip(val shape: String?) : UiBuilderModifierPlan

  data object FillMaxHeight : UiBuilderModifierPlan

  data class Width(val dp: Float) : UiBuilderModifierPlan

  data class Height(val dp: Float) : UiBuilderModifierPlan

  /** At least one edge is usable; a bound constraining neither end is not a bound. */
  data class WidthIn(val minDp: Float?, val maxDp: Float?) : UiBuilderModifierPlan

  data class HeightIn(val minDp: Float?, val maxDp: Float?) : UiBuilderModifierPlan

  data class AspectRatio(val ratio: Float) : UiBuilderModifierPlan

  data class WrapContentSize(val alignment: String?) : UiBuilderModifierPlan

  data class Offset(val xDp: Float, val yDp: Float) : UiBuilderModifierPlan

  data class ZIndex(val value: Float) : UiBuilderModifierPlan

  /** The colour is kept as its authored token or literal; resolving one needs the theme. */
  data class Background(val color: String, val shape: String?) : UiBuilderModifierPlan

  data class Border(val widthDp: Float, val color: String, val shape: String?) :
    UiBuilderModifierPlan

  data class Alpha(val alpha: Float) : UiBuilderModifierPlan

  data class Shadow(val elevationDp: Float, val shape: String?, val clip: Boolean?) :
    UiBuilderModifierPlan

  data class Rotate(val degrees: Float) : UiBuilderModifierPlan

  data class Scale(val scaleX: Float, val scaleY: Float) : UiBuilderModifierPlan

  data object VerticalScroll : UiBuilderModifierPlan

  data object HorizontalScroll : UiBuilderModifierPlan

  data class TestTag(val tag: String) : UiBuilderModifierPlan
}

internal fun uiBuilderModifier(value: JsonObject): UiBuilderModifierPlan? =
  when (value.optionalString("type")) {
    "fillMaxSize" -> UiBuilderModifierPlan.FillMaxSize
    "fillMaxWidth" -> UiBuilderModifierPlan.FillMaxWidth
    "matchParentSize" -> UiBuilderModifierPlan.MatchParentSize
    "padding" ->
      UiBuilderModifierPlan.Padding(
        value.number("startDp"),
        value.number("topDp"),
        value.number("endDp"),
        value.number("bottomDp"),
      )
    "size" -> {
      // The wire type requires both dimensions and neither is guaranteed to be a number: a JSON
      // null or a string decodes into the document and reaches here. Reading that as "no size" is
      // the only thing this can honestly do with it.
      val width = value.numberOrNull("widthDp")
      val height = value.numberOrNull("heightDp")
      if (width == null && height == null) null else UiBuilderModifierPlan.Size(width, height)
    }
    // A spelling `shapeFor` cannot resolve is rejected here rather than there, so the application
    // step has nothing left to fail on. `shape` is a free string on the wire.
    "clip" ->
      value.optionalString("shape").let { shape ->
        if (isResolvableShape(shape)) UiBuilderModifierPlan.Clip(shape) else null
      }
    "fillMaxHeight" -> UiBuilderModifierPlan.FillMaxHeight
    "width" -> value.numberOrNull("widthDp")?.let(UiBuilderModifierPlan::Width)
    "height" -> value.numberOrNull("heightDp")?.let(UiBuilderModifierPlan::Height)
    // A bound constraining neither end is not a bound, the same reading `size` gets.
    "widthIn" -> {
      val min = value.numberOrNull("minDp")
      val max = value.numberOrNull("maxDp")
      if (min == null && max == null) null else UiBuilderModifierPlan.WidthIn(min, max)
    }
    "heightIn" -> {
      val min = value.numberOrNull("minDp")
      val max = value.numberOrNull("maxDp")
      if (min == null && max == null) null else UiBuilderModifierPlan.HeightIn(min, max)
    }
    // Zero and negative ratios are a divide by nothing in the layout pass, which is a crash rather
    // than a bad layout.
    "aspectRatio" ->
      value.numberOrNull("ratio")?.takeIf { it > 0f }?.let(UiBuilderModifierPlan::AspectRatio)
    "wrapContentSize" ->
      value.optionalString("alignment").let { alignment ->
        if (alignment == null || isResolvableAlignment(alignment))
          UiBuilderModifierPlan.WrapContentSize(alignment)
        else null
      }
    "offset" -> UiBuilderModifierPlan.Offset(value.number("xDp"), value.number("yDp"))
    "zIndex" -> value.numberOrNull("zIndex")?.let(UiBuilderModifierPlan::ZIndex)
    // The colour is read here and resolved at application, where the theme is: an unresolvable
    // token is refused now rather than throwing mid-composition.
    "background" ->
      uiBuilderColorValue(value["color"])?.let { color ->
        val shape = value.optionalString("shape")
        if (shape == null || isResolvableShape(shape)) {
          UiBuilderModifierPlan.Background(color, shape)
        } else null
      }
    "border" ->
      uiBuilderColorValue(value["color"])?.let { color ->
        val width = value.numberOrNull("widthDp") ?: return@let null
        val shape = value.optionalString("shape")
        if (shape == null || isResolvableShape(shape)) {
          UiBuilderModifierPlan.Border(width, color, shape)
        } else null
      }
    "alpha" -> value.numberOrNull("alpha")?.let(UiBuilderModifierPlan::Alpha)
    "shadow" ->
      value.numberOrNull("elevationDp")?.let { elevation ->
        val shape = value.optionalString("shape")
        if (shape == null || isResolvableShape(shape)) {
          UiBuilderModifierPlan.Shadow(elevation, shape, value["clip"]?.booleanOrNull())
        } else null
      }
    "rotate" -> value.numberOrNull("degrees")?.let(UiBuilderModifierPlan::Rotate)
    "scale" -> {
      val x = value.numberOrNull("scaleX")
      val y = value.numberOrNull("scaleY")
      if (x == null || y == null) null else UiBuilderModifierPlan.Scale(x, y)
    }
    "verticalScroll" -> UiBuilderModifierPlan.VerticalScroll
    "horizontalScroll" -> UiBuilderModifierPlan.HorizontalScroll
    "testTag" ->
      value.optionalString("tag")?.takeIf(String::isNotBlank)?.let(UiBuilderModifierPlan::TestTag)
    else -> null
  }

/**
 * The authored colour inside a modifier, as the token or literal the theme resolves.
 *
 * A `UiValueV1` on the wire — `{"type": "colorToken", "value": "primary"}` or a `#AARRGGBB` literal
 * — read here so an unresolvable spelling is a refused write rather than a composition that throws.
 * The same rule `clip` follows for shapes.
 */
private fun uiBuilderColorValue(element: JsonElement?): String? {
  val value = (element as? JsonObject)?.optionalString("value") ?: return null
  return value.takeIf { isResolvableColor(it) }
}

private fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun isResolvableShape(value: String?): Boolean =
  value.isNullOrEmpty() || value in NAMED_SHAPES || value.toFloatOrNull() != null

private val NAMED_SHAPES = setOf("large", "medium", "small")

private fun UiBuilderNode.actionModifier(
  activate: () -> Unit,
  enabled: Boolean,
): Modifier =
  if (eventBindings["click"] == null || componentId in INTERACTIVE_COMPONENTS) Modifier
  else Modifier.clickable(enabled = enabled, onClick = activate)

private fun UiBuilderSemanticActionEntry?.orEmpty() = this ?: UiBuilderSemanticActionEntry()

/** The nine alignments a document may name, for `wrapContentSize` and the child alignment below. */
private fun alignmentFor(value: String?): Alignment =
  when (value) {
    "topStart" -> Alignment.TopStart
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.Center
  }

private fun isResolvableAlignment(value: String): Boolean = value in RESOLVABLE_ALIGNMENTS

private val RESOLVABLE_ALIGNMENTS =
  setOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )

private fun UiBuilderNode.childAlignment(): Alignment =
  when (string("alignment")) {
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.TopStart
  }

private fun UiBuilderNode.dispatch(
  event: String,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
) {
  val actions = eventBindings[event] as? JsonArray ?: return
  actions.forEach { element ->
    uiBuilderStateWrite(element.jsonObject, state)?.let { (variable, next) ->
      onState(variable, next)
    }
  }
}

/**
 * The state write one action performs, or null when it performs none.
 *
 * Extracted from the renderer so it can be tested without a composition: the transition is pure,
 * and a rule about what a button does to a variable should not need a frame to verify.
 */
internal fun uiBuilderStateWrite(
  action: JsonObject,
  state: Map<String, String?>,
): Pair<String, String?>? {
  val variable = action.optionalString("variable") ?: return null
  val value = action["value"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
  return when (action.optionalString("type")) {
    "select",
    "setText",
    // `set` is the protocol's own name for an assignment and behaves exactly as `select` does:
    // write the value. It was unimplemented, so a document authored by any other client using it
    // crashed this renderer rather than working.
    "set" -> variable to value
    "selectOrClear" -> variable to if (state[variable] == value) null else value
    // Declared by the protocol and previously fatal here. A flag is stored in its string form,
    // which is how `stateEquals` already compares it.
    "toggle" -> variable to (state[variable]?.toBooleanStrictOrNull() != true).toString()
    // Not an error. This renderer is fed wire data authored by other clients and by future
    // versions of this one, and a preview that dies on a single unrecognised action loses the
    // whole screen — including every part that does work. Losing one interaction is the smaller
    // failure, and a visible one: the control does nothing when pressed.
    else -> null
  }
}

private fun UiBuilderNode.resolvedBool(name: String, state: Map<String, String?>): Boolean {
  val value = obj(name)
  return if (value.optionalString("type") == "stateEquals") {
    uiBuilderStateEquals(state[value.optionalString("variable")], value["value"])
  } else value["value"]?.jsonPrimitive?.booleanOrNull ?: false
}

/**
 * Whether a `stateEquals` comparison holds — decided the way the Compose export decides it.
 *
 * This renderer keeps state in its string form, so comparing the strings makes `1` and `1.0` two
 * different values. The generated Kotlin declares a `float` variable as `Double` and emits
 * `variable == 1.0`, which calls them the same. The preview and the exported screen then disagree
 * about whether a chip is selected, which is exactly the divergence this builder exists to not
 * have.
 *
 * The operand's own JSON type settles which comparison is the faithful one, without needing the
 * declaration here: an unquoted number exports as a numeric literal and so compares numerically; a
 * quoted one exports as a string literal and so keeps comparing as text, where `1` and `1.0` are
 * properly unequal.
 */
internal fun uiBuilderStateEquals(held: String?, operand: JsonElement?): Boolean {
  val primitive = operand as? JsonPrimitive
  val expected = primitive?.contentOrNull
  if (held == expected) return true
  if (held == null || expected == null || primitive.isString) return false
  val number = held.toDoubleOrNull() ?: return false
  return number == expected.toDoubleOrNull()
}

private fun UiBuilderNode.obj(name: String): JsonObject =
  properties[name]?.objectOrEmpty() ?: JsonObject(emptyMap())

private fun UiBuilderNode.hasModifier(type: String): Boolean = modifiers.any {
  it.objectOrEmpty().optionalString("type") == type
}

private fun UiBuilderNode.textContentTopPaddingDp(): Float =
  modifiers
    .sumOf { modifier ->
      val value = modifier.objectOrEmpty()
      if (value.optionalString("type") == "padding") value.number("topDp").toDouble() else 0.0
    }
    .toFloat()

private fun UiBuilderNode.string(name: String): String =
  obj(name)["value"]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun UiBuilderNode.float(name: String, fallback: Float = 0f): Float =
  obj(name)["value"]?.jsonPrimitive?.floatOrNull ?: fallback

/** A dimension the document actually carries, or null — which is not the same as zero. */
private fun UiBuilderNode.dimension(name: String): Dp? =
  obj(name)["value"]?.jsonPrimitive?.floatOrNull?.dp

private fun UiBuilderNode.integer(name: String, fallback: Int = 0): Int =
  obj(name)["value"]?.jsonPrimitive?.intOrNull ?: fallback

private fun UiBuilderNode.bool(name: String, fallback: Boolean = false): Boolean =
  obj(name)["value"]?.jsonPrimitive?.booleanOrNull ?: fallback

@Composable
private fun UiBuilderNode.textStyle(): androidx.compose.ui.text.TextStyle {
  val style =
    when (string("style")) {
      "displayLarge" -> MaterialTheme.typography.displayLarge
      "displayMedium" -> MaterialTheme.typography.displayMedium
      "displaySmall" -> MaterialTheme.typography.displaySmall
      "headlineLarge" -> MaterialTheme.typography.headlineLarge
      "headlineMedium" -> MaterialTheme.typography.headlineMedium
      "headlineSmall" -> MaterialTheme.typography.headlineSmall
      "titleLarge" -> MaterialTheme.typography.titleLarge
      "titleMedium" -> MaterialTheme.typography.titleMedium
      "titleSmall" -> MaterialTheme.typography.titleSmall
      "bodyLarge" -> MaterialTheme.typography.bodyLarge
      "bodyMedium" -> MaterialTheme.typography.bodyMedium
      "bodySmall" -> MaterialTheme.typography.bodySmall
      "labelLarge" -> MaterialTheme.typography.labelLarge
      "labelMedium" -> MaterialTheme.typography.labelMedium
      "labelSmall" -> MaterialTheme.typography.labelSmall
      "" -> LocalTextStyle.current
      else -> error("unsupported text style '${string("style")}' on $id")
    }
  val scale = LocalUiBuilderTypeScale.current
  return if (scale == 1f) style
  else style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
}

private fun UiBuilderNode.fontWeight() =
  when (string("fontWeight")) {
    "normal" -> FontWeight.Normal
    "bold" -> FontWeight.Bold
    "semiBold" -> FontWeight.SemiBold
    "medium" -> FontWeight.Medium
    else -> null
  }

private fun UiBuilderNode.fontStyle() =
  when (string("fontStyle")) {
    "normal" -> FontStyle.Normal
    "italic" -> FontStyle.Italic
    else -> null
  }

private fun UiBuilderNode.textOverflow() =
  when (string("overflow")) {
    "ellipsis" -> TextOverflow.Ellipsis
    "visible" -> TextOverflow.Visible
    else -> TextOverflow.Clip
  }

private fun UiBuilderNode.textAlign() =
  when (string("textAlign")) {
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    "justify" -> TextAlign.Justify
    else -> TextAlign.Start
  }

private fun UiBuilderNode.textDecoration() =
  when (string("textDecoration")) {
    "underline" -> TextDecoration.Underline
    "lineThrough" -> TextDecoration.LineThrough
    else -> null
  }

/**
 * The colours a document may name, in one place.
 *
 * Read by [UiBuilderNode.color] for a property and by [uiBuilderColor] for a modifier, so the two
 * cannot drift into accepting different spellings of the same design token.
 */
@Composable
private fun colorTokenOrNull(value: String): Color? =
  when (value) {
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "surfaceContainer" -> MaterialTheme.colorScheme.surfaceContainer
    "surfaceContainerLow" -> MaterialTheme.colorScheme.surfaceContainerLow
    "surfaceContainerHigh" -> MaterialTheme.colorScheme.surfaceContainerHigh
    "surfaceContainerHighest" -> MaterialTheme.colorScheme.surfaceContainerHighest
    "primary" -> MaterialTheme.colorScheme.primary
    "onPrimary" -> MaterialTheme.colorScheme.onPrimary
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "onTertiary" -> MaterialTheme.colorScheme.onTertiary
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "onSurfaceVariant" -> MaterialTheme.colorScheme.onSurfaceVariant
    "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
    "transparent" -> Color.Transparent
    else -> null
  }

/** Whether [colorTokenOrNull] or a literal can resolve this, asked without a theme in hand. */
private fun isResolvableColor(value: String): Boolean =
  value.startsWith("#") || value in RESOLVABLE_COLOR_TOKENS

private val RESOLVABLE_COLOR_TOKENS =
  setOf(
    "background",
    "surface",
    "surfaceContainer",
    "surfaceContainerLow",
    "surfaceContainerHigh",
    "surfaceContainerHighest",
    "primary",
    "onPrimary",
    "tertiary",
    "onTertiary",
    "onSurface",
    "onSurfaceVariant",
    "outlineVariant",
    "transparent",
  )

/** A modifier's authored colour, resolved. Refused already if it were not resolvable. */
@Composable
private fun uiBuilderColor(value: String): Color =
  if (value.startsWith("#")) Color(parseArgb(value))
  else colorTokenOrNull(value) ?: Color.Unspecified

@Composable
private fun UiBuilderNode.color(name: String, fallback: Color): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  return when (value) {
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "surfaceContainer" -> MaterialTheme.colorScheme.surfaceContainer
    "surfaceContainerLow" -> MaterialTheme.colorScheme.surfaceContainerLow
    "surfaceContainerHigh" -> MaterialTheme.colorScheme.surfaceContainerHigh
    "surfaceContainerHighest" -> MaterialTheme.colorScheme.surfaceContainerHighest
    "primary" -> MaterialTheme.colorScheme.primary
    "onPrimary" -> MaterialTheme.colorScheme.onPrimary
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "onTertiary" -> MaterialTheme.colorScheme.onTertiary
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "onSurfaceVariant" -> MaterialTheme.colorScheme.onSurfaceVariant
    "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
    "transparent" -> Color.Transparent
    "" -> fallback
    else -> error("unsupported color token '$value' for $name on $id")
  }
}

private fun UiBuilderNode.shape(themeCornerRadius: Float) =
  if (string("shape").isNotEmpty()) shapeFor(string("shape"), id, themeCornerRadius)
  else RoundedCornerShape(float("shapeDp").dp)

private fun shapeFor(
  value: String?,
  nodeId: String? = null,
  themeCornerRadius: Float = 16f,
) =
  RoundedCornerShape(
    when (value) {
      "large" -> themeCornerRadius.dp
      "medium" -> (themeCornerRadius * 0.75f).dp
      "small" -> (themeCornerRadius * 0.5f).dp
      "",
      null -> 0.dp
      else ->
        value.toFloatOrNull()?.dp
          ?: error("unsupported shape '$value'${nodeId?.let { " on $it" }.orEmpty()}")
    }
  )

private fun UiBuilderNode.themeColor(name: String): Color? =
  string(name)
    .takeIf { it.startsWith("#") }
    ?.let { value -> runCatching { Color(parseArgb(value)) }.getOrNull() }

/**
 * How this Column distributes its children down the axis.
 *
 * The catalog has always declared `verticalArrangement`; the renderer read only `verticalSpacingDp`
 * and the Compose exporter emitted only `Arrangement.spacedBy` of it, so a design that asked for
 * `spaceBetween` got `Top` on the canvas AND in the generated Kotlin. Both are fixed together,
 * because a property honoured by one and not the other is the disagreement that made the linear
 * gradient's `direction` worth finding.
 *
 * Spacing composes with the three *aligned* arrangements through `spacedBy(space, alignment)` —
 * which is also why the default path is unchanged: `spacedBy(space)` IS `spacedBy(space, Top)`, so
 * every design authored before this renders identically. The three `space*` arrangements distribute
 * the free space themselves and Compose has no form that also inserts a fixed gap, so there the
 * arrangement wins and the spacing is not silently added on top of it.
 */
private fun UiBuilderNode.verticalArrangement(): Arrangement.Vertical {
  val spacing = float("verticalSpacingDp")
  return when (string("verticalArrangement")) {
    "center" ->
      if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.CenterVertically)
      else Arrangement.Center
    "bottom" ->
      if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.Bottom) else Arrangement.Bottom
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.Top) else Arrangement.Top
  }
}

/**
 * The Row counterpart of [verticalArrangement], ignored in the same way and for the same reason.
 */
private fun UiBuilderNode.horizontalArrangement(): Arrangement.Horizontal {
  val spacing = float("horizontalSpacingDp")
  return when (string("horizontalArrangement")) {
    "center" ->
      if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.CenterHorizontally)
      else Arrangement.Center
    "end" -> if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.End) else Arrangement.End
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else ->
      if (spacing > 0f) Arrangement.spacedBy(spacing.dp, Alignment.Start) else Arrangement.Start
  }
}

/**
 * How this Column aligns its children across the axis.
 *
 * `Start` is both Compose's default and what every design authored while this was ignored has been
 * rendering, so the fallback is not a choice — it is the only value that leaves them unchanged.
 */
private fun UiBuilderNode.horizontalAlignment() =
  when (string("horizontalAlignment")) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
  }

private fun UiBuilderNode.verticalAlignment() =
  when (string("verticalAlignment")) {
    "top" -> Alignment.Top
    "bottom" -> Alignment.Bottom
    else -> Alignment.CenterVertically
  }

private fun UiBuilderNode.icon(): ImageVector =
  googleMaterialIcon(string("iconKey"))?.imageVector
    ?: error("unsupported Google Material icon '${string("iconKey")}' on $id")

@Composable
private fun BuilderIcon(node: UiBuilderNode, modifier: Modifier) {
  val vector = node.icon()
  val description = node.string("contentDescription")
  val tint = node.color("color", LocalContentColor.current)
  val sized = modifier.size(node.float("sizeDp", 24f).dp)
  if (!LocalUiBuilderExportStructuredIcons.current) {
    Icon(vector, description.ifEmpty { null }, sized, tint = tint)
    return
  }
  val paths = remember(vector) { vector.requireSimpleStructuredPaths() }
  val layoutDirection = LocalLayoutDirection.current
  val accessible =
    if (description.isEmpty()) Modifier
    else
      Modifier.semantics {
        contentDescription = description
        role = Role.Image
      }
  Canvas(sized.then(accessible)) {
    val scaleX = size.width / vector.viewportWidth
    val scaleY = size.height / vector.viewportHeight
    withTransform({
      if (vector.autoMirror && layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
        translate(size.width, 0f)
        scale(-scaleX, scaleY)
      } else {
        scale(scaleX, scaleY)
      }
    }) {
      paths.forEach { item -> drawPath(item.path, tint.copy(alpha = tint.alpha * item.fillAlpha)) }
    }
  }
}

private data class StructuredIconPath(val path: Path, val fillAlpha: Float)

private fun ImageVector.requireSimpleStructuredPaths(): List<StructuredIconPath> {
  require(
    root.rotation == 0f &&
      root.pivotX == 0f &&
      root.pivotY == 0f &&
      root.scaleX == 1f &&
      root.scaleY == 1f &&
      root.translationX == 0f &&
      root.translationY == 0f &&
      root.clipPathData.isEmpty()
  ) {
    "catalog icon $name has unsupported root transforms for structured SVG export"
  }
  return root.map { node ->
    require(node is VectorPath) {
      "catalog icon $name has a non-path child that cannot be proven for structured SVG export"
    }
    require(
      node.fill != null &&
        node.stroke == null &&
        node.trimPathStart == 0f &&
        node.trimPathEnd == 1f &&
        node.trimPathOffset == 0f
    ) {
      "catalog icon $name has unsupported paint or trim for structured SVG export"
    }
    StructuredIconPath(
      path =
        PathParser().addPathNodes(node.pathData).toPath().apply { fillType = node.pathFillType },
      fillAlpha = node.fillAlpha,
    )
  }
}

private fun JsonObject.paddingValues() =
  PaddingValues(
    start = number("startDp").dp,
    top = number("topDp").dp,
    end = number("endDp").dp,
    bottom = number("bottomDp").dp,
  )

private fun JsonObject.number(name: String, fallback: Float = 0f) =
  this[name]?.jsonPrimitive?.floatOrNull ?: fallback

private fun JsonObject.numberOrNull(name: String) = this[name]?.jsonPrimitive?.floatOrNull

private fun JsonElement.objectOrEmpty() = this as? JsonObject ?: JsonObject(emptyMap())

private fun parseArgb(value: String): Long =
  value.removePrefix("#").toLongOrNull(16)?.let { if (value.length == 7) it or 0xff000000 else it }
    ?: 0xff000000

private val INTERACTIVE_COMPONENTS =
  setOf("m3/button", "m3/filter-chip", "m3/icon-button", "m3/tab")

private val JetcasterDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F99),
    outlineVariant = Color(0xFF46464F),
    surfaceContainer = Color(0xFF1D1F25),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B),
  )

/**
 * The Material 3 component a Wear content id is drawn as, or the id itself.
 *
 * `wear-m3/text`, `wear-m3/card` and `wear-m3/button` are Wear Material 3 components — the
 * generated screen names `Text`, `TitleCard` and `Button` from `androidx.wear.compose.material3` —
 * and this canvas cannot draw them, because that library is an Android AAR the Wasm build cannot
 * link. So it draws the nearest Material 3 shape, which is what the `wear-m3` catalog's
 * `wasm.notes` say it does.
 *
 * One mapping rather than three duplicated branches, and a mapping rather than a borrow: the ids
 * used to *be* `m3/text` and friends, and a Wear design holding a component named after the mobile
 * Material library claimed something no watch screen can mean. The drawing is borrowed; the
 * identity is not.
 */
internal fun String.wearScreenStandIn(): String =
  when (this) {
    "wear-m3/text" -> "m3/text"
    "wear-m3/card" -> "m3/card"
    "wear-m3/button" -> "m3/button"
    else -> this
  }

/**
 * A dialog drawn where it sits, with `AlertDialog`'s own surface, spacing and button row.
 *
 * ## Why not a real `Dialog`
 *
 * Two reasons, and the second is the one that decides it.
 *
 * A real `Dialog` is a **window**. It leaves the composition's layout, centres itself over the
 * whole screen and scrims everything behind it — so it would draw outside the canvas the operator
 * is arranging, would not be hit-testable as a node, and would export as a picture of a scrim. The
 * canvas is a place to lay a screen out; a window is not a thing that can be laid out in it.
 *
 * And `AlertDialog` requires `onDismissRequest`, which a design has nothing to write into. The
 * document's action vocabulary is `toggle`, `set`, `select` and `selectOrClear` over declared state
 * variables — there is no "close this dialog", because there is no visibility state a dialog is
 * bound to. A real dialog emitted from here would be one nobody could close, which is worse than a
 * panel that admits what it is.
 *
 * So this is the same trade `m3/search-bar` makes, and it is written down in the same place: the
 * component's `wasm.notes` in the catalog say the dialog is drawn inline, and the Compose export
 * emits a matching compatibility helper rather than claiming API parity.
 *
 * ## The geometry is Material's, not invented
 *
 * `AlertDialogDefaults` and the Material 3 dialog spec: a 28dp corner — Material's own,
 * deliberately not the theme's `themeCornerRadius`, which every other surface reads —
 * `surfaceContainerHigh`, 6dp tonal elevation, 24dp padding, 280..560dp wide, and the buttons on
 * one end-aligned row with the dismissing action before the confirming one. An icon, when there is
 * one, is centred and takes the title centre with it — which is Material's rule, not a preference.
 */
@Composable
private fun BuilderDialogSurface(
  node: UiBuilderNode,
  modifier: Modifier,
  icon: @Composable (Modifier) -> Unit,
  title: @Composable (Modifier) -> Unit,
  text: @Composable (Modifier) -> Unit,
  hasIcon: Boolean,
  hasTitle: Boolean,
  hasText: Boolean,
  buttons: @Composable RowScope.(Modifier) -> Unit,
) {
  val corner = node.dimension("shapeDp") ?: DIALOG_CORNER_DP.dp
  Surface(
    modifier = modifier.widthIn(min = DIALOG_MINIMUM_WIDTH_DP.dp, max = DIALOG_MAXIMUM_WIDTH_DP.dp),
    shape = RoundedCornerShape(corner),
    color = node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHigh),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = node.dimension("tonalElevationDp") ?: DIALOG_TONAL_ELEVATION_DP.dp,
  ) {
    Column(
      Modifier.padding(DIALOG_PADDING_DP.dp),
      verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_SPACING_DP.dp),
      // An icon centres the header. Without one the header is start-aligned, which is what every
      // dialog in the Material spec that has no icon looks like.
      horizontalAlignment = if (hasIcon) Alignment.CenterHorizontally else Alignment.Start,
    ) {
      if (hasIcon) icon(Modifier)
      if (hasTitle) title(Modifier)
      if (hasText) {
        // The supporting text is the one part that stays start-aligned under a centred icon:
        // centred body copy is not what Material draws, and a paragraph reads worse for it.
        Column(
          Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_SPACING_DP.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          text(Modifier)
        }
      }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DIALOG_BUTTON_SPACING_DP.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        buttons(Modifier)
      }
    }
  }
}

/**
 * Material's own `DatePicker`, with every source of today's date taken out of it.
 *
 * A picker that reads the clock is a picker whose render changes overnight: the calendar opens on
 * the current month and rings today's cell, so the same document would produce a different PNG on
 * the first of every month and a different one again the day the ring moved. Both are pinned here —
 * the selection and the displayed month come from `selectedDate`, which is an ISO date the document
 * carries — so a render is a function of the design and nothing else, which is what the golden
 * lanes and the visual diff both assume.
 *
 * `input` is not a second component. It is `DisplayMode.Input`, the typed date field Material calls
 * the date input, reached from the same state — which is why the catalog spends a `mode` property
 * here rather than a second id.
 */
@Composable
private fun BuilderDatePicker(node: UiBuilderNode, modifier: Modifier) {
  val selectedDate = node.string("selectedDate").ifEmpty { DEFAULT_SELECTED_DATE }
  val selectedMillis =
    isoDateToEpochMillis(selectedDate) ?: isoDateToEpochMillis(DEFAULT_SELECTED_DATE)
  val input = node.string("mode") == "input"
  val state =
    key(selectedMillis, input) {
      rememberDatePickerState(
        initialSelectedDateMillis = selectedMillis,
        // The month the calendar opens on. Left to Material it is *this* month, read from the
        // system clock, which is the whole nondeterminism this component had to avoid.
        initialDisplayedMonthMillis = selectedMillis,
        initialDisplayMode = if (input) DisplayMode.Input else DisplayMode.Picker,
      )
    }
  DatePicker(state = state, modifier = modifier, showModeToggle = node.bool("showModeToggle"))
}

/**
 * Material's own `TimePicker`, or its `TimeInput`, with the hour and minute the document holds.
 *
 * Same rule as [BuilderDatePicker] and for the same reason: `rememberTimePickerState` defaults to
 * the current time, so an unpinned clock face would draw a different picture every minute.
 */
@Composable
private fun BuilderTimePicker(node: UiBuilderNode, modifier: Modifier) {
  val hour = node.integer("hour", DEFAULT_PICKED_HOUR).coerceIn(0, 23)
  val minute = node.integer("minute", DEFAULT_PICKED_MINUTE).coerceIn(0, 59)
  val is24Hour = node.bool("is24Hour", true)
  val state =
    key(hour, minute, is24Hour) {
      rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24Hour)
    }
  if (node.string("mode") == "input") TimeInput(state = state, modifier = modifier)
  else TimePicker(state = state, modifier = modifier)
}

/**
 * `YYYY-MM-DD` as UTC epoch milliseconds, or null when it is not a date.
 *
 * Written out rather than taken from a date library because this module has none on its floor and a
 * dependency for one civil-date conversion is a poor trade. The algorithm is the standard
 * days-from-civil one: shift the year so March starts it, which makes the leap day the last day of
 * the year and removes every special case from the month arithmetic.
 *
 * Null rather than a substituted date on bad input: the caller decides what an unparseable date
 * falls back to, and silently drawing January 1970 would look like a rendering bug rather than a
 * typo in a property.
 */
internal fun isoDateToEpochMillis(value: String): Long? {
  val parts = value.split('-')
  if (parts.size != 3) return null
  val year = parts[0].toIntOrNull() ?: return null
  val month = parts[1].toIntOrNull() ?: return null
  val day = parts[2].toIntOrNull() ?: return null
  if (month !in 1..12 || day !in 1..31) return null
  val shiftedYear = if (month <= 2) year - 1 else year
  val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
  val yearOfEra = shiftedYear - era * 400
  val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
  val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
  val epochDay = era * 146_097L + dayOfEra - 719_468L
  return epochDay * 86_400_000L
}

/** Material 3 dialog geometry, from `AlertDialogDefaults` and the dialog spec. */
internal const val DIALOG_CORNER_DP = 28f

internal const val DIALOG_TONAL_ELEVATION_DP = 6f

private const val DIALOG_PADDING_DP = 24

private const val DIALOG_ITEM_SPACING_DP = 16

private const val DIALOG_BUTTON_SPACING_DP = 8

private const val DIALOG_MINIMUM_WIDTH_DP = 280

private const val DIALOG_MAXIMUM_WIDTH_DP = 560

/**
 * The date a picker shows when the design names none.
 *
 * A fixed day rather than today, for the determinism [BuilderDatePicker] exists to keep, and this
 * particular day because it is the one the frozen fixtures already pin their `fixedTime` to.
 */
internal const val DEFAULT_SELECTED_DATE = "2024-05-16"

internal const val DEFAULT_PICKED_HOUR = 10

internal const val DEFAULT_PICKED_MINUTE = 30
