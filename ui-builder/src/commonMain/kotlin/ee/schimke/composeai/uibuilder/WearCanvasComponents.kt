package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.ArcProgressIndicator
import androidx.wear.compose.material3.Button
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
import androidx.wear.compose.material3.IconButton
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
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText

/**
 * Wear components drawn by Wear Compose, on the Wasm canvas.
 *
 * ## What changed, and why this file exists
 *
 * For most of this module's life the canvas drew a Wear design with Material 3 lookalikes: a Wear
 * card was literally a mobile Material card, a `ListHeader` was a `Box` with a `Text` centred in
 * 48dp, and a `TransformingLazyColumn` was a plain `Column`. The reason given — in
 * [wearScreenStandIn]'s KDoc, in `WearCanvasStandInTest` and in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` — was that `androidx.wear.compose:compose-material3` is
 * an Android AAR that Compose Multiplatform for Wasm cannot link, so a Wear component was either
 * drawn as something else or not drawn at all.
 *
 * The first half of that is still true and checkable: that artifact's Gradle module metadata
 * carries exactly two variants, `releaseVariantReleaseApiPublication` and its runtime twin, and one
 * artifact, an `.aar`. It is not a multiplatform publication and there is no `wasmJs` variant to
 * resolve.
 *
 * The conclusion did not follow. The canvas does not have to link *that* artifact. The Wear kit
 * repository maintains a Compose Multiplatform port of the same source — `ee.schimke.wearcmp:*` —
 * which keeps the `androidx.wear.compose.material3` package names and publishes `jvm` and `wasmJs`
 * variants. Those are exactly this module's two targets, and it already renders its whole kit
 * through that port on a desktop lane. `settings.gradle.kts` names where the port is published and
 * `gradle/libs.versions.toml` pins the version.
 *
 * So every import above is the real component. `SwitchButton` here is Wear's `SwitchButton`,
 * measured and drawn by its own code, not an impression of it assembled from Material 3 pieces at
 * sizes read off a screenshot. That was the specific thing the old rule existed to prevent, and it
 * is no longer the price of drawing a Wear screen.
 *
 * ## What this is still not
 *
 * The port is not the kit rendition. The published Wear renders — and the device-preview lane —
 * draw with the genuine AndroidX library under Robolectric through that platform's own native
 * bundle, and that stays the thing this repository measures against the kit. This canvas is the
 * editing surface and the first-line preview; the two lanes disagreeing about a component is a
 * finding about the port, not about the design.
 */
@Composable
internal fun WearCanvasListHeader(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  // `maxLines` and `overflow` are the label's rather than the header's: upstream's `ListHeader`
  // takes a content lambda, so the string is this `Text` and the truncation is its argument. Both
  // were declared on the component and read by nobody, which meant a header a design had clipped to
  // one line drew as many as it wrapped to.
  ListHeader(modifier = modifier) { Text(text, maxLines = maxLines, overflow = overflow) }
}

/** Wear's own sub-header, replacing a `Text` that was styled to look like one. */
@Composable
internal fun WearCanvasListSubHeader(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  ListSubHeader(modifier = modifier) { Text(text, maxLines = maxLines, overflow = overflow) }
}

/**
 * Wear's `SwitchButton` — a component with no Material 3 counterpart, and therefore one the canvas
 * previously could not draw at any fidelity. `google-home-wear` uses eleven of them.
 *
 * `onCheckedChange` is empty rather than wired to the document: the canvas draws a design, it does
 * not run it, so the toggle reflects the authored `checked` and nothing moves when it is clicked.
 * That matches how every other stateful component behaves here.
 */
@Composable
internal fun WearCanvasSwitchButton(
  checked: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
) {
  SwitchButton(
    checked = checked,
    onCheckedChange = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    label = label,
    secondaryLabel = secondaryLabel,
  )
}

