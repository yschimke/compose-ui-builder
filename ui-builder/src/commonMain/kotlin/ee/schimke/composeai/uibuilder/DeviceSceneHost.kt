package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
 * drives it — including the rotating side button.
 *
 * ## Why a scene rather than a subtree
 *
 * The editor is one composition: the canvas, the frame beside it and every device pane are all
 * nodes in the same tree, which is why they are cheap and why they all share the editor's
 * `CompositionLocal`s. What they cannot share is the *input* a watch sends. Rotary input is
 * dispatched by Compose to the **focused** node and walked up from there
 * (`FocusOwner.dispatchRotaryEvent`), and the only public entry point that starts that walk is
 * `ComposeScene.sendRotaryScrollEvent` — a scene, not a subtree. A pane that is a subtree cannot be
 * told "the side button turned"; a pane that is a scene can.
 *
 * `DeviceSceneRotaryTest` is the experiment that settled it: a Wear `TransformingLazyColumn`
 * composed in a scene of its own turns through its own snap/fling behaviour when a rotary event
 * arrives, and it needed three things that are not in any documentation — the scene's dispatch, the
 * **upward** walk from focus (a rotary modifier below the focus target is never reached), and a
 * frame clock at a real cadence (the scroll is animated, so a clock that advances a nanosecond per
 * frame leaves the list exactly where it was while the event reports itself handled).
 *
 * ## What this costs, stated rather than discovered
 *
 * - **`CanvasLayersComposeScene` is `@InternalComposeUiApi`** — "use only between compose-ui
 *   modules sharing the same exact version, subject to change without notice in major, minor, or
 *   patch releases". `JvmSkiaStructuredSvgRecorder` already opts into the same factory for the SVG
 *   export lane, so this is a coupling the repository has taken once; a pane drawing through it
 *   inherits it and a Compose bump is where it would break.
 * - **A second composition per pane.** The scene composes, measures and draws on its own, and the
 *   editor blits the result. The frame pump below stops the moment the scene has nothing to do, so
 *   a settled design costs one draw rather than sixty a second — but an animating design costs two
 *   compositions while it animates.
 * - **CompositionLocals do not cross.** [content] is composed *inside* the scene, so the caller
 *   wraps it in whatever the design needs — the same providers the editor installs around its
 *   canvas, by value rather than by inheritance.
 *
 * ## Input
 *
 * A pointer press, move or release is forwarded into the scene, and a wheel is forwarded as
 * **rotary**: one wheel notch is scaled by [WHEEL_PIXELS_PER_ROTARY_TICK] into the pixels a
 * rotating side button reports. The wheel is the pane's while the pointer is over it, which is a
 * deliberate change from the editor's own scrolling — the pane is where a design is driven, and the
 * workspace still scrolls everywhere else.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@Composable
internal fun DeviceSceneHost(
  /**
   * What the scene is of. The scene is rebuilt when this changes — a new document revision, a
   * different device frame, a different density.
   */
  key: Any,
  sizePx: IntSize,
  density: Density,
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  /** Whether a wheel over this pane is delivered as rotary. Off for panes that are not a device. */
  rotary: Boolean = true,
  /**
   * What [content] draws — the design, for a device pane. A change asks the scene for a frame; see
   * the pump below for why the lambda cannot say so itself.
   */
  contentKey: Any? = null,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // The scene is rebuilt when the *frame* changes, not when the design does. A scene composes its
  // content once; handing it the latest lambda through a state is what lets an edit recompose the
  // design inside a scene that is already standing, instead of tearing down and rebuilding a
  // composition on every keystroke.
  val latestContent = rememberUpdatedState(content)
  val sceneRoot = LocalDeviceSceneRoot.current
  val holder =
    remember(key, sizePx, density, layoutDirection) {
      DeviceScene(sizePx, density, layoutDirection) { sceneRoot { latestContent.value() } }
    }
  DisposableEffect(holder) { onDispose { holder.close() } }
  // The pump asks for frames only while the scene has work: a design that has settled costs a draw,
  // and one that is animating (a spinner, a rotary fling) keeps its own frames.
  //
  // Restarted by an edit as well as by input. An edit invalidates the scene's own composition —
  // a composable lambda is updated in place rather than replaced, so [content] keeps its identity
  // and cannot key anything — but the scene only composes inside [DeviceScene.frame], and nothing
  // else calls it. Keyed on input alone, the loop exited with the first settled frame, so every
  // pane
  // kept drawing the revision it opened on until a pointer happened to cross it: a widget's three
  // host previews stayed empty while its canvas filled up. [contentKey] is the caller saying what
  // the lambda draws, and one frame is always taken for it.
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
 * How far one wheel notch scrolls, in the pixels a rotating side button reports.
 *
 * A notch on the web is about 100px of scroll and a Wear encoder tick is a fraction of a row, so
 * without scaling one notch would throw the list several items. This is a *feel* constant and it is
 * approximate on purpose: a real encoder is high-resolution and a wheel is not, so the snap
 * behaviour will settle on different boundaries than a watch would. The pane says so in its caption
 * rather than implying it is the watch's own feel.
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
   * A rotating side button. Returns whether the scene delivered it — a design with nothing rotary
   * on it (or nothing focused) answers false, and the caller then leaves the wheel to the editor.
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
