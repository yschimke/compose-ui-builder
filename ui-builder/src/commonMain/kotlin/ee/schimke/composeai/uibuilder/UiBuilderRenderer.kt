@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
  val baseColorScheme =
    when {
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
  val cornerRadius = themeHost?.float(THEME_CORNER_RADIUS, 16f)?.coerceIn(0f, 48f) ?: 16f
  CompositionLocalProvider(
    LocalDensity provides density,
    LocalLayoutDirection provides layoutDirection,
    LocalUiBuilderTypeScale provides typeScale,
    LocalUiBuilderCornerRadius provides cornerRadius,
  ) {
    MaterialTheme(colorScheme = colorScheme) {
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
            if (
              document.nodes[root]?.componentId?.startsWith("remote-m3/widget-container-") == true
            )
              Modifier.align(Alignment.Center)
            else Modifier
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
) {
  val node = requireNotNull(document.nodes[nodeId]) { "unknown node: $nodeId" }
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
    )
  }

  when (node.componentId) {
    "remote-m3/widget-container-small" ->
      WearWidgetHostScaffold(
        modifier = measured,
        widthDp = 216,
        heightDp = 76,
      ) {
        slot("content").forEach { child(it, Modifier.fillMaxSize()) }
      }
    "remote-m3/widget-container-large" ->
      WearWidgetHostScaffold(
        modifier = measured,
        widthDp = 216,
        heightDp = 124,
      ) {
        slot("content").forEach { child(it, Modifier.fillMaxSize()) }
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
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
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
        horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
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
    "shape/linear-gradient" ->
      Box(
        measured.background(
          Brush.verticalGradient(
            listOf(
              node.color("startColor", Color.Transparent),
              node.color("endColor", Color.Transparent),
            )
          )
        )
      )
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
 * Compose UI counterpart of the stable Glance Wear widget preview frame. The fixed outer canvas
 * includes the host's 8dp padding around its 200dp-wide content area; the 26dp squircle and default
 * surfaceContainerLow fill are host chrome rather than authored widget content.
 */
@Composable
private fun WearWidgetHostScaffold(
  modifier: Modifier,
  widthDp: Int,
  heightDp: Int,
  content: @Composable () -> Unit,
) {
  Box(
    modifier =
      modifier
        .size(widthDp.dp, heightDp.dp)
        .clip(RoundedCornerShape(26.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .padding(8.dp),
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

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
      Modifier.padding(6.dp),
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

private fun Modifier.applyModifier(value: JsonObject, themeCornerRadius: Float): Modifier =
  when (val plan = uiBuilderModifier(value)) {
    UiBuilderModifierPlan.FillMaxSize -> fillMaxSize()
    UiBuilderModifierPlan.FillMaxWidth -> fillMaxWidth()
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
    else -> null
  }

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
