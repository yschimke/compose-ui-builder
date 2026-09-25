@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.ResolvedUiBuilderAsset
import ee.schimke.composeai.uibuilder.artwork.ProjectOwnedJetcasterArtwork
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.decodeUiBuilderAssetBitmap
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.resolveAsset
import kotlinx.serialization.json.JsonObject

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
internal val LocalFrameNavigationSuiteType = compositionLocalOf {
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
internal fun AdaptiveNavigationSuiteScaffold(
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
internal fun AdaptiveSupportingPaneScaffold(
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
internal fun CompatibleHorizontalCarousel(
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
internal fun CompatibleFloatingToolbar(
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
internal fun BuilderButton(
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
internal fun LegacyListItem(
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
internal fun AssetImage(document: UiBuilderDocument, node: UiBuilderNode, modifier: Modifier) {
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
  ReportContentMissing()
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
@UiBuilderCanvasAddonApi
public fun NativeOnlyPlaceholder(
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
internal fun RemoteContentFrame(
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

@Composable
internal fun UnsupportedComponentDiagnostic(componentId: String, modifier: Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      "Unsupported component: $componentId",
      Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}
