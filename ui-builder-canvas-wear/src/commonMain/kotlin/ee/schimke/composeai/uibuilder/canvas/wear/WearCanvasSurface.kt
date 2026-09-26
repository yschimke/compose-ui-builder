package ee.schimke.composeai.uibuilder.canvas.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.ScreenStage
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.scrollAway
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderUnrolled
import ee.schimke.composeai.uibuilder.canvas.ReportContentMissing
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.bool
import ee.schimke.composeai.uibuilder.canvas.color
import ee.schimke.composeai.uibuilder.canvas.string
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.wearcmp.port.WearDeviceConfiguration
import kotlin.math.PI
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
internal fun UiBuilderDocument.wearScreenWidthDp(frame: UiBuilderFrameGeometry): Int =
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
internal fun WearScreenScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  frame: UiBuilderFrameGeometry,
  screenWidthDp: Int,
  edgeButton: @Composable (Modifier) -> Unit,
  /** The edge button's `size`, or null when the `edgeButton` slot is empty. */
  edgeButtonSize: String?,
  content: @Composable (Modifier) -> Unit,
) {
  val width = screenWidthDp.dp
  val hasEdgeButton = edgeButtonSize != null
  val unrolled = LocalUiBuilderUnrolled.current
  val screenPadding = wearScreenContentPadding(screenWidthDp, frame)
  // `ScreenScaffold`'s edge button takes the space BELOW its list rather than a place on top of it:
  // the scaffold extends the list's bottom content padding by the button's height, so the last row
  // settles above the button instead of under it. Without that the button sat over whatever row
  // the list ended on, and over the first screenful on a list that had not scrolled at all.
  val padding =
    if (edgeButtonSize != null && !unrolled) {
      screenPadding.withBottom(wearEdgeButtonReservedHeight(edgeButtonSize))
    } else {
      screenPadding
    }
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
      if (unrolled) {
        // The extent is deliberately not a viewport. Keep its ordinary inset so every item is
        // legible, including the first and last ones, while the frame pane below uses the real
        // lazy-list content-padding path.
        //
        // The edge button is the end of the scroll, so on the extent it follows the last row, in
        // the slot the scaffold keeps for it, and hugs the bottom cap. Overlaid on the extent it
        // covered the last rows of every list long enough to need one.
        Column(
          Modifier.fillMaxWidth()
            .padding(
              padding.withBottom(if (hasEdgeButton) width * WEAR_EDGE_BUTTON_INSET else null)
            )
        ) {
          content(Modifier.fillMaxWidth())
          if (hasEdgeButton) {
            edgeButton(
              Modifier.align(Alignment.CenterHorizontally)
                .padding(top = ScreenScaffoldDefaults.EdgeButtonSpacing)
            )
          }
        }
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
    // In a viewport the edge button is revealed by the scroll, as `ScreenScaffold` reveals it: it
    // grows in as the list reaches its end and shrinks away as the list scrolls back up. A frame
    // drawn at rest shows the list at its top, so a list longer than the screen shows no button,
    // and one that fits, or a screen that does not scroll, shows it. Drawing it unconditionally
    // put it over the first screenful of every long list.
    val edgeButtonRevealed by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    if (hasEdgeButton && !unrolled && edgeButtonRevealed) {
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
internal const val ROUND_SCREEN_FRAME = "frame/round-screen"

/** Measured off the reference card: inset 26dp at its top row, full width 26dp down. */
internal const val WEAR_CARD_CORNER_RADIUS_DP = 26f

/**
 * The subset of a Material 3 scheme a Wear design actually draws through, in Wear's own values.
 *
 * The canvas installs this one mobile `MaterialTheme` for its own chrome — the editor's menus, the
 * labels, the placeholders — and the Wear components drawn inside it read their own library's
 * tokens, which is why only the roles those components resolve through it are replaced. The rest
 * stay Material 3's dark scheme, because a colour this catalog has never drawn is a colour nobody
 * has measured, and inventing one would put a number in the picture that no watch produced.
 */
internal val WearDarkColorScheme =
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
 * The bottom content padding `ScreenScaffold` gives its list when it holds an edge button.
 *
 * The button's maximum height for its size, its own vertical padding either side, and the spacing
 * the scaffold keeps between it and the last row. The heights are the port's `EdgeButtonSize`
 * values (46, 56, 70 and 96 dp), which the library keeps `internal`; the two paddings are read from
 * the library, not restated.
 */
private fun wearEdgeButtonReservedHeight(size: String): Dp {
  val height =
    when (size) {
      "extra-small" -> 46.dp
      "medium" -> 70.dp
      "large" -> 96.dp
      else -> 56.dp
    }
  return height +
    ScreenScaffoldDefaults.EdgeButtonMinSpacing * 2 +
    ScreenScaffoldDefaults.EdgeButtonSpacing
}

/** These padding values with [bottom] in place of their own; null keeps the bottom as it is. */
private fun PaddingValues.withBottom(bottom: Dp?): PaddingValues =
  PaddingValues(
    start = calculateStartPadding(LayoutDirection.Ltr),
    top = calculateTopPadding(),
    end = calculateEndPadding(LayoutDirection.Ltr),
    bottom = bottom ?: calculateBottomPadding(),
  )

/**
 * The Wear components this catalog publishes and the browser cannot draw, as ids.
 *
 * Derived from `WearScreenCodeExporter`'s own constants rather than listed again: the generator and
 * the canvas have to agree about which ids these are, and two lists is two chances not to.
 */
internal val WEAR_NATIVE_ONLY: Set<String> = WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS

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