/**
 * Wear's `Slider`. Also previously undrawable, and also in `google-home-wear` three times.
 *
 * `valueRange` is the authored `valueFrom..valueTo` rather than a range derived from `steps`,
 * because the document carries both and the two can legitimately differ — a brightness row running
 * 0..100 in 10 steps is not the same component as one running 0..11.
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
    // Guarded rather than trusted: `Slider` requires a non-empty range and the canvas must not
    // crash on a half-authored document — a canvas that throws cannot draw the Issues panel that
    // would explain why.
    valueRange = if (valueTo > valueFrom) valueFrom..valueTo else 0f..1f,
    segmented = segmented,
  )
}

/**
 * The real `TransformingLazyColumn`, with the transformation the canvas used to leave out.
 *
 * The old branch was a `Column`, and its comment argued the case honestly: `TransformingLazyColumn`
 * scales and fades its rows against the round display through `SurfaceTransformation` and
 * `Modifier.transformedHeight`, "neither of which exists off Android", so approximating the curve
 * by hand would draw a different wrong picture and imply it was right. Both now exist here —
 * `androidx.wear.compose.material3.lazy.transformedHeight` is imported above — so the rows scale
 * and fade because the library does it, which is the only version of this worth drawing.
 *
 * Items are passed as a count plus an index lambda rather than a list of composables so the lazy
 * column stays lazy: `TransformingLazyColumnScope.items` composes what is on screen.
 */
@Composable
internal fun WearCanvasTransformingLazyColumn(
  itemCount: Int,
  verticalSpacingDp: Float,
  modifier: Modifier = Modifier,
  /**
   * Whether each row carries `transformedHeight` and `SurfaceTransformation`.
   *
   * The design's `transformation` property, which the exporter has always honoured and the canvas
   * never read — so a design that asked for no transformation still got one on the canvas, and the
   * generated screen did not. `none` is a real choice rather than a corner: it is what a list drawn
   * for a comparison against a stitched capture wants, since `ScrollMode.LONG` turns the
   * transformation off in order to stitch.
   */
  transformation: Boolean = true,
  item: @Composable (Int, Modifier) -> Unit,
) {
  val provided = LocalWearScreenListState.current
  val remembered = rememberTransformingLazyColumnState()
  // The scaffold's state when this list is inside one, so the clock and the indicator can see it.
  val state = provided ?: remembered
  val spec = rememberTransformationSpec()
  TransformingLazyColumn(
    state = state,
    // `ScreenScaffold` gives this to its list; it is content padding, not padding around a shorter
    // viewport. That distinction is visible at either end of a round screen: a lazy list needs the
    // room in its scroll range so its first and last rows can settle clear of the bezel.
    contentPadding = LocalWearScreenContentPadding.current,
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(verticalSpacingDp.dp),
  ) {
    items(itemCount) { index ->
      if (!transformation) {
        // The design asked for none, which is a real choice: a stitched `ScrollMode.LONG` capture
        // turns the transformation off in order to stitch, so a list drawn against one wants the
        // same. Both halves are off — the layout half and the drawing half below.
        item(
          index,
          Modifier.fillMaxWidth()
            .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding),
        )
      } else {
        // **Both halves of the transformation.** `transformedHeight` is the layout half — the row
        // gets shorter as it approaches the bezel — and `SurfaceTransformation` is the drawing
        // half: the scale and the fade, which the component applies to itself. Passing only the
        // first is what made the canvas's Wear list look like a plain column of full-size rows
        // while the generated screen beside it scaled and faded. The library's own components take
        // the second as a parameter, so it travels to them by a local rather than by rewriting
        // every call.
        CompositionLocalProvider(
          LocalWearSurfaceTransformation provides SurfaceTransformation(spec)
        ) {
          item(
            index,
            Modifier.fillMaxWidth()
              .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
              .transformedHeight(this, spec),
          )
        }
      }
    }
  }
}

/**
 * The list state the enclosing Wear screen scaffold owns, or null outside one.
 *
 * `ScreenScaffold` exists to hold one list, and the parts of a Wear screen that are *about* that
 * list — the clock scrolling away, the scroll indicator — read its state. The canvas draws the
 * scaffold as a stand-in, so the stand-in owns the state and shares it here rather than letting the
 * list remember one of its own that nothing else can see.
 */
