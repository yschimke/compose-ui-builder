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
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.PaneScaffoldScope
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
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
import androidx.compose.ui.layout.layout
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
import ee.schimke.composeai.uibuilder.decodeUiBuilderAssetBitmap
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.resolveAsset
import kotlinx.serialization.json.JsonObject

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
 * `m3/navigation-suite-scaffold`: the real `NavigationSuiteScaffold`, choosing rail or bar from its
 * own frame like [AdaptiveSupportingPaneScaffold]. The unrolled editor gets `NavigationSuite` in a
 * `Row` at every width, since the scaffold cannot measure an unbounded height.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AdaptiveNavigationSuiteScaffold(
  modifier: Modifier,
  screenHeight: Dp,
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
          // The rail lays itself out at exactly its `maxHeight`, and the extent layout's first pass
          // measures against an unbounded one to find how tall the design is: the rail asked for a
          // 96 x Int.MAX_VALUE layout and the whole design failed to open. In that pass it is one
          // screen tall, which the canvas frame never goes below anyway, so the content still
          // decides the extent; the pass that follows is bounded to it, and the rail fills it.
          modifier =
            Modifier.layout { measurable, constraints ->
              val placeable =
                measurable.measure(
                  if (constraints.hasBoundedHeight) constraints
                  else
                    constraints.copy(
                      maxHeight = maxOf(constraints.minHeight, screenHeight.roundToPx())
                    )
                )
              layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            },
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

/**
 * The real `SupportingPaneScaffold`, so the canvas, preview pane and generated source all adapt
 * through `androidx.compose.material3.adaptive`.
 *
 * `layoutMode` maps onto the directive: `adaptive`, `twoPane` and `expandedTwoPane` leave it as
 * computed, `singlePane` pins `maxHorizontalPartitions` to 1. The pane visibility flags go to the
 * [ThreePaneScaffoldValue].
 *
 * The size class is computed from this scaffold's own constraints ([`WindowSizeClass.compute`]),
 * not `currentWindowAdaptiveInfo()`, so each device frame in the preview pane is its own window;
 * posture still comes from the real window.
 *
 * The unrolled editor ([LocalUiBuilderUnrolled]) gets [UnfoldedSupportingPaneScaffold] instead: the
 * real scaffold cannot be measured against an unbounded height, and the editing surface always
 * shows every pane. A pane-count disagreement between editor and preview is therefore expected
 * ([`UI_BUILDER_PREVIEW_FIDELITY.md`](../../../../../../docs/design/UI_BUILDER_PREVIEW_FIDELITY.md)).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AdaptiveSupportingPaneScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  mainPane: @Composable (Modifier) -> Unit,
  supportingPane: @Composable (Modifier) -> Unit,
  extraPane: (@Composable (Modifier) -> Unit)? = null,
) {
  val listDetail = node.componentId == "layout/list-detail-pane-scaffold"
  if (LocalUiBuilderUnrolled.current) {
    UnfoldedSupportingPaneScaffold(node, modifier, mainPane, supportingPane, extraPane)
    return
  }
  val mainVisible = node.bool(if (listDetail) "listPaneVisible" else "mainPaneVisible", true)
  val supportingVisible =
    node.bool(if (listDetail) "detailPaneVisible" else "supportingPaneVisible", true)
  // The three widths the catalog declares. Absent is not zero: an unstated width means "whatever
  // the library would have chosen", which is what every design authored before these were read
  // has been getting.
  val mainWidth =
    node.dimension(if (listDetail) "listPanePreferredWidthDp" else "mainPanePreferredWidthDp")
  val supportingWidth =
    node.dimension(
      if (listDetail) "detailPanePreferredWidthDp" else "supportingPanePreferredWidthDp"
    )
  val paneSpacing =
    node.dimension("paneSpacingDp")
      ?: if (node.string("paneSizing") in setOf("fixedStart", "fixedEnd")) 24.dp else null
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
    val anchor =
      when (node.string("paneSizing")) {
        "fixedStart" ->
          PaneExpansionAnchor.Offset.fromStart(
            node.float("fixedPaneWidthDp", 360f).coerceAtLeast(0f).dp +
              directive.horizontalPartitionSpacerSize / 2
          )
        "fixedEnd" ->
          PaneExpansionAnchor.Offset.fromEnd(
            node.float("fixedPaneWidthDp", 360f).coerceAtLeast(0f).dp +
              directive.horizontalPartitionSpacerSize / 2
          )
        "split" ->
          PaneExpansionAnchor.Proportion(node.float("splitFraction", .5f).coerceIn(.1f, .9f))
        else -> null
      }
    // Key the policy so editing it replaces the remembered initial expansion anchor.
    key(anchor) {
      val expansion =
        rememberPaneExpansionState(
          anchors = listOfNotNull(anchor),
          initialAnchoredIndex = if (anchor == null) -1 else 0,
        )
      if (listDetail) {
        val requestedRole =
          if ("activePaneIndex" in node.properties)
            when (node.float("activePaneIndex").toInt().coerceIn(0, 2)) {
              1 -> ListDetailPaneScaffoldRole.Detail
              2 -> ListDetailPaneScaffoldRole.Extra
              else -> ListDetailPaneScaffoldRole.List
            }
          else
            when (node.string("activePane")) {
              "detail" -> ListDetailPaneScaffoldRole.Detail
              "extra" -> ListDetailPaneScaffoldRole.Extra
              else -> ListDetailPaneScaffoldRole.List
            }
        val available = buildList {
          if (mainVisible) add(ListDetailPaneScaffoldRole.List)
          if (supportingVisible) add(ListDetailPaneScaffoldRole.Detail)
          if (extraPane != null) add(ListDetailPaneScaffoldRole.Extra)
        }
        val role = requestedRole.takeIf { it in available } ?: available.firstOrNull()
        val computed =
          calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
            currentDestination = role?.let { ThreePaneScaffoldDestinationItem<Nothing>(it) },
          )
        ListDetailPaneScaffold(
          directive = directive,
          value =
            ThreePaneScaffoldValue(
              primary = if (supportingVisible) computed.primary else PaneAdaptedValue.Hidden,
              secondary = if (mainVisible) computed.secondary else PaneAdaptedValue.Hidden,
              tertiary = if (extraPane != null) computed.tertiary else PaneAdaptedValue.Hidden,
            ),
          listPane = { mainPane(preferredPaneWidth(Modifier, mainWidth).fillMaxSize()) },
          detailPane = {
            supportingPane(preferredPaneWidth(Modifier, supportingWidth).fillMaxSize())
          },
          extraPane =
            extraPane?.let { content ->
              {
                content(
                  preferredPaneWidth(Modifier, node.dimension("extraPanePreferredWidthDp"))
                    .fillMaxSize()
                )
              }
            },
          paneExpansionState = expansion,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        val computed =
          calculateThreePaneScaffoldValue(
            maxHorizontalPartitions = directive.maxHorizontalPartitions,
            adaptStrategies = SupportingPaneScaffoldDefaults.adaptStrategies(),
            currentDestination =
              if (!mainVisible && supportingVisible)
                ThreePaneScaffoldDestinationItem<Nothing>(SupportingPaneScaffoldRole.Supporting)
              else null,
          )
        SupportingPaneScaffold(
          directive = directive,
          value =
            ThreePaneScaffoldValue(
              primary = if (mainVisible) computed.primary else PaneAdaptedValue.Hidden,
              secondary = if (supportingVisible) computed.secondary else PaneAdaptedValue.Hidden,
              tertiary = PaneAdaptedValue.Hidden,
            ),
          mainPane = { mainPane(preferredPaneWidth(Modifier, mainWidth).fillMaxSize()) },
          supportingPane = {
            supportingPane(preferredPaneWidth(Modifier, supportingWidth).fillMaxSize())
          },
          paneExpansionState = expansion,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

/** [PaneScaffoldScope.preferredWidth] where a width was authored, and nothing where none was. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun PaneScaffoldScope.preferredPaneWidth(modifier: Modifier, width: Dp?): Modifier =
  if (width == null) modifier else modifier.preferredWidth(width)

/**
 * The unrolled editor's stand-in for [AdaptiveSupportingPaneScaffold]: every declared pane at every
 * width, with `layoutMode` ignored, so no pane becomes unselectable. Widths become weights so the
 * pair fits any canvas.
 */
@Composable
private fun UnfoldedSupportingPaneScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  mainPane: @Composable (Modifier) -> Unit,
  supportingPane: @Composable (Modifier) -> Unit,
  extraPane: (@Composable (Modifier) -> Unit)?,
) {
  val listDetail = node.componentId == "layout/list-detail-pane-scaffold"
  val mainVisible = node.bool(if (listDetail) "listPaneVisible" else "mainPaneVisible", true)
  val supportingVisible =
    node.bool(if (listDetail) "detailPaneVisible" else "supportingPaneVisible", true)
  val mainWidth =
    node
      .float(if (listDetail) "listPanePreferredWidthDp" else "mainPanePreferredWidthDp", 744f)
      .coerceAtLeast(1f)
  val supportWidth =
    node
      .float(
        if (listDetail) "detailPanePreferredWidthDp" else "supportingPanePreferredWidthDp",
        512f,
      )
      .coerceAtLeast(1f)
  val spacing = node.float("paneSpacingDp").coerceAtLeast(0f)
  Row(modifier) {
    if (mainVisible) mainPane(Modifier.weight(mainWidth).fillMaxSize())
    if (mainVisible && supportingVisible) Spacer(Modifier.width(spacing.dp))
    if (supportingVisible) supportingPane(Modifier.weight(supportWidth).fillMaxSize())
    if (extraPane != null && (mainVisible || supportingVisible)) Spacer(Modifier.width(spacing.dp))
    extraPane?.invoke(
      Modifier.weight(node.float("extraPanePreferredWidthDp", 360f).coerceAtLeast(1f)).fillMaxSize()
    )
    if (!mainVisible && !supportingVisible) Box(Modifier.fillMaxSize())
  }
}

