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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.renderDensity
import ee.schimke.composeai.uibuilder.codegen.codeColor
import ee.schimke.composeai.uibuilder.codegen.highlightKotlin
import ee.schimke.composeai.uibuilder.codegen.rememberCodePaneSyntaxTheme
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.readsClock
import ee.schimke.composeai.uibuilder.renderer.sdk.withTimeRunning
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Kotlin the Compose export would write for the document on the canvas, so a designer sees the
 * code an edit produces without running an export.
 *
 * [EditorGeneratedCode.Refused] shows the refusal reasons in the same place, since they are what
 * the designer needs to make the design expressible. Selectable but not editable: it is regenerated
 * on every canvas edit.
 */
@Composable
internal fun GeneratedCodePane(
  code: EditorGeneratedCode,
  /**
   * What produced the source: a widget's Kotlin is Remote Compose (a `WearWidgetDocument`), not the
   * Compose export.
   */
  caption: String,
  modifier: Modifier = Modifier,
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
      when (code) {
        is EditorGeneratedCode.Source -> {
          val clipboard = LocalClipboard.current
          val scope = rememberCoroutineScope()
          var copied by remember { mutableStateOf<String?>(null) }
          LaunchedEffect(copied) {
            if (copied != null) {
              delay(EXPORT_STATUS_MILLIS)
              copied = null
            }
          }
          // A copy button, since copying is what this pane is for.
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              copied ?: caption,
              Modifier.weight(1f),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
            )
            TextButton(
              onClick = {
                scope.launch {
                  copied =
                    runCatching { clipboard.setClipEntry(plainTextClipEntry(code.kotlin)) }
                      .fold({ "Copied the source" }, { "Could not copy: ${it.message}" })
                }
              },
              modifier = Modifier.semantics { contentDescription = "Copy source" },
            ) {
              Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Copy", style = MaterialTheme.typography.labelLarge)
            }
          }
          val vertical = rememberScrollState()
          val horizontal = rememberScrollState()
          val syntaxTheme = rememberCodePaneSyntaxTheme()
          // Tokenizing is keyed on the source, so an edit elsewhere on the canvas — a selection, a
          // scroll, a drag over the drop target — recomposes this pane without re-running it.
          val highlighted =
            remember(code.kotlin, syntaxTheme) { highlightKotlin(code.kotlin, syntaxTheme) }
          // Initially scroll past the imports, once; after that the reader's scroll is kept across
          // edits.
          var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
          var pastImports by remember { mutableStateOf(false) }
          LaunchedEffect(layout) {
            val laidOut = layout ?: return@LaunchedEffect
            if (pastImports) return@LaunchedEffect
            pastImports = true
            val line = firstLineAfterImports(code.kotlin)
            if (line in 1 until laidOut.lineCount) {
              vertical.scrollTo(laidOut.getLineTop(line).roundToInt())
            }
          }
          SelectionContainer(Modifier.padding(top = 8.dp)) {
            Text(
              highlighted,
              Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal),
              onTextLayout = { layout = it },
              // The palette's own foreground so unstyled runs match the styled ones.
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
 * The line the generated code starts on, after the `package`/`import` header and surrounding blank
 * lines; 0 when there is none.
 */
internal fun firstLineAfterImports(kotlin: String): Int {
  val lines = kotlin.lines()
  val lastHeader =
    lines.indexOfLast { it.startsWith("import ") || it.startsWith("package ") }.takeIf { it >= 0 }
      ?: return 0
  var line = lastHeader + 1
  while (line < lines.size && lines[line].isBlank()) line++
  return if (line < lines.size) line else 0
}

/**
 * The same design drawn by real Compose on the host, beside the Wasm canvas so renderer differences
 * are visible.
 *
 * Live where the host can stream (frames pushed, taps dispatched into the real composition),
 * otherwise the still, which is also shown until the first frame lands; the label says which.
 * Clicking a still selects a layer (it has a node-box map); clicking a live session is the click.
 * Refusals are shown here like in the code pane; transport failures are reported separately.
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
        // A painting live frame wins over everything, including a pending re-render, so keystrokes
        // do not blank it.
        liveFrame != null ->
          LiveNativeFrame(
            frame = liveFrame,
            onInput = { stream.send(it) },
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          )
        // A failed stream with no still to fall back on; live is optional, so a compiled still is
        // never blanked by a live failure.
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
 * The native pane's caption, which tells the user whether taps will work: still, connecting, or
 * live (naming the backend, e.g. "live on Android").
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
 * A live native frame and the gestures forwarded to its composition.
 *
 * The frame is drawn scaled by one factor (`displayed / image`), which presses are divided by to
 * get image pixels, as [NativeRenderFrame] does for node boxes. A stationary press is sent as one
 * `click` on release (the daemon's click fast-path can race a batched down/up); `pointerDown` is
 * sent only once a drag starts.
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
          // Keyed on the scale too, since the handler closes over it.
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
                    // The platform's touch slop, so a slight wobble is still a tap.
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
          // On Wear a wheel is a rotating side button turn, forwarded as the protocol's
          // `rotaryScroll`.
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
                    // Same half-pixel CSS-to-device conversion as the Wasm device scene, so RSB
                    // speed matches Preview.
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
 * The design without the editor overlay: the same Wasm renderer, every claimed frame, no compile.
 *
 * Answers how the design behaves and survives other frames: controls and scrolling are live, and
 * clicks do not select. It costs the host nothing, unlike the native pane ([EditorPane]).
 *
 * Draws one frame per selected `exportDevices` entry plus enabled axes (the [UiBuilderVariantPane]
 * list), not the design's own frame, in a scrolling layout rather than shrunk to thumbnails.
 */
@Composable
internal fun DesignPreviewPane(
  document: UiBuilderDocument,
  /**
   * The device and axis frames to draw after the design's own; see
   * [UiBuilderDocument.variantPanes].
   */
  variants: List<UiBuilderVariantPane>,
  modifier: Modifier = Modifier,
  /** The catalog's pinned runtime, which draws each device pane when the catalog has one. */
  deviceRenderer: UiBuilderCanvasRenderer? = null,
  /**
   * Draw [variants] exactly as given, instead of a Wear widget's host-shape panes (the compact
   * preview tab has already chosen one).
   */
  exactPanes: Boolean = false,
  /**
   * Whether each frame's clock and animations run, via a document copy that says so
   * ([withTimeRunning]) so catalog runtimes hear it too.
   */
  timeRunning: Boolean = false,
  /** Shows the [PreviewTimeToggle] when set. */
  onTimeRunningChange: ((Boolean) -> Unit)? = null,
) {
  val hostDensity = LocalDensity.current
  val panes =
    remember(document, variants, exactPanes, timeRunning) {
      (if (exactPanes) variants else document.widgetPreviewPanes() ?: variants).map {
        it.copy(document = it.document.withTimeRunning(timeRunning))
      }
    }
  // A catalog runtime may animate anything it draws; the canvas renderer moves only with the clock.
  val readsClock = remember(document.nodes) { document.readsClock }
  val hasMotion = deviceRenderer != null || readsClock
  Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      if (onTimeRunningChange != null && hasMotion) {
        PreviewTimeToggle(timeRunning, onTimeRunningChange, Modifier.align(Alignment.End))
      }
      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        if (panes.isEmpty()) {
          Text(
            "Select devices or display variants to compare",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
          )
          return@BoxWithConstraints
        }
        // One scale for the whole row so devices stay comparable, chosen so the tallest frame fits
        // the height and the widest the width. Capped at the larger of 1:1 in design pixels and 1:1
        // in dp; [ConstrainedFramePane] clips inside its scaled layer, so frames can be magnified.
        val tallest = panes.maxOf { it.heightDp }
        val widest = panes.maxOf { it.widthDp }
        val oneToOnePixels = panes.minOf {
          it.document.renderDensity(hostDensity).density / hostDensity.density
        }
        val scale =
          minOf(maxHeight.value / (tallest + VARIANT_LABEL_ROOM_DP), maxWidth.value / widest)
            .coerceIn(MIN_CANVAS_ZOOM, maxOf(oneToOnePixels, 1f))
        // How many frames fit across at that scale; where two or more fit, frames wrap into a grid.
        // The surface always scrolls vertically, so narrow panes stack.
        val gap = 16.dp
        val perRow = ((maxWidth + gap) / ((widest * scale).dp + gap)).toInt().coerceAtLeast(1)
        // Centred `FlowRow`. The scroll is hoisted so runtime-drawn panes (hand-placed DOM layers)
        // follow scrolling.
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
 * The frame with the overlay that makes it selectable.
 *
 * Node boxes arrive in frame pixels and the image is laid out at an exact scaled size (not
 * [ContentScale.Fit]), so one factor `displayed / image` maps between them. A click selects the
 * smallest containing box (ties go to the first reported). Nodes without a box are not selectable
 * here.
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
