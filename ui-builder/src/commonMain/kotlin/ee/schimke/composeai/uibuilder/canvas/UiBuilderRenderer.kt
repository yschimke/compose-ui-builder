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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldScope
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.LocalContentColor as WearLocalContentColor
import androidx.wear.compose.material3.ScreenStage
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.scrollAway
import androidx.window.core.layout.WindowSizeClass
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcCustomComponentRegistry
import ee.schimke.composeai.rcplayer.compose.RcCustomContent
import ee.schimke.composeai.rcplayer.compose.RcPlayerTheme
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.ResolvedUiBuilderAsset
import ee.schimke.composeai.uibuilder.artwork.ProjectOwnedJetcasterArtwork
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.canvasAdapterMappings
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.decodeUiBuilderAssetBitmap
import ee.schimke.composeai.uibuilder.editor.THEME_BACKGROUND
import ee.schimke.composeai.uibuilder.editor.THEME_CONTENT
import ee.schimke.composeai.uibuilder.editor.THEME_CORNER_RADIUS
import ee.schimke.composeai.uibuilder.editor.THEME_PRIMARY
import ee.schimke.composeai.uibuilder.editor.THEME_SURFACE
import ee.schimke.composeai.uibuilder.editor.THEME_TYPE_SCALE
import ee.schimke.composeai.uibuilder.editor.supportingText
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
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
import ee.schimke.composeai.uibuilder.resolveAsset
import ee.schimke.composeai.uibuilder.withFontFamily
import ee.schimke.wearcmp.port.LocalWearDeviceConfiguration
import ee.schimke.wearcmp.port.WearDeviceConfiguration
import kotlin.io.encoding.Base64
import kotlin.math.PI
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
 * Provided by the editor from the catalog rather than compiled in like [WEAR_NATIVE_ONLY], because
 * a pack's components arrive at run time and this renderer cannot know them: they are whatever
 * another catalog's record proved a call site for. Empty by default, so every other host of this
 * surface — the previews, the renderer bundle — is unchanged.
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
  val fontFamily = typeface?.let(LocalUiBuilderFontFamilies.current::get)
  val typography =
    MaterialTheme.typography.let { base -> fontFamily?.let(base::withFontFamily) ?: base }
  val wearScreen =
    document.roots.singleOrNull()?.let(document.nodes::get)?.let { node ->
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
  val themeHost = document.topLevelNodes.firstOrNull { it.componentId == "m3/surface" }
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
  // The watch the components inside this design are laid out against — see
  // [wearDeviceConfiguration] for what the browser answers when nobody says.
  //
  // The trigger is the PLATFORM the catalog declares, not a component id this file recognises: a
  // Wear catalog is one whose components are drawn with the Wear port, and that is the catalog's
  // statement rather than something to be inferred from a namespace. A board holding one Wear card
  // needs a watch as much as a whole screen does, and so does a shelf thumbnail of one picker.
  val wearDevice =
    if (LocalUiBuilderCatalogPlatform.current == UiBuilderCatalogPlatform.WEAR.wireValue) {
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
    // The design's content colour, not the host's. `MaterialTheme` below sets none, so text with no
    // colour of its own inherited whatever sat outside this surface: the editor chrome's
    // light-on-dark on the canvas, and the platform default black inside a device pane's scene,
    // where no local crosses. A Wear widget's label was white on one and black on the other — near
    // invisible on the widget's dark fill.
    LocalContentColor provides colorScheme.onBackground,
    *wearDevice,
  ) {
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
      val updateExtentInputs = LocalCanvasExtentInputs.current
      Box(
        renderSurface?.let { Modifier.requiredSize(it.widthDp.dp, it.heightDp.dp) }
          ?: Modifier.fillMaxSize()
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
            Modifier.fillMaxSize().pointerInput(overlayBounds.toMap(), onNodeSelected) {
              detectTapGestures { position ->
                overlayBounds
                  .filterValues { it.contains(position) }
                  .minByOrNull { (_, rect) -> rect.width * rect.height }
                  ?.key
                  // The editor selects a node, because a node is what its inspector edits. Which
                  // copy was tapped is the question the selection model has yet to be asked.
                  ?.let { onNodeSelected?.invoke(it.nodeId) }
              }
            }
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
  host.RenderCanvasNode(
    entry = entry,
    registry = LocalCanvasAdapterRegistry.current,
    modifier = modifier,
    onNavigate = navigate,
    handlesClick = { it.componentId in INTERACTIVE_COMPONENTS },
    applyModifier = { current, value ->
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
      // The Wear screen. Unlike the widget container above, this stand-in is EMITTED rather than
      // erased: `ScreenScaffold` is a composable the author calls, so `WearScreenCodeExporter`
      // names
      // it. What is faked is only the drawing — the canvas has no Wear Compose to draw with.
      ROUND_SCREEN_FRAME ->
        WearScreenScaffold(
          node = node,
          modifier = measured,
          frame = LocalUiBuilderFrameGeometry.current,
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
            if (slot("label").isEmpty()) Text(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  Text(node.string("secondaryLabel"))
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
            if (slot("label").isEmpty()) Text(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  Text(node.string("secondaryLabel"))
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
            if (slot("label").isEmpty()) Text(node.string("label"))
            else slot("label").forEach { wearChild(it, Modifier) }
          },
          secondaryLabel =
            when {
              slot("secondaryLabel").isNotEmpty() -> ({
                  slot("secondaryLabel").forEach { wearChild(it, Modifier) }
                })
              node.string("secondaryLabel").isNotEmpty() -> ({
                  Text(node.string("secondaryLabel"))
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
        WearCanvasButtonGroup(childCount = children.size, modifier = measured) { index ->
          child(children[index], Modifier)
        }
      }
      "wear-m3/icon-button" ->
        WearCanvasIconButton(
          variant = node.string("variant"),
          enabled = node.bool("enabled", true),
          modifier = measured,
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
        WearCanvasButton(node.string("variant"), node.bool("enabled", true), measured) {
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
            hasDismiss = slot("dismissButton").isNotEmpty(),
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
        if (
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
          HorizontalUncontainedCarousel(
            state = carouselState,
            itemWidth = node.float("itemWidthDp", 128f).dp,
            modifier = measured,
            itemSpacing = node.float("itemSpacingDp").dp,
            contentPadding = PaddingValues(start = node.float("contentPaddingStartDp").dp),
          ) { index ->
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
        val inputField: @Composable () -> Unit = {
          slot("inputField").forEach { child(it, Modifier.fillMaxSize()) }
        }
        if (
          uiBuilderRenderStrategy(node.componentId, LocalUiBuilderUnrolled.current) ==
            UiBuilderRenderStrategy.AUTHORING_ADAPTER
        ) {
          Surface(
            measured.height(56.dp),
            shape = CircleShape,
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
            shape = RoundedCornerShape(node.float("shapeDp", DIALOG_CORNER_DP).dp),
            containerColor =
              node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHigh),
            tonalElevation = node.float("tonalElevationDp", DIALOG_TONAL_ELEVATION_DP).dp,
          )
        }
      }
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
      // A Wear component with no Material 3 counterpart, drawn as a named placeholder and not as a
      // lookalike. See [NativeOnlyPlaceholder] for why this is the honest shape rather than a
      // gap in the implementation.
      in WEAR_NATIVE_ONLY ->
        NativeOnlyPlaceholder(node, measured) {
          // Every slot's children, flattened. A placeholder cannot lay a child out the way the real
          // component would — that is what makes it a placeholder — but dropping the children would
          // hide whole subtrees from the layers panel's counterpart on the canvas, and an icon
          // inside an icon button is the thing an author is looking for.
          node.slots.values.flatten().forEach { childId -> child(childId, Modifier) }
        }
      // A pack's component: declared by the catalog, proven by another catalog's record, and drawn
      // here as its name and place for the reason the Wear ones are — the browser cannot link the
      // classes that draw it. The caption says whose it is, because a `Session Card` on a Material
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
 * vertical content padding (10%) and its list's minimum vertical content padding (23%) — the two
 * numbers [wearScreenContentPadding] interpolates from the same diameter.
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
  frame: UiBuilderFrameGeometry,
  screenWidthDp: Int,
  edgeButton: @Composable (Modifier) -> Unit,
  hasEdgeButton: Boolean,
  content: @Composable (Modifier) -> Unit,
) {
  val width = screenWidthDp.dp
  val padding = wearScreenContentPadding(screenWidthDp, frame)
  // Wear Material 3 is dark-first and its `background` is pure black — measured off the reference
  // render, not read from the editor theme, which is the bug the widget container's default
  // background comments: reading the theme made the watch go white in a light editor.
  val background = node.color("background", WEAR_SCREEN_BACKGROUND)
  val timeText = node.string("timeText")
  val scrollIndicator = node.bool("scrollIndicator", true)
  // **The list's state, owned here and shared.** `ScreenScaffold` exists to hold one list: it hands
  // the list its `contentPadding`, its scroll indicator reads where that list is, and
  // `AppScaffold`'s
  // clock scrolls away as it moves. None of that can happen if the scaffold and the list each
  // remember their own state, which is what the canvas did — the clock sat still and the indicator
  // was absent, because neither could see the list. The real scaffold wires this by construction;
  // this is the stand-in doing the same thing by hand.
  val listState = rememberTransformingLazyColumnState()
  val scrollInfo = remember(listState) { ScrollInfoProvider(listState) }
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
    CompositionLocalProvider(
      LocalWearScreenListState provides listState,
      LocalWearScreenContentPadding provides padding,
    ) {
      // This is intentionally not `Column.padding(padding)`: native `ScreenScaffold` hands the
      // padding to its `TransformingLazyColumn`, where it belongs to the list's scroll range. An
      // outer padded viewport leaves the final row clipped at the round frame when it reaches end.
      if (LocalUiBuilderUnrolled.current) {
        // The extent is deliberately not a viewport. Keep its ordinary inset so every item is
        // legible, including the first and last ones, while the frame pane below uses the real
        // lazy-list content-padding path.
        Column(Modifier.fillMaxWidth().padding(padding)) { content(Modifier.fillMaxWidth()) }
      } else {
        content(Modifier.fillMaxSize())
      }
    }
    // Overlaid, not a band above the content. `TimeText` belongs to `AppScaffold` and is drawn
    // over the screen; what makes room for it is the list's own top content padding, which is
    // already applied above. Drawing it as a row that displaced the content — which this did —
    // pushed every row down by the height of a clock the real screen draws on top of nothing.
    //
    // And it **scrolls away**, through the library's own modifier rather than a hand-rolled fade:
    // `scrollAway` is what `AppScaffold` applies to its time text, driven by the
    // `ScrollInfoProvider`
    // the real `ScreenScaffold` publishes. The provider here is the library's own adapter for this
    // exact state, and the stage is what the scaffold passes: `Scrolling` while the finger or the
    // side button is moving the list, `Idle` once it settles.
    if (timeText.isNotEmpty()) {
      Box(
        Modifier.matchParentSize().scrollAway(scrollInfo) {
          if (listState.isScrollInProgress) ScreenStage.Scrolling else ScreenStage.Idle
        }
      ) {
        WearCurvedTimeText(timeText, Modifier.fillMaxSize())
      }
    }
    // The scroll indicator, drawn where it belongs and only where it means something. On the
    // *extent* there is no viewport for it to show a position within — that is the argument the
    // comment above records, and the long screenshot agrees — so it is drawn only when this pane
    // has a viewport, which is the frame pane and every device pane beside it.
    if (scrollIndicator && !LocalUiBuilderUnrolled.current) {
      ScrollIndicator(
        state = listState,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
      )
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
private fun wearScreenContentPadding(
  screenWidthDp: Int,
  frame: UiBuilderFrameGeometry,
): PaddingValues {
  val padding = frame.paddingFor(screenWidthDp)
  return PaddingValues(horizontal = padding.horizontalDp.dp, vertical = padding.verticalDp.dp)
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

/** Measured off the reference card: inset 26dp at its top row, full width 26dp down. */
private const val WEAR_CARD_CORNER_RADIUS_DP = 26f

/**
 * The subset of a Material 3 scheme a Wear design actually draws through, in Wear's own values.
 *
 * The canvas installs this one mobile `MaterialTheme` for its own chrome — the editor's menus, the
 * labels, the placeholders — and the Wear components drawn inside it read their own library's
 * tokens, which is why only the roles those components resolve through it are replaced. The rest
 * stay Material 3's dark scheme, because a colour this catalog has never drawn is a colour nobody
 * has measured, and inventing one would put a number in the picture that no watch produced.
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

// `WEAR_LIST_HEADER_HEIGHT_DP` (48f) and `WEAR_LIST_HEADER_SP` (14.5f) stood here. Both were
// measured off upstream renders to size a `Box`+`Text` replica of `ListHeader`, and both are gone
// because the canvas draws the real `ListHeader` now — see `WearCanvasComponents`. A number read
// off a screenshot that nothing in the build can re-check is the cost the old approach carried;
// deleting the numbers rather than leaving them unreferenced is what makes that cost actually go.

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
  val url = node.string("documentUrl")
  val resolve = LocalRemoteComposeDocuments.current
  // Bytes win. A design that carries its own document has already been decided; reaching for the
  // network as well would make an offline reopen of a saved design depend on a host it does not
  // need, and would leave two answers to the question of what this node holds.
  val decoded =
    when {
      encoded.isNotBlank() -> remember(encoded) { decodeRemoteComposeDocument(encoded) }
      url.isNotBlank() -> resolve(url)
      else ->
        remember {
          Result.failure(
            IllegalArgumentException(
              "Remote Compose node needs either documentBase64 or documentUrl"
            )
          )
        }
    }
  if (decoded == null) {
    // Waiting, not broken — see [LocalRemoteComposeDocuments]. The URL is shown because it is the
    // only thing an author can act on while it is unresolved.
    RemoteComposeDiagnostic(message = "Loading $url", modifier = modifier, error = false)
    return
  }
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

/**
 * A captured inline subtree, played rather than described.
 *
 * ## Why the registry is built from the design and not from the document
 *
 * The captured document names its custom components by the string the generated body wrote —
 * `RemoteCustomComponent(name = "field")`, from the node's own `name` property — and the host is
 * what supplies the Compose that fills each one. So the two halves of that contract are the design
 * node and its `content` slot, and they are what this walks: every `remote-compose/custom` under
 * this node registers a renderer under its `name` that draws its own children.
 *
 * That is the same seam an embedded `remote-compose/document` uses through its named slots, reached
 * from the other side — and it is what makes a design nest Compose inside Remote Compose inside
 * Compose with a real player in the middle rather than a frame.
 *
 * ## The walk stops at a custom component
 *
 * What is under one is host content again, so its own descendants are not part of the remote
 * subtree and must not be searched for further custom components: a `remote-compose/custom` nested
 * inside another one's `content` belongs to whatever *that* content is, not to this document. The
 * same rule `RemoteScopes` applies when it decides which vocabulary a node is written in.
 */
@Composable
private fun PlayedInlineRemoteContent(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  captured: Result<RcDocument>,
  modifier: Modifier,
  slotContent: @Composable (String, Modifier) -> Unit,
) {
  val rcDocument = captured.getOrNull()
  if (rcDocument == null) {
    RemoteComposeDiagnostic(
      message =
        captured.exceptionOrNull()?.message
          ?: "the captured Remote Compose document could not be read",
      modifier = modifier,
    )
    return
  }
  val fills = remember(document, node.id) { document.customComponentFills(node) }
  val renderers = fills.mapValues { (_, fillIds) ->
    val content: RcCustomContent = { _, next ->
      Column(next) { fillIds.forEach { slotContent(it, Modifier.fillMaxWidth()) } }
    }
    content
  }
  val customComponents = RcCustomComponentRegistry(renderers)
  // The same preflight the embedded document runs, and it earns its place here for a sharper
  // reason: these bytes were generated from this design, so an unregistered name is a disagreement
  // between the emitter and the canvas rather than a document somebody else published. Saying which
  // name is missing is what turns that into something an author can act on.
  val missing =
    remember(rcDocument, customComponents.names) {
      rcDocument
        .composeSupportReport(availableCustomComponents = customComponents.names)
        .issues
        .filter { it.operation == "Custom" }
    }
  if (missing.isNotEmpty()) {
    RemoteComposeDiagnostic(message = missing.joinToString("\n") { it.detail }, modifier = modifier)
    return
  }
  val inherited =
    when (document.environment["theme"]?.jsonPrimitive?.contentOrNull) {
      "light" -> RcPlayerTheme.Light
      "dark" -> RcPlayerTheme.Dark
      else -> RcPlayerTheme.System
    }
  // The document's own shape, where it declares one, and this is the one place an inline node
  // differs from an embedded one on purpose. An embedded document is a node an author added and
  // sized: its modifiers are what they asked for, and overriding them with the bytes' aspect would
  // ignore the ask. An inline node was never sized *as a document* — the author drew a subtree, and
  // the only statement about how much room it wants is the one the capture wrote into the header.
  // Without this the player takes every pixel the column has left and the design's own content
  // below the remote content stops being drawn at all.
  val header = rcDocument.header
  val shaped =
    if (header.width > 0 && header.height > 0) {
      modifier.aspectRatio(header.width.toFloat() / header.height.toFloat())
    } else modifier
  RcComposePlayer(
    document = rcDocument,
    modifier = shaped,
    theme =
      when (node.string("theme")) {
        "light" -> RcPlayerTheme.Light
        "dark" -> RcPlayerTheme.Dark
        "system" -> RcPlayerTheme.System
        else -> inherited
      },
    customComponents = customComponents,
  )
}

/**
 * Every custom component in [host]'s remote subtree, as `name` to the node ids that fill it.
 *
 * A map rather than a list because that is what a registry is keyed by, and two nodes sharing one
 * name is a design decision rather than an error — the later one wins here, exactly as it would in
 * a registry built by hand.
 */
private fun UiBuilderDocument.customComponentFills(host: UiBuilderNode): Map<String, List<String>> {
  val fills = mutableMapOf<String, List<String>>()
  val seen = mutableSetOf<String>()
  fun walk(id: String) {
    if (!seen.add(id)) return
    val node = nodes[id] ?: return
    if (node.componentId == REMOTE_COMPOSE_CUSTOM_COMPONENT_ID) {
      val name = node.string("name")
      if (name.isNotEmpty()) fills[name] = node.slots["content"].orEmpty()
      // Deliberately not descended into: see the KDoc above.
      return
    }
    node.slots.values.flatten().forEach(::walk)
  }
  host.slots["content"].orEmpty().forEach(::walk)
  return fills
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
private fun RemoteComposeDiagnostic(
  message: String,
  modifier: Modifier,
  /** False for a document that is merely not here yet, which is not the same as a broken one. */
  error: Boolean = true,
) {
  val container =
    if (error) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
  val content =
    if (error) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
  Surface(modifier, color = container) { Text(message, Modifier.padding(8.dp), color = content) }
}

/**
 * The real `SupportingPaneScaffold`, not an imitation of one.
 *
 * ## Why this is the whole point of the pane it draws in
 *
 * This component used to be a `BoxWithConstraints` here that expanded past a width it computed
 * itself, and the Kotlin export emitted a *second* hand-rolled helper with a different threshold
 * again — so a design could expand at one width on the canvas and another in the app, and the
 * preview pane could not answer the question it exists for. Both are gone: the canvas, the preview
 * pane and the generated source now go through `androidx.compose.material3.adaptive`, so "does it
 * adapt" is answered by the library that will answer it in production.
 *
 * ## How `layoutMode` reaches a component that has no such parameter
 *
 * It does not, and it never could — the scaffold takes a `PaneScaffoldDirective` and derives its
 * panes from the window, which is why the record-driven export gate refuses the property and still
 * does: mapping a mode onto a directive is a computation, and a record can only write a value as an
 * argument to a member. A hand-written emitter can compute, so here and in
 * [CapabilityComposeCodeExporter] the property maps onto the directive:
 *
 * - `adaptive`, `twoPane` and `expandedTwoPane` leave the directive as the window computed it, so
 *   the library decides and a tablet design collapses to one pane on a phone. The two-pane
 *   spellings behaved that way under the old stand-in too — it required the frame to be wide enough
 *   — so nothing observable changes for a design that uses them.
 * - `singlePane` pins `maxHorizontalPartitions` to 1: one pane at every width, on purpose.
 *
 * `adaptive` is the one spelling whose behaviour changes, and it changes to what its name says. The
 * stand-in's expansion test required the mode to be one of the two-pane spellings, so a design
 * asking for `adaptive` was the one design that never adapted.
 *
 * ## The frame is the window, not the browser
 *
 * `currentWindowAdaptiveInfo()` reports the window the *workspace* is in, and in the preview pane
 * that is one browser holding a row of device frames. Asked directly it gives every frame the same
 * answer, so a phone frame and a tablet frame beside it would expand or collapse together — which
 * is the one thing the multi-frame rung exists to disprove.
 *
 * So the size class is computed from this scaffold's **own** constraints
 * ([`WindowSizeClass.compute`]), which inside [ConstrainedFramePane] are the device's width and
 * height in the device's own density. Each frame is its own window, which is what it is standing in
 * for. The posture is still the real one — a hinge is a property of hardware, not of a frame, and
 * on the native lane the window really is the window.
 *
 * ## The visibility flags are the scaffold's value, not its directive
 *
 * `mainPaneVisible` / `supportingPaneVisible` say which panes this design has at all, which is a
 * different question from how many the window can show. They go to the [ThreePaneScaffoldValue];
 * the directive decides the rest, and `calculateThreePaneScaffoldValue` hides the supporting pane
 * when the partitions do not reach it.
 *
 * ## The unrolled editor gets the stand-in; every constrained frame gets this
 *
 * The switch is [LocalUiBuilderUnrolled] — the same one that already turns `layout/lazy-column`
 * into a `Column`, `layout/lazy-grid` into a non-lazy grid and drops a `verticalScroll`. One signal
 * decides all of it, so "unrolled" and "constrained" cannot each answer for a different component:
 * the visual editor gets [UnfoldedSupportingPaneScaffold] and the preview pane gets this.
 *
 * There is a second reason it has to be this way round, and it is not a preference. The real
 * scaffold cannot be measured against an unbounded height — `ThreePaneContentMeasurePolicy` lays
 * out at the height it is given, and `Constraints.Infinity` is not a size: `Size(1280 x 2147483647)
 * is out of range`. The editor's canvas measures exactly that way on purpose, because unrolling a
 * `LazyColumn` so its ninth row can be edited is what [CanvasExtentLayout] is for, and `unrolled =
 * true` is set at the same call site.
 *
 * Reading the incoming constraints here instead would answer differently in that layout's probe
 * pass than in its placement pass, and the extent reported would then belong to a layout nobody
 * drew. A composition local set once by the pane is stable across both.
 *
 * So this is the fidelity ladder meeting a real component, and it resolves the way the ladder says:
 * the editing surface is the one allowed to lie, and it draws every pane the design declares at
 * every canvas width — the full expanded experience, always. Every constrained frame — the preview
 * pane, each device and axis, the native lane — gets the real one, and that is where a design
 * collapses to a phone. A 1-vs-2 disagreement about pane count is therefore expected and is the
 * documented meaning of that row
 * ([`UI_BUILDER_PREVIEW_FIDELITY.md`](../../../../../../docs/design/UI_BUILDER_PREVIEW_FIDELITY.md)).
 */
/**
 * The navigation suite type the enclosing [AdaptiveNavigationSuiteScaffold] chose for its frame.
 *
 * `NavigationSuiteItem` asks for its type from `currentWindowAdaptiveInfo()` by default, and on the
 * canvas that is the browser window holding every device frame — so a phone frame's items would
 * draw as rail items inside a bar. The scaffold decides once, from its own frame, and its items
 * read that answer here. Outside a scaffold an item draws as a rail item, the widest form.
 */
private val LocalFrameNavigationSuiteType = compositionLocalOf {
  NavigationSuiteType.WideNavigationRailCollapsed
}

/**
 * `m3/navigation-suite-scaffold`: `NavigationSuiteScaffold` itself, deciding rail or bar from the
 * frame it is drawn in — the same rule, and for the same reason, as
 * [AdaptiveSupportingPaneScaffold]: each device frame is its own window, so a phone frame gets a
 * bar and a tablet frame beside it a rail.
 *
 * The unrolled editor gets the rail at every width, with the design's content beside it, which is
 * the full tablet experience the editing surface always draws. The scaffold proper cannot be
 * measured against the unbounded height that surface lays out with, so it is `NavigationSuite` —
 * the library's own navigation component, without the scaffold — in a `Row`.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AdaptiveNavigationSuiteScaffold(
  modifier: Modifier,
  items: @Composable () -> Unit,
  primaryAction: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  if (LocalUiBuilderUnrolled.current) {
    val type = NavigationSuiteType.WideNavigationRailCollapsed
    CompositionLocalProvider(LocalFrameNavigationSuiteType provides type) {
      Row(modifier) {
        NavigationSuite(
          navigationSuiteType = type,
          primaryActionContent = primaryAction,
          content = items,
        )
        Box(Modifier.weight(1f)) { content() }
      }
    }
    return
  }
  val posture = currentWindowAdaptiveInfo().windowPosture
  BoxWithConstraints(modifier) {
    val type =
      NavigationSuiteScaffoldDefaults.navigationSuiteType(
        WindowAdaptiveInfo(WindowSizeClass.compute(maxWidth.value, maxHeight.value), posture)
      )
    CompositionLocalProvider(LocalFrameNavigationSuiteType provides type) {
      NavigationSuiteScaffold(
        navigationItems = items,
        navigationSuiteType = type,
        primaryActionContent = primaryAction,
        content = content,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AdaptiveSupportingPaneScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  mainPane: @Composable (Modifier) -> Unit,
  supportingPane: @Composable (Modifier) -> Unit,
) {
  if (LocalUiBuilderUnrolled.current) {
    UnfoldedSupportingPaneScaffold(node, modifier, mainPane, supportingPane)
    return
  }
  val mainVisible = node.bool("mainPaneVisible", true)
  val supportingVisible = node.bool("supportingPaneVisible", true)
  // The three widths the catalog declares. Absent is not zero: an unstated width means "whatever
  // the library would have chosen", which is what every design authored before these were read
  // has been getting.
  val mainWidth = node.dimension("mainPanePreferredWidthDp")
  val supportingWidth = node.dimension("supportingPanePreferredWidthDp")
  val paneSpacing = node.dimension("paneSpacingDp")
  val posture = currentWindowAdaptiveInfo().windowPosture
  BoxWithConstraints(modifier) {
    val frameInfo =
      WindowAdaptiveInfo(
        WindowSizeClass.compute(maxWidth.value, maxHeight.value),
        posture,
      )
    val frameDirective = calculatePaneScaffoldDirective(frameInfo)
    val directive =
      (if (node.string("layoutMode") == "singlePane")
          frameDirective.copy(maxHorizontalPartitions = 1)
        else frameDirective)
        // The gap BETWEEN partitions is the directive's, not a pane's, which is why it is the one
        // of the three that is set here rather than on a pane modifier.
        .let {
          if (paneSpacing != null) it.copy(horizontalPartitionSpacerSize = paneSpacing) else it
        }
    // The library's own computation, so "two panes or one" is its answer rather than ours.
    //
    // The destination decides which pane wins a sole partition, and it is the supporting pane
    // exactly when the design declares no main pane. Masking afterwards is not enough there: the
    // one partition goes to the primary by default, so hiding the primary for a supporting-only
    // design would leave a value with everything hidden and draw a blank frame.
    val computed =
      calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        adaptStrategies = SupportingPaneScaffoldDefaults.adaptStrategies(),
        currentDestination =
          if (!mainVisible && supportingVisible)
            ThreePaneScaffoldDestinationItem<Nothing>(SupportingPaneScaffoldRole.Supporting)
          else null,
      )
    val value =
      ThreePaneScaffoldValue(
        primary = if (mainVisible) computed.primary else PaneAdaptedValue.Hidden,
        secondary = if (supportingVisible) computed.secondary else PaneAdaptedValue.Hidden,
        tertiary = PaneAdaptedValue.Hidden,
      )
    SupportingPaneScaffold(
      directive = directive,
      value = value,
      // `preferredWidth` is parent data the scaffold's measure policy reads, not a size modifier,
      // so it decides the partition and `fillMaxSize` still fills whatever partition it got. This
      // is how the authored widths reach a REAL scaffold: they were declared, stored, carried on
      // the wire and echoed into provenance, and then read by nobody, so Gmail's 400-beside-760
      // drew as roughly 810/360 — close to the opposite of what it asked for, with no diagnostic
      // saying so (docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md, gap 2).
      mainPane = { mainPane(preferredPaneWidth(Modifier, mainWidth).fillMaxSize()) },
      supportingPane = {
        supportingPane(preferredPaneWidth(Modifier, supportingWidth).fillMaxSize())
      },
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/** [PaneScaffoldScope.preferredWidth] where a width was authored, and nothing where none was. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun PaneScaffoldScope.preferredPaneWidth(modifier: Modifier, width: Dp?): Modifier =
  if (width == null) modifier else modifier.preferredWidth(width)

/**
 * The unrolled canvas's stand-in for the adaptive scaffold, and only its stand-in.
 *
 * Reached through [LocalUiBuilderUnrolled], which is the visual editor and nothing else — the
 * preview pane, the device and axis frames, the native lane and every export are constrained and
 * get the real component. See [AdaptiveSupportingPaneScaffold] for why the split runs on that one
 * signal rather than on two.
 *
 * **It always draws the expanded experience.** Every pane the design declares is on screen at every
 * canvas width, and `layoutMode` is not consulted at all — that property is now the real scaffold's
 * directive, and the question it answers ("how many panes fits here?") is a device question the
 * preview pane exists to answer. Collapsing here would answer it with the canvas's own width, which
 * is a window nobody ships, and the cost of being wrong is not a mis-drawn picture: a hidden pane
 * is a subtree that cannot be selected, dropped into or edited.
 *
 * That is the same licence as the unrolled column above it. The canvas shows you the thing you are
 * editing, including the parts a device would not show; the preview pane beside it is what says
 * which parts those are.
 *
 * The widths are proportional rather than absolute for that reason too — a tablet's 744 + 512 dp
 * pair at its authored size would overflow a narrow canvas and push the supporting pane off the
 * edge, so they become weights and the pair fills whatever frame it is given in the ratio the
 * design asked for.
 */
@Composable
private fun UnfoldedSupportingPaneScaffold(
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
  Row(modifier) {
    if (mainVisible) mainPane(Modifier.weight(mainWidth).fillMaxSize())
    if (mainVisible && supportingVisible) Spacer(Modifier.width(spacing.dp))
    if (supportingVisible) supportingPane(Modifier.weight(supportWidth).fillMaxSize())
    if (!mainVisible && !supportingVisible) Box(Modifier.fillMaxSize())
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

/**
 * An `asset/image` node: the picture its `assetKey` names, or a placeholder that says which key it
 * could not draw.
 *
 * Resolution is [UiBuilderDocument.resolveAsset]'s, shared with the SVG lanes; this composable only
 * decides what each answer looks like. The one rule here is that **no key fails the frame**. This
 * used to `error()` on a key it did not know, and because the design is one composition, a single
 * inserted node took a whole screen down — in the editor, in the daemon render behind
 * `ui_builder_export`, and for every collaborator with the design open. A key with nothing behind
 * it is now an ordinary picture-shaped placeholder carrying the key, which is what a designer needs
 * to see to fix it and what an agent's next render shows it has not.
 */
@Composable
private fun AssetImage(document: UiBuilderDocument, node: UiBuilderNode, modifier: Modifier) {
  val contentDescription = node.string("contentDescription").ifEmpty { null }
  val contentScale =
    when (node.string("contentScale")) {
      "fit" -> ContentScale.Fit
      "fillBounds" -> ContentScale.FillBounds
      "inside" -> ContentScale.Inside
      else -> ContentScale.Crop
    }
  val exportRaster = LocalUiBuilderExportRasterAssets.current[node.id]
  if (exportRaster != null) {
    Image(
      bitmap = exportRaster,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
    )
    return
  }
  val key = node.string("assetKey")
  when (val resolved = document.resolveAsset(key)) {
    is ResolvedUiBuilderAsset.Embedded -> {
      // Remembered by digest, not by node: the same bytes under two nodes decode once, and a key
      // re-pointed at a new picture decodes again because the digest moved.
      val bitmap = remember(resolved.contentDigest) { decodeUiBuilderAssetBitmap(resolved.bytes) }
      if (bitmap != null) {
        Image(
          bitmap = bitmap,
          contentDescription = contentDescription,
          modifier = modifier,
          contentScale = contentScale,
        )
      } else {
        MissingAssetPlaceholder(key, contentDescription, modifier)
      }
    }
    is ResolvedUiBuilderAsset.Uploaded -> {
      val bitmap = LocalUiBuilderAssetBitmaps.current(resolved.contentDigest)
      if (bitmap != null) {
        Image(
          bitmap = bitmap,
          contentDescription = contentDescription,
          modifier = modifier,
          contentScale = contentScale,
        )
      } else {
        MissingAssetPlaceholder(key, contentDescription, modifier)
      }
    }
    is ResolvedUiBuilderAsset.ProjectOwned ->
      ProjectOwnedJetcasterArtwork(
        assetKey = key,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
      )
    ResolvedUiBuilderAsset.Generated -> GeneratedCoverPlaceholder(modifier)
    is ResolvedUiBuilderAsset.Missing -> MissingAssetPlaceholder(key, contentDescription, modifier)
  }
}

/**
 * The frame of a picture nobody can show here: a neutral ground, a picture glyph, and the key.
 *
 * Neutral rather than an error container, because nothing is necessarily wrong — the bytes may be
 * uploading, may live on a host this lane cannot reach, or may simply not have been given yet. What
 * it must be is *visible* and *legible*: a viewer should see at a glance that a picture belongs
 * here, and read which key to fill. Drawn with the theme's own surface-variant pair so it sits in
 * either scheme, and with nothing animated or random so two renders of one design are one image.
 */
@Composable
private fun MissingAssetPlaceholder(
  assetKey: String,
  contentDescription: String?,
  modifier: Modifier,
) {
  val ground = MaterialTheme.colorScheme.surfaceVariant
  val ink = MaterialTheme.colorScheme.onSurfaceVariant
  val semantics =
    if (contentDescription == null) modifier
    else modifier.semantics { this.contentDescription = contentDescription }
  BoxWithConstraints(semantics.background(ground), contentAlignment = Alignment.Center) {
    Canvas(Modifier.matchParentSize()) {
      val inset = size.minDimension * 0.18f
      val frameWidth = size.width - inset * 2
      val frameHeight = size.height - inset * 2
      if (frameWidth <= 0f || frameHeight <= 0f) return@Canvas
      val stroke = Stroke((size.minDimension * 0.035f).coerceAtLeast(1f))
      drawRect(
        ink.copy(alpha = 0.55f),
        Offset(inset, inset),
        androidx.compose.ui.geometry.Size(frameWidth, frameHeight),
        style = stroke,
      )
      drawCircle(
        ink.copy(alpha = 0.55f),
        size.minDimension * 0.07f,
        Offset(inset + frameWidth * 0.30f, inset + frameHeight * 0.32f),
      )
      drawPath(
        Path().apply {
          moveTo(inset, inset + frameHeight)
          lineTo(inset + frameWidth * 0.38f, inset + frameHeight * 0.52f)
          lineTo(inset + frameWidth * 0.60f, inset + frameHeight * 0.76f)
          lineTo(inset + frameWidth * 0.76f, inset + frameHeight * 0.60f)
          lineTo(inset + frameWidth, inset + frameHeight)
          close()
        },
        ink.copy(alpha = 0.35f),
      )
    }
    // The key, on the canvas and in a PNG, where there is room for a word — an avatar-sized frame
    // shows the glyph alone rather than three clipped letters. Not in a structured SVG: that
    // recorder fails closed on any text it cannot attribute to an authored text node, which is the
    // right rule for an export and the wrong place for a label; the SVG keeps the frame and glyph.
    if (!LocalUiBuilderExportStructuredIcons.current && maxWidth >= 96.dp && maxHeight >= 48.dp) {
      Text(
        text = assetKey,
        modifier =
          Modifier.align(Alignment.BottomCenter).padding(horizontal = 4.dp, vertical = 2.dp),
        color = ink,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** The gate-0 fixture's generated cover: a gradient with a few shapes, no bytes behind it. */
@Composable
private fun GeneratedCoverPlaceholder(modifier: Modifier) {
  val palette = listOf(Color(0xFF6750A4), Color(0xFFB69DF8), Color(0xFF21005D))
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

/**
 * The Wear components this catalog publishes and the browser cannot draw, as ids.
 *
 * Derived from `WearScreenCodeExporter`'s own constants rather than listed again: the generator and
 * the canvas have to agree about which ids these are, and two lists is two chances not to.
 */
private val WEAR_NATIVE_ONLY: Set<String> = WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS

/**
 * A Wear component the canvas names instead of drawing.
 *
 * ## Why this is the honest shape, and not a gap
 *
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` rules out one thing exactly: *do not fabricate a
 * component in the Wasm canvas to stand in for a library the canvas cannot link*. Wear Material 3
 * is an Android AAR; a `CheckboxButton` drawn here would be a Material 3 `Checkbox` in a row at a
 * width, a corner radius and a label baseline read off a screenshot — an impression of upstream
 * with nothing in this build to check it against, and wrong silently.
 *
 * So it is not drawn. What is drawn is the node's *identity and place*: a dashed outline carrying
 * the component's name, sized by whatever the layout gives it, with its children inside. That is
 * enough to author with — you can see the row is there, select it, reorder it, put an icon in it —
 * and it claims nothing about size, colour or shape. The picture comes from the native lane, which
 * compiles this design's own generated Kotlin against real Wear Compose on the Android daemon;
 * `wear-m3` declares that lane authoritative and this canvas approximate, and the editor's render
 * surface menu says so where a renderer is chosen.
 *
 * A dashed outline rather than [UnsupportedComponentDiagnostic]'s error container, because nothing
 * is wrong. The component is in the catalog, it exports, and it renders — just not here.
 */
@Composable
private fun NativeOnlyPlaceholder(
  node: UiBuilderNode,
  modifier: Modifier,
  /** Whose component this is, where that is not obvious from the palette — a pack's id. */
  caption: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val outline = MaterialTheme.colorScheme.outline
  Column(
    modifier
      .fillMaxWidth()
      .drawBehind {
        drawRoundRect(
          color = outline,
          cornerRadius = CornerRadius(8.dp.toPx()),
          style =
            androidx.compose.ui.graphics.drawscope.Stroke(
              width = 1.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            ),
        )
      }
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      node.componentId.substringAfter('/').replace('-', ' ') + (caption?.let { " · $it" } ?: ""),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    node.string("label").takeIf(String::isNotEmpty)?.let {
      Text(it, color = MaterialTheme.colorScheme.onSurface)
    }
    content()
  }
}

/**
 * A labelled frame around content that is not what it appears to be drawn with.
 *
 * [NativeOnlyPlaceholder]'s neighbour and deliberately not the same thing. That one stands in for a
 * component the canvas cannot draw *at all*, so it draws a name and a box. This draws the content
 * for real — the stand-ins are the right shapes and the right text — and marks the boundary the
 * content sits on, because a subtree in a different vocabulary is a fact about the design that a
 * flush render would hide.
 */
@Composable
private fun RemoteContentFrame(
  label: String,
  detail: String?,
  modifier: Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val outline = MaterialTheme.colorScheme.tertiary
  Column(
    modifier
      .drawBehind {
        drawRoundRect(
          color = outline,
          cornerRadius = CornerRadius(8.dp.toPx()),
          style =
            androidx.compose.ui.graphics.drawscope.Stroke(
              width = 1.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
            ),
        )
      }
      .padding(horizontal = 6.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      detail?.let { "$label · $it" } ?: label,
      color = outline,
      style = MaterialTheme.typography.labelSmall,
    )
    content()
  }
}

/**
 * A Lottie element: what animation it holds, where it came from, and whether it will export.
 *
 * The unresolved case is the one worth drawing loudly. `url` and `json` are two halves of one
 * source — the builder fetches the first into the second — and an element carrying only a URL looks
 * finished in the layers panel while [RemoteContentEmitter] refuses it, because a widget is built
 * with no network to fetch from. Saying so here is what turns that into something an author can fix
 * before they press export.
 */
@Composable
private fun LottiePlaceholder(node: UiBuilderNode, modifier: Modifier) {
  val outline = MaterialTheme.colorScheme.outline
  val json = node.string("json")
  val url = node.string("url")
  Column(
    modifier
      .fillMaxWidth()
      .drawBehind {
        drawRoundRect(
          color = outline,
          cornerRadius = CornerRadius(8.dp.toPx()),
          style =
            androidx.compose.ui.graphics.drawscope.Stroke(
              width = 1.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            ),
        )
      }
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      "Lottie animation",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    // The file name rather than the whole URL: a Lottie URL is usually a long CDN path, and the
    // canvas has a widget's worth of width to say something useful in.
    url.takeIf(String::isNotEmpty)?.let {
      Text(
        it.substringAfterLast('/').ifEmpty { it },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(
      // Short on purpose: a Small widget is 216×76dp, and a sentence that wraps past the frame is
      // a sentence the author reads half of.
      when {
        json.isNotEmpty() -> "${json.length.animationSize()} · compiled into the document"
        url.isNotEmpty() -> "Not fetched — the export needs the JSON"
        else -> "No animation — add a URL or JSON"
      },
      color =
        if (json.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** `840 B`, `12 KiB` — the animation's weight, in the unit that reads at that size. */
private fun Int.animationSize(): String = if (this < 1024) "$this B" else "${this / 1024} KiB"

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

private fun UiBuilderNode.obj(name: String): JsonObject =
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

private fun UiBuilderNode.string(name: String): String = valueScalar(name)?.contentOrNull.orEmpty()

private fun UiBuilderNode.float(name: String, fallback: Float = 0f): Float =
  valueScalar(name)?.floatOrNull ?: fallback

/** A dimension the document actually carries, or null — which is not the same as zero. */
private fun UiBuilderNode.dimension(name: String): Dp? = valueScalar(name)?.floatOrNull?.dp

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

private fun UiBuilderNode.bool(name: String, fallback: Boolean = false): Boolean =
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
private fun UiBuilderNode.color(name: String, fallback: Color): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  if (value.isEmpty()) return fallback
  return colorTokenOrNull(value) ?: fallback
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
