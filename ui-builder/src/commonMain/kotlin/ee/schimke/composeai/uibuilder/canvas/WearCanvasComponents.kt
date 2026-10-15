package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.ArcProgressIndicator
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ConfirmationDialogContent
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FailureConfirmationDialogContent
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialogContent
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.OutlinedCard
import androidx.wear.compose.material3.OutlinedIconButton
import androidx.wear.compose.material3.PageIndicatorDefaults
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SegmentedCircularProgressIndicator
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SliderDefaults
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.SuccessConfirmationDialogContent
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.VerticalPageIndicator
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText

/**
 * Wear components drawn by Wear Compose, on the Wasm canvas.
 *
 * `androidx.wear.compose:compose-material3` is an Android-only AAR, but the Wear kit's Compose
 * Multiplatform port (`ee.schimke.wearcmp:*`) keeps the `androidx.wear.compose.material3` package
 * names and publishes `jvm` and `wasmJs` variants. So every import above is the real component,
 * measured and drawn by its own code. `settings.gradle.kts` names where the port is published and
 * `gradle/libs.versions.toml` pins the version.
 *
 * The port is not the kit rendition: published Wear renders and the device-preview lane use the
 * genuine AndroidX library under Robolectric, and remain the reference. Disagreement between the
 * lanes is a finding about the port, not the design.
 */
@Composable
internal fun WearCanvasListHeader(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  // `maxLines` and `overflow` go on the label `Text`, since upstream's `ListHeader` takes a content
  // lambda.
  val transformation = wearRowTransformation()
  OutsideWearRow {
    ListHeader(modifier = modifier, transformation = transformation) {
      Text(text, maxLines = maxLines, overflow = overflow)
    }
  }
}

/** Wear's own sub-header, replacing a `Text` that was styled to look like one. */
@Composable
internal fun WearCanvasListSubHeader(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  val transformation = wearRowTransformation()
  OutsideWearRow {
    ListSubHeader(modifier = modifier, transformation = transformation) {
      Text(text, maxLines = maxLines, overflow = overflow)
    }
  }
}

/**
 * Wear's `SwitchButton`. [onCheckedChange] is the renderer's two-way binding: a `checked` read from
 * a design flag is written back on tap, as `m3/switch` does; an authored `checked` stays put.
 */
@Composable
internal fun WearCanvasSwitchButton(
  checked: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
  onCheckedChange: (Boolean) -> Unit = {},
) {
  val transformation = wearRowTransformation()
  OutsideWearRow {
    SwitchButton(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = modifier.fillMaxWidth(),
      enabled = enabled,
      label = label,
      secondaryLabel = secondaryLabel,
      transformation = transformation,
    )
  }
}

/**
 * Wear's `Slider`. `valueRange` is the authored `valueFrom..valueTo` rather than derived from
 * `steps`; the two can legitimately differ.
 */
@Composable
internal fun WearCanvasSlider(
  value: Float,
  valueFrom: Float,
  valueTo: Float,
  steps: Int,
  segmented: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  Slider(
    value = value,
    onValueChange = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    // `steps` is the count BETWEEN the ends, so it cannot be negative however the document reads.
    steps = steps.coerceAtLeast(0),
    // Guarded: `Slider` requires a non-empty range, and a half-authored document must not crash the
    // canvas.
    valueRange = if (valueTo > valueFrom) valueFrom..valueTo else 0f..1f,
    segmented = segmented,
  )
}

/**
 * The real `TransformingLazyColumn`, scaling and fading rows via the library's
 * `SurfaceTransformation` and `transformedHeight`.
 *
 * Items are passed as a count plus an index lambda so the column stays lazy.
 */
