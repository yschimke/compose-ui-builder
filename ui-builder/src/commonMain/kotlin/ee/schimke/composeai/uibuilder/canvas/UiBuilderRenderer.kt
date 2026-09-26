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
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
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
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasDocumentHost
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasDocumentScope
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasMode
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasRenderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.LocalCanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.RenderCanvasNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderModifierPlan
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
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
import ee.schimke.composeai.uibuilder.renderer.sdk.uiBuilderModifier
import ee.schimke.composeai.uibuilder.withFontFamily
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
 * Component id to canvas adapter id, as the catalog names them.
 *
 * The dispatch below is keyed on this rather than on the component id —
 * `UI_BUILDER_CATALOG_CONTRACT.md` item 17, "the adapter registry is the existing `when`, keyed by
 * adapter id instead of component id". Provided by the editor from the catalog, exactly like
 * [LocalUiBuilderNativeOnly].
 *
 * Empty by default and empty in practice today, which makes this change a no-op on every current
 * catalog: with no entry, a component keys on its own id and draws what it drew before. What it
 * buys is that a catalog CAN now say which adapter draws its component, so a new component needs no
 * case written here — which is the coupling that made this repository hold a copy of every
 * catalog's inventory.
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
 * Every component id the catalog offers, so the `else` branch can tell two different things apart.
 *
 * Falling off the `when` means one of two things, and until now both drew the same error container
 * reading "Unsupported component". They are not the same:
 * - **the catalog does not offer this id** — a design pinned to another catalog, a stale import, a
 *   typo. Something IS wrong, and the error container is right.
 * - **the catalog offers it and this canvas has no case for it.** Nothing is wrong. The component
 *   is on the palette, it exports, and m3-catalog renders every one of them — the browser just
 *   cannot draw it. That is [NativeOnlyPlaceholder]'s situation exactly, and it is what the
 *   published shelf already *says*: `PublishedUiBuilderCatalog.wasm()` writes the note "drawn on
 *   the canvas as a named placeholder" for every component with no adapter, and then the canvas
 *   drew an error instead. This is the renderer keeping that promise (m3-catalog#324).
 *
 * Membership, deliberately, rather than the capability's own `adapterStatus`. That field would be
 * the direct statement of "this canvas can draw it" and it is not trustworthy: the frozen
 * `m3-catalog` capabilities report `planned` for `layout/box`, `m3/button`, `m3/card`,
 * `m3/icon-button`, `m3/search-bar`, `m3/search-input-field`, `m3/snackbar-host` and
 * `m3/horizontal-floating-toolbar`, all eight of which the `when` above draws. Reaching the `else`
 * is the renderer's own first-hand answer to the same question and cannot drift from it.
 */
internal val LocalUiBuilderCatalogComponentIds =
  staticCompositionLocalOf<Set<String>> { emptySet() }

/** Destination selected by a `navigatePage` action in the live canvas. */
internal val LocalUiBuilderNavigator = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * Which host container a Wear widget design is drawn inside.
 *
 * A composition local rather than a document property, because the shape is not the design's: the
 * launcher draws the frame from `WearWidgetParams`, and the same widget appears in every shape the
 * platform ships. Switching it asks "what does this look like in the host's other frame", which is
 * a view over the design rather than an edit to it — nothing here writes to the document, and a
 * design saved while the rectangular frame is showing reopens exactly as it was.
 *
 * Defaults to the squircle, so every other host of this surface — the thumbnails, the JVM render
 * port, the previews — draws the frame it always has.
 */
internal val LocalWearWidgetHostShape = staticCompositionLocalOf { WearWidgetHostShape.Default }

/**
 * Remote Compose documents a host has fetched for the `documentUrl` of an embedded document node.
 *
 * A composition local rather than a renderer parameter, for the reason [LocalUiBuilderNativeOnly]
 * is one: every surface that draws a document — the canvas, the thumbnails, the JVM render port,
 * the previews — would otherwise have to thread a parameter it has no opinion about.
 *
 * A **lookup**, not a fetch. Loading bytes is suspending, size-limited and cancellable, and none of
 * those belong inside a composable that draws: the host resolves a URL once, decides what an
 * over-large or unreachable one means, and answers here with the document or the failure. `null` is
 * the third answer and the common one — *not resolved yet*, which is what a first frame sees and
 * what a host with no resolver at all always answers. The node draws its own waiting state for it
 * rather than an error, because a design pointing at a URL nobody has fetched is not a broken
 * design.
 */
public val LocalRemoteComposeDocuments:
  androidx.compose.runtime.ProvidableCompositionLocal<(String) -> Result<RcDocument>?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * Remote Compose documents a host has **captured** from a design's own inline content, by node id.
 *
 * The sibling of [LocalRemoteComposeDocuments] and deliberately a second local rather than a
 * widening of it. That one is keyed by URL because an embedded document names a URL and two nodes
 * pointing at the same one are the same bytes; this one is keyed by *node*, because an inline
 * subtree is not addressed by anything — it is the design, and what identifies it is where it sits.
 *
 * A **lookup**, not a capture, for the same reason: producing these bytes means compiling the
 * generated `@RemoteComposable` body and running `captureSingleRemoteDocument` on an Android
 * daemon, which is a network round trip to `ServeUiBuilderInlineCapture` and cannot happen inside a
 * composable that draws. The host captures once, decides what a failed capture means, and answers
 * here.
 *
 * `null` — nothing captured for this node — is the common answer and the honest one: it is what a
 * host with no capture lane always says, and what every node says before anyone has asked for a
 * capture. The node then draws the Compose stand-ins in their marked frame, exactly as it always
 * has. A capture is an *upgrade* from describing the content to playing it, never a precondition
 * for drawing the design.
 */
public val LocalRemoteComposeCaptures:
  androidx.compose.runtime.ProvidableCompositionLocal<(String) -> Result<RcDocument>?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * Draw the design at its whole extent rather than at its frame: lists unrolled, scrolling dropped.
 *
 * Compose refuses to measure a scrollable against an unbounded height — a `LazyColumn` under
 * `wrapContentSize(unbounded = true)` fails with "measured with an infinity maximum height
 * constraints" rather than growing — so a pane that draws a design at content height cannot be the
 * design's own composition. It is a **proxy**: `layout/lazy-column` becomes a `Column`,
 * `layout/lazy-grid` a non-lazy grid, and a `verticalScroll` modifier is dropped, so the content
 * that would be behind a scroll position is laid out where it would sit if the screen were tall
 * enough to hold it.
 *
 * What that costs is the laziness itself, and it is worth stating rather than discovering: nothing
 * here recycles, `fillParentMaxHeight` measures against the extent instead of the viewport, and a
 * sticky header does not stick. The same trade [WearScreenScaffold] already makes for the Wear
 * stadium, for the same reason and with the same honesty about it.
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
 * The density the design is drawn at: the one its environment names, not the host's.
 *
 * Public to the module rather than inlined into [UiBuilderSurface], because the editor's canvas has
 * to ask the same question. The canvas sizes the frame in *pixels* and this renderer then reads
 * those pixels as dp at this density, so the frame is the size the design was authored for only
 * while the two agree about what the density is. They used to each decide separately — the canvas
 * simply did not ask, and used the host's — and a design whose density was not the browser's was
 * handed a frame scaled by the ratio between them: a 240dp watch at 2.0 in a browser at 1.0 became
 * 120dp of room, which is not enough for a 216dp widget. See `PinnedDesignCanvas`.
 *
 * [fallback] is the host's own density, which is what an environment that names neither field
 * means.
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
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
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
                // A screen is taller than its frame by design — the stadium IS the scroll extent —
                // so it is pinned to the top and centred across, the way a long screenshot reads.
                entry.adapterId == ROUND_SCREEN_FRAME -> Modifier.align(Alignment.TopCenter)
                else -> Modifier
              }
            },
          ) { entry, rootModifier ->
            RenderNode(document = document, entry = entry, host = this, modifier = rootModifier)
          }
          if (editorOverlay) {
            // Every box the selected node drew, not one: a node id is what the editor selects, and
            // once a node can draw more than once the outline follows all of them rather than
            // whichever copy the map happened to answer with.
            val selected = overlayBounds.filterKeys { it.nodeId == selectedNodeId }.values.toList()
            Canvas(
              // The design's box, not the incoming constraints: a surface measured against an
              // unbounded axis — the pop-out beside the device frame — wraps its content, and a
              // `fillMaxSize` overlay there came out zero along that axis and caught no click.
              Modifier.matchParentSize()
                .then(
                  if (!overlayTakesInput) Modifier
                  else
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
          slot("content").forEach { child(it, Modifier.fillMaxSize()) }
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
        WearScreenScaffold(
          node = node,
          modifier = measured,
          screenWidthDp = document.wearScreenWidthDp(LocalUiBuilderFrameGeometry.current),
          edgeButton = { next -> slot("edgeButton").forEach { child(it, next) } },
          hasEdgeButton = slot("edgeButton").isNotEmpty(),
        ) { next ->
          slot("content").forEach { child(it, next) }
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
        )
      "wear-m3/page-indicator" ->
        WearCanvasPageIndicator(
          vertical = node.string("variant") == "vertical",
          modifier = measured,
        )
      "wear-m3/edge-button" ->
        WearCanvasEdgeButton(
          size = node.string("size"),
          enabled = node.bool("enabled", true),
          modifier = measured,
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
      // Wear's own `Text`, out of the port the canvas links: Wear Compose publishes its own text
      // component, and the canvas has no reason to call the mobile one.
      //
      // The difference is not the name. This branch used to read four properties (`text`, `color`,
      // `style`, `maxLines`) where the catalog declares sixteen, so every `fontSizeSp`,
      // `lineHeightSp`, `softWrap` and `overflow` a design set on a Wear text node was inert on the
      // canvas while the mobile branch beside it honoured all of them. Wear's `Text` takes the same
      // argument list, so this now reads what the mobile one reads.
      //
      // The style comes from `wearTextStyle`, which resolves the role names against Wear's own
      // typography — Wear's type scale, not Material 3's — and is what makes the sizes right when a
      // design sets none.
      "wear-m3/text" ->
        WearText(
          node.string("text"),
          measured,
          color = node.color("color", Color.Unspecified),
          style = wearTextStyle(node.string("style")),
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
          onTextLayout = { host.recordTextLayout(path, it) },
        )
      "wear-m3/card" ->
        WearCanvasCard(node.string("variant"), measured) {
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
          // At the extent the list is a Column: no viewport, no row transformation, and the rows
          // are
          // the list's unscaled layout — which is exactly the `ScrollMode.LONG` reference, whose
          // stitch turns the transformation off. The real lazy layout cannot be measured against an
          // unbounded height: it reports infinity, and the canvas fails outright with
          // `Size(w x 2147483647) is out of range` — the same wall `layout/scaffold` and
          // `layout/lazy-column` each already draw around. Without this the whole editor is blank
          // for
          // a Wear screen, because the extent's height is the content's.
          Column(
            modifier = measured,
            verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp", 4f).dp),
          ) {
            items.forEach { child(it, Modifier) }
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
      "layout/supporting-pane-scaffold" ->
        AdaptiveSupportingPaneScaffold(
          node,
          measured,
          { next -> slot("mainPane").forEach { child(it, next) } },
          { next -> slot("supportingPane").forEach { child(it, next) } },
        )
      // The vocabulary switch. Everything below is `@RemoteComposable` in the code this design
      // generates, and this canvas draws it with the ordinary Compose stand-ins the `remote-m3`
      // catalog has always used for the same components — a `RemoteColumn` is drawn by a `Column`.
      //
      // Framed rather than drawn flush, and the frame is the honest part. The browser has no Remote
      // Compose writer, so these are the shapes the generated body *describes* rather than the
      // pixels
      // a player produces; the frame is what stops an author reading them as the latter. The rule
      // this keeps is `wear-m3`'s — never fake a component so it runs in Wasm — applied to a whole
      // subtree rather than to one component.
      //
      // Once a host has captured the subtree ([LocalRemoteComposeCaptures]) none of that applies:
      // there are real bytes, and they are played by the same `RcComposePlayer` that draws the
      // embedded document beside it. The frame stays — the boundary is still a fact about the
      // design
      // — but it stops standing in for the content, which is the difference between marking a scope
      // and approximating it.
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
        // `Scaffold` is a `SubcomposeLayout`, and one measured against an unbounded height does not
        // grow — it fails outright with `Size(w x 2147483647) is out of range`. So the extent draws
        // the same three parts as a plain Column: the bar, then the content under it.
        //
        // The snackbar host is dropped rather than stacked below the content. It is a transient
        // overlay that floats above the *viewport*, and a strip three screens long has no viewport
        // to
        // float above — drawing it at the bottom of the extent would put it where nobody will ever
        // see it and add a band of empty space where the design has none. The frame pane beside the
        // extent is a real `Scaffold`, so that is where a snackbar keeps its meaning.
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
      // A row that wraps. The one layout primitive in this catalog whose response to a narrow
      // window needs no breakpoint, no `if` and no second design: children that do not fit the line
      // go on the next one, which is what a Material chip group has always done and what
      // `layout/row` cannot do — at 411 dp a row of four filter chips squeezes each one to a letter
      // per line rather than wrapping (docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md, gap 3).
      //
      // Every property it reads is one `layout/row` and `layout/lazy-grid` already declare, read by
      // the same two helpers: the main axis is a row's
      // `horizontalArrangement`/`horizontalSpacingDp`
      // and the cross axis — the gap BETWEEN lines — is a column's pair. Nothing new to learn, and
      // nothing new for the wire to carry.
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
          host.updateSemanticAction(node.id) { it.copy(scrollBy = lazyState::dispatchRawDelta) }
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
          host.updateSemanticAction(node.id) { it.copy(scrollBy = lazyState::dispatchRawDelta) }
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
          slot("content").forEach { child(it, Modifier) }
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
          onTextLayout = { host.recordTextLayout(path, it) },
        )
      "asset/image" -> AssetImage(document, node, measured)
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
      // On the palette, exportable, rendered by its own catalog — and this canvas has no case for
      // it. The shelf already promises exactly this picture; see
      // [LocalUiBuilderCatalogComponentIds]
      // for why membership answers the question and `adapterStatus` does not.
      //
      // Below every specific case, so it can only ever catch an id that would otherwise have drawn
      // an error: nothing this renderer knows how to draw can be demoted to a placeholder by it.
      // Both of these ask about the COMPONENT id, not the adapter id above, which is why they are
      // an
      // `if` inside the `else` rather than two more `when` branches. Keying the membership test on
      // the adapter would mean a component whose catalog names an adapter this build lacks — the
      // one
      // case item 17 exists to handle — comparing an adapter id against a set of component ids,
      // missing, and drawing an ERROR where the contract says it must draw a placeholder.
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
private fun UiBuilderDocument.wearScreenWidthDp(frame: UiBuilderFrameGeometry): Int =
  frame.diameterFor(environment["widthDp"]?.jsonPrimitive?.intOrNull)

/**
 * The watch the Wear components in this design are laid out against.
 *
 * ## Why the host has to say, and what happened when it did not
 *
 * `androidx.wear.compose` reads the device out of Android's `Configuration`, which does not exist
 * off Android. The CMP port replaces that with one value, `LocalWearDeviceConfiguration`, and each
 * platform answers it for itself: the JVM takes the 192dp reference watch, and **the browser
 * reports its own viewport** — `window.innerWidth` / `window.innerHeight` — because a viewport is
 * the closest thing a browser has to `Configuration.screenWidthDp`.
 *
 * So on the Wasm canvas a Wear component was laid out against the editor window. Measured on the
 * real `ScreenScaffold`, whose content padding is 5.2% of the screen's width and 10% of its height:
 * 10dp x 20dp at 192x192, and **75dp x 90dp at 1440x900**. The same design drawn on the desktop
 * canvas — same code, JVM default — got the watch. Two lanes of one canvas disagreed about what a
 * watch is, and the Wasm one was the size of a browser.
 *
 * What that reached is every component that branches on the screen: `DatePicker` and `TimePicker`
 * (their `isLargeScreen` typography and 46dp options against the small screen's 36dp), `EdgeButton`
 * (its arc is computed from the screen width), `SwipeToReveal`, `PagerScaffold`, the progress
 * indicator's and scroll indicator's stroke widths. The Wear screen scaffold did not show it,
 * because the canvas draws that one itself with a measured padding table — which is exactly why
 * this went unnoticed: the one component big enough to be obvious was the one not asking.
 *
 * ## What it answers
 *
 * The frame the document names, by [wearScreenWidthDp]'s rule, so the stand-in scaffold and the
 * components inside it cannot disagree about the screen they are on. Both axes are the diameter: a
 * round watch's screen is as tall as it is wide, and the port reads `screenHeightDp` for its
 * vertical content padding (10%) and its list's minimum vertical content padding (23%), which
 * `ScreenScaffoldDefaults.contentPadding` computes from this configuration.
 *
 * `isScreenRound` is true because a Wear design in this builder is drawn on a round watch — the
 * scaffold stand-in is a stadium for that reason — and the remaining fields stay at the port's
 * defaults, which are the deterministic ones: a 24-hour clock whatever the browser's locale says,
 * and the left wrist. A canvas whose picture moved with the host's locale could not be diffed.
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
 * Robolectric and reports what the scaffold hands its list. The content padding is now the
 * library's own `ScreenScaffoldDefaults.contentPadding`, which that probe was measuring.
 *
 * ## Only the extent is drawn by hand
 *
 * A pane with a viewport — the frame pane and every device pane — is the Wear port's real
 * `ScreenScaffold`, edge button, scroll indicator and all. The extent cannot be: it has no viewport
 * for a scaffold to reserve space in or reveal a button against, so that path alone is drawn here,
 * with the button on the bottom cap where the scaffold puts it at the end of the scroll.
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
        // space. Remove the pad when the port stops truncating text that fits.
        timeTextCurvedText("$time ")
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
 * The Material 3 scheme a Wear design's mobile-side pieces draw through, taken from Wear's own.
 *
 * The canvas installs one mobile `MaterialTheme` for its own chrome and for the few mobile nodes a
 * Wear design can hold (an icon's tint, text with no colour of its own), and the Wear components
 * inside read the port's theme, which [WearCatalogTheme] installs. This is Wear's default
 * `ColorScheme` role for role, so the two cannot disagree. It used to be colours sampled off a
 * reference render, and sampling put a value under the wrong role: the warm `#FFDCC2` was filed as
 * `onSurfaceVariant` and is Wear's `tertiary`, and `#F6EDFF` stood in for `onBackground`, which is
 * `onSurface`.
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
  // The viewed shape's published spec is the baseline; a design that authored its own padding or
  // radius still overrides it, which is what it always did — only the number it overrides changed
  // from "the squircle's" to "this shape's". The content box is not on that list and cannot be:
  // it is the footprint the host reserves, not a value a widget holds.
  val horizontalPadding = node.float("horizontalPaddingDp", spec.horizontalPaddingDp)
  val verticalPadding = node.float("verticalPaddingDp", spec.verticalPaddingDp)
  val cornerRadius = node.float("cornerRadiusDp", spec.cornerRadiusDp)
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

/** A Wear property's colour: a literal as itself, a role through Wear's own scheme. */
@Composable
private fun UiBuilderNode.wearColor(name: String): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  return wearThemeColor(value)
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