/**
 * The carousel on the unrolled extent only. A device frame draws Material's real
 * `HorizontalUncontainedCarousel`; the extent measures against an unbounded height, which the real
 * carousel cannot take, so this lays the same items out on the same data contract.
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

/**
 * The carousel with every item laid out, for the pop-out that unrolls it sideways — see
 * [LocalUiBuilderUnrolledHorizontal]. The same padding, spacing and item width as the lazy stand-in
 * above; a `LazyRow` cannot be measured against the unbounded width this is drawn in.
 */
@Composable
internal fun UnrolledHorizontalCarousel(
  node: UiBuilderNode,
  modifier: Modifier,
  ids: List<String>,
  child: @Composable (String, Modifier) -> Unit,
) {
  Row(
    modifier.padding(PaddingValues(start = node.float("contentPaddingStartDp").dp)),
    horizontalArrangement = Arrangement.spacedBy(node.float("itemSpacingDp").dp),
  ) {
    ids.forEach { child(it, Modifier.width(node.float("itemWidthDp", 128f).dp)) }
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
 * An `asset/image` node: the picture its `assetKey` resolves to via
 * [UiBuilderDocument.resolveAsset], or a placeholder naming the key. An unknown key must never fail
 * the whole frame.
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
 * A component the canvas names instead of drawing, for a pack whose classes the browser cannot
 * link: a dashed outline with the name, children inside. Never a lookalike
 * (`docs/design/UI_BUILDER_WEAR_SCREEN.md`); the native lane draws the real thing. Not
 * [UnsupportedComponentDiagnostic], because nothing is wrong.
 */
@Composable
internal fun NativeOnlyPlaceholder(
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
internal fun LottiePlaceholder(node: UiBuilderNode, modifier: Modifier) {
  val outline = MaterialTheme.colorScheme.outline
  val json = node.string("json")
  val url = node.string("url")
  if (json.isEmpty() && url.isEmpty()) ReportContentMissing()
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
internal fun UnsupportedComponentDiagnostic(componentId: String, modifier: Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      "Unsupported component: $componentId",
      Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}
