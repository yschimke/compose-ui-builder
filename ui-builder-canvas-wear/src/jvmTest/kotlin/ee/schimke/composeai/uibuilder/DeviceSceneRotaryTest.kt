package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * The experiment that decides whether the device panes can be their own Compose scene.
 *
 * The plan is to give each device pane a `CanvasLayersComposeScene` of its own, so a mouse wheel
 * can be delivered as a **rotary side button** — `ComposeScene.sendRotaryScrollEvent` — and the
 * Wear components that listen for rotary turn the way they do on a watch. Three things had to be
 * true, and none of them is in any documentation. Each is a test below, because each was wrong in
 * the first attempt:
 *
 * 1. **The scene dispatches rotary at all.** It does: `BaseComposeScene.sendRotaryScrollEvent` →
 *    `RootNodeOwner.onRotaryEvent` → `FocusOwner.dispatchRotaryEvent`.
 * 2. **The dispatch walks UP from the focused node**, not out from the node under the pointer. A
 *    rotary modifier *below* the focus target in the chain is never reached — so a plain
 *    `onRotaryScrollEvent` on the same element as `focusable()` receives nothing, while the same
 *    modifier above it receives everything. This is the shape a Wear list has (the list is an
 *    ancestor of the focused row), and it is why the pane does not have to place anything itself.
 * 3. **The scroll is animated, so the frame clock has to be real.** A rotary event moves a Wear
 *    list through its snap/fling behaviour, and a hand-driven clock that advances a nanosecond per
 *    frame leaves it exactly where it started — the event is *handled*, the list does not move, and
 *    the failure reads as "rotary does not work here". 60fps frames and thirty of them per event is
 *    what makes it turn.
 *
 * **The scene factory is `@InternalComposeUiApi`** — "use only between compose-ui modules sharing
 * the same exact version, subject to change without notice". The SVG export lane already opts into
 * the same factory (`JvmSkiaStructuredSvgRecorder`), so a pane that draws through it inherits a
 * commitment this repository has made once already; it is stated here because that is the cost.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DeviceSceneRotaryTest {

  /** 60fps in nanoseconds: the clock a hand-driven scene needs for anything animated. */
  private val frameNanos = 16_666_667L

  /** A Wear list of six 64dp rows in a 384px square, at the 2× density a watch document states. */
  private class WearListScene {
    val recomposer = FrameRecomposer(Dispatchers.Unconfined)
    val scene =
      CanvasLayersComposeScene(
        recomposer,
        density = Density(2f),
        layoutDirection = LayoutDirection.Ltr,
        size = IntSize(384, 384),
      )
    lateinit var state: TransformingLazyColumnState
    private var frame = 0L

    init {
      scene.setContent {
        state = rememberTransformingLazyColumnState()
        TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
          items(6) { index -> Text("Row $index", Modifier.fillMaxWidth().height(64.dp)) }
        }
      }
      settle()
    }

    /** One frame of the three steps Compose 1.12 split `scene.render` into. */
    fun settle(frames: Int = 2) {
      repeat(frames) {
        frame += 16_666_667L
        recomposer.performFrame(frame)
        scene.measureAndLayout()
      }
    }

    fun close() {
      scene.close()
      recomposer.close()
    }
  }

  @Test
  fun `a rotary event turns a wear list composed in its own scene`() {
    val list = WearListScene()
    try {
      val before = list.state.anchorItemIndex
      assertTrue(
        list.scene.sendRotaryScrollEvent(200f, 0f, list.recomposer.hashCode().toLong()),
        "the scene did not deliver the rotary event to the Wear list",
      )
      // The list moves through its own snap/fling behaviour, so it needs frames at a real cadence.
      list.settle(frames = 30)
      assertTrue(
        list.state.anchorItemIndex > before,
        "the rotary event was delivered and the list did not move: anchor was $before, is " +
          "${list.state.anchorItemIndex}",
      )
    } finally {
      list.close()
    }
  }

  /**
   * The direction of the walk, stated as a test because getting it backwards fails silently.
   *
   * A rotary modifier **above** the focus target is an ancestor of it and is reached; the same
   * modifier below it is not, and the scene answers `false` with nothing logged.
   */
  @Test
  fun `the dispatch reaches a rotary modifier that is an ancestor of the focused node`() {
    var received = 0f
    var focused = false
    val requester = FocusRequester()
    val recomposer = FrameRecomposer(Dispatchers.Unconfined)
    val scene =
      CanvasLayersComposeScene(
        recomposer,
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        size = IntSize(384, 384),
      )
    var frame = 0L
    fun settle(frames: Int = 2) {
      repeat(frames) {
        frame += frameNanos
        recomposer.performFrame(frame)
        scene.measureAndLayout()
      }
    }
    try {
      scene.setContent {
        Box(
          Modifier.fillMaxSize()
            // Rotary first, focus second: the rotary node is the focus target's ancestor.
            .onRotaryScrollEvent {
              received += it.verticalScrollPixels
              true
            }
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
        )
      }
      settle()
      requester.requestFocus()
      settle()
      assertTrue(focused, "the probe's own focus target never took focus")

      assertTrue(
        scene.sendRotaryScrollEvent(120f, 0f, frame),
        "a rotary modifier above the focused node should receive the event",
      )
      assertEquals(120f, received, "the event reached the scene and not the modifier")
    } finally {
      scene.close()
      recomposer.close()
    }
  }
}