@Composable
internal fun WearCanvasTransformingLazyColumn(
  itemCount: Int,
  verticalSpacingDp: Float,
  modifier: Modifier = Modifier,
  /**
   * Whether each row carries `transformedHeight` and `SurfaceTransformation`: the design's
   * `transformation` property. `none` matches a stitched `ScrollMode.LONG` capture, which turns the
   * transformation off.
   */
  transformation: Boolean = true,
  /**
   * The node id of each row, for scrolling the selected one into view (see [RevealSelectedItem]).
   * Empty where nothing asks.
   */
  itemIds: List<String> = emptyList(),
  item: @Composable (Int, Modifier) -> Unit,
) {
  val provided = LocalWearScreenListState.current
  val remembered = rememberTransformingLazyColumnState()
  // The scaffold's state when this list is inside one, so the clock and the indicator can see it.
  val state = provided ?: remembered
  RevealSelectedItem(itemIds, { id -> state.showsWhole(itemIds.indexOf(id)) }) {
    state.animateScrollToItem(it)
  }
  val spec = rememberTransformationSpec()
  TransformingLazyColumn(
    state = state,
    // Content padding (as `ScreenScaffold` gives its list), not a shorter viewport, so first and
    // last rows can scroll clear of the bezel.
    contentPadding = LocalWearScreenContentPadding.current,
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(verticalSpacingDp.dp),
  ) {
    items(itemCount) { index ->
      if (!transformation) {
        // The design asked for no transformation; both the layout and drawing halves are off.
        item(
          index,
          Modifier.fillMaxWidth()
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding),
        )
      } else {
        // **Both halves of the transformation.** `transformedHeight` is the layout half (rows
        // shorten near the bezel) and `SurfaceTransformation` the drawing half (scale and fade),
        // which library components take as a parameter; it travels to them by a local.
        //
        // A row whose component takes no transformation (a `Text`, `IconButton`, slider) is
        // transformed here as a whole, as `ButtonGroup` transforms itself; otherwise it would
        // shorten but draw full size.
        val row = remember(this, spec) { WearRowTransformation(SurfaceTransformation(spec)) }
        CompositionLocalProvider(LocalWearRowTransformation provides row) {
          item(
            index,
            Modifier.fillMaxWidth()
              .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
              .transformedHeight(this, spec)
              .graphicsLayer {
                if (row.appliedBy == 0) with(row.surface) { applyContainerTransformation() }
              },
          )
        }
      }
    }
  }
}

/**
 * The list state the enclosing Wear screen scaffold owns, or null outside one. The clock and scroll
 * indicator read it, so the scaffold stand-in owns it and shares it here.
 */
internal val LocalWearScreenListState =
  staticCompositionLocalOf<androidx.wear.compose.foundation.lazy.TransformingLazyColumnState?> {
    null
  }

/** The `ScreenScaffold` content padding the enclosing transforming list must consume. */
internal val LocalWearScreenContentPadding = staticCompositionLocalOf { PaddingValues() }

/**
 * The row transformation the enclosing Wear list is applying, and whether anything in the row has
 * applied it. The canvas dispatches by id, so this carries the argument a parent would pass in
 * generated code; see [wearRowTransformation].
 */
internal class WearRowTransformation(val surface: SurfaceTransformation) {
  /** How many components in the row are applying [surface] themselves; the list does, at none. */
  var appliedBy by mutableIntStateOf(0)
    private set

  internal fun claim() {
    appliedBy++
  }

  internal fun release() {
    appliedBy--
  }
}

/** The row transformation of the enclosing Wear list, or null outside one. */
internal val LocalWearRowTransformation = staticCompositionLocalOf<WearRowTransformation?> { null }

/**
 * The row's transformation, for a component that takes one as its `transformation` argument, or
 * null outside a transforming list.
 *
 * Taking it tells the list not to transform the row again. The component must then draw inside
 * [OutsideWearRow], so nested buttons aren't scaled a second time about their own centres.
 */
@Composable
internal fun wearRowTransformation(): SurfaceTransformation? {
  val row = LocalWearRowTransformation.current ?: return null
  DisposableEffect(row) {
    row.claim()
    onDispose { row.release() }
  }
  return row.surface
}

/** Draws [content] as no part of a list row: nothing in it takes the row's transformation. */
@Composable
internal fun OutsideWearRow(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalWearRowTransformation provides null, content = content)
}

// The rest of the catalog: each is the component the generated screen names, drawn by its own
// library, with parameters read from the catalog's declared properties.
//
// Callbacks are empty unless the renderer passes one. Selection rows take the renderer's two-way
// binding, so a row bound to a design flag ticks on the canvas; one with an authored literal does
// not move.

/** Wear's `CheckboxButton`: a full-width labelled row, not the mobile 20dp square. */
@Composable
internal fun WearCanvasCheckboxButton(
  checked: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
  onCheckedChange: (Boolean) -> Unit = {},
) {
  val transformation = wearRowTransformation()
  OutsideWearRow {
    CheckboxButton(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = modifier.fillMaxWidth(),
      enabled = enabled,
      label = label,
      secondaryLabel = secondaryLabel,
      transformation = transformation,
    )
  }
}

