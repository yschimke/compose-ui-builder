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
 * Draw horizontal scrollers unrolled along their own axis: `layout/lazy-row` as a `Row`, the
 * carousel's items side by side, `horizontalScroll` dropped. Separate from [LocalUiBuilderUnrolled]
 * (vertical): only the pop-out of a horizontal container is measured against an unbounded width.
 */
internal val LocalUiBuilderUnrolledHorizontal = staticCompositionLocalOf { false }

/**
 * The selected node and every ancestor, so a lazy container can tell whether one of its items holds
 * the selection. Empty when nothing is selected.
 */
internal val LocalUiBuilderRevealChain = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * The whole design a re-rooted surface (the pop-out) is a piece of, or null; theme lookups read it
 * since the themed surface sits above the popped-out container.
 */
internal val LocalUiBuilderDetachedFrom = staticCompositionLocalOf<UiBuilderDocument?> { null }

/**
 * Leave pointer input to the design under the editor overlay: on the device view the overlay
 * selects through [passThroughClick] so lists still get wheel and drag.
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
 * Scrolls the item of a device-sized lazy container that holds the selection into view, only when
 * the selection changes (manual scrolling is respected). Not at the extent, nor in surfaces without
 * a selection.
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
 * Keeps [composed] holding the ids a carousel currently draws; `CarouselState` has no layout info,
 * but it composes exactly its visible items.
 */
@Composable
internal fun MarkComposed(composed: MutableSet<String>, id: String) {
  DisposableEffect(composed, id) {
    composed += id
    onDispose { composed -= id }
  }
}

/**
 * A primary click on the overlay that lets the design under it keep the pointer. Watches the shared
 * stream in [PointerEventPass.Initial]: a press released within touch slop is a click (its release
 * consumed, [onClick] told where); a press that travels further is a scroll and is left alone.
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
