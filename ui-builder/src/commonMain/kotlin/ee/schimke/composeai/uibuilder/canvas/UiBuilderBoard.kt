package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The board: the container a design holding several top-level items keeps them in.
 *
 * It is an ordinary `layout/column`, so the renderer, both exporters and the screen projection need
 * no new shape; see `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`.
 */
object UiBuilderBoard {
  /** What a board *is*. Not a new component: see the class doc. */
  const val COMPONENT_ID: String = "layout/column"

  /** The slot a board holds its items in — a column's only one. */
  const val SLOT: String = "children"

  /**
   * The default gap between two items on a new board, in dp: wide enough to read as separate
   * things. Editable in the inspector like any node property.
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
 * A `layout/column` root is reused as the board so a design doesn't grow a column per item. Null
 * for an empty design (first Add is a root insert) and for any other root, which must be wrapped
 * first.
 */
val UiBuilderDocument.boardRootId: String?
  get() = roots.singleOrNull()?.takeIf { nodes[it]?.componentId == UiBuilderBoard.COMPONENT_ID }

/**
 * Whether this design is drawn as a board: a root board holding more than one item. Only the editor
 * chrome asks; renderer and export just draw the tree.
 */
val UiBuilderDocument.isBoard: Boolean
  get() = boardRootId?.let { (nodes[it]?.slots?.get("children")?.size ?: 0) > 1 } == true

/** How many top-level items the board holds, or 0 when this design is not one. */
val UiBuilderDocument.boardItemCount: Int
  get() = if (isBoard) nodes[boardRootId]?.slots?.get("children")?.size ?: 0 else 0

/**
 * The nodes a document-level question about "the top of the design" looks at: its roots, plus the
 * board's items when a board is the root.
 *
 * Keeps root-level lookups (e.g. the theme-host `m3/surface`) true after an Add beside wraps the
 * root. One level deep, deliberately.
 */
val UiBuilderDocument.topLevelNodes: List<UiBuilderNode>
  get() {
    val board = boardRootId
    val ids = if (board == null) roots else nodes[board]?.slots?.get(UiBuilderBoard.SLOT).orEmpty()
    return ids.mapNotNull(nodes::get)
  }
