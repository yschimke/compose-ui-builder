@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme as WearColorScheme
import androidx.wear.compose.material3.LocalContentColor as WearLocalContentColor
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.ProvideThemeTextStyle
import ee.schimke.composeai.uibuilder.ThemeTypefacesHost
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.editor.THEME_BACKGROUND
import ee.schimke.composeai.uibuilder.editor.THEME_CONTENT
import ee.schimke.composeai.uibuilder.editor.THEME_CORNER_RADIUS
import ee.schimke.composeai.uibuilder.editor.THEME_PRIMARY
import ee.schimke.composeai.uibuilder.editor.THEME_SURFACE
import ee.schimke.composeai.uibuilder.editor.THEME_TYPE_SCALE
import ee.schimke.composeai.uibuilder.editor.supportingText
import ee.schimke.composeai.uibuilder.ensureBundledWearDeviceFonts
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.LAUNCHER_WIDGET_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.LauncherWidgetCodeExporter
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.UiExpressions
import ee.schimke.composeai.uibuilder.export.UiRemoteTheme
import ee.schimke.composeai.uibuilder.export.UiTimeText
import ee.schimke.composeai.uibuilder.export.WearScreenTheme
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetHostSpec
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.cardContentFill
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.optionalString
import ee.schimke.composeai.uibuilder.export.stateSelection
import ee.schimke.composeai.uibuilder.nativeOnlyComponentIds
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2
import ee.schimke.composeai.uibuilder.rememberThemeRoleFamilies
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasDocumentHost
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasDocumentScope
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasMode
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasRenderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.LocalCanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.RenderCanvasNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderDrawCanvas
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderModifierPlan
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderTimeText
import ee.schimke.composeai.uibuilder.renderer.sdk.alignmentFor
import ee.schimke.composeai.uibuilder.renderer.sdk.applyCanvasModifier
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasStateEquals
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasStateWrite
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasStateWrites
import ee.schimke.composeai.uibuilder.renderer.sdk.googleMaterialIcon
import ee.schimke.composeai.uibuilder.renderer.sdk.isResolvableAlignment
import ee.schimke.composeai.uibuilder.renderer.sdk.reconcileCanvasState
import ee.schimke.composeai.uibuilder.renderer.sdk.rememberGoogleMaterialIconVector
import ee.schimke.composeai.uibuilder.renderer.sdk.uiBuilderModifier
import ee.schimke.composeai.uibuilder.withFontFamily
import ee.schimke.composeai.uibuilder.withRoleFamilies
import ee.schimke.wearcmp.port.LocalWearDeviceConfiguration
import ee.schimke.wearcmp.port.WearDeviceConfiguration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
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

/**
 * Component ids the catalog declares and this canvas draws as named placeholders — a pack's.
 *
 * Provided by the editor from the catalog rather than compiled in, because a pack's components
 * arrive at run time and this renderer cannot know them: they are whatever another catalog's record
 * proved a call site for. Empty by default, so every other host of this surface — the previews, the
 * renderer bundle — is unchanged.
 */
/**
 * Component id to canvas adapter id, as the catalog names them; the dispatch below keys on the
 * adapter (`UI_BUILDER_CATALOG_CONTRACT.md` item 17). A component with no entry keys on its own id.
 */
internal val LocalUiBuilderCanvasAdapters =
  staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** Canvas-only vocabulary projections for the adapters above. */
internal val LocalUiBuilderCanvasAdapterMappings =
  staticCompositionLocalOf<Map<String, CanvasAdapterMappingV1>> { emptyMap() }

internal val LocalUiBuilderNativeOnly = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * Told when a node draws a stand-in for content the author has not given it yet — a Remote Compose
 * document with no bytes, a Lottie with no animation, a picture with no asset behind it.
 *
 * Null on the canvas, where that stand-in is exactly right: it says what to fill. Set by the
 * component list, where it is not — a tile of a component that has not been given anything draws
 * its own "fill me in" message, and a palette of error boxes reads as a broken catalog.
 */
internal val LocalUiBuilderContentMissing = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Report to [LocalUiBuilderContentMissing], once per composition of the stand-in. */
@Composable
internal fun ReportContentMissing() {
  val report = LocalUiBuilderContentMissing.current ?: return
  SideEffect { report() }
}

/**
 * Every component id the catalog offers, so the `else` branch can tell an unknown id (an error)
 * from one the catalog offers but this canvas cannot draw (a [NativeOnlyPlaceholder], as the
 * published shelf promises; m3-catalog#324).
 *
 * Membership rather than the capability's `adapterStatus`, which is stale in the frozen m3-catalog
 * capabilities.
 */
internal val LocalUiBuilderCatalogComponentIds =
  staticCompositionLocalOf<Set<String>> { emptySet() }

/** Destination selected by a `navigatePage` action in the live canvas. */
internal val LocalUiBuilderNavigator = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * Which host container a Wear widget design is drawn inside. A composition local because the
 * launcher, not the design, owns the frame; switching it never edits the document.
 */
internal val LocalWearWidgetHostShape = staticCompositionLocalOf { WearWidgetHostShape.Default }

/**
 * Remote Compose documents a host has fetched for embedded document nodes, keyed by `documentUrl`.
 * A lookup, not a fetch: the host loads bytes outside composition. `null` means not resolved yet,
 * and the node draws a waiting state rather than an error.
 */
public val LocalRemoteComposeDocuments:
  androidx.compose.runtime.ProvidableCompositionLocal<(String) -> Result<RcDocument>?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * Remote Compose documents a host has captured from a design's inline content, keyed by node id
 * (inline subtrees have no URL). A lookup, not a capture. `null` is the common answer, and the node
 * then draws its Compose stand-ins; a capture only upgrades that to real playback.
 */
public val LocalRemoteComposeCaptures:
  androidx.compose.runtime.ProvidableCompositionLocal<(String) -> Result<RcDocument>?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * Draw the design at its whole extent rather than its frame. Scrollables cannot be measured against
 * an unbounded height, so this is a proxy: lazy lists become non-lazy layouts and `verticalScroll`
 * is dropped. Nothing recycles, `fillParentMaxHeight` measures against the extent, and sticky
 * headers do not stick.
 */
internal val LocalUiBuilderUnrolled = staticCompositionLocalOf { false }

/**
 * Draw a dialog as its surface, in place, rather than as a window.
 *
 * A real `AlertDialog` is a popup: it leaves the layout it was composed in and floats over the
 * whole host, scrim and all. On the canvas that is the component. In a palette thumbnail it is a
 * dialog escaping a 104 dp tile and covering the editor, so the component list sets this and gets
 * the same stand-in an unrolled canvas draws — without unrolling anything else.
 */
internal val LocalUiBuilderInlineDialogs = staticCompositionLocalOf { false }

internal enum class UiBuilderRenderStrategy {
  REAL,
  AUTHORING_ADAPTER,
  COMPATIBILITY_ADAPTER,
}

/** The audited boundary between editor-only stand-ins and bounded Preview components. */
internal fun uiBuilderRenderStrategy(
  componentId: String,
  unrolled: Boolean,
): UiBuilderRenderStrategy =
  when (componentId) {
    "layout/horizontal-carousel",
    "m3/search-bar",
    "m3/search-input-field",
    "m3/dialog" ->
      if (unrolled) UiBuilderRenderStrategy.AUTHORING_ADAPTER else UiBuilderRenderStrategy.REAL
    // Compose Multiplatform 1.12 resolves Material 3 1.9, before this API was added.
    "m3/horizontal-floating-toolbar" -> UiBuilderRenderStrategy.COMPATIBILITY_ADAPTER
    else -> UiBuilderRenderStrategy.REAL
  }

/**
 * The density the design is drawn at: its environment's, not the host's. Shared with the editor
 * canvas, which sizes the frame in pixels, so both agree on the frame's dp size (see
 * `PinnedDesignCanvas`). [fallback] is the host density.
 */
internal fun UiBuilderDocument.renderDensity(fallback: Density): Density =
  Density(
    density = environmentScale("density") ?: fallback.density,
    fontScale = environmentScale("fontScale") ?: fallback.fontScale,
  )

