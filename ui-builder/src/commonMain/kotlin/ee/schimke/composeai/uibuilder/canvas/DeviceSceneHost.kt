package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers

/**
 * Carries host-specific root locals into a [DeviceScene], whose composition cannot inherit them.
 */
public val LocalDeviceSceneRoot =
  staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> {
    { content -> content() }
  }

/**
 * A device pane drawn by **its own Compose scene**, so the design can be driven the way a watch
 * drives it, including the rotating side button.
 *
 * Rotary input is dispatched to the focused node and walked up (`FocusOwner.dispatchRotaryEvent`),
 * and the only public entry is `ComposeScene.sendRotaryScrollEvent`: a scene, not a subtree.
 * `DeviceSceneRotaryTest` pins what it takes: the scene's dispatch, the upward walk from focus, and
 * a frame clock at a real cadence (the scroll is animated).
 *
 * Costs:
 * - **`CanvasLayersComposeScene` is `@InternalComposeUiApi`**, version-locked to compose-ui;
 *   `JvmSkiaStructuredSvgRecorder` already depends on it, and a Compose bump is where it would
 *   break.
 * - **A second composition per pane**; the frame pump stops when the scene is idle, so only
 *   animation costs extra.
 * - **CompositionLocals do not cross**: the caller wraps [content] in the providers the design
 *   needs.
 *
 * Pointer press, move and release are forwarded; a wheel is forwarded as **rotary**, scaled by
 * [WHEEL_PIXELS_PER_ROTARY_TICK]. The wheel belongs to the pane while the pointer is over it.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@Composable
internal fun DeviceSceneHost(
  /** What the scene is of; it is rebuilt when this changes (revision, device frame, density). */
  key: Any,
  sizePx: IntSize,
  density: Density,
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  /** Whether a wheel over this pane is delivered as rotary. Off for panes that are not a device. */
  rotary: Boolean = true,
  /**
   * What [content] draws (the design, for a device pane). A change asks the scene for a frame; see
   * the pump below.
   */
  contentKey: Any? = null,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // The scene is rebuilt per frame, not per design edit: the latest lambda is passed through state
  // so edits recompose inside the standing scene.
  val latestContent = rememberUpdatedState(content)
  val sceneRoot = LocalDeviceSceneRoot.current
  // The editor's font resolver, not the scene's own: bundled and downloaded fallback fonts (emoji,
  // symbols) are registered with it. A separate resolver would never see them and re-download the
  // same font on every layout.
  val fontFamilyResolver = LocalFontFamilyResolver.current
  val holder =
    remember(key, sizePx, density, layoutDirection, fontFamilyResolver) {
      DeviceScene(sizePx, density, layoutDirection) {
        CompositionLocalProvider(LocalFontFamilyResolver provides fontFamilyResolver) {
          sceneRoot { latestContent.value() }
        }
      }
    }
  DisposableEffect(holder) { onDispose { holder.close() } }
  // The pump asks for frames only while the scene has work: a settled design costs one draw, an
  // animating one keeps its frames.
  //
  // Restarted by edits as well as input: the scene only composes inside [DeviceScene.frame], and
  // the lambda keeps its identity, so [contentKey] says what is drawn and always gets one frame.
  var pump by remember(holder) { mutableIntStateOf(0) }
  LaunchedEffect(holder, pump, contentKey) {
    do {
      androidx.compose.runtime.withFrameNanos { holder.frame(it) }
    } while (holder.hasWork())
  }
  Canvas(
    modifier
      .fillMaxSize()
      .pointerInput(holder) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull() ?: continue
            val forwarded =
              when (event.type) {
                PointerEventType.Press,
                PointerEventType.Move,
                PointerEventType.Release -> holder.pointer(event.type, change.position)
                else -> false
              }
            if (forwarded) {
              change.consume()
              pump += 1
            }
          }
        }
      }
      .pointerInput(holder, rotary) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            if (!rotary) continue
            val pixels = change.scrollDelta.y * WHEEL_PIXELS_PER_ROTARY_TICK
            if (holder.rotary(pixels, change.uptimeMillis)) {
              change.consume()
              pump += 1
            }
          }
        }
      }
  ) {
    // Read so the canvas redraws when the scene draws something new.
    val version = holder.drawVersion
    if (version >= 0) {
      drawIntoCanvas { canvas -> holder.scene.draw(canvas) }
    }
  }
}

/**
 * How far one wheel notch scrolls, in the pixels a rotating side button reports. An approximate
 * feel constant (a web notch is ~100px, an encoder tick a fraction of a row); the pane's caption
 * says it isn't the watch's own feel.
 */
private const val WHEEL_PIXELS_PER_ROTARY_TICK = 0.5f

/** The scene, its recomposer and the small amount of state the composable above reads. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class DeviceScene(
  sizePx: IntSize,
  density: Density,
  layoutDirection: LayoutDirection,
  content: @Composable () -> Unit,
) {
  private val recomposer = FrameRecomposer(Dispatchers.Unconfined)
  val scene: ComposeScene =
    CanvasLayersComposeScene(
      frameRecomposer = recomposer,
      density = density,
      layoutDirection = layoutDirection,
      size = sizePx,
    )

  /** Bumped whenever the scene drew, so the editor's canvas redraws with it. */
  var drawVersion by mutableIntStateOf(0)
    private set

  private var closed = false

  init {
    scene.setContent { content() }
  }

  fun hasWork(): Boolean =
    !closed &&
      (recomposer.hasPendingWork() || scene.hasPendingMeasureOrLayout || scene.hasPendingDraw)

  /** One frame: advance the clock, settle the layout, note that there is something to draw. */
  fun frame(nanos: Long) {
    if (closed) return
    recomposer.performFrame(nanos)
    scene.measureAndLayout()
    if (scene.hasPendingDraw) drawVersion += 1
  }

  fun pointer(type: PointerEventType, position: Offset): Boolean {
    if (closed) return false
    scene.sendPointerEvent(eventType = type, position = position)
    return true
  }

  /**
   * A rotating side button. Returns whether the scene delivered it; false (nothing rotary or
   * focused) leaves the wheel to the editor.
   */
  fun rotary(verticalScrollPixels: Float, timeMillis: Long): Boolean =
    !closed && scene.sendRotaryScrollEvent(verticalScrollPixels, 0f, timeMillis)

  fun close() {
    if (closed) return
    closed = true
    scene.close()
    recomposer.close()
  }
}
