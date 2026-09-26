package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
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
 * to the wheel and the drag that are the reason to look at the frame at all. So the device view
 * hit-tests from the canvas instead — see `PinnedDesignCanvas` — and the overlay only draws.
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