/** A positive, finite number from the environment, or null for anything else — missing included. */
private fun UiBuilderDocument.environmentScale(name: String): Float? =
  environment[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf {
    it.isFinite() && it > 0f
  }

fun uiBuilderLayers(editorOverlay: Boolean): List<UiBuilderLayer> =
  if (editorOverlay) listOf(UiBuilderLayer.Design, UiBuilderLayer.EditorOverlay)
  else listOf(UiBuilderLayer.Design)

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
  /**
   * Components the catalog declares that this canvas cannot draw as themselves, drawn as named
   * placeholders instead of as errors. See [LocalUiBuilderNativeOnly]. Inherited from an enclosing
   * provider by default, so an editor that provides it once covers its canvas and every thumbnail.
   */
  nativeOnlyComponentIds: Set<String> = LocalUiBuilderNativeOnly.current,
  /**
   * Every component id the catalog offers, so one this canvas has no case for draws as a named
   * placeholder rather than as an error. See [LocalUiBuilderCatalogComponentIds]. Inherited from an
   * enclosing provider, like the set above it.
   */
  catalogComponentIds: Set<String> = LocalUiBuilderCatalogComponentIds.current,
  /**
   * Which adapter draws each component, where the catalog names one. See
   * [LocalUiBuilderCanvasAdapters]. Inherited from an enclosing provider, like the two sets above.
   */
  canvasAdapterIds: Map<String, String> = LocalUiBuilderCanvasAdapters.current,
  /** Canvas-only vocabulary projections paired with [canvasAdapterIds]. */
  canvasAdapterMappings: Map<String, CanvasAdapterMappingV1> =
    LocalUiBuilderCanvasAdapterMappings.current,
  /** Executable adapter implementations supplied by the selected catalog runtime. */
  canvasAdapterRegistry: CanvasAdapterRegistry = LocalCanvasAdapterRegistry.current,
  /** Explicit protocol-v2 frame; absent for in-process previews and historical v1 runtimes. */
  renderSurface: UiBuilderRendererSurfaceV2? = null,
  /**
   * Draw the design at its whole extent rather than at its frame — see [LocalUiBuilderUnrolled] for
   * what that swaps and what it costs.
   */
  unrolled: Boolean = LocalUiBuilderUnrolled.current,
  /**
   * Which host container a widget root is framed in. Only a Wear widget design reads it; every
   * other root draws the same whatever it says. See [LocalWearWidgetHostShape].
   */
  wearWidgetHostShape: WearWidgetHostShape = LocalWearWidgetHostShape.current,
) {
  // Wear's face, before anything below can read the Wear type scale: the port resolves it once.
  ensureBundledWearDeviceFonts()
  val overlayBounds =
    remember(document.id, document.revision, renderSessionId) {
      mutableStateMapOf<UiBuilderInstancePath, Rect>()
    }
  val theme = document.environment["theme"]?.jsonPrimitive?.contentOrNull
  val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
  val platformDensity = LocalDensity.current
  val documentDensity = document.renderDensity(platformDensity)
  val density =
    renderSurface?.let { Density(density = it.density, fontScale = documentDensity.fontScale) }
      ?: documentDensity
  val effectiveUnrolled =
    renderSurface?.mode == UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED ||
      (renderSurface == null && unrolled)
  val layoutDirection =
    if (document.environment["layoutDirection"]?.jsonPrimitive?.contentOrNull == "rtl")
      LayoutDirection.Rtl
    else LayoutDirection.Ltr
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
  // Asked for here, where the name is read: the registry loads a family only when something needs
  // it, and the map below is snapshot state, so this recomposes in the family once it arrives.
  val fontRegistry = LocalUiBuilderFontRegistry.current
  LaunchedEffect(fontRegistry, typeface) { typeface?.let { fontRegistry?.request(it) } }
  val fontFamily = typeface?.let(LocalUiBuilderFontFamilies.current::get)
  val typography =
    MaterialTheme.typography.let { base -> fontFamily?.let(base::withFontFamily) ?: base }
  val detachedFrom = LocalUiBuilderDetachedFrom.current
  val overlayTakesInput = !LocalUiBuilderOverlayPassesInput.current
  val wearScreen =
    (detachedFrom ?: document).roots.singleOrNull()?.let(document.nodes::get)?.let { node ->
      val adapter = canvasAdapterIds[node.componentId] ?: node.componentId
      adapter == ROUND_SCREEN_FRAME
    } == true
  val baseColorScheme =
    when {
      wearScreen -> WearDarkColorScheme
      dark && document.id.startsWith("fixture-jetcaster-") -> JetcasterDarkColorScheme
      dark -> darkColorScheme()
      else -> lightColorScheme()
    }
  // `topLevelNodes`, not `roots`: a board is a container the editor put there when a second item
  // was added, and the themed surface under it is still the top of the design. Scanning roots alone
  // dropped the palette, the type scale and the corner radius the moment a themed screen joined a
  // board. `UiBuilderEditorState.themeHost` asks this the same way.
  val themeHost =
    (detachedFrom ?: document).topLevelNodes.firstOrNull { it.componentId == "m3/surface" }
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
  // The host's typefaces per role group, over the document-wide one: the design's display face on
  // its display roles and so on, the environment's family on whatever the host leaves unset.
  val hostTypefaces =
    rememberThemeRoleFamilies(
      ThemeTypefaces.families { themeHost?.string(it) },
      wear = false,
    )
  // Wear's large corner on a Wear screen — see [WEAR_CARD_CORNER_RADIUS_DP]. Material 3's 16dp
  // default draws a recognisably different card, and the card is most of what a Wear list is.
  val cornerRadius =
    themeHost?.float(THEME_CORNER_RADIUS, 16f)?.coerceIn(0f, 48f)
      ?: if (wearScreen) WEAR_CARD_CORNER_RADIUS_DP else 16f
  // The watch the components inside this design are laid out against — see
  // [wearDeviceConfiguration] for what the browser answers when nobody says.
  //
  // The trigger is the PLATFORM the catalog declares, not a component id this file recognises: a
  // Wear catalog is one whose components are drawn with the Wear port, and that is the catalog's
  // statement rather than something to be inferred from a namespace. A board holding one Wear card
  // needs a watch as much as a whole screen does, and so does a shelf thumbnail of one picker.
  val wearCatalog = LocalUiBuilderCatalogPlatform.current == UiBuilderCatalogPlatform.WEAR.wireValue
  val wearDevice =
    if (wearCatalog) {
      arrayOf<ProvidedValue<*>>(
        LocalWearDeviceConfiguration provides
          document.wearDeviceConfiguration(LocalUiBuilderFrameGeometry.current)
      )
    } else {
      // Nothing in the design reads it, and a mobile design is not drawn on a watch: leaving the
      // platform's own answer in place is the honest one rather than claiming a 192dp round device.
      emptyArray()
    }
  CompositionLocalProvider(
    LocalDensity provides density,
    LocalLayoutDirection provides layoutDirection,
    LocalUiBuilderTypeScale provides typeScale,
    LocalThemeHostProperties provides { name: String -> themeHost?.string(name) },
    LocalDocumentTypeface provides typeface,
    LocalUiBuilderCornerRadius provides cornerRadius,
    LocalUiBuilderNativeOnly provides nativeOnlyComponentIds,
    LocalUiBuilderCatalogComponentIds provides catalogComponentIds,
    LocalUiBuilderCanvasAdapters provides canvasAdapterIds,
    LocalUiBuilderCanvasAdapterMappings provides canvasAdapterMappings,
    LocalCanvasAdapterRegistry provides canvasAdapterRegistry,
    LocalUiBuilderUnrolled provides effectiveUnrolled,
    LocalWearWidgetHostShape provides wearWidgetHostShape,
    // What a lazy container scrolls to; see [RevealSelectedItem]. Recomputed only when the
    // selection or the design moves, and equal sets keep the reveal from firing again on an edit.
    LocalUiBuilderRevealChain provides
      remember(document, selectedNodeId) { document.selectionChain(selectedNodeId).toSet() },
    // Consumed above: a surface nested inside this one draws a design of its own.
    LocalUiBuilderDetachedFrom provides null,
    LocalUiBuilderOverlayPassesInput provides false,
    // The design's content colour, not the host's. `MaterialTheme` below sets none, so text with no
    // colour of its own inherited whatever sat outside this surface: the editor chrome's
    // light-on-dark on the canvas, and the platform default black inside a device pane's scene,
    // where no local crosses. A Wear widget's label was white on one and black on the other — near
    // invisible on the widget's dark fill.
    LocalContentColor provides colorScheme.onBackground,
    *wearDevice,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = typography.withRoleFamilies(hostTypefaces),
    ) {
      // The host's default text role ([ThemeTextStyle]) over the theme's own `bodyLarge`, for every
      // text that names no style.
      ProvideThemeTextStyle(ThemeTextStyle.role { themeHost?.string(it) }) {
        WearCatalogTheme(wearCatalog) {
          val updateExtentInputs = LocalCanvasExtentInputs.current
          Box(
            (renderSurface?.let { Modifier.requiredSize(it.widthDp.dp, it.heightDp.dp) }
                ?: Modifier.fillMaxSize())
              // A detached container has no root surface of its own under it to paint the design's
              // ground, so its rows would sit on whatever the host happens to be.
              .then(
                if (detachedFrom != null) Modifier.background(colorScheme.background) else Modifier
              )
          ) {
            CanvasDocumentHost(
              document = document,
              adapterIds = canvasAdapterIds,
              adapterMappings = canvasAdapterMappings,
              mode = if (effectiveUnrolled) CanvasMode.AuthoringUnrolled else CanvasMode.Device,
              density = density,
              modifier = Modifier.fillMaxSize(),
              renderSessionId = renderSessionId,
              // This canvas measures its own extent (`CanvasExtentLayout`); the host's is for a
              // runtime drawing into a root the size it was handed.
              measureUnrolledExtent = false,
              runtimeActionController = runtimeActionController,
              onInspectionSnapshot = onInspectionSnapshot,
              onInspectionInvalidated = onInspectionInvalidated,
              onStateSnapshot = { state ->
                updateExtentInputs?.invoke(CanvasExtentInputs(document, state))
              },
              onOverlayBounds = { path, rect -> overlayBounds[path] = rect },
              onOverlayBoundsForgotten = { path -> overlayBounds.remove(path) },
              rootModifier = { entry ->
                when {
                  entry.node.componentId.startsWith("remote-m3/widget-container-") ->
                    Modifier.align(Alignment.Center)
                  // A screen is taller than its frame by design — the stadium IS the scroll extent
                  // —
                  // so it is pinned to the top and centred across, the way a long screenshot reads.
                  entry.adapterId == ROUND_SCREEN_FRAME -> Modifier.align(Alignment.TopCenter)
                  else -> Modifier
                }
              },
            ) { entry, rootModifier ->
              RenderNode(document = document, entry = entry, host = this, modifier = rootModifier)
            }
            if (editorOverlay) {
              // Every box the selected node drew, not one: a node id is what the editor selects,
              // and
              // once a node can draw more than once the outline follows all of them rather than
              // whichever copy the map happened to answer with.
              val selected =
                overlayBounds.filterKeys { it.nodeId == selectedNodeId }.values.toList()
              Canvas(
                // The design's box, not the incoming constraints: a surface measured against an
                // unbounded axis — the pop-out beside the device frame — wraps its content, and a
                // `fillMaxSize` overlay there came out zero along that axis and caught no click.
                Modifier.matchParentSize()
                  .then(
                    if (!overlayTakesInput) {
                      // The device frame: every box each drawing of a node reported, so a click on
                      // any
                      // copy a loop or a component draws selects that node — the inspection keeps
                      // one
                      // box per id, and hit-testing it found only the copy that measured last.
                      Modifier.passThroughClick { position ->
                        overlayBounds
                          .filterValues { it.contains(position) }
                          .minByOrNull { (_, rect) -> rect.width * rect.height }
                          ?.key
                          ?.let { onNodeSelected?.invoke(it.nodeId) }
                      }
                    } else
                      Modifier.pointerInput(overlayBounds.toMap(), onNodeSelected) {
                        detectTapGestures { position ->
                          overlayBounds
                            .filterValues { it.contains(position) }
                            .minByOrNull { (_, rect) -> rect.width * rect.height }
                            ?.key
                            // The editor selects a node, because a node is what its inspector
                            // edits. Which copy was tapped is the question the selection model has
                            // yet to be asked.
                            ?.let { onNodeSelected?.invoke(it.nodeId) }
                        }
                      }
                  )
              ) {
                selected.forEach { rect ->
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
  }
}

@Composable
private fun RenderNode(
  document: UiBuilderDocument,
  entry: CanvasRenderNode,
  host: CanvasDocumentScope,
  modifier: Modifier = Modifier,
) {
  val navigate = LocalUiBuilderNavigator.current
  val themeCornerRadius = LocalUiBuilderCornerRadius.current
  val nativeOnly = LocalUiBuilderNativeOnly.current
  val unrolledHorizontal = LocalUiBuilderUnrolledHorizontal.current
  host.RenderCanvasNode(
    entry = entry,
    registry = LocalCanvasAdapterRegistry.current,
    modifier = modifier,
    onNavigate = navigate,
    handlesClick = { it.componentId in INTERACTIVE_COMPONENTS },
    applyModifier = { current, value ->
      // Dropped the way the extent drops `verticalScroll`: a sideways pop-out measures against an
      // unbounded width, and a scroller there would be a viewport with nothing to clip.
      if (
        unrolledHorizontal && (value["type"] as? JsonPrimitive)?.contentOrNull == "horizontalScroll"
      ) {
        current
      } else
        current.applyCanvasModifier(
          value = value,
          mode = host.mode,
          resolveColor = ::uiBuilderColor,
          resolveShape = { shapeFor(it, themeCornerRadius = themeCornerRadius) },
        )
    },
    missingComponent = { label, next -> UnsupportedComponentDiagnostic(label, next) },
  ) {
    val node = this.node
    val path = this.path
    val state = this.state
    val enabled = prepared.enabled
    val activate = { prepared.dispatch("click") }
    val measured = prepared.modifier
    fun slot(name: String) = this.slot(name)
    val renderChild = this.renderChild
    val child: @Composable (String, Modifier) -> Unit = { id, next -> Child(id, next) }
    // A Wear container sets Wear's content colour — `onPrimary` inside a filled button — and a
    // child drawn with Material 3's `Text` reads Material 3's, which the surface pins to the
    // design's `onBackground`. Without this bridge a widget button's label was light on light.
    val wearChild: @Composable (String, Modifier) -> Unit = { id, next ->
      CompositionLocalProvider(LocalContentColor provides WearLocalContentColor.current) {
        Child(id, next)
      }
    }

    // The launcher widget root, by component id whatever adapter its catalog names: a launcher
    // widget is drawn into the cell the launcher gives it, filled with its own background, and its
    // one child fills that. It has no host frame of its own — the launcher clips it to its radius.
    if (node.componentId == LauncherWidgetCodeExporter.ROOT) {
      Box(
        measured.background(node.color("background", Color.Transparent)),
        contentAlignment = Alignment.Center,
      ) {
        slot("content").forEach { child(it, Modifier.fillMaxSize()) }
      }
      return@RenderCanvasNode
    }
    // A launcher widget's text, by component id for the same reason: `remote-creation-compose`'s
    // `RemoteText` is a library call the published catalog record does not carry, so no catalog
    // can name an adapter for it, and without this every launcher design's text drew as an
    // unsupported component. Its own names (`fontSize`, `color`, `maxLines`), as it exports them.
    if (node.componentId == LAUNCHER_WIDGET_TEXT_COMPONENT_ID) {
      Text(
        node.string("text"),
        measured,
        color = node.color("color", Color.Unspecified),
        fontSize =
          node.float("fontSize").takeIf { it > 0f }?.sp
            ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        maxLines = node.lineCount("maxLines"),
        onTextLayout = { host.recordTextLayout(path, it) },
      )
      return@RenderCanvasNode
    }
    when (adapterId) {
      // Both container sizes, framed in whichever host shape is being viewed. The footprint is read
      // from `hostSpec` rather than written here, so this canvas and the native render beside it
      // cannot disagree about what the host reserves — see [WearWidgetHostSpec].
      "remote-m3/widget-container-small",
      "remote-m3/widget-container-large" -> {
        // Never null in this branch — the two ids are the enum's own — and `Small` rather than `!!`
        // for the reason every other lookup here refuses to throw: a canvas that crashes cannot
        // draw
        // the Issues panel that would explain why.
        val size =
          WearWidgetScaffoldSize.entries.firstOrNull { it.componentId == node.componentId }
            ?: WearWidgetScaffoldSize.Small
        WearWidgetContainerScaffold(
          node = node,
          modifier = measured,
          spec = size.hostSpec(LocalWearWidgetHostShape.current),
          brushes = { next -> slot("background").forEach { child(it, next) } },
          hasBrushes = slot("background").isNotEmpty(),
        ) {
          // The widget's typefaces: `RemoteMaterialTheme`'s typography in the generated widget,
          // Wear's here, where the remote components are drawn by the Wear port.
          ThemeTypefacesHost(read = { node.string(it) }) {
            slot("content").forEach { child(it, Modifier.fillMaxSize()) }
          }
        }
      }
      // **Experimental.** The adaptive widget, edited at Large because Large is the size where
      // every
      // slot shows. This is `AdaptiveWearWidget.resolve`'s Large arrangement drawn over the
      // authored slots rather than over the resolved design, so each node the designer placed stays
      // the node they select; the preview panes beside it draw the resolved design at both sizes.
      AdaptiveWearWidget.COMPONENT_ID ->
        WearWidgetContainerScaffold(
          node = node,
          modifier = measured,
          spec = WearWidgetScaffoldSize.Large.hostSpec(LocalWearWidgetHostShape.current),
          brushes = { next -> slot(AdaptiveWearWidget.BACKGROUND).forEach { child(it, next) } },
          hasBrushes = slot(AdaptiveWearWidget.BACKGROUND).isNotEmpty(),
        ) {
          Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AdaptiveWearWidget.ACTION_SPACING_DP.dp),
          ) {
            Column(
              Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(AdaptiveWearWidget.TEXT_SPACING_DP.dp),
            ) {
              (slot(AdaptiveWearWidget.HEADLINE) + slot(AdaptiveWearWidget.SUPPORTING)).forEach {
                child(it, Modifier)
              }
            }
            slot(AdaptiveWearWidget.ACTION).forEach { child(it, Modifier) }
          }
        }
      // The Wear screen. Unlike the widget container above, this is EMITTED rather than erased:
      // `ScreenScaffold` is a composable the author calls, so `WearScreenCodeExporter` names it.
      // A viewport is the port's real `ScreenScaffold`; only the unrolled extent is drawn here.
      ROUND_SCREEN_FRAME ->
        WearScreenThemed(node) {
          ThemeTypefacesHost(read = { node.string(it) }) {
            WearScreenScaffold(
              node = node,
              modifier = measured,
              screenWidthDp = document.wearScreenWidthDp(LocalUiBuilderFrameGeometry.current),
              edgeButton = { next -> slot("edgeButton").forEach { child(it, next) } },
              hasEdgeButton = slot("edgeButton").isNotEmpty(),
            ) { next ->
              slot("content").forEach { child(it, next) }
            }
          }
        }
      // Wear's own `ListHeader`, drawn by Wear Compose. This used to be a `Box` of
      // `WEAR_LIST_HEADER_HEIGHT_DP` with a centred `Text` at `WEAR_LIST_HEADER_SP`, which is the
      // hand-assembled replica `WearCanvasComponents`' KDoc explains the canvas no longer has to
      // keep: those two numbers were read off upstream and nothing in this build could check them.
      "wear-m3/list-header" ->
        WearCanvasListHeader(
          text = node.string("text"),
          modifier = measured,
          // The label's truncation, which upstream's `ListHeader` cannot take — it takes a content
          // lambda — so it belongs on the `Text` inside. Both properties were declared and read by
          // nobody, so a header a design clipped to one line drew as many as it wrapped to.
          maxLines = node.lineCount("maxLines"),
          overflow = node.textOverflow(),
        )
      // Previously undrawn entirely: Wear publishes a sub-header of its own and the canvas had no
      // Material 3 component that could stand in for it, so `google-home-wear`'s seven of these
      // were
      // dashed placeholders until the port arrived.
      "wear-m3/list-sub-header" ->
        WearCanvasListSubHeader(
          text = node.string("text"),
          modifier = measured,
          maxLines = node.lineCount("maxLines"),
          overflow = node.textOverflow(),
        )
      "wear-m3/switch-button" ->
        WearCanvasSwitchButton(
          checked = node.bool("checked"),
          enabled = node.bool("enabled", true),
          modifier = measured,
          label = {
            if (slot("label").isEmpty()) WearText(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  WearText(node.string("secondaryLabel"))
                })
              else -> null
            },
        )
      "wear-m3/slider" ->
        WearCanvasSlider(
          value = node.float("value"),
          valueFrom = node.float("valueFrom"),
          valueTo = node.float("valueTo", 1f),
          steps = node.integer("steps"),
          segmented = node.bool("segmented"),
          enabled = node.bool("enabled", true),
          modifier = measured,
        )
      "wear-m3/checkbox-button" ->
        WearCanvasCheckboxButton(
          checked = node.bool("checked"),
          enabled = node.bool("enabled", true),
          modifier = measured,
          label = {
            if (slot("label").isEmpty()) WearText(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  WearText(node.string("secondaryLabel"))
                })
              else -> null
            },
        )
      "wear-m3/radio-button" ->
        WearCanvasRadioButton(
          selected = node.bool("selected"),
          enabled = node.bool("enabled", true),
          modifier = measured,
          label = {
            if (slot("label").isEmpty()) WearText(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  WearText(node.string("secondaryLabel"))
                })
              else -> null
            },
        )
      "wear-m3/stepper" ->
        WearCanvasStepper(
          value = node.float("value"),
          valueFrom = node.float("valueFrom"),
          valueTo = node.float("valueTo", 1f),
          steps = node.integer("steps"),
          enabled = node.bool("enabled", true),
          modifier = measured,
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      "wear-m3/progress-indicator" ->
        WearCanvasProgressIndicator(
          variant = node.string("variant"),
          progress = node.float("progress"),
          segments = node.integer("segments", 1),
          enabled = node.bool("enabled", true),
          modifier = measured,
          indicatorColor = node.wearColor("indicatorColor"),
          trackColor = node.wearColor("trackColor"),
        )
      "wear-m3/page-indicator" ->
        WearCanvasPageIndicator(
          vertical = node.string("variant") == "vertical",
          modifier = measured,
          pageCount = node.integer("pageCount", 4),
          selectedPage = node.integer("selectedPage", 0),
          selectedColor = node.authoredWearColor("selectedColor"),
          unselectedColor = node.authoredWearColor("unselectedColor"),
          backgroundColor = node.authoredWearColor("backgroundColor"),
        )
      "wear-m3/edge-button" ->
        WearCanvasEdgeButton(
          size = node.string("size"),
          enabled = node.bool("enabled", true),
          modifier = measured,
          containerColor = node.wearColor("containerColor"),
          contentColor = node.wearColor("contentColor"),
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      "wear-m3/button-group" -> {
        val children = slot("children")
        WearCanvasButtonGroup(
          weights = children.map { document.nodes[it]?.layoutWeight()?.weight },
          modifier = measured,
        ) { index, weighted ->
          child(children[index], weighted)
        }
      }
      "wear-m3/icon-button" ->
        WearCanvasIconButton(
          variant = node.string("variant"),
          enabled = node.bool("enabled", true),
          modifier = measured,
          containerColor = node.wearColor("containerColor"),
          contentColor = node.wearColor("contentColor"),
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      "wear-m3/text-button" ->
        WearCanvasTextButton(
          variant = node.string("variant"),
          enabled = node.bool("enabled", true),
          modifier = measured,
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      // Routed to the canvas's own icon drawer rather than to Wear's `Icon`. An icon is a tinted
      // vector at a size on both platforms — Wear publishes no shape of its own here — and
      // `BuilderIcon` is what owns this build's key table, its tint resolution and the
      // structured-path export the SVG lane needs. Drawing it twice would be two answers to one
      // question.
      "wear-m3/icon" -> BuilderIcon(node, measured)
      // Wear's own `Text`, reading the same properties as the mobile branch. The style comes from
      // `wearTextStyle`, which resolves roles against Wear's type scale.
      "wear-m3/text" -> {
        val font = resolveFontSettings(node, wearTextStyle(node.string("style")), wear = true)
        WearText(
          node.string("text"),
          measured,
          color = node.color("color", Color.Unspecified),
          style = font.style,
          fontWeight = if (font.setsWeight) null else node.fontWeight(),
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
          onTextLayout = { host.recordTextLayout(path, it) },
        )
      }
      "wear-m3/card" ->
        WearCanvasCard(
          node.string("variant"),
          measured,
          containerColor = node.wearColor("containerColor"),
          contentColor = node.wearColor("contentColor"),
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      "wear-m3/button" ->
        WearCanvasButton(
          node.string("variant"),
          node.bool("enabled", true),
          measured,
          containerColor = node.wearColor("containerColor"),
          contentColor = node.wearColor("contentColor"),
        ) {
          slot("content").forEach { wearChild(it, Modifier) }
        }
      // The dialogs. Drawn only when the document says they are showing: `visible` is the flag the
      // generated screen hangs them on, and a canvas that drew every dialog at once would describe
      // a
      // screen nobody can reach.
      "wear-m3/alert-dialog" ->
        if (node.bool("visible", true)) {
          WearCanvasAlertDialog(
            title = node.string("title"),
            text = node.string("text"),
            modifier = measured,
            hasConfirm = slot("confirmButton").isNotEmpty(),
          ) {
            slot("content").forEach { child(it, Modifier) }
          }
        }
      "wear-m3/confirmation-dialog" ->
        if (node.bool("visible", true)) {
          WearCanvasConfirmationDialog(
            text = node.string("text"),
            variant = node.string("variant"),
            modifier = measured,
          )
        }
      "wear-m3/open-on-phone-dialog" ->
        if (node.bool("visible", true)) {
          WearCanvasOpenOnPhoneDialog(text = node.string("text"), modifier = measured)
        }
      "wear-m3/date-picker" ->
        WearCanvasDatePicker(
          initialDate = node.string("initialDate"),
          type = node.string("type"),
          modifier = measured,
        )
      "wear-m3/time-picker" ->
        WearCanvasTimePicker(
          initialTime = node.string("initialTime"),
          type = node.string("type"),
          modifier = measured,
        )
      "wear-m3/transforming-lazy-column" -> {
        val items = slot("items")
        if (LocalUiBuilderUnrolled.current) {
          // At the extent the list is a Column: no viewport and no row transformation, matching the
          // `ScrollMode.LONG` reference. The real lazy layout cannot be measured against an
          // unbounded height.
          //
          // Rows get the full width and are centred, as `WearCanvasTransformingLazyColumn` does, so
          // headers match the device previews.
          Column(
            modifier = measured,
            verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp", 4f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            items.forEach { child(it, Modifier.fillMaxWidth()) }
          }
        } else {
          // The real lazy column, scaling and fading its rows through the library's own
          // `transformedHeight`. The `Column` this replaces said in its own comment that the
          // transformation "does not exist off Android"; it does now, via the CMP port.
          WearCanvasTransformingLazyColumn(
            itemCount = items.size,
            verticalSpacingDp = node.float("verticalSpacingDp", 4f),
            modifier = measured,
            // `transformation` is the design's choice, and the canvas read it nowhere: the rows
            // always
            // carried the treatment here while the generated screen honoured the property.
            transformation = node.string("transformation") != "none",
            itemIds = items,
          ) { index, itemModifier ->
            child(items[index], itemModifier)
          }
        }
      }
      "layout/list-detail-pane-scaffold" ->
        AdaptiveSupportingPaneScaffold(
          node,
          measured,
          { next -> slot("listPane").forEach { child(it, next) } },
          { next -> slot("detailPane").forEach { child(it, next) } },
          if (slot("extraPane").isEmpty()) null
          else { next -> slot("extraPane").forEach { child(it, next) } },
        )
      "layout/supporting-pane-scaffold" ->
        AdaptiveSupportingPaneScaffold(
          node,
          measured,
          { next -> slot("mainPane").forEach { child(it, next) } },
          { next -> slot("supportingPane").forEach { child(it, next) } },
        )
      // Inline remote content: everything below is `@RemoteComposable` in the generated code.
      // Without a capture it is drawn with ordinary Compose stand-ins in a marked frame, since the
      // browser has no Remote Compose writer. With a capture ([LocalRemoteComposeCaptures]) the
      // real bytes are played by `RcComposePlayer`, and the frame only marks the boundary.
      REMOTE_COMPOSE_INLINE_COMPONENT_ID -> {
        val captured = LocalRemoteComposeCaptures.current(node.id)
        RemoteContentFrame(
          label = "Remote Compose",
          detail = if (captured == null) null else "played",
          modifier = measured,
        ) {
          if (captured == null) {
            slot("content").forEach { child(it, Modifier.fillMaxWidth()) }
          } else {
            PlayedInlineRemoteContent(
              document = document,
              node = node,
              captured = captured,
              modifier = Modifier.fillMaxWidth(),
              slotContent = { fill, next -> Box(next) { child(fill, Modifier.fillMaxWidth()) } },
            )
          }
        }
      }
      // The way back out. A custom component is a hole the document reserves for host content, so
      // what the canvas draws inside it is ordinary Compose — which is also what a registered
      // renderer draws on a real player. The name is on the frame because it is the whole contract:
      // a preview draws this only where a custom component of that name is registered, and an
      // author
      // who cannot see the name cannot check that.
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID ->
        RemoteContentFrame(
          label = "Custom",
          detail = node.string("name").ifEmpty { "unnamed" },
          modifier =
            measured
              .then(node.dimension("widthDp")?.let { Modifier.width(it) } ?: Modifier)
              .then(node.dimension("heightDp")?.let { Modifier.height(it) } ?: Modifier),
        ) {
          slot("content").forEach { child(it, Modifier.fillMaxWidth()) }
        }
      "remote-compose/document" ->
        RemoteComposeDocument(
          document = document,
          node = node,
          modifier = measured,
          state = state,
          onEvent = { event -> event.bindingName()?.let(prepared::dispatch) },
          slotContent = { name, next ->
            Box(next) { slot(name).forEach { child(it, Modifier.fillMaxSize()) } }
          },
        )
      // The Lottie element. Drawn as its identity and place, like the Wear components below and for
      // a sharper version of the same reason: the animation this node carries is not *played* by
      // the
      // export at all — Horologist's `LottieAnimation` compiles it into the document's own
      // operations
      // while the widget is being built — and the browser can neither run that Android-only
      // creation
      // API nor host a Lottie runtime to fake it with. What the canvas can say truthfully is which
      // animation is here and whether it is ready to export, so that is what it says.
      "remote-m3/lottie" -> LottiePlaceholder(node, measured)
      "layout/scaffold" -> {
        val containerColor = node.color("containerColor", MaterialTheme.colorScheme.background)
        // `Scaffold` is a `SubcomposeLayout` and fails against an unbounded height, so the extent
        // draws the bar then the content as a Column. The snackbar host is dropped: it floats over
        // a viewport, which the extent does not have.
        if (LocalUiBuilderUnrolled.current) {
          Column(measured.background(containerColor)) {
            slot("topBar").forEach { child(it, Modifier) }
            slot("content").forEach { child(it, Modifier) }
          }
        } else {
          Scaffold(
            modifier = measured,
            containerColor = containerColor,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { slot("topBar").forEach { child(it, Modifier) } },
            snackbarHost = { slot("snackbarHost").forEach { child(it, Modifier) } },
          ) { padding ->
            slot("content").forEach { child(it, Modifier.padding(padding)) }
          }
        }
      }
      "layout/box" ->
        Box(measured, contentAlignment = alignmentFor(node.boxContentAlignment())) {
          val children =
            if (UiBuilderBuildFeatures.remoteCompose && SHOW_BY_STATE in node.properties)
              listOfNotNull(node.stateSelection()?.selectedNode(state, document.stateVariables))
            else slot("children")
          children.forEach { id ->
            val item = document.nodes.getValue(id)
            val parentSizing =
              if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
            child(
              id,
              // A child that names no alignment of its own takes the box's `contentAlignment`,
              // exactly as `Box(contentAlignment = …)` does in the exported source.
              parentSizing
                .then(item.boxAlignment()?.let { Modifier.align(alignmentFor(it)) } ?: Modifier)
                .zIndex(item.float("zIndex")),
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
            val weight = item.layoutWeight()
            // A weight is a share of what is left over, and at the extent there is no "left over":
            // the column is measured against an unbounded height, so a weighted child is handed no
            // space at all and draws nothing. That is the failure that looks most like success —
            // the
            // strip measures a plausible height and the list inside it is simply blank — so the
            // extent drops every weight, authored or inferred, and lets each child wrap.
            val sized =
              when {
                LocalUiBuilderUnrolled.current ->
                  if (item.componentId == "layout/lazy-column") Modifier.fillMaxWidth()
                  else Modifier
                weight != null -> Modifier.weight(weight.weight, weight.fill ?: true)
                // A lazy column with no weight of its own would measure its children unbounded and
                // fail; taking what is left is the only sane reading of "put a list here".
                item.componentId == "layout/lazy-column" -> Modifier.fillMaxWidth().weight(1f)
                else -> Modifier
              }
            val aligned =
              item.crossAxisAlignment()?.let { sized.align(horizontalAlignmentFor(it)) } ?: sized
            child(id, aligned)
          }
        }
      "layout/row" ->
        Row(
          measured,
          horizontalArrangement = node.horizontalArrangement(),
          verticalAlignment = node.verticalAlignment(),
        ) {
          slot("children").forEach { id ->
            val item = document.nodes.getValue(id)
            val weight = item.layoutWeight()
            val sized =
              if (weight == null) Modifier else Modifier.weight(weight.weight, weight.fill ?: true)
            val aligned =
              item.crossAxisAlignment()?.let { sized.align(verticalAlignmentFor(it)) } ?: sized
            child(id, aligned)
          }
        }
      // A row that wraps, the one layout primitive that adapts to a narrow window without a
      // breakpoint (docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md, gap 3). The main axis reads a
      // row's `horizontalArrangement`/`horizontalSpacingDp`; the gap between lines reads a column's
      // pair.
      "layout/flow-row" ->
        FlowRow(
          measured,
          horizontalArrangement = node.horizontalArrangement(),
          verticalArrangement = node.verticalArrangement(),
          // Absent and zero both mean "as many as fit", which is the whole point of the component;
          // a design that wants three per line says three.
          maxItemsInEachRow = node.integer("maxItemsInEachRow").takeIf { it > 0 } ?: Int.MAX_VALUE,
        ) {
          slot("children").forEach { child(it, Modifier) }
        }
      // Remote Compose's two layouts that HIDE a child rather than squeeze it, lowest
      // `collapsiblePriority` first. Same arrangement and alignment vocabulary as the eager
      // column and row, so a design moves between them by changing the id.
      "layout/collapsible-column",
      "layout/collapsible-row" -> {
        val vertical = node.componentId == "layout/collapsible-column"
        val children = slot("children")
        val items = children.map { document.nodes.getValue(it) }
        val shared = items.mapNotNull { it.crossAxisAlignment() }.distinct().singleOrNull()
        CollapsibleLinearLayout(
          vertical = vertical,
          priorities = items.map { it.collapsiblePriority() },
          // At the extent there is no leftover to share, exactly as in a column.
          weights =
            if (LocalUiBuilderUnrolled.current) items.map { null }
            else items.map { it.layoutWeight()?.weight },
          horizontalArrangement = node.horizontalArrangement(),
          verticalArrangement = node.verticalArrangement(),
          horizontalAlignment = shared?.let(::horizontalAlignmentFor) ?: node.horizontalAlignment(),
          verticalAlignment =
            shared?.let(::verticalAlignmentFor)
              ?: if (vertical) Alignment.Top else node.verticalAlignment(),
          modifier = measured,
        ) {
          children.forEach { child(it, Modifier) }
        }
      }
      // `RemoteFitBox`: the children are alternatives, largest first, and the first that fits is
      // the one drawn. At the extent everything fits, so the first child is what an author sees
      // while editing, and the device preview is where the choice is made against the real host.
      "layout/fit-box" ->
        FitBoxLayout(
          alignment =
            BiasAlignment(
              horizontalBias =
                when (node.string("horizontalAlignment")) {
                  "start" -> -1f
                  "end" -> 1f
                  else -> 0f
                },
              verticalBias =
                when (node.string("verticalArrangement")) {
                  "top" -> -1f
                  "bottom" -> 1f
                  else -> 0f
                },
            ),
          modifier = measured,
        ) {
          slot("children").forEach { child(it, Modifier) }
        }
      "layout/lazy-row" -> {
        // Unrolled only in a sideways pop-out, the one surface measured against an unbounded
        // width; the extent is the frame's width and keeps the real row — see
        // [LocalUiBuilderUnrolledHorizontal].
        if (unrolledHorizontal) {
          Row(
            modifier = measured.padding(node.obj("contentPadding").paddingValues()),
            horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
          ) {
            slot("items").forEach { child(it, Modifier) }
          }
        } else {
          val lazyState = rememberLazyListState()
          host.updateSemanticAction(node.id) {
            it.copy(scrollToItem = { index -> lazyState.requestScrollToItem(index) })
          }
          RevealSelectedItem(slot("items"), lazyState::showsWhole) {
            lazyState.animateScrollToItem(it)
          }
          LazyRow(
            modifier = measured,
            state = lazyState,
            contentPadding = node.obj("contentPadding").paddingValues(),
            horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
          ) {
            items(slot("items"), key = { it }) { child(it, Modifier) }
          }
        }
      }
      "layout/lazy-column" -> {
        // A list drawn at the extent is a Column of the same children: same order, same spacing,
        // same padding, no viewport. See [LocalUiBuilderUnrolled].
        if (LocalUiBuilderUnrolled.current) {
          Column(
            modifier = measured.padding(node.obj("contentPadding").paddingValues()),
            verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
          ) {
            slot("items").forEach { child(it, Modifier) }
          }
        } else {
          val lazyState = rememberLazyListState()
          host.updateSemanticAction(node.id) {
            it.copy(
              scrollBy = lazyState::dispatchRawDelta,
              scrollToItem = { index -> lazyState.requestScrollToItem(index) },
            )
          }
          RevealSelectedItem(slot("items"), lazyState::showsWhole) {
            lazyState.animateScrollToItem(it)
          }
          LazyColumn(
            modifier = measured,
            state = lazyState,
            contentPadding = node.obj("contentPadding").paddingValues(),
            verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
          ) {
            items(slot("items"), key = { it }) { child(it, Modifier) }
          }
        }
      }
      "layout/lazy-grid" -> {
        val minimum = node.obj("columns").number("minimumCellWidthDp", 362f).coerceAtLeast(1f)
        // A vertical grid refuses an unbounded height for the same reason a column does, so the
        // extent draws its cells as wrapping rows. Spans are lost with the lazy layout; a `full`
        // span still takes the row it is given rather than the whole line.
        if (LocalUiBuilderUnrolled.current) {
          FlowRow(
            modifier = measured.padding(node.obj("contentPadding").paddingValues()),
            horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
            verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
          ) {
            slot("items").forEach { child(it, Modifier.width(minimum.dp)) }
          }
        } else {
          val lazyState = rememberLazyGridState()
          host.updateSemanticAction(node.id) {
            it.copy(
              scrollBy = lazyState::dispatchRawDelta,
              scrollToItem = { index -> lazyState.requestScrollToItem(index) },
            )
          }
          RevealSelectedItem(slot("items"), lazyState::showsWhole) {
            lazyState.animateScrollToItem(it)
          }
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
      }
      "layout/horizontal-carousel" -> {
        val items = slot("items")
        if (unrolledHorizontal) {
          UnrolledHorizontalCarousel(node, measured, items) { id, next -> child(id, next) }
        } else if (
          uiBuilderRenderStrategy(node.componentId, LocalUiBuilderUnrolled.current) ==
            UiBuilderRenderStrategy.AUTHORING_ADAPTER
        ) {
          val lazyState = rememberLazyListState()
          CompatibleHorizontalCarousel(node, measured, lazyState, items) { id, next ->
            child(id, next)
          }
        } else {
          val carouselState = rememberCarouselState { items.size }
          host.updateSemanticAction(node.id) { it.copy(scrollBy = carouselState::dispatchRawDelta) }
          val composedItems = remember(carouselState) { mutableSetOf<String>() }
          RevealSelectedItem(items, composedItems::contains) {
            carouselState.animateScrollToItem(it)
          }
          HorizontalUncontainedCarousel(
            state = carouselState,
            itemWidth = node.float("itemWidthDp", 128f).dp,
            modifier = measured,
            itemSpacing = node.float("itemSpacingDp").dp,
            contentPadding = PaddingValues(start = node.float("contentPaddingStartDp").dp),
          ) { index ->
            MarkComposed(composedItems, items[index])
            child(items[index], Modifier)
          }
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
      "m3/search-bar" -> {
        // The corner the screen exporter writes as `shape`: drawn here too, or an edit to it would
        // change the export and nothing on the canvas.
        val searchShape = node.dimension("shapeDp")?.let(::RoundedCornerShape)
        val inputField: @Composable () -> Unit = {
          slot("inputField").forEach { child(it, Modifier.fillMaxSize()) }
        }
        if (
          uiBuilderRenderStrategy(node.componentId, LocalUiBuilderUnrolled.current) ==
            UiBuilderRenderStrategy.AUTHORING_ADAPTER
        ) {
          Surface(
            measured.height(56.dp),
            shape = searchShape ?: CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = node.float("tonalElevationDp").dp,
          ) {
            Column { inputField() }
          }
        } else {
          SearchBar(
            inputField = inputField,
            expanded = node.bool("expanded"),
            onExpandedChange = {},
            modifier = measured,
            shape = searchShape ?: SearchBarDefaults.inputFieldShape,
            tonalElevation = node.float("tonalElevationDp").dp,
          ) {
            slot("expandedContent").forEach { child(it, Modifier) }
          }
        }
      }
      "m3/search-input-field" -> {
        val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
        // A literal query is a field that only shows text — the spelling a design can export
        // without stateful authoring — so it draws that text rather than an empty field.
        val value = if (variable != null) state[variable].orEmpty() else node.string("value")
        val onValueChange: (String) -> Unit = { if (variable != null) host.setState(variable, it) }
        if (
          uiBuilderRenderStrategy(node.componentId, LocalUiBuilderUnrolled.current) ==
            UiBuilderRenderStrategy.AUTHORING_ADAPTER
        ) {
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
                onValueChange,
                Modifier.fillMaxWidth(),
                enabled = node.bool("enabled", true),
                textStyle =
                  LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
              )
            }
            slot("trailingIcon").forEach { child(it, Modifier) }
          }
        } else {
          SearchBarDefaults.InputField(
            query = value,
            onQueryChange = onValueChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            modifier = measured,
            enabled = node.bool("enabled", true),
            placeholder = { slot("placeholder").forEach { child(it, Modifier) } },
            leadingIcon = { slot("leadingIcon").forEach { child(it, Modifier) } },
            trailingIcon = { slot("trailingIcon").forEach { child(it, Modifier) } },
          )
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
      // Both read state, because a tab row is the one place where clicking is the whole point. The
      // click already reached the reducer; the row drew its indicator from the literal the design
      // was
      // saved with and the tab drew its own selection the same way, so the press moved the variable
      // and nothing on the canvas moved with it.
      "m3/primary-tab-row" ->
        PrimaryTabRow(node.resolvedInteger("selectedIndex", state), measured) {
          slot("tabs").forEach { child(it, Modifier) }
        }
      // The same row, scrolling instead of dividing the width: on a phone five tabs keep their
      // labels rather than each getting a fifth of the screen and an ellipsis.
      "m3/primary-scrollable-tab-row" ->
        PrimaryScrollableTabRow(node.resolvedInteger("selectedIndex", state), measured) {
          slot("tabs").forEach { child(it, Modifier) }
        }
      "m3/tab" ->
        Tab(
          node.resolvedBool("selected", state),
          activate,
          measured,
          enabled = enabled,
          text = { slot("text").forEach { child(it, Modifier) } },
        )
      "m3/navigation-suite-scaffold" -> {
        val primaryAction = slot("primaryAction")
        AdaptiveNavigationSuiteScaffold(
          measured,
          screenHeight =
            document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.dp
              ?: 0.dp,
          items = { slot("navigationItems").forEach { child(it, Modifier) } },
          primaryAction = { primaryAction.forEach { child(it, Modifier) } },
          content = { slot("content").forEach { child(it, Modifier) } },
        )
      }
      "m3/navigation-suite-item" -> {
        val label = slot("label")
        NavigationSuiteItem(
          selected = node.resolvedBool("selected", state),
          onClick = activate,
          icon = { slot("icon").forEach { child(it, Modifier) } },
          label = if (label.isEmpty()) null else ({ label.forEach { child(it, Modifier) } }),
          modifier = measured,
          navigationSuiteType = LocalFrameNavigationSuiteType.current,
          enabled = enabled,
        )
      }
      "m3/list-item" ->
        LegacyListItem(
          node,
          measured,
          slot("headline"),
          slot("supporting"),
          slot("trailing"),
          child,
        )
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
          // Filled only along the axes the card was given a size on — see [cardContentFill] for why
          // `fillMaxSize` here made a card with no height swallow its column (#483).
          val fill = node.cardContentFill()
          Box(
            Modifier.then(if (fill.width) Modifier.fillMaxWidth() else Modifier)
              .then(if (fill.height) Modifier.fillMaxHeight() else Modifier)
          ) {
            slot("content").forEach { id ->
              val item = document.nodes.getValue(id)
              val parentSizing =
                if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
              child(
                id,
                // Unaligned, a child sits where the card's content box puts it: top-start.
                parentSizing
                  .then(item.boxAlignment()?.let { Modifier.align(alignmentFor(it)) } ?: Modifier)
                  .zIndex(item.float("zIndex")),
              )
            }
          }
        }
      // `onCheckedChange` rather than the clickable modifier every node gets: a control that
      // reports
      // its own change is what makes the box tickable in the live playground, and Material draws
      // the
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
        // The variable the slider writes, the same seam a text field's `value` uses. A slider with
        // no
        // variable still moves — Material needs a value to draw a thumb — but the movement goes
        // nowhere, which is what an unbound control means everywhere else in this catalog.
        val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
        val from = node.float("valueFrom", 0f)
        val to = node.float("valueTo", 1f).coerceAtLeast(from)
        val bound = variable?.let { state[it]?.toFloatOrNull() }
        Slider(
          value = (bound ?: node.float("value")).coerceIn(from, to),
          onValueChange = { next ->
            if (variable != null) host.setState(variable, next.toString())
          },
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
        // The variable the field writes, not a local `remember`. A design's text field is a view of
        // a
        // declared state variable — that is what makes typing in the canvas change the design
        // rather
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
          if (variable != null) host.setState(variable, next)
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
      "m3/dialog" -> {
        if (
          LocalUiBuilderInlineDialogs.current ||
            uiBuilderRenderStrategy(node.componentId, LocalUiBuilderUnrolled.current) ==
              UiBuilderRenderStrategy.AUTHORING_ADAPTER
        ) {
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
        } else {
          AlertDialog(
            onDismissRequest = {},
            confirmButton = { slot("confirmButton").forEach { child(it, Modifier) } },
            modifier = measured,
            dismissButton = { slot("dismissButton").forEach { child(it, Modifier) } },
            icon = { slot("icon").forEach { child(it, Modifier) } },
            title = { slot("title").forEach { child(it, Modifier) } },
            text = { slot("text").forEach { child(it, Modifier) } },
            shape =
              node.dimension("shapeDp")?.let(::RoundedCornerShape) ?: AlertDialogDefaults.shape,
            containerColor =
              node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHigh),
            tonalElevation =
              node.dimension("tonalElevationDp") ?: AlertDialogDefaults.TonalElevation,
          )
        }
      }
      "m3/date-picker" -> BuilderDatePicker(node, measured)
      "m3/time-picker" -> BuilderTimePicker(node, measured, document.timePickerLayoutType())
      "m3/icon-button" ->
        BuilderIconButton(
          variant = node.string("variant"),
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
          val content = slot("content")
          // A button whose label is a property rather than a child — the `remote-widgets`
          // `WidgetButton(text = …)` this adapter draws — shows that label, not an empty pill.
          if (content.isEmpty() && "text" in node.properties) Text(node.string("text"))
          else content.forEach { child(it, Modifier) }
        }
      "m3/horizontal-floating-toolbar" ->
        CompatibleFloatingToolbar(node, measured) {
          slot("content").forEach { child(it, Modifier) }
        }
      "m3/horizontal-divider" ->
        HorizontalDivider(
          measured,
          // Absent means Material's own thickness, not zero — a hairline is what a divider is, and
          // `float(name)`'s zero fallback would have drawn nothing at all.
          thickness = node.dimension("thicknessDp") ?: DividerDefaults.Thickness,
          color = node.color("color", MaterialTheme.colorScheme.outlineVariant),
        )
      "m3/icon" -> BuilderIcon(node, measured)
      "m3/text" -> {
        val font = resolveFontSettings(node, node.textStyle(), wear = false)
        Text(
          node.string("text"),
          measured,
          color = node.color("color", Color.Unspecified),
          style = font.style,
          fontWeight = if (font.setsWeight) null else node.fontWeight(),
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
          onTextLayout = { host.recordTextLayout(path, it) },
        )
      }
      "asset/image" -> AssetImage(document, node, measured)
      // A drawing: its operations are drawn by the canvas, not entered as nodes of their own.
      // `this.entry`: the node being drawn, not the root this renderer was entered from.
      UiDrawing.CANVAS -> UiBuilderDrawCanvas(this.entry, measured) { uiBuilderColor(it) }
      UiTimeText.ID -> {
        val clock =
          UiExpressions.Clock.of(
            (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull
          )
        val time =
          "${clock.hour.toString().padStart(2, '0')}:${clock.minute.toString().padStart(2, '0')}"
        UiBuilderTimeText(node, time, measured) { uiBuilderColor(it) }
      }
      // The scaffold's theme copy, for one child: the role names and properties are the same.
      UiRemoteTheme.ID ->
        WearScreenThemed(node) {
          Box(measured) { slot(UiRemoteTheme.SLOT).forEach { id -> child(id, Modifier) } }
        }
      in UiDrawing.BY_ID -> Unit
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
      // A pack's component: declared by the catalog, proven by another catalog's record, and drawn
      // here as its name and place — the browser cannot link the classes that draw it. The caption
      // says whose it is, because a `Session Card` on a Material
      // 3 palette is a thing worth being told came from Confetti.
      in nativeOnly ->
        NativeOnlyPlaceholder(node, measured, caption = node.componentId.substringBefore('/')) {
          node.slots.values.flatten().forEach { childId -> child(childId, Modifier) }
        }
      // Checked against the component id, not the adapter id, so a component whose catalog names an
      // adapter this build lacks draws a placeholder rather than an error (contract item 17).
      else ->
        if (node.componentId in LocalUiBuilderCatalogComponentIds.current) {
          // On the palette, exportable, rendered by its own catalog — and this canvas has no case
          // for it, either because its id has no branch or because the adapter it named is one this
          // build does not ship. The shelf already promises exactly this picture.
          NativeOnlyPlaceholder(node, measured) {
            // The children, for the reason the Wear and pack placeholders keep theirs: an icon
            // inside an icon button is the thing an author is looking for, and dropping it would
            // hide whole subtrees from the canvas.
            node.slots.values.flatten().forEach { childId -> child(childId, Modifier) }
          }
        } else {
          // Not the catalog's at all. Now the only thing this says, and it is true when it says it.
          UnsupportedComponentDiagnostic(node.componentId, measured)
        }
    }
  }
}

/** The `type` a value wrapper declares, or null when it is absent or is not a scalar. */
private fun JsonObject.wrapperType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

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
    // `horizontal`/`vertical` name the axis without a sense, which is what the widget templates
    // write. Read here and identically by `RemoteContentEmitter`, so that a side scrim drawn on
    // this canvas is the one the generated Kotlin paints — before this, both fell through to the
    // vertical `else` and the design's own axis was silently discarded on the way in.
    "leftToRight",
    "horizontal" -> Brush.horizontalGradient(listOf(start, end))
    "rightToLeft" -> Brush.horizontalGradient(listOf(end, start))
    else -> Brush.verticalGradient(listOf(start, end))
  }
}

/**
 * The screen diameter a Wear design is authored against, from the document's own frame (the Screen
 * inspector's `wearos_*_round` presets). Falls back to the small round size for a frame no watch
 * was picked for.
 */
private fun UiBuilderDocument.wearScreenWidthDp(frame: UiBuilderFrameGeometry): Int =
  frame.diameterFor(environment["widthDp"]?.jsonPrimitive?.intOrNull)

/**
 * The watch the Wear components in this design are laid out against.
 *
 * The Wear port reads `LocalWearDeviceConfiguration`, which on Wasm defaults to the browser
 * viewport — so components that branch on screen size (pickers, `EdgeButton`, padding) were laid
 * out against the editor window. This answers with the document's diameter on both axes, round,
 * with deterministic defaults (24-hour clock, left wrist) so renders do not vary with the host.
 */
internal fun UiBuilderDocument.wearDeviceConfiguration(
  frame: UiBuilderFrameGeometry = UiBuilderFrameGeometry.None
): WearDeviceConfiguration {
  val diameter = wearScreenWidthDp(frame)
  return WearDeviceConfiguration(
    isScreenRound = true,
    screenWidthDp = diameter,
    screenHeightDp = diameter,
  )
}

/**
 * The platform the served catalog declares — see [UiBuilderCatalogPlatform].
 *
 * A composition local for the reason the other catalog-derived ones are: it is a statement about
 * the catalog rather than about a component, and the renderer needs it to answer a question only
 * the catalog can answer — *is this composition drawn with a watch library at all?* The default is
 * the empty word, which is "the host did not say", rather than [UiBuilderCatalogPlatform.DEFAULT],
 * because a host that says nothing is not the same as one that said "mobile".
 */
internal val LocalUiBuilderCatalogPlatform = staticCompositionLocalOf { "" }

/**
 * The frame the served catalog declares for its screens — see [UiBuilderFrameGeometry].
 *
 * A composition local rather than a renderer parameter, for the reason
 * [LocalUiBuilderCanvasAdapters] gives: it is the catalog's statement, the editor provides it once
 * for the canvas and every thumbnail, and a host that provides nothing gets the frame the document
 * itself names.
 */
internal val LocalUiBuilderFrameGeometry = staticCompositionLocalOf { UiBuilderFrameGeometry.None }

/**
 * The Wear screen at its whole extent, drawn as a long screenshot: the frame's width, the content's
 * height and a round cap at each end — the same stadium `@ScrollingPreview(modes =
 * [ScrollMode.LONG])` produces.
 *
 * Only the extent is drawn by hand; panes with a viewport use the port's real `ScreenScaffold`.
 * Content padding is the library's `ScreenScaffoldDefaults.contentPadding`. Rows are not
 * transformed: without a scroll position they are drawn at full content width, as at the centre of
 * the display.
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
  val unrolled = LocalUiBuilderUnrolled.current
  // What `ScreenScaffold` gives its list, from the library: it computes it off the watch that
  // `LocalWearDeviceConfiguration` names, which this surface sets from the design's own frame. It
  // was a table measured off the real scaffold and interpolated between sizes, and the "not a
  // clean fraction" it had to explain was the library rounding each side up to a whole dp.
  val padding = ScreenScaffoldDefaults.contentPadding
  // Wear's own `background`, from the Wear theme rather than the editor's: reading the editor
  // theme is the bug the widget container's default background comments, where the watch went
  // white in a light editor.
  val background = node.color("background", WearMaterialTheme.colorScheme.background)
  val timeText = node.string("timeText")
  val scrollIndicator = node.bool("scrollIndicator", true)
  // **The list's state, owned here and shared.** `ScreenScaffold` exists to hold one list: it hands
  // the list its `contentPadding`, its scroll indicator reads where that list is, and
  // `AppScaffold`'s clock scrolls away as it moves. None of that can happen if the scaffold and the
  // list each remember their own state, so the list reads this one through a local.
  val listState = rememberTransformingLazyColumnState()
  // The clock is the library's `TimeText`, told the design's time rather than the system's so a
  // render is deterministic. It used to be drawn here glyph by glyph, on the premise that Compose
  // Multiplatform cannot curve text; the port's `CurvedText` does, through Skia.
  val clock: @Composable () -> Unit = {
    if (timeText.isNotEmpty()) {
      val source = remember(timeText) { FixedTimeSource(timeText) }
      TimeText(timeSource = source) { time ->
        // The trailing space is a workaround for the port, not a design choice. Its Skia
        // `CurvedTextDelegate.doDraw` truncates when the text is more than a pixel wider than the
        // sweep the layout allotted it, and `TimeText` is allotted slightly less than its text,
        // so the last glyph was dropped: "10:10" drew "10:1". Padded, the glyph dropped is the
        // pad. An em space rather than a space: on a small round watch the shortfall is wider
        // than a space, and "10:10 " still drew "10:1". Above a font scale of 1 the shortfall
        // outgrows any one pad, which is the port's to fix (wear-compose-foundation's
        // `CurvedTextDelegate.truncate`). Remove the pad when the port stops truncating text
        // that fits.
        timeTextCurvedText("$time\u2003")
      }
    }
  }
  Box(
    modifier =
      modifier
        .width(width)
        // At least one screenful, so an empty scaffold is a watch face rather than a sliver.
        .heightIn(min = width)
        .clip(RoundedCornerShape(percent = 50))
        .background(background)
    // Nothing is drawn over the design: a fold guide would be editor chrome in the layer that must
    // stay pixel-comparable with renders. No scroll indicator either — the extent has no viewport,
    // and the real `ScrollMode.LONG` capture draws none.
  ) {
    if (unrolled) {
      CompositionLocalProvider(
        LocalWearScreenListState provides listState,
        LocalWearScreenContentPadding provides padding,
      ) {
        // The extent is deliberately not a viewport. Keep its ordinary inset so every item is
        // legible, including the first and last ones, while the frame pane below uses the real
        // lazy-list content-padding path.
        //
        // The edge button is the end of the scroll, so on the extent it follows the last row, in
        // the slot the scaffold keeps for it. Overlaid on the extent it covered the last rows of
        // every list long enough to need one.
        //
        // And it sits ON the bottom cap, as `ScreenScaffold` puts it: the button's own shape is the
        // curve, and its size already carries its floor above the edge. A list shorter than one
        // screenful leaves the gap above the button, not below it, which is what the weighted
        // spacer does — the column is at least a screenful, as the stadium is. The bottom inset
        // is the button's, so the list's bottom padding does not stack under it.
        Column(
          Modifier.fillMaxWidth()
            .heightIn(min = width)
            .padding(padding.withBottom(if (hasEdgeButton) 0.dp else null))
        ) {
          content(Modifier.fillMaxWidth())
          if (hasEdgeButton) {
            Spacer(Modifier.weight(1f))
            edgeButton(
              Modifier.align(Alignment.CenterHorizontally)
                .padding(top = ScreenScaffoldDefaults.EdgeButtonSpacing)
            )
          }
        }
      }
    } else {
      // **A viewport is drawn by the real `ScreenScaffold`**, which the Wear port publishes. This
      // pane used to rebuild it: an edge-button height table, a reveal on `canScrollForward`, a
      // hand-placed scroll indicator and a 4% gap under the button that nothing had measured —
      // and the gap was what put the button visibly off the bottom curve. The scaffold owns all
      // of that, including the part a rebuild gets wrong by construction: it extends the list's
      // bottom content padding by the button's MEASURED height, and grows the button in as the
      // list reaches its end. The padding it hands its content is what the list reads.
      //
      // Inside the real `AppScaffold`, which is what draws the clock and scrolls it away as the
      // list the `ScreenScaffold` registers with it moves.
      val body: @Composable BoxScope.(PaddingValues) -> Unit = { scaffoldPadding ->
        CompositionLocalProvider(
          LocalWearScreenListState provides listState,
          LocalWearScreenContentPadding provides scaffoldPadding,
        ) {
          content(Modifier.fillMaxSize())
        }
      }
      val indicator: (@Composable BoxScope.() -> Unit)? =
        if (scrollIndicator) {
          { ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd)) }
        } else {
          null
        }
      AppScaffold(timeText = clock, containerColor = background) {
        if (hasEdgeButton) {
          ScreenScaffold(
            scrollState = listState,
            edgeButton = { edgeButton(Modifier) },
            contentPadding = padding,
            scrollIndicator = indicator,
            content = body,
          )
        } else {
          ScreenScaffold(
            scrollState = listState,
            contentPadding = padding,
            scrollIndicator = indicator,
            content = body,
          )
        }
      }
    }
    // The extent has no `AppScaffold` — it has no viewport for one to scroll the clock away in — so
    // the clock is drawn over the top cap, on the circle a watch has there, and it stays put.
    if (unrolled) {
      Box(Modifier.align(Alignment.TopCenter).size(width)) { clock() }
    }
  }
}

/** The design's time, always: a render that read the system clock could not be diffed. */
private class FixedTimeSource(private val time: String) : TimeSource {
  @Composable override fun currentTime(): String = time
}

/** `wearos_small_round` and `wearos_xl_round` from `DeviceDimensions`, as the accepted range. */
private const val WEAR_SMALL_ROUND_DP = 192

private const val WEAR_XL_ROUND_DP = 240

/**
 * The drawing that frames a round screen: a stadium at the frame's width, the clock over it, and
 * the slot that hugs the bottom curve.
 *
 * A catalog names it in `wasm.canvas` for its screen root, which is what
 * [LocalUiBuilderCanvasAdapters] reads, so the same drawing serves a catalog whose screen root is
 * called something else — or is called nothing this build has heard of. The Wear catalog names it
 * like any other, which is why there is no `wear-m3/` id here at all.
 */
private const val ROUND_SCREEN_FRAME = "frame/round-screen"

/**
 * Wear's large corner, which its cards use: `MaterialTheme.shapes.large` is 26dp. A number rather
 * than the shape because [LocalUiBuilderCornerRadius] is one, read by mobile nodes as a radius.
 */
private const val WEAR_CARD_CORNER_RADIUS_DP = 26f

/**
 * Wear's default `ColorScheme`, role for role, for the mobile `MaterialTheme` a Wear design's few
 * mobile nodes draw through, so it cannot disagree with [WearCatalogTheme].
 */
private val WearDarkColorScheme =
  WearColorScheme().let { wear ->
    darkColorScheme(
      primary = wear.primary,
      onPrimary = wear.onPrimary,
      primaryContainer = wear.primaryContainer,
      onPrimaryContainer = wear.onPrimaryContainer,
      secondary = wear.secondary,
      onSecondary = wear.onSecondary,
      secondaryContainer = wear.secondaryContainer,
      onSecondaryContainer = wear.onSecondaryContainer,
      tertiary = wear.tertiary,
      onTertiary = wear.onTertiary,
      tertiaryContainer = wear.tertiaryContainer,
      onTertiaryContainer = wear.onTertiaryContainer,
      background = wear.background,
      onBackground = wear.onBackground,
      surface = wear.surfaceContainer,
      onSurface = wear.onSurface,
      onSurfaceVariant = wear.onSurfaceVariant,
      surfaceContainerLow = wear.surfaceContainerLow,
      surfaceContainer = wear.surfaceContainer,
      surfaceContainerHigh = wear.surfaceContainerHigh,
      surfaceContainerHighest = wear.surfaceContainerHigh,
      outline = wear.outline,
      outlineVariant = wear.outlineVariant,
      error = wear.error,
      onError = wear.onError,
      errorContainer = wear.errorContainer,
      onErrorContainer = wear.onErrorContainer,
    )
  }

/**
 * The port's own theme, for a catalog drawn with the port.
 *
 * Nothing installed it: every Wear component read `LocalColorScheme`'s default, which happens to be
 * the same `ColorScheme()`, so the screens looked right with no theme behind them. Installing it is
 * what makes the theme a thing the canvas sets rather than an accident it relies on.
 */
@Composable
private fun WearCatalogTheme(wear: Boolean, content: @Composable () -> Unit) {
  if (wear) WearMaterialTheme(content = content) else content()
}

// `WEAR_LIST_HEADER_HEIGHT_DP` (48f) and `WEAR_LIST_HEADER_SP` (14.5f) stood here. Both were
// measured off upstream renders to size a `Box`+`Text` replica of `ListHeader`, and both are gone
// because the canvas draws the real `ListHeader` now — see `WearCanvasComponents`. A number read
// off a screenshot that nothing in the build can re-check is the cost the old approach carried;
// deleting the numbers rather than leaving them unreferenced is what makes that cost actually go.

// `WEAR_EDGE_BUTTON_INSET` (0.04 of the diameter) stood here: a gap under the edge button, 7.7dp at
// 192. It was the one number in this stand-in nobody measured, and `ScreenScaffold` has no such
// gap — the button's own height includes its floor above the edge. It is gone for the reason the
// list-header numbers above went.

/** These padding values with [bottom] in place of their own; null keeps the bottom as it is. */
private fun PaddingValues.withBottom(bottom: Dp?): PaddingValues =
  PaddingValues(
    start = calculateStartPadding(LayoutDirection.Ltr),
    top = calculateTopPadding(),
    end = calculateEndPadding(LayoutDirection.Ltr),
    bottom = bottom ?: calculateBottomPadding(),
  )

/**
 * Compose UI counterpart of the stable Glance Wear widget host frame.
 *
 * The outer canvas is the host's padding around the container's content area; the squircle radius
 * and the default fill are host chrome rather than authored widget content, and [brushes] is the
 * widget's own `WearWidgetBrush` chain drawn into that same rounded rect.
 */
@Composable
internal fun WearWidgetContainerScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  spec: WearWidgetHostSpec,
  brushes: @Composable (Modifier) -> Unit,
  hasBrushes: Boolean,
  content: @Composable () -> Unit,
) {
  // The frame is the viewed host's published spec and nothing else. A design saved while padding
  // and radius were authorable still carries them, but only the predefined host shapes are
  // supported, so those values are ignored rather than drawn as a frame no launcher produces.
  val horizontalPadding = spec.horizontalPaddingDp
  val verticalPadding = spec.verticalPaddingDp
  val cornerRadius = spec.cornerRadiusDp
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
          (spec.contentWidthDp + 2f * horizontalPadding).dp,
          (spec.contentHeightDp + 2f * verticalPadding).dp,
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
 * Where a `Box` child sits, from its chain and then from the property that used to say it.
 *
 * The modifier is the authored form now; `alignment` as a *property* is what documents written
 * before the vocabulary existed carry, and they keep rendering. Modifier first, so a design that
 * has both says what its chain says — the chain is the thing an author can see and reorder.
 */
private fun UiBuilderNode.boxAlignment(): String? =
  modifierPlans().filterIsInstance<UiBuilderModifierPlan.Align>().firstOrNull()?.alignment
    // A spelling neither form resolves is no alignment at all, so the box's own applies.
    ?: string("alignment").takeIf(::isResolvableAlignment)

/**
 * Where a `layout/box` puts a child that says nothing about it: `Box(contentAlignment = …)`.
 *
 * Unset, or a spelling that does not resolve, is Compose's own default, `TopStart` — where the box
 * has always put an unaligned child — so a design that never touched the property draws as before.
 */
private fun UiBuilderNode.boxContentAlignment(): String =
  string("contentAlignment").takeIf(::isResolvableAlignment) ?: "topStart"

/** What a `Row` or `Column` child asks of its cross axis, or null for the parent's own default. */
private fun UiBuilderNode.crossAxisAlignment(): String? =
  modifierPlans().firstNotNullOfOrNull { plan ->
    when (plan) {
      is UiBuilderModifierPlan.AlignHorizontal -> plan.alignment
      is UiBuilderModifierPlan.AlignVertical -> plan.alignment
      else -> null
    }
  }

/** The share of the main axis this child claims, from its chain and then from the old property. */
private fun UiBuilderNode.layoutWeight(): UiBuilderModifierPlan.Weight? =
  modifierPlans().filterIsInstance<UiBuilderModifierPlan.Weight>().firstOrNull()
    ?: float("weight").takeIf { it > 0f }?.let { UiBuilderModifierPlan.Weight(it, null) }

/** The `collapsiblePriority` a collapsible column or row reads off this child, or null for none. */
private fun UiBuilderNode.collapsiblePriority(): Float? =
  modifiers
    .mapNotNull { it as? JsonObject }
    .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "collapsiblePriority" }
    ?.let { (it["priority"] as? JsonPrimitive)?.floatOrNull ?: 0f }

private fun UiBuilderNode.modifierPlans(): List<UiBuilderModifierPlan> = modifiers.mapNotNull {
  (it as? JsonObject)?.let(::uiBuilderModifier)
}

private fun horizontalAlignmentFor(value: String): Alignment.Horizontal =
  when (value) {
    "centerHorizontally" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
  }

private fun verticalAlignmentFor(value: String): Alignment.Vertical =
  when (value) {
    "centerVertically" -> Alignment.CenterVertically
    "bottom" -> Alignment.Bottom
    else -> Alignment.Top
  }

/** Later actions observe earlier writes even when a host applies callbacks after dispatch. */
internal fun uiBuilderStateWrites(
  actions: JsonArray,
  state: Map<String, String?>,
): List<Pair<String, String?>> = canvasStateWrites(actions, state)

/** Authoring a declaration resets that variable's preview value; unrelated interactions survive. */
internal fun reconcilePreviewState(
  state: MutableMap<String, String?>,
  before: JsonObject,
  after: JsonObject,
) {
  reconcileCanvasState(state, before, after)
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
): Pair<String, String?>? = canvasStateWrite(action, state)

/**
 * An integer property, read from state where the document says so.
 *
 * State is held in its string form here, so `2` arrives as `"2"`. A variable a handler drove
 * through a fractional step reads as `"2.0"`, which is the same index; anything else is not an
 * index at all and leaves the row on its fallback rather than throwing the preview away.
 */
private fun UiBuilderNode.resolvedInteger(
  name: String,
  state: Map<String, String?>,
  fallback: Int = 0,
): Int {
  val value = obj(name)
  if (value.wrapperType() != "state") {
    return (value["value"] as? JsonPrimitive)?.intOrNull ?: fallback
  }
  val held = state[(value["variable"] as? JsonPrimitive)?.contentOrNull] ?: return fallback
  return held.toIntOrNull() ?: held.toDoubleOrNull()?.toInt() ?: fallback
}

private fun UiBuilderNode.resolvedBool(name: String, state: Map<String, String?>): Boolean {
  val value = obj(name)
  return if (value.wrapperType() == "stateEquals") {
    uiBuilderStateEquals(
      state[(value["variable"] as? JsonPrimitive)?.contentOrNull],
      value["value"],
    )
  } else (value["value"] as? JsonPrimitive)?.booleanOrNull ?: false
}

/**
 * Whether a `stateEquals` comparison holds, decided as the Compose export decides it: an unquoted
 * numeric operand compares numerically (`1 == 1.0`), a quoted one as text.
 */
internal fun uiBuilderStateEquals(held: String?, operand: JsonElement?): Boolean {
  return canvasStateEquals(held, operand)
}

internal fun UiBuilderNode.obj(name: String): JsonObject =
  properties[name]?.objectOrEmpty() ?: JsonObject(emptyMap())

private fun UiBuilderNode.hasModifier(type: String): Boolean = modifiers.any {
  it.objectOrEmpty().optionalString("type") == type
}

/**
 * The scalar a property's value wrapper holds, or null when it holds an object, an array or a null.
 *
 * Read with a safe cast rather than `jsonPrimitive`. A property can hold a binding whose key never
 * resolved, or a wrapper an editor built wrong, and `#484`'s rule applies to the shape of a value
 * as much as to its content: one node this canvas cannot resolve draws its own default, it does not
 * fail the frame. The export gate refuses the same document by name, in a panel this canvas has to
 * stay alive to show.
 */
private fun UiBuilderNode.valueScalar(name: String): JsonPrimitive? =
  obj(name)["value"] as? JsonPrimitive

internal fun UiBuilderNode.string(name: String): String = valueScalar(name)?.contentOrNull.orEmpty()

internal fun UiBuilderNode.float(name: String, fallback: Float = 0f): Float =
  valueScalar(name)?.floatOrNull ?: fallback

/** A dimension the document actually carries, or null — which is not the same as zero. */
internal fun UiBuilderNode.dimension(name: String): Dp? = valueScalar(name)?.floatOrNull?.dp

private fun UiBuilderNode.integer(name: String, fallback: Int = 0): Int =
  valueScalar(name)?.intOrNull ?: fallback

/**
 * A count of lines, which is a count: `Text` throws on a `maxLines` below one, and the catalog
 * declares an unbounded integer, so a document written through the protocol can carry a zero or a
 * negative. Clamped here rather than refused, because a header that draws one line is a design
 * somebody can see and fix; a composition that throws is a blank canvas with no way back.
 */
private fun UiBuilderNode.lineCount(name: String): Int =
  integer(name, Int.MAX_VALUE).coerceAtLeast(1)

internal fun UiBuilderNode.bool(name: String, fallback: Boolean = false): Boolean =
  valueScalar(name)?.booleanOrNull ?: fallback

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
    "secondary" -> MaterialTheme.colorScheme.secondary
    "onSecondary" -> MaterialTheme.colorScheme.onSecondary
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
    "secondary",
    "onSecondary",
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

/**
 * A property's colour, through the same table a modifier's goes through.
 *
 * An unknown token draws [fallback] rather than throwing. It used to be `error("unsupported color
 * token …")`, which is the asset failure of #484 in another property: one node's value that this
 * canvas could not resolve failed the whole frame. The reducers refuse such a value at commit now,
 * and what still arrives — a design committed before the rule — is drawn in the component's own
 * default, which is what an unset colour draws anyway.
 */
@Composable
internal fun UiBuilderNode.color(name: String, fallback: Color): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  if (value.isEmpty()) return fallback
  return colorTokenOrNull(value) ?: fallback
}

/** [wearColor] when the design sets [name]; otherwise unspecified, so the component's default. */
@Composable
private fun UiBuilderNode.authoredWearColor(name: String): Color =
  if (string(name).isEmpty()) Color.Unspecified else wearColor(name)

/** A Wear property's colour: a literal as itself, a role through Wear's own scheme. */
@Composable
private fun UiBuilderNode.wearColor(name: String): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  return wearThemeColor(value)
}

/**
 * The scaffold's theme: Wear's scheme, and the Material 3 one a few foundation nodes resolve tokens
 * through, with the roles the design overrides replaced — the same `copy` the generated screen
 * wraps itself in. See [WearScreenTheme]. A role named as a token reads the stock scheme around it,
 * as the generated `MaterialTheme.colorScheme.copy(primary = MaterialTheme.colorScheme.…)` does.
 */
@Composable
private fun WearScreenThemed(node: UiBuilderNode, content: @Composable () -> Unit) {
  val overrides =
    WearScreenTheme.ROLES.mapNotNull { role ->
        node.string(WearScreenTheme.property(role)).takeIf(String::isNotEmpty)?.let { role to it }
      }
      .toMap()
  if (overrides.isEmpty()) {
    content()
    return
  }
  val resolved =
    overrides
      .mapNotNull { (name, value) ->
        val color =
          if (value.startsWith("#")) Color(parseArgb(value))
          else wearThemeColor(value).takeIf { it != Color.Unspecified }
        color?.let { name to it }
      }
      .toMap()
  fun role(name: String): Color? = resolved[name]
  val wear = WearMaterialTheme.colorScheme
  val wearScheme =
    wear.copy(
      primary = role("primary") ?: wear.primary,
      onPrimary = role("onPrimary") ?: wear.onPrimary,
      primaryContainer = role("primaryContainer") ?: wear.primaryContainer,
      onPrimaryContainer = role("onPrimaryContainer") ?: wear.onPrimaryContainer,
      secondary = role("secondary") ?: wear.secondary,
      onSecondary = role("onSecondary") ?: wear.onSecondary,
      secondaryContainer = role("secondaryContainer") ?: wear.secondaryContainer,
      onSecondaryContainer = role("onSecondaryContainer") ?: wear.onSecondaryContainer,
      tertiary = role("tertiary") ?: wear.tertiary,
      onTertiary = role("onTertiary") ?: wear.onTertiary,
      tertiaryContainer = role("tertiaryContainer") ?: wear.tertiaryContainer,
      onTertiaryContainer = role("onTertiaryContainer") ?: wear.onTertiaryContainer,
      surfaceContainerLow = role("surfaceContainerLow") ?: wear.surfaceContainerLow,
      surfaceContainer = role("surfaceContainer") ?: wear.surfaceContainer,
      surfaceContainerHigh = role("surfaceContainerHigh") ?: wear.surfaceContainerHigh,
      onSurface = role("onSurface") ?: wear.onSurface,
      onSurfaceVariant = role("onSurfaceVariant") ?: wear.onSurfaceVariant,
      outline = role("outline") ?: wear.outline,
      outlineVariant = role("outlineVariant") ?: wear.outlineVariant,
      background = role("background") ?: wear.background,
      onBackground = role("onBackground") ?: wear.onBackground,
      error = role("error") ?: wear.error,
      onError = role("onError") ?: wear.onError,
    )
  val mobile = MaterialTheme.colorScheme
  val mobileScheme =
    mobile.copy(
      primary = role("primary") ?: mobile.primary,
      onPrimary = role("onPrimary") ?: mobile.onPrimary,
      primaryContainer = role("primaryContainer") ?: mobile.primaryContainer,
      onPrimaryContainer = role("onPrimaryContainer") ?: mobile.onPrimaryContainer,
      secondary = role("secondary") ?: mobile.secondary,
      onSecondary = role("onSecondary") ?: mobile.onSecondary,
      secondaryContainer = role("secondaryContainer") ?: mobile.secondaryContainer,
      onSecondaryContainer = role("onSecondaryContainer") ?: mobile.onSecondaryContainer,
      tertiary = role("tertiary") ?: mobile.tertiary,
      onTertiary = role("onTertiary") ?: mobile.onTertiary,
      tertiaryContainer = role("tertiaryContainer") ?: mobile.tertiaryContainer,
      onTertiaryContainer = role("onTertiaryContainer") ?: mobile.onTertiaryContainer,
      surfaceContainerLow = role("surfaceContainerLow") ?: mobile.surfaceContainerLow,
      surfaceContainer = role("surfaceContainer") ?: mobile.surfaceContainer,
      surfaceContainerHigh = role("surfaceContainerHigh") ?: mobile.surfaceContainerHigh,
      onSurface = role("onSurface") ?: mobile.onSurface,
      onSurfaceVariant = role("onSurfaceVariant") ?: mobile.onSurfaceVariant,
      outline = role("outline") ?: mobile.outline,
      outlineVariant = role("outlineVariant") ?: mobile.outlineVariant,
      background = role("background") ?: mobile.background,
      onBackground = role("onBackground") ?: mobile.onBackground,
      error = role("error") ?: mobile.error,
      onError = role("onError") ?: mobile.onError,
    )
  MaterialTheme(colorScheme = mobileScheme) {
    WearMaterialTheme(colorScheme = wearScheme, content = content)
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
 * How this Column distributes its children, matching the exporter: aligned arrangements compose
 * with spacing via `spacedBy(space, alignment)` (so the default is unchanged), while `space*`
 * arrangements ignore the gap because Compose has no form for both.
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

@Composable
private fun BuilderIcon(node: UiBuilderNode, modifier: Modifier) {
  val key = node.string("iconKey")
  checkNotNull(googleMaterialIcon(key)) { "unsupported Google Material icon '$key' on ${node.id}" }
  val description = node.string("contentDescription")
  val tint = node.color("color", LocalContentColor.current)
  val sized = modifier.size(node.float("sizeDp", 24f).dp)
  // In the browser an icon's vector arrives with its data shard; until then the icon holds its
  // size and draws nothing, so the layout around it does not move when it appears.
  val vector =
    rememberGoogleMaterialIconVector(key)
      ?: run {
        Box(sized)
        return
      }
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

internal fun JsonObject.paddingValues() =
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

internal fun parseArgb(value: String): Long =
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
 * A dialog drawn inline with `AlertDialog`'s surface, spacing and button row.
 *
 * Not a real `Dialog`: that is a separate window, outside the canvas and not hit-testable, and
 * `AlertDialog` requires an `onDismissRequest` the document's action vocabulary cannot express. The
 * catalog's `wasm.notes` and the export's compatibility helper say the same.
 *
 * The geometry is Material's: a 28dp corner (not the theme radius), `surfaceContainerHigh`, 6dp
 * tonal elevation, 24dp padding, 280..560dp wide, end-aligned buttons with dismiss before confirm,
 * and a centred icon centring the title.
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
  Surface(
    modifier = modifier.widthIn(min = DIALOG_MINIMUM_WIDTH_DP.dp, max = DIALOG_MAXIMUM_WIDTH_DP.dp),
    shape = node.dimension("shapeDp")?.let(::RoundedCornerShape) ?: AlertDialogDefaults.shape,
    color = node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHigh),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = node.dimension("tonalElevationDp") ?: AlertDialogDefaults.TonalElevation,
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
 * Material's `DatePicker` with today's date taken out: selection and displayed month come from the
 * document's `selectedDate`, so renders do not change with the clock. `input` mode is
 * `DisplayMode.Input`.
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
private fun BuilderTimePicker(
  node: UiBuilderNode,
  modifier: Modifier,
  layoutType: TimePickerLayoutType?,
) {
  val hour = node.integer("hour", DEFAULT_PICKED_HOUR).coerceIn(0, 23)
  val minute = node.integer("minute", DEFAULT_PICKED_MINUTE).coerceIn(0, 59)
  val is24Hour = node.bool("is24Hour", true)
  val state =
    key(hour, minute, is24Hour) {
      rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24Hour)
    }
  if (node.string("mode") == "input") TimeInput(state = state, modifier = modifier)
  else if (layoutType != null)
    TimePicker(state = state, modifier = modifier, layoutType = layoutType)
  else TimePicker(state = state, modifier = modifier)
}

/**
 * The clock's layout for the design's own window, or null to leave it to Material.
 *
 * Left to Material, `TimePicker` reads the *host* window: a phone design edited in a laptop browser
 * drew the landscape clock, digits beside the dial, which is not what that phone shows — and in a
 * palette thumbnail, laid out in a portrait frame, the dial overlapped the minutes. The document's
 * environment names the window it is designed for; a portrait one gets the vertical clock.
 */
private fun UiBuilderDocument.timePickerLayoutType(): TimePickerLayoutType? {
  val width = environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null
  val height = environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return null
  return if (width > height) TimePickerLayoutType.Horizontal else TimePickerLayoutType.Vertical
}

/**
 * `YYYY-MM-DD` as UTC epoch milliseconds, or null when it is not a date, using the days-from-civil
 * algorithm (this module has no date library).
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

/**
 * The Material 3 icon button the design's `variant` names — `IconButton`, `FilledIconButton`,
 * `FilledTonalIconButton` or `OutlinedIconButton`, which are four functions, not a style argument.
 * The catalog has always offered the four and the canvas drew every one as the standard button.
 */
@Composable
private fun BuilderIconButton(
  variant: String,
  onClick: () -> Unit,
  modifier: Modifier,
  enabled: Boolean,
  content: @Composable () -> Unit,
) {
  when (variant) {
    "filled" -> FilledIconButton(onClick, modifier, enabled, content = content)
    "tonal" -> FilledTonalIconButton(onClick, modifier, enabled, content = content)
    "outlined" -> OutlinedIconButton(onClick, modifier, enabled, content = content)
    else -> IconButton(onClick, modifier, enabled, content = content)
  }
}

/**
 * `AlertDialogDefaults`' shape and tonal elevation, as numbers, for the code exporter: generated
 * code writes the value out. The canvas reads `AlertDialogDefaults` itself.
 */
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
