package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/**
 * Draw the horizontal scrollers unrolled along their own axis: `layout/lazy-row` as a `Row`, the
 * carousel's items side by side, and a `horizontalScroll` modifier dropped.
 *
 * Separate from [LocalUiBuilderUnrolled] because the two answer different surfaces. The extent
 * unrolls *vertically* — its width is the frame's, so a lazy row inside it is still a real lazy row
 * and has to stay one, pixel for pixel. Only the pop-out of a single horizontal container is
 * measured against an unbounded width, and a `LazyRow` refuses that exactly as a `LazyColumn`
 * refuses an unbounded height.
 */
internal val LocalUiBuilderUnrolledHorizontal = staticCompositionLocalOf { false }

/**
 * The selected node and every ancestor of it, which is what a lazy container asks to find out
 * whether one of its items holds the selection. Empty wherever nothing is selected.
 */
internal val LocalUiBuilderRevealChain = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * The whole design a re-rooted surface is a piece of, or null for a surface drawing a design whole.
 *
 * The pop-out draws one container as its own document, and the theme the design is drawn in lives
 * on a surface *above* that container: read from the re-rooted document alone, the palette, type
 * scale and corner radius fell back to the defaults and the pop-out was a differently coloured copy
 * of the thing it is a copy of.
 */
internal val LocalUiBuilderDetachedFrom = staticCompositionLocalOf<UiBuilderDocument?> { null }

/**
 * Leave pointer input to the design under the editor overlay rather than catching every press.
 *
 * The overlay is a sibling drawn over the design, and a sibling on top takes the whole pointer
 * stream: harmless on the extent, where nothing scrolls, but it left the device frame's lists deaf
 * to the wheel and the drag that are the reason to look at the frame at all. So on the device view
 * the overlay selects through [passThroughClick], which shares the stream instead.
 */
internal val LocalUiBuilderOverlayPassesInput = staticCompositionLocalOf { false }

/** Each node's parent, from the slots that hold it. */
internal fun UiBuilderDocument.parentIds(): Map<String, String> = buildMap {
  nodes.values.forEach { parent ->
    parent.slots.values.forEach { children -> children.forEach { put(it, parent.id) } }
  }
}

/** [nodeId] and its ancestors, nearest first; empty for no selection or an unknown id. */
internal fun UiBuilderDocument.selectionChain(nodeId: String?): List<String> {
  if (nodeId == null || nodeId !in nodes) return emptyList()
  val parents = parentIds()
  val chain = mutableListOf(nodeId)
  // Bounded by the node count, because a cycle is a malformed document the Issues panel has to be
  // able to draw rather than a loop the canvas should spin in.
  while (chain.size <= nodes.size) chain += parents[chain.last()] ?: break
  return chain
}

/**
 * Scrolls the item of a device-sized lazy container that holds the selection into view.
 *
 * Selecting from the layers panel, or from the unrolled pop-out beside the frame, names a node the
 * frame may have scrolled far away from, and a selection outline drawn around nothing on screen is
 * a selection nobody can see. Only when the selection *changes*, never on every recomposition: an
 * author who then scrolls the frame by hand is looking somewhere on purpose.
 *
 * Nothing at the extent, where every item is already drawn — nor in any surface that names no
 * selection, like the preview panes.
 */
@Composable
internal fun RevealSelectedItem(
  itemIds: List<String>,
  /**
   * Whether the item is wholly inside the viewport; a partly clipped row is scrolled to as well.
   */
  showsWhole: (String) -> Boolean,
  scrollTo: suspend (Int) -> Unit,
) {
  val chain = LocalUiBuilderRevealChain.current
  if (chain.isEmpty() || LocalUiBuilderUnrolled.current) return
  val index = itemIds.indexOfFirst { it in chain }
  if (index < 0) return
  LaunchedEffect(chain, index) {
    // One frame first, so a container composed in this same pass has placed its items and the
    // visibility question has an answer.
    withFrameNanos {}
    if (!showsWhole(itemIds[index])) scrollTo(index)
  }
}

internal fun LazyListState.showsWhole(key: String): Boolean {
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
  return item.offset >= info.viewportStartOffset &&
    item.offset + item.size <= info.viewportEndOffset
}

/** By index: a Wear list's rows carry no keys of their own. */
internal fun TransformingLazyColumnState.showsWhole(index: Int): Boolean {
  val info = layoutInfo
  val item = info.visibleItems.firstOrNull { it.index == index } ?: return false
  return item.offset >= 0 && item.offset + item.transformedHeight <= info.viewportSize.height
}

internal fun LazyGridState.showsWhole(key: String): Boolean {
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
  return item.offset.y >= info.viewportStartOffset &&
    item.offset.y + item.size.height <= info.viewportEndOffset
}

/**
 * Keeps [composed] holding the ids a carousel currently draws.
 *
 * `CarouselState` publishes no layout info, so "is it on screen" is answered by whether the
 * carousel composed it — which it does for exactly the items in its viewport.
 */
@Composable
internal fun MarkComposed(composed: MutableSet<String>, id: String) {
  DisposableEffect(composed, id) {
    composed += id
    onDispose { composed -= id }
  }
}

/**
 * A primary click on the overlay that lets the design under it keep the pointer.
 *
 * The overlay is a sibling drawn over the design, and a sibling on top takes the pointer stream
 * from the siblings beneath — which on the device frame left its lists deaf to the wheel and the
 * drag that scroll them. This shares the stream with its siblings instead and watches it in the
 * [PointerEventPass.Initial] pass: a press that comes back up within the touch slop is a click, its
 * release is consumed so the button under it does not also fire, and [onClick] is told where it
 * landed. A press that travels further is somebody scrolling, and is left alone.
 */
internal fun Modifier.passThroughClick(onClick: (Offset) -> Unit): Modifier =
  this then PassThroughClickElement(onClick)

private data class PassThroughClickElement(val onClick: (Offset) -> Unit) :
  ModifierNodeElement<PassThroughClickNode>() {
  override fun create() = PassThroughClickNode(onClick)

  override fun update(node: PassThroughClickNode) {
    node.onClick = onClick
  }
}

private class PassThroughClickNode(var onClick: (Offset) -> Unit) :
  Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
  private var pressed: PointerId? = null
  private var pressedAt = Offset.Zero

  override fun onPointerEvent(
    pointerEvent: PointerEvent,
    pass: PointerEventPass,
    bounds: IntSize,
  ) {
    if (pass != PointerEventPass.Initial) return
    val change = pointerEvent.changes.firstOrNull() ?: return
    when {
      change.changedToDown() ->
        if (pointerEvent.buttons.isSecondaryPressed) pressed = null
        else {
          pressed = change.id
          pressedAt = change.position
        }
      change.id != pressed -> Unit
      (change.position - pressedAt).getDistance() >
        currentValueOf(LocalViewConfiguration).touchSlop -> pressed = null
      change.changedToUp() -> {
        pressed = null
        change.consume()
        onClick(change.position)
      }
    }
  }

  override fun onCancelPointerInput() {
    pressed = null
  }

  override fun sharePointerInputWithSiblings(): Boolean = true
}