/** Wear's `RadioButton`, the selection twin of the checkbox row above. */
@Composable
internal fun WearCanvasRadioButton(
  selected: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
  onSelect: () -> Unit = {},
) {
  val transformation = wearRowTransformation()
  OutsideWearRow {
    RadioButton(
      selected = selected,
      onSelect = onSelect,
      modifier = modifier.fillMaxWidth(),
      enabled = enabled,
      label = label,
      secondaryLabel = secondaryLabel,
      transformation = transformation,
    )
  }
}

/**
 * Wear's `Stepper`: two buttons around a content slot. The range is guarded like
 * [WearCanvasSlider]'s.
 */
@Composable
internal fun WearCanvasStepper(
  value: Float,
  valueFrom: Float,
  valueTo: Float,
  steps: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Stepper(
    value = value,
    onValueChange = {},
    steps = steps.coerceAtLeast(0),
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    valueRange = if (valueTo > valueFrom) valueFrom..valueTo else 0f..1f,
    // Required parameters, and the library's own defaults for them — the catalog declares no
    // property for the icons, so inventing a pair here would be the canvas making something up.
    decreaseIcon = { SliderDefaults.DecreaseIcon() },
    increaseIcon = { SliderDefaults.IncreaseIcon() },
    content = { content() },
  )
}

/**
 * The four progress indicators the catalog declares, chosen by the `variant` property. `progress`
 * is a lambda in Wear's API; here it returns the fixed authored number.
 */
@Composable
internal fun WearCanvasProgressIndicator(
  variant: String,
  progress: Float,
  segments: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  indicatorColor: Color = Color.Unspecified,
  trackColor: Color = Color.Unspecified,
  /**
   * Set for `RemoteCurvedProgressIndicator`, which shares the `arc` variant but is determinate; see
   * [CurvedArc]. Null for Wear's own `ArcProgressIndicator`, which is indeterminate.
   */
  curved: CurvedArc? = null,
) {
  val clamped = progress.coerceIn(0f, 1f)
  // The design's two colours over the library's own: `Unspecified` is "Wear's default" to
  // `ProgressIndicatorDefaults.colors`, as it is in the generated call.
  val colors =
    ProgressIndicatorDefaults.colors(indicatorColor = indicatorColor, trackColor = trackColor)
  when (variant) {
    "linear" ->
      LinearProgressIndicator(
        progress = { clamped },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = colors,
      )
    "segmented-circular" ->
      SegmentedCircularProgressIndicator(
        // Wear's own parameter name. The catalog property is `segments`, which is the kit's word
        // for the axis; they are the same number.
        segmentCount = segments.coerceAtLeast(1),
        progress = { clamped },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
      )
    "arc" ->
      if (curved != null) {
        // `RemoteCurvedProgressIndicator`, which the export and the device preview draw as a
        // determinate arc. Drawing Wear's indeterminate `ArcProgressIndicator` for it showed an
        // animation that a one-frame PNG export at a frozen clock drew as nothing, and never at
        // the authored progress.
        CurvedArcProgress(
          progress = clamped,
          arc = curved,
          indicator = if (enabled) colors.indicatorBrush else colors.disabledIndicatorBrush,
          track = if (enabled) colors.trackBrush else colors.disabledTrackBrush,
          modifier = modifier,
        )
      } else {
        // Wear's own arc takes angles, not a progress fraction, and the wear-m3 export writes it,
        // so this draws the library's indeterminate arc rather than faking a determinate one.
        ArcProgressIndicator(modifier = modifier, colors = colors)
      }
    // `circular` and anything unrecognised. A default rather than a refusal: an unknown enum is a
    // document the validator should be complaining about, not a reason for the canvas to go blank.
    else ->
      CircularProgressIndicator(
        progress = { clamped },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
      )
  }
}

/**
 * Wear's real page indicators at a deterministic default page (one of four). Remote Compose's
 * `RemotePageIndicatorState` is target state the canvas cannot reproduce; the Browser Preview plays
 * it from the exported document.
 */