internal val LocalWearScreenListState =
  staticCompositionLocalOf<androidx.wear.compose.foundation.lazy.TransformingLazyColumnState?> {
    null
  }

/** The `ScreenScaffold` content padding the enclosing transforming list must consume. */
internal val LocalWearScreenContentPadding = staticCompositionLocalOf { PaddingValues() }

/**
 * The row transformation the enclosing Wear list is applying, or null outside one.
 *
 * The canvas dispatches a component by id, so a component cannot be handed the transformation its
 * parent would pass it in generated code. This is that argument, carried the way a
 * `CompositionLocal` carries anything else the tree knows and the call site does not.
 */
internal val LocalWearSurfaceTransformation =
  staticCompositionLocalOf<SurfaceTransformation?> { null }

// ── The rest of the catalog
// ───────────────────────────────────────────────────────────────────────
//
// Everything below was a dashed placeholder until the port arrived. None of it is a lookalike: each
// is the component the generated screen names, drawn by the library that defines it. The parameters
// are read off the catalog's own declared properties, so a component drawing here and a component
// exporting there are answering from the same contract.
//
// Callbacks are empty throughout. The canvas draws a design, it does not run one — a control shows
// its authored state and nothing moves when it is clicked, which is how every other stateful
// component on this surface behaves.

/** Wear's `CheckboxButton`: a full-width labelled row, not the mobile 20dp square. */
@Composable
internal fun WearCanvasCheckboxButton(
  checked: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
) {
  CheckboxButton(
    checked = checked,
    onCheckedChange = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    label = label,
    secondaryLabel = secondaryLabel,
  )
}

/** Wear's `RadioButton`, the selection twin of the checkbox row above. */
@Composable
internal fun WearCanvasRadioButton(
  selected: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable RowScope.() -> Unit,
  secondaryLabel: (@Composable RowScope.() -> Unit)? = null,
) {
  RadioButton(
    selected = selected,
    onSelect = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    label = label,
    secondaryLabel = secondaryLabel,
  )
}

/**
 * Wear's `Stepper`, which is a slider's discrete sibling: two buttons around a content slot.
 *
 * The range is guarded the way [WearCanvasSlider]'s is, and for the same reason — a half-authored
 * document must not take the canvas down with it.
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
 * The four progress indicators the catalog declares, chosen by the `variant` property.
 *
 * `progress` is a lambda in Wear's API rather than a value — it is read during draw so an animation
 * does not recompose the caller. The canvas has a fixed authored number, so the lambda is constant.
 */
@Composable
internal fun WearCanvasProgressIndicator(
  variant: String,
  progress: Float,
  segments: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val clamped = progress.coerceIn(0f, 1f)
  when (variant) {
    "linear" ->
      LinearProgressIndicator(
        progress = { clamped },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
      )
    "segmented-circular" ->
      SegmentedCircularProgressIndicator(
        // Wear's own parameter name. The catalog property is `segments`, which is the kit's word
        // for the axis; they are the same number.
        segmentCount = segments.coerceAtLeast(1),
        progress = { clamped },
        modifier = modifier,
        enabled = enabled,
      )
    // `ArcProgressIndicator` takes start and end angles rather than a progress fraction, so the
    // authored `progress` has nowhere to go and this draws the library's indeterminate arc. Called
    // out rather than faked: sweeping the end angle by `clamped` myself would be a replica of a
    // determinate arc that upstream does not offer at this call site.
    "arc" -> ArcProgressIndicator(modifier = modifier)
    // `circular` and anything unrecognised. A default rather than a refusal: an unknown enum is a
    // document the validator should be complaining about, not a reason for the canvas to go blank.
    else ->
      CircularProgressIndicator(progress = { clamped }, modifier = modifier, enabled = enabled)
  }
}

/**
 * `EdgeButton`, drawn with its own shape.
 *
 * `UI_BUILDER_WEAR_SCREEN.md` recorded this one as "placed, not shaped": the slot generated a real
 * `EdgeButton` while the canvas drew a borrowed flat button at the bottom cap, because the shape
 * comes from the screen and the canvas had no way to ask for it. It does now — the button's hugging
 * curve is the library's, at the `EdgeButtonSize` the document names.
 */
