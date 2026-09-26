@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.codegen.codeColor
import ee.schimke.composeai.uibuilder.codegen.highlightKotlin
import ee.schimke.composeai.uibuilder.codegen.rememberCodePaneSyntaxTheme
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import kotlin.math.roundToInt

/**
 * The Kotlin the Compose export would write for the document on the canvas.
 *
 * ## Why it is here rather than behind the export button
 *
 * The builder's proposition is that a design *is* code. Until this pane the only way to read the
 * code a design produced was to run an export and open the artifact, which is a round trip long
 * enough that nobody made it after a single edit — so "what did dropping that Column do to the
 * source" was, in practice, unanswerable.
 *
 * ## Why it shows refusals in the same place
 *
 * [EditorGeneratedCode.Refused] is not an error state of this pane, it is the pane's other answer.
 * A design the export cannot express has no source to show, and the reasons are what a designer
 * needs in order to make one — putting them behind a different tab would mean the pane silently
 * showed nothing whenever it mattered most.
 *
 * The text is selectable and not editable: it is generated, and a pane that let you type into it
 * would be offering an edit the next keystroke on the canvas throws away.
 */
@Composable
internal fun GeneratedCodePane(
  code: EditorGeneratedCode,
  /**
   * What produced the source, because two generators feed this pane.
   *
   * A widget's Kotlin is not a Compose export and saying so would be wrong twice over: it is Remote
   * Compose, and it goes out as a `WearWidgetDocument` rather than into that export's package.
   */
  caption: String,
  modifier: Modifier = Modifier,
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
      when (code) {
        is EditorGeneratedCode.Source -> {
          Text(
            caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
          val vertical = rememberScrollState()
          val horizontal = rememberScrollState()
          val syntaxTheme = rememberCodePaneSyntaxTheme()
          // Tokenizing is keyed on the source, so an edit elsewhere on the canvas — a selection, a
          // scroll, a drag over the drop target — recomposes this pane without re-running it.
          val highlighted =
            remember(code.kotlin, syntaxTheme) { highlightKotlin(code.kotlin, syntaxTheme) }
          SelectionContainer(Modifier.padding(top = 8.dp)) {
            Text(
              highlighted,
              Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal),
              // The palette's own foreground rather than `onSurface`: whatever the highlighter did
              // not claim is still code, and two sources for the one colour would show up as the
              // unstyled runs sitting a shade off the styled ones.
              color = syntaxTheme.codeColor(),
              // Generated Kotlin is aligned by column, so a proportional face would misreport the
              // indentation the export actually writes.
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              softWrap = false,
            )
          }
        }
        is EditorGeneratedCode.Refused -> {
          Text(
            "No Compose source · the export would refuse this design",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
          )
          // Selectable for the same reason the source is: a refusal is the pane's other answer,
          // and it is no more quotable than the Kotlin if it can only be read.
          SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
              itemsIndexed(code.reasons) { _, reason ->
                Text(
                  reason,
                  Modifier.padding(bottom = 8.dp),
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The same design, drawn by real Compose on the host instead of by this browser.
 *
 * ## Why the editor shows two renderers at once
 *
 * The Wasm canvas is immediate and costs the server nothing, and it cannot answer "what does this
 * look like on Android" — platform text metrics, the device frames the render lane knows, the
 * Robolectric-backed lane. Side by side is deliberate rather than a toggle: a difference between
 * the two renderers is a thing a designer needs to *see*, and one that replaced the other would
 * hide exactly that.
 *
 * ## Live where the host can stream, a still where it cannot
 *
 * The compile lane has always stood a live session up behind the still — the same daemon, the same
 * classes, and on a catalog whose native backend is Android that daemon is Robolectric-backed
 * Android. This pane now opens it: the frame is pushed rather than fetched, and a tap on it is
 * dispatched into the real composition rather than resolved against a map of rectangles. That is
 * what makes this the pane you can *use* the screen in, and the reason it is the only pane in the
 * workspace that leaves the browser.
 *
 * The still does not go away, because it is what there is until the first frame lands and what
 * there is when a host has no live backend for the design's mode. Two states, one pane, and the
 * label says which you are looking at rather than leaving you to guess from whether taps work.
 *
 * ## Selection belongs to the still
 *
 * A still is a picture with a map of node boxes over it, so clicking it selects a layer. A live
 * session is the screen, so clicking it *is* the click — a tap that both selected a node and
 * pressed the button under it would be two answers to one gesture, and the one a designer wants
 * here is the button. The layers panel still selects, on either.
 *
 * ## Refusals, again, in the same place
 *
 * A design the generator cannot express has no native render, and the reasons are the actionable
 * half — the same rule the code pane follows, and the same list, because it is the same gate. A
 * transport failure says something different and says it separately: try again, versus fix the
 * design.
 */
@Composable
internal fun NativeRenderPane(
  render: UiBuilderNativeRender?,
  pending: Boolean,
  /** The live session, or null where the host opened none — see the function doc. */
  stream: UiBuilderNativeStream? = null,
  /** The catalog's own word for which daemon draws this, so the label can name it. */
  backend: String = "",
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val liveFrame = stream?.frame
  // Read once rather than through the interface at each use: it is an open property on a host's
  // own implementation, so two reads could disagree and the second would be the one drawn.
  val liveFailure = stream?.failure
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      Text(
        nativePaneCaption(
          live = liveFrame != null,
          // A stream that has failed is not connecting. Left true it claimed "connecting to
          // Android…" over a still that had given up on the live lane minutes ago.
          connecting = stream != null && liveFailure == null,
          backend = backend,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      when {
        // The live frame wins over everything below it the moment one lands, including over a
        // pending re-render: a stream that is painting is the most current thing this pane has,
        // and dropping back to "Compiling this design…" because a still was re-requested would
        // blank a working screen on every keystroke.
        liveFrame != null ->
          LiveNativeFrame(
            frame = liveFrame,
            onInput = { stream.send(it) },
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          )
        // A stream that failed with no still to fall back to. Gated on the still being absent
        // too: the live lane is the *optional* half of this pane, and a full live-seat budget or a
        // grant without live scope would otherwise blank a compiled frame that arrived perfectly
        // well. Said separately from a compile failure because the two are fixed differently.
        liveFailure != null && render?.image == null ->
          SelectionContainer {
            Text(
              liveFailure,
              Modifier.padding(top = 12.dp),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        pending && render == null ->
          Text(
            "Compiling this design…",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        render == null ->
          Text(
            "Not rendered yet.",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        // The host's own words about why it could not draw this, so they are selectable: what a
        // compile failure says is the whole content of the report somebody is about to file.
        render.failure != null ->
          SelectionContainer {
            Text(
              render.failure,
              Modifier.padding(top = 12.dp),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        render.refusals.isNotEmpty() -> {
          Text(
            "No native render · the generator refuses this design",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
          )
          SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
              itemsIndexed(render.refusals) { _, reason ->
                Text(
                  reason,
                  Modifier.padding(bottom = 8.dp),
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
        render.image != null ->
          NativeRenderFrame(
            image = render.image,
            nodeBounds = render.nodeBounds,
            selectedNodeId = selectedNodeId,
            onNodeSelected = onNodeSelected,
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          )
        else ->
          Text(
            "The host compiled this design and returned no frame.",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
      }
    }
  }
}

/**
 * What the native pane calls itself, which is the one place a person learns whether taps will work.
 *
 * Three states rather than one label with a spinner: a still is a picture, a stream that has not
 * painted yet is a promise, and a painting stream is the screen. Naming the backend where the
 * catalog declared one ("live on Android") is the point of this whole pane — "native" is a claim
 * about a toolkit, and Android is which one.
 */
internal fun nativePaneCaption(live: Boolean, connecting: Boolean, backend: String): String {
  val where =
    when (backend) {
      UiBuilderPreviewSurfaces.BACKEND_ANDROID -> "Android"
      UiBuilderPreviewSurfaces.BACKEND_DESKTOP -> "desktop"
      else -> "the host"
    }
  return when {
    live -> "Native · live on $where · taps reach the screen"
    connecting -> "Native · connecting to $where…"
    else -> "Native render · compiled on the host"
  }
}

/**
 * A live native frame, and the gestures that reach the composition drawing it.
 *
 * ## One factor, inverted
 *
 * The frame arrives in the daemon's own render pixels and is drawn scaled to fit this pane, so
 * there is exactly one factor between the two spaces — `displayed / image` — and a press is turned
 * back into image pixels by dividing by it. The same arithmetic [NativeRenderFrame] does for its
 * node boxes, in the other direction, and for the same reason: the host cannot be told about a
 * layout nobody on it can see.
 *
 * ## Why a tap is not a down and an up
 *
 * The daemon has a click fast-path that renders *between* press and release, so a batched
 * down-then-up can race `Modifier.clickable` and land as nothing. A press that never moves is
 * therefore sent as one `click` once it lifts, and the `pointerDown` is only sent when a drag
 * actually starts — which is also what makes a drag a drag rather than a click followed by moves.
 */
@Composable
private fun LiveNativeFrame(
  frame: UiBuilderNativeFrame,
  onInput: (UiBuilderNativeInput) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val imageWidth = frame.image.width.toFloat()
    val imageHeight = frame.image.height.toFloat()
    val density = LocalDensity.current
    val scale =
      minOf(
          with(density) { maxWidth.toPx() } / imageWidth,
          with(density) { maxHeight.toPx() } / imageHeight,
        )
        .coerceAtMost(1f)
    val displayedWidth = with(density) { (imageWidth * scale).toDp() }
    val displayedHeight = with(density) { (imageHeight * scale).toDp() }
    Image(
      bitmap = frame.image,
      contentDescription = "Live native preview",
      modifier =
        Modifier.size(displayedWidth, displayedHeight)
          // Keyed on the scale as well as the frame's size: the handler closes over the factor it
          // inverts, and a pane resized under a running stream would otherwise keep sending
          // coordinates in the old one.
          .pointerInput(imageWidth, imageHeight, scale) {
            awaitPointerEventScope {
              while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun pixels(offset: Offset): Pair<Int, Int> =
                  (offset.x / scale).roundToInt().coerceIn(0, imageWidth.toInt() - 1) to
                    (offset.y / scale).roundToInt().coerceIn(0, imageHeight.toInt() - 1)
                var dragging = false
                var last = down.position
                while (true) {
                  val event = awaitPointerEvent()
                  val change = event.changes.firstOrNull { it.id == down.id } ?: break
                  if (change.pressed) {
                    // The threshold is the platform's own, so a press that wobbles by a pixel on
                    // the way up is still a tap — which is what a mouse user means and what a
                    // touch user cannot avoid.
                    if (
                      !dragging &&
                        (change.position - down.position).getDistance() >
                          viewConfiguration.touchSlop
                    ) {
                      dragging = true
                      val (x, y) = pixels(down.position)
                      onInput(UiBuilderNativeInput("pointerDown", x, y))
                    }
                    if (dragging && change.position != last) {
                      last = change.position
                      val (x, y) = pixels(change.position)
                      onInput(UiBuilderNativeInput("pointerMove", x, y))
                    }
                    change.consume()
                  } else {
                    val (x, y) = pixels(change.position)
                    onInput(UiBuilderNativeInput(if (dragging) "pointerUp" else "click", x, y))
                    change.consume()
                    break
                  }
                }
              }
            }
          }
          // A Wear pane has no browser scroll surface: a wheel over it is a turn of the rotating
          // side button. The stream protocol and daemon call this `rotaryScroll`; previously this
          // pane only forwarded presses, so native Compose never received the wheel at all.
          .pointerInput(imageWidth, imageHeight, scale) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                onInput(
                  UiBuilderNativeInput(
                    kind = "rotaryScroll",
                    pixelX =
                      (change.position.x / scale).roundToInt().coerceIn(0, imageWidth.toInt() - 1),
                    pixelY =
                      (change.position.y / scale).roundToInt().coerceIn(0, imageHeight.toInt() - 1),
                    // Browser wheel deltas are CSS-pixel motion; the daemon's rotary input is
                    // device-pixel motion. Keep the same half-pixel conversion the Wasm device
                    // scene uses so moving from Preview to Native does not double the RSB speed.
                    scrollDeltaY = change.scrollDelta.y * 0.5f,
                  )
                )
                change.consume()
              }
            }
          },
    )
  }
}

/**
 * The design with the editor taken off it: the same renderer, every device, no compile.
 *
 * ## Why this pane is not the native one
 *
 * It draws exactly what the canvas beside it draws — the same Wasm Compose, the same document, the
 * same pixels — and that is deliberate. The question it answers is not "how does this look on
 * Android"; it is "how does this *behave*, and how does it survive the other frames". A full-size
 * selection overlay sits on the authoring canvas and swallows every tap, so a screen wired to react
 * could not be made to react by the person who wired it. Here there is no overlay: the controls are
 * live, the design's own scrolling is live, and nothing being clicked changes the selection.
 *
 * It is also free. Nothing here asks the host for anything, which is why it can be switched on
 * mid-thought and switched off again — and why the pane that *does* cost a compile is a separate
 * choice somebody makes on purpose ([EditorPane]).
 *
 * ## Only the selected frames, side by side
 *
 * One frame per selected device in `exportDevices`, plus the switched-on axes — exactly the
 * [UiBuilderVariantPane] list. The editor already draws the design's own frame; repeating it here
 * merely duplicates the first device in the common case and obscures the comparison this pane is
 * for. Laid out in a scrolling row rather than scaled to fit: a device frame shrunk to a thumbnail
 * answers nothing about text that only just fits.
 */
@Composable
internal fun DesignPreviewPane(
  document: UiBuilderDocument,
  /**
   * The device and axis frames to draw after the design's own — see
   * [UiBuilderDocument.variantPanes].
   */
  variants: List<UiBuilderVariantPane>,
  modifier: Modifier = Modifier,
  /** The catalog's pinned runtime, which draws each device pane when the catalog has one. */
  deviceRenderer: UiBuilderCanvasRenderer? = null,
) {
  val hostDensity = LocalDensity.current
  val panes = document.wearWidgetScaffoldSize()?.let(document::wearWidgetPreviewPanes) ?: variants
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        if (panes.isEmpty()) {
          Text(
            "Select devices or display variants to compare",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
          )
          return@BoxWithConstraints
        }
        // One scale for the whole row, so two devices in it are drawn at the same ratio and are
        // actually comparable — picking a scale per frame would make a watch and a tablet look the
        // same size, which is the one thing this row exists to contradict.
        //
        // Chosen so the tallest frame fits the pane's height and the widest fits its width: the
        // row then always shows at least one frame whole, and the rest are reached by scrolling
        // rather than by squeezing every phone in the row down to a thumbnail.
        //
        // Capped at whichever is larger of 1:1 against the design's pixels and 1:1 against its dp.
        // A 2x watch in a 1x host still draws at its own pixels. A 1x widget in a 2.625x host is no
        // longer held to its pixels, which drew it at 38%, a strip nobody could read: that cap
        // existed because the frame clipped before it was scaled, and [ConstrainedFramePane] now
        // clips inside its scaled layer, so a frame can be magnified.
        val tallest = panes.maxOf { it.heightDp }
        val widest = panes.maxOf { it.widthDp }
        val oneToOnePixels = panes.minOf {
          it.document.renderDensity(hostDensity).density / hostDensity.density
        }
        val scale =
          minOf(maxHeight.value / (tallest + VARIANT_LABEL_ROOM_DP), maxWidth.value / widest)
            .coerceIn(MIN_CANVAS_ZOOM, maxOf(oneToOnePixels, 1f))
        // How many frames fit across the pane at that scale, and the whole of "should this be a
        // grid?". A watch frame is 192dp and a pane is often 700dp wide, so the row that answered
        // a phone question — one frame per screen, the rest scrolled off the right — put three
        // watches where two would fit and hid the third. Where two or more fit, the frames wrap
        // instead: the same scale, the same comparison, all of them visible.
        //
        // The preview surface always scrolls vertically. In a compact pane `perRow` is one, so
        // device previews stack rather than disappearing off the right edge behind a horizontal
        // scroll. Wider panes retain the side-by-side comparison grid.
        val gap = 16.dp
        val perRow = ((maxWidth + gap) / ((widest * scale).dp + gap)).toInt().coerceAtLeast(1)
        // Centred, so a row that fits sits in the middle of the pane rather than in its top-left.
        // `FlowRow` remains a one-column grid when the pane is narrow.
        // Hoisted so a runtime-drawn pane, a DOM layer placed by hand, is placed again as this
        // column scrolls rather than staying at the window position it was first given.
        val scroll = rememberScrollState()
        Column(
          Modifier.fillMaxSize().verticalScroll(scroll),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = perRow,
          ) {
            panes.forEach { pane ->
              key(pane.id) {
                VariantPane(
                  pane = pane,
                  scale = scale,
                  hostDensity = hostDensity,
                  deviceRenderer = deviceRenderer,
                  positionVersion = scroll.value,
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The frame itself, with the overlay that makes it a surface rather than a picture.
 *
 * ## The one coordinate transform
 *
 * The host reports each node's box in the frame's own pixels, and the frame is drawn scaled to fit
 * this pane. So there is exactly one factor — `displayed / image` — and it is computed here, where
 * the displayed size is decided, rather than being sent over the wire in a space that would have to
 * agree with a layout nobody on the server can see. The image is laid out at that exact size
 * instead of being left to [ContentScale.Fit], so the overlay and the pixels underneath it cannot
 * disagree about where the frame starts.
 *
 * ## Hit-testing picks the smallest box
 *
 * A click lands inside every ancestor of the node that drew it — the column, the card, the row —
 * and the innermost of those is the one a designer means, which is what the layers panel would
 * select too. Ties (a wrapper exactly the size of its child) go to whichever the host reported
 * first; there is no better answer and both are the same rectangle.
 *
 * A node with no reported box is not selectable here. That is the honest outcome for something the
 * render never placed, and the layers panel still selects it.
 */
@Composable
private fun NativeRenderFrame(
  image: ImageBitmap,
  nodeBounds: Map<String, UiBuilderNativeNodeBounds>,
  selectedNodeId: String?,
  onNodeSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val frameWidth = image.width.toFloat()
    val frameHeight = image.height.toFloat()
    val availableWidth = with(density) { maxWidth.toPx() }
    val availableHeight = with(density) { maxHeight.toPx() }
    // `coerceAtMost(1f)`: a frame smaller than the pane is shown at its own size, because a render
    // blown up past 1:1 is a blurrier answer to "what does this look like on the device".
    val scale =
      minOf(availableWidth / frameWidth, availableHeight / frameHeight)
        .coerceAtMost(1f)
        .coerceAtLeast(0.01f)
    val shownWidth = with(density) { (frameWidth * scale).toDp() }
    val shownHeight = with(density) { (frameHeight * scale).toDp() }
    Box(
      Modifier.size(shownWidth, shownHeight).pointerInput(nodeBounds, scale) {
        detectTapGestures { offset ->
          val x = offset.x / scale
          val y = offset.y / scale
          nodeBounds
            .filterValues { it.contains(x, y) }
            .minByOrNull { it.value.area }
            ?.let { onNodeSelected(it.key) }
        }
      }
    ) {
      Image(
        bitmap = image,
        contentDescription = "Native render of this design",
        // The box is already the frame's exact displayed size, so this only says "no letterboxing
        // inside it" — the fit was decided above, where the overlay's scale was.
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
      )
      val selected = selectedNodeId?.let(nodeBounds::get)
      if (selected != null) {
        val outline = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
          drawRect(
            color = outline,
            topLeft = Offset(selected.x * scale, selected.y * scale),
            size = Size(selected.width * scale, selected.height * scale),
            style = Stroke(width = 2f),
          )
        }
      }
    }
  }
}