@Composable
internal fun WearCanvasPageIndicator(
  vertical: Boolean,
  modifier: Modifier = Modifier,
  pageCount: Int = 4,
  selectedPage: Int = 0,
  selectedColor: Color = Color.Unspecified,
  unselectedColor: Color = Color.Unspecified,
  backgroundColor: Color = Color.Unspecified,
) {
  val pages = pageCount.coerceAtLeast(1)
  val state = rememberPagerState(initialPage = selectedPage.coerceIn(0, pages - 1)) { pages }
  // `initialPage` is read once; a preview state that moves the selection moves the pager too.
  androidx.compose.runtime.LaunchedEffect(selectedPage, pages) {
    state.scrollToPage(selectedPage.coerceIn(0, pages - 1))
  }
  val selected = selectedColor.takeOrElse { PageIndicatorDefaults.selectedColor }
  val unselected = unselectedColor.takeOrElse { PageIndicatorDefaults.unselectedColor }
  val background = backgroundColor.takeOrElse { PageIndicatorDefaults.backgroundColor }
  if (vertical)
    VerticalPageIndicator(
      pagerState = state,
      modifier = modifier,
      selectedColor = selected,
      unselectedColor = unselected,
      backgroundColor = background,
    )
  else
    HorizontalPageIndicator(
      pagerState = state,
      modifier = modifier,
      selectedColor = selected,
      unselectedColor = unselected,
      backgroundColor = background,
    )
}

/**
 * `EdgeButton`, drawn with the library's own hugging curve at the `EdgeButtonSize` the document
 * names.
 */
@Composable
internal fun WearCanvasEdgeButton(
  size: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  containerColor: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  content: @Composable () -> Unit,
) {
  EdgeButton(
    onClick = {},
    // `EdgeButton` is the filled shape, and the generated call recolours it the same way.
    colors = ButtonDefaults.buttonColors(containerColor, contentColor),
    buttonSize =
      when (size) {
        "extra-small" -> EdgeButtonSize.ExtraSmall
        "medium" -> EdgeButtonSize.Medium
        "large" -> EdgeButtonSize.Large
        else -> EdgeButtonSize.Small
      },
    modifier = modifier,
    enabled = enabled,
    content = { content() },
  )
}

/**
 * `ButtonGroup`: a row that shares its spacing and swells the pressed child. Only the resting state
 * is drawn, since `animateWidth` needs per-child interaction sources.
 */
@Composable
internal fun WearCanvasButtonGroup(
  weights: List<Float?>,
  modifier: Modifier = Modifier,
  child: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
  // The catalog allows an empty group, but the library's measure throws on no children; draw
  // nothing at the group's width.
  if (weights.isEmpty()) {
    Box(modifier.fillMaxWidth())
    return
  }
  // The group takes the row's transformation as a whole and its buttons none; upstream warns that
  // both would apply it twice.
  val transformation = wearRowTransformation()
  OutsideWearRow {
    ButtonGroup(modifier = modifier.fillMaxWidth(), transformation = transformation) {
      // `ButtonGroupScope.weight` can only be written in this scope, so the group applies each
      // child's `weight` (as `Row`/`Column` read `layoutWeight`).
      weights.forEachIndexed { index, weight ->
        child(index, if (weight != null) Modifier.weight(weight) else Modifier)
      }
    }
  }
}

/** `IconButton` and `TextButton` share a variant vocabulary, so they share this mapping. */
@Composable
internal fun WearCanvasIconButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  containerColor: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  content: @Composable () -> Unit,
) {
  when (variant) {
    "filled" ->
      FilledIconButton(
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor, contentColor),
      ) {
        content()
      }
    // The port publishes no `FilledVariantIconButton`; upstream's is a `FilledIconButton` with the
    // variant palette, which is exactly what this draws.
    "filled-variant" ->
      FilledIconButton(
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.filledVariantIconButtonColors(containerColor, contentColor),
      ) {
        content()
      }
    "filled-tonal" ->
      FilledTonalIconButton(
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor, contentColor),
      ) {
        content()
      }
    "outlined" ->
      OutlinedIconButton(
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = contentColor),
      ) {
        content()
      }
    else ->
      IconButton(
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor, contentColor),
      ) {
        content()
      }
  }
}

/**
 * A theme role, resolved against **Wear's** colour scheme (what `MaterialTheme.colorScheme.<role>`
 * names in the generated screen), not the canvas's mobile scheme.
 *
 * `surface` and `surfaceContainerHighest` don't exist in Wear; they map to the role the Wear export
 * writes for them. Anything else unknown is `Unspecified` (the component's own default).
 */
