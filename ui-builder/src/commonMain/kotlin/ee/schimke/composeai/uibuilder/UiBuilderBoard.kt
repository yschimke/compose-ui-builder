package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The board: the container a design holding several top-level items keeps them in.
 *
 * It is an ordinary `layout/column`, and that is the whole design decision
 * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
 * A column's `children` slot already accepts the `Scaffold`, `Container` and `Leaf` roles with
 * `AnyContent` traits, so a scaffold, a card and a chip are legal siblings in it; it already
 * declares the spacing and the alignment that are the whole of the arrangement; and all three
 * catalogs already carry it. Nothing downstream of the document learns a new shape — the renderer,
 * both Kotlin exporters and the screen projection see the column they have always seen, because
 * that is what it is.
 *
 * The alternative was to let `roots` hold several items and have four consumers each invent a
 * picture the document did not contain. The four reasons that was refused are in the design doc;
 * the short one is that a synthetic arrangement has nowhere to put "move this up" or "make the gap
 * smaller", and a real node has the inspector.
 */
object UiBuilderBoard {
  /** What a board *is*. Not a new component: see the class doc. */
  const val COMPONENT_ID: String = "layout/column"

  /** The slot a board holds its items in — a column's only one. */
  const val SLOT: String = "children"

  /**
   * The gap between two items on a new board, in dp.
   *
   * 24 rather than a tighter number because the gap is what says "these are separate things". A
   * board holding a card and a dialog 8 dp apart reads as one screen laid out badly. A default
   * rather than a rule: it is a property of a node in the document, so the inspector edits it like
   * any other.
   */
  const val SPACING_DP: Int = 24

  /** A new, empty board — the node an Add beside wraps an existing root in. */
  fun node(id: String): UiBuilderNode =
    UiBuilderNode(
      id = id,
      componentId = COMPONENT_ID,
      properties =
        JsonObject(
          mapOf(
            "verticalSpacingDp" to literal("float", JsonPrimitive(SPACING_DP)),
            "horizontalAlignment" to literal("enum", JsonPrimitive("center")),
          )
        ),
      modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxWidth"))))),
    )

  private fun literal(type: String, value: JsonPrimitive): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))
}

/**
 * The node an Add beside would append into, or null when there is nothing to append beside yet.
 *
 * A `layout/column` root *is* the board — reusing it rather than wrapping it in a second one is
 * what keeps a design from growing a column per item added. Its spacing is then the author's own,
 * which is the honest outcome of the board being an ordinary node: what you see in the inspector is
 * what arranges the items.
 *
 * Null for an empty design (the first Add is an ordinary root insert) and for any other root, which
 * has to be wrapped first.
 */
val UiBuilderDocument.boardRootId: String?
  get() = roots.singleOrNull()?.takeIf { nodes[it]?.componentId == UiBuilderBoard.COMPONENT_ID }

/**
 * Whether this design is drawn as a board: a root board holding more than one item.
 *
 * A board with one child is a screen that happens to sit in a column, and saying otherwise would
 * put "this is a board of several items" on a design showing one. Nothing in the renderer or the
 * export branches on this — they draw the tree — so it is only ever asked by the editor's chrome,
 * to say what is being looked at.
 */
val UiBuilderDocument.isBoard: Boolean
  get() = boardRootId?.let { (nodes[it]?.slots?.get("children")?.size ?: 0) > 1 } == true

/** How many top-level items the board holds, or 0 when this design is not one. */
val UiBuilderDocument.boardItemCount: Int
  get() = if (isBoard) nodes[boardRootId]?.slots?.get("children")?.size ?: 0 else 0

/**
 * The nodes a document-level question about "the top of the design" has to look at: its roots, and
 * the board's items when a board is the root.
 *
 * A board is a container the editor put there, not something the author reached for, so a question
 * that was true of the root before an Add beside must stay true after it. The theme host is the
 * case that made this necessary: both lookups for the `m3/surface` carrying the palette, the type
 * scale and the corner radius scanned `roots` alone, so wrapping a themed screen dropped its theme
 * from the canvas and made Apply theme refuse the document for having no root surface.
 *
 * One level, deliberately. This is "the things the board holds", not a search: a surface three
 * levels inside a card was never the theme host and does not become one.
 */
val UiBuilderDocument.topLevelNodes: List<UiBuilderNode>
  get() {
    val board = boardRootId
    val ids = if (board == null) roots else nodes[board]?.slots?.get(UiBuilderBoard.SLOT).orEmpty()
    return ids.mapNotNull(nodes::get)
  }