@Composable
internal fun WearCanvasEdgeButton(
  size: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  EdgeButton(
    onClick = {},
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
 * `ButtonGroup`: a row that shares its spacing, and that swells the pressed child.
 *
 * Only the spacing shows here. The expansion is `ButtonGroupScope.animateWidth`, which needs an
 * interaction source per child to have anything to react to, and a canvas that is never pressed has
 * none — so this draws the group's resting state, which is the state a design describes.
 */
@Composable
internal fun WearCanvasButtonGroup(
  childCount: Int,
  modifier: Modifier = Modifier,
  child: @Composable (Int) -> Unit,
) {
  ButtonGroup(modifier = modifier.fillMaxWidth()) { repeat(childCount) { index -> child(index) } }
}

/** `IconButton` and `TextButton` share a variant vocabulary, so they share this mapping. */
@Composable
internal fun WearCanvasIconButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  when (variant) {
    "filled" -> FilledIconButton(onClick = {}, modifier = modifier, enabled = enabled) { content() }
    "filled-tonal" ->
      FilledTonalIconButton(onClick = {}, modifier = modifier, enabled = enabled) { content() }
    "outlined" ->
      OutlinedIconButton(onClick = {}, modifier = modifier, enabled = enabled) { content() }
    else -> IconButton(onClick = {}, modifier = modifier, enabled = enabled) { content() }
  }
}

@Composable
internal fun WearCanvasTextButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // One composable, four palettes. Unlike `IconButton`, Wear does not publish a `TextButton` per
  // variant — the variant IS the colors, which is why this reads differently from the function
  // above despite the two sharing a vocabulary in the catalog.
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
 * Wear's dialogs, drawn in place.
 *
 * The catalog is right that these are "a screen *state*, not a place in the layout" — the top-level
 * `AlertDialog` and friends take a `visible` flag and draw over the whole display. A canvas cannot
 * use those: `UiBuilderRenderer`'s own note on the Material 3 dialog explains why a real `Dialog`
 * is unusable here, and it applies unchanged — it is a window, it leaves the composition's layout
 * and scrims everything behind it, including the editor.
 *
 * Wear publishes the inside of each dialog separately, as `*DialogContent`, and that is what draws
 * here. It is the library's own surface, spacing, icon box and button row — not a replica assembled
 * to look like one. The kit's own stickers are captured the same way for the same reason.
 *
 * The consequence is that a dialog draws where its node sits rather than over the screen, and that
 * `visible` selects whether it is drawn at all rather than animating it in.
 */
@Composable
internal fun WearCanvasAlertDialog(
  title: String,
  text: String,
  modifier: Modifier = Modifier,
  hasConfirm: Boolean,
  hasDismiss: Boolean,
  content: @Composable () -> Unit,
) {
  val titleSlot: @Composable () -> Unit = { Text(title) }
  val textSlot: (@Composable () -> Unit)? = text.takeIf { it.isNotEmpty() }?.let { { Text(it) } }
  // Two overloads, not one call with nullable buttons: Wear's two-button alert is a distinct shape
  // from its button-less one and its parameters are non-null, so which dialog is drawn is decided
  // here rather than by passing null. Both slots or neither — a single confirm would silently draw
  // the wrong one.
  if (hasConfirm && hasDismiss) {
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
 * The two pickers, declared here and drawn per target.
 *
 * `DatePicker` takes a `LocalDate` and `TimePicker` a `LocalTime`, and the port spells those
 * differently on each target: `kotlinx.datetime` for `wasmJs`, `java.time` for the JVM. That is the
 * port's own `expect`/`actual` showing through rather than a difference this module invented, and
 * it is why these two are the only Wear components here that cannot be written once.
 *
 * Both actuals parse the authored string and fall back to a FIXED date and time on anything
 * unparseable — never to "now". A picker showing the current moment would give this design a
 * different render every minute, which is the same reason the Wear screen scaffold freezes its
 * clock text. An unparseable value is a validator's complaint; a canvas that threw on one could not
 * draw the Issues panel that would explain it.
 */
@Composable
internal expect fun WearCanvasDatePicker(initialDate: String, type: String, modifier: Modifier)

@Composable
internal expect fun WearCanvasTimePicker(initialTime: String, type: String, modifier: Modifier)

// ── The type scale
// ────────────────────────────────────────────────────────────────────────
//
// Wear's text, card and button are their own components here, drawn by Wear Compose through the
// port. What the canvas still does for them is resolve a type ROLE, because a design names one
// (`titleMedium`) and the library needs a `TextStyle`.
//
// Material 3 and Wear publish the same fifteen role names and different scales behind them, so a
// `titleMedium` resolved against the wrong theme draws *a* title and not *this* title — a silently
// wrong size, in the surface an author reads sizes off. Below it resolves against Wear's own
// typography.

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
  content: @Composable () -> Unit,
) {
  // **Both halves of the row transformation.** Outside a `TransformingLazyColumn` there is none and
  // the library's own default applies — it is internal, so the honest way to leave it in place is
  // not to pass the argument. Inside one, the list provides the real thing and it is passed here,
  // which is what makes a card scale and fade as it approaches the bezel instead of only getting
  // shorter.
  val transformation = LocalWearSurfaceTransformation.current
  when (variant) {
    // The authored content is the card's TITLE, and the body slot is left empty. A `TitleCard` has
    // both and the catalog declares one, so this puts it in the slot that is the card's primary
    // line rather than under an empty heading.
    "title" ->
      if (transformation == null) {
        TitleCard(onClick = {}, title = { content() }, modifier = modifier) {}
      } else {
        TitleCard(
          onClick = {},
          title = { content() },
          modifier = modifier,
          transformation = transformation,
        ) {}
      }
    // Same placement. `AppCard` also wants an app name, and the catalog declares no property for
    // one, so it is left empty rather than invented.
    "app" ->
      if (transformation == null) {
        AppCard(onClick = {}, appName = {}, title = { content() }, modifier = modifier) {}
      } else {
        AppCard(
          onClick = {},
          appName = {},
          title = { content() },
          modifier = modifier,
          transformation = transformation,
        ) {}
      }
    "outlined" ->
      if (transformation == null) {
        OutlinedCard(onClick = {}, modifier = modifier) { content() }
      } else {
        OutlinedCard(onClick = {}, modifier = modifier, transformation = transformation) {
          content()
        }
      }
    else ->
      if (transformation == null) {
        Card(onClick = {}, modifier = modifier) { content() }
      } else {
        Card(onClick = {}, modifier = modifier, transformation = transformation) { content() }
      }
  }
}

/** Wear's `Button`, in the four shapes the catalog declares. */
@Composable
internal fun WearCanvasButton(
  variant: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  label: @Composable () -> Unit,
) {
  val slot: @Composable RowScope.() -> Unit = { label() }
  val transformation = LocalWearSurfaceTransformation.current
  when (variant) {
    "filled-tonal" ->
      if (transformation == null) {
        FilledTonalButton(onClick = {}, modifier = modifier, enabled = enabled, label = slot)
      } else {
        FilledTonalButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          label = slot,
          transformation = transformation,
        )
      }
    "outlined" ->
      if (transformation == null) {
        OutlinedButton(onClick = {}, modifier = modifier, enabled = enabled, label = slot)
      } else {
        OutlinedButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          label = slot,
          transformation = transformation,
        )
      }
    "child" ->
      if (transformation == null) {
        ChildButton(onClick = {}, modifier = modifier, enabled = enabled, label = slot)
      } else {
        ChildButton(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          label = slot,
          transformation = transformation,
        )
      }
    else ->
      if (transformation == null) {
        Button(onClick = {}, modifier = modifier, enabled = enabled, label = slot)
      } else {
        Button(
          onClick = {},
          modifier = modifier,
          enabled = enabled,
          label = slot,
          transformation = transformation,
        )
      }
  }
}