@Composable
internal fun wearThemeColor(role: String): Color =
  with(MaterialTheme.colorScheme) {
    when (role) {
      "background" -> background
      "surface",
      "surfaceContainer" -> surfaceContainer
      "surfaceContainerLow" -> surfaceContainerLow
      "surfaceContainerHigh",
      "surfaceContainerHighest" -> surfaceContainerHigh
      "primary" -> primary
      "onPrimary" -> onPrimary
      "secondary" -> secondary
      "onSecondary" -> onSecondary
      "tertiary" -> tertiary
      "onTertiary" -> onTertiary
      "onSurface" -> onSurface
      "onSurfaceVariant" -> onSurfaceVariant
      "outlineVariant" -> outlineVariant
      "transparent" -> Color.Transparent
      else -> Color.Unspecified
    }
  }

@Composable
internal fun WearCanvasTextButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // Wear has one `TextButton` with per-variant colors, unlike its per-variant `IconButton`s.
  TextButton(
    onClick = {},
    modifier = modifier,
    enabled = enabled,
    colors =
      when (variant) {
        "filled" -> TextButtonDefaults.filledTextButtonColors()
        "filled-tonal" -> TextButtonDefaults.filledTonalTextButtonColors()
        "filled-variant" -> TextButtonDefaults.filledVariantTextButtonColors()
        "outlined" -> TextButtonDefaults.outlinedTextButtonColors()
        else -> TextButtonDefaults.textButtonColors()
      },
  ) {
    content()
  }
}

/**
 * Wear's dialogs, drawn in place. The top-level `AlertDialog` etc. are windows that scrim the whole
 * editor (see `UiBuilderRenderer`'s Material 3 dialog note), so this draws Wear's published
 * `*DialogContent`: the library's own surface, spacing, icon box and button row.
 *
 * A dialog draws where its node sits, and `visible` selects whether it is drawn rather than
 * animating it in.
 */
@Composable
internal fun WearCanvasAlertDialog(
  title: String,
  text: String,
  modifier: Modifier = Modifier,
  hasConfirm: Boolean,
  content: @Composable () -> Unit,
) {
  val titleSlot: @Composable () -> Unit = { Text(title) }
  val textSlot: (@Composable () -> Unit)? = text.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
  // Two overloads, since Wear's two-button alert is a distinct shape with non-null parameters. A
  // confirm selects the two-button shape, which defaults `dismissButton`, so the canvas matches the
  // watch. A dismiss alone has no overload; the export refuses it.
  if (hasConfirm) {
    AlertDialogContent(
      confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = {}) },
      dismissButton = { AlertDialogDefaults.DismissButton(onClick = {}) },
      modifier = modifier,
      title = titleSlot,
      text = textSlot,
      content = { item { content() } },
    )
  } else {
    AlertDialogContent(
      modifier = modifier,
      title = titleSlot,
      text = textSlot,
      content = { item { content() } },
    )
  }
}

@Composable
internal fun WearCanvasConfirmationDialog(
  text: String,
  variant: String,
  modifier: Modifier = Modifier,
) {
  // Read outside the lambda: `curvedText` is a `CurvedScope` block, not a `@Composable` one, so a
  // composable call inside it does not compile.
  val style = ConfirmationDialogDefaults.curvedTextStyle
  val curved: (CurvedScope.() -> Unit)? =
    text.takeIf { it.isNotEmpty() }?.let { { confirmationDialogCurvedText(it, style) } }
  when (variant) {
    "success" -> SuccessConfirmationDialogContent(modifier = modifier, curvedText = curved)
    "failure" -> FailureConfirmationDialogContent(modifier = modifier, curvedText = curved)
    // The generic overlay is the one an app brings its own glyph to, which is the content slot.
    // Empty here: the catalog declares no icon property for it, so there is nothing to draw.
    else -> ConfirmationDialogContent(modifier = modifier, curvedText = curved) {}
  }
}

@Composable
internal fun WearCanvasOpenOnPhoneDialog(text: String, modifier: Modifier = Modifier) {
  val style = OpenOnPhoneDialogDefaults.curvedTextStyle
  OpenOnPhoneDialogContent(
    curvedText =
      text.takeIf { it.isNotEmpty() }?.let { { openOnPhoneDialogCurvedText(it, style) } },
    durationMillis = OpenOnPhoneDialogDefaults.DurationMillis,
    modifier = modifier,
  ) {
    OpenOnPhoneDialogDefaults.Icon()
  }
}

/**
 * The two pickers, declared here and drawn per target: the port uses `kotlinx.datetime` on `wasmJs`
 * and `java.time` on the JVM.
 *
 * Both actuals parse the authored string and fall back to a FIXED date and time (never "now") so
 * renders are deterministic and an unparseable value doesn't crash the canvas.
 */
@Composable
internal expect fun WearCanvasDatePicker(initialDate: String, type: String, modifier: Modifier)

@Composable
internal expect fun WearCanvasTimePicker(initialTime: String, type: String, modifier: Modifier)

// Type roles: a design names a role (`titleMedium`) and the library needs a `TextStyle`. Material 3
// and Wear share role names but not scales, so roles resolve against Wear's own typography.

/** Wear's type scale, by the same fifteen role names Material 3 uses. */
@Composable
internal fun wearTextStyle(style: String): TextStyle =
  when (style) {
    "displayLarge" -> MaterialTheme.typography.displayLarge
    "displayMedium" -> MaterialTheme.typography.displayMedium
    "displaySmall" -> MaterialTheme.typography.displaySmall
    // Wear's scale has no `headline*`. Mapped onto the title roles rather than falling through to
    // the ambient style, which would silently draw body text where a design asked for a headline.
    "headlineLarge" -> MaterialTheme.typography.titleLarge
    "headlineMedium" -> MaterialTheme.typography.titleMedium
    "headlineSmall" -> MaterialTheme.typography.titleSmall
    "titleLarge" -> MaterialTheme.typography.titleLarge
    "titleMedium" -> MaterialTheme.typography.titleMedium
    "titleSmall" -> MaterialTheme.typography.titleSmall
    "bodyLarge" -> MaterialTheme.typography.bodyLarge
    "bodyMedium" -> MaterialTheme.typography.bodyMedium
    "bodySmall" -> MaterialTheme.typography.bodySmall
    "bodyExtraSmall" -> MaterialTheme.typography.bodyExtraSmall
    // The numeral roles `RemoteText` offers for a widget's figures — a time, a count, a distance.
    // Without them a numeral fell through to the ambient body style and drew at a quarter its size.
    "numeralExtraLarge" -> MaterialTheme.typography.numeralExtraLarge
    "numeralLarge" -> MaterialTheme.typography.numeralLarge
    "numeralMedium" -> MaterialTheme.typography.numeralMedium
    "numeralSmall" -> MaterialTheme.typography.numeralSmall
    "numeralExtraSmall" -> MaterialTheme.typography.numeralExtraSmall
    "labelLarge" -> MaterialTheme.typography.labelLarge
    "labelMedium" -> MaterialTheme.typography.labelMedium
    "labelSmall" -> MaterialTheme.typography.labelSmall
    else -> LocalTextStyle.current
  }

/** Wear's `Card`, in the four shapes the catalog declares. */
@Composable
internal fun WearCanvasCard(
  variant: String,
  modifier: Modifier = Modifier,
  containerColor: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  content: @Composable () -> Unit,
) {
  // The design's colours over the card's own, as the generated `CardDefaults.cardColors(…)` writes
  // them. `OutlinedCard` has no container to recolour, and the export refuses one there.
  val colors =
    if (variant == "outlined") CardDefaults.outlinedCardColors(contentColor = contentColor)
    else CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
  // **Both halves of the row transformation.** Outside a `TransformingLazyColumn` the argument is
  // omitted so the library's internal default applies; inside one the list's real transformation is
  // passed.
  val transformation = wearRowTransformation()
  // What is on the card is not the row: the card's transformation already carries it.
  val body: @Composable () -> Unit = { OutsideWearRow(content) }
  when (variant) {
    // The authored content is the `TitleCard`'s title; the body slot is left empty.
    "title" ->
      if (transformation == null) {
        TitleCard(onClick = {}, title = { body() }, modifier = modifier, colors = colors) {}
      } else {
        TitleCard(
          onClick = {},
          title = { body() },
          modifier = modifier,
          colors = colors,
          transformation = transformation,
        ) {}
      }
    // Same placement. `AppCard` also wants an app name, and the catalog declares no property for
    // one, so it is left empty rather than invented.
    "app" ->
      if (transformation == null) {
        AppCard(
          onClick = {},
          appName = {},
          title = { body() },
          modifier = modifier,
          colors = colors,
        ) {}
      } else {
        AppCard(
          onClick = {},
          appName = {},
          title = { body() },
          modifier = modifier,
          colors = colors,
          transformation = transformation,
        ) {}
      }
    "outlined" ->
      if (transformation == null) {
        OutlinedCard(onClick = {}, modifier = modifier, colors = colors) { body() }
      } else {
        OutlinedCard(
          onClick = {},
          modifier = modifier,
          colors = colors,
          transformation = transformation,
        ) {
          body()
        }
      }
    else ->
      if (transformation == null) {
        Card(onClick = {}, modifier = modifier, colors = colors) { body() }
      } else {
        Card(
          onClick = {},
          modifier = modifier,
          colors = colors,
          transformation = transformation,
        ) {
          body()
        }
      }
  }
}

/** Wear's `Button`, in the four shapes the catalog declares. */
@Composable
internal fun WearCanvasButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  containerColor: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  label: @Composable () -> Unit,
) {
  // The variant's own colours function with the design's overrides in it, as the export writes:
  // an unset colour is `Unspecified`, which each function reads as "the variant's default".
  val colors =
    when (variant) {
      "filled-tonal" -> ButtonDefaults.filledTonalButtonColors(containerColor, contentColor)
      "outlined" -> ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
      "child" -> ButtonDefaults.childButtonColors(contentColor = contentColor)
      else -> ButtonDefaults.buttonColors(containerColor, contentColor)
    }
  val slot: @Composable RowScope.() -> Unit = { OutsideWearRow(label) }
  val transformation = wearRowTransformation()
  when (variant) {
    "filled-tonal" ->
      if (transformation == null) {
        FilledTonalButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
        )
      } else {
        FilledTonalButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
          transformation = transformation,
        )
      }
    "outlined" ->
      if (transformation == null) {
        OutlinedButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
        )
      } else {
        OutlinedButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
          transformation = transformation,
        )
      }
    "child" ->
      if (transformation == null) {
        ChildButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
        )
      } else {
        ChildButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
          transformation = transformation,
        )
      }
    else ->
      if (transformation == null) {
        Button(onClick = {}, modifier = modifier, enabled = enabled, colors = colors, label = slot)
      } else {
        Button(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          colors = colors,
          label = slot,
          transformation = transformation,
        )
      }
  }
}

/**
 * `RemoteCurvedProgressIndicator`'s geometry, with its defaults: the track runs [sweepAngle]
 * degrees clockwise from [startAngle] (three o'clock is 0), inset from the smaller side by
 * [paddingDp] and half the stroke; the indicator covers `progress` of it, from the far end when
 * [reverseDirection].
 */
internal data class CurvedArc(
  val startAngle: Float = 135f,
  val sweepAngle: Float = 90f,
  val strokeWidthDp: Float = 8f,
  val paddingDp: Float = 2f,
  val reverseDirection: Boolean = false,
)

/** [CurvedArc]'s track and its determinate indicator, both with round caps, as the player draws. */
@Composable
internal fun CurvedArcProgress(
  progress: Float,
  arc: CurvedArc,
  indicator: androidx.compose.ui.graphics.Brush,
  track: androidx.compose.ui.graphics.Brush,
  modifier: Modifier = Modifier,
) {
  androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
    val stroke = arc.strokeWidthDp.dp.toPx()
    val radius = size.minDimension / 2f - arc.paddingDp.dp.toPx() - stroke / 2f
    if (radius <= 0f) return@Canvas
    val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
    val box = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
    val style =
      androidx.compose.ui.graphics.drawscope.Stroke(
        width = stroke,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
      )
    drawArc(track, arc.startAngle, arc.sweepAngle, false, topLeft, box, style = style)
    val sweep = arc.sweepAngle * progress
    if (sweep <= 0f) return@Canvas
    val start =
      if (arc.reverseDirection) arc.startAngle + arc.sweepAngle - sweep else arc.startAngle
    drawArc(indicator, start, sweep, false, topLeft, box, style = style)
  }
}
