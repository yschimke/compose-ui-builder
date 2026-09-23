package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Rect
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderBreadcrumbEntry
import ee.schimke.composeai.uibuilder.editor.UiBuilderDropAxis
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSlotInspection
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A canvas drag answers two questions the slot tint never could: *which* slot, and where **inside**
 * it. These tests pin the seam to the pointer against measured geometry, the land to the seam that
 * was shown, and the refusal rules a move drag inherits from the layers panel.
 */
class CanvasDropPlanTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private val state = reducer.initial(document, selectedNodeId = "plan-a")

  /** A slot's union, as the renderer would report it. */
  private fun slot(parent: String, name: String, bounds: Rect) =
    UiBuilderSlotInspection(
      parentNodeId = parent,
      slotName = name,
      childNodeIds = document.nodes.getValue(parent).slots[name].orEmpty(),
      measuredChildNodeIds = document.nodes.getValue(parent).slots[name].orEmpty(),
      bounds = bounds.toPixelBounds(),
    )

  private fun bounds(vararg pairs: Pair<String, Rect>): Map<String, UiBuilderPixelBounds> =
    pairs.associate { (id, rect) ->
      id to rect.toPixelBounds()
    }

  private fun Rect.toPixelBounds() = UiBuilderPixelBounds(left, top, right - left, bottom - top)

  /**
   * The slot geometry the tests read: a scaffold above, a column in its content, and a box with one
   * child inside the column. The content and the column deliberately do not share their union's
   * area — a tie would make the smallest-slot answer depend on map order — and the column's
   * children are narrower than the column, leaving a sparse strip the container rule must answer
   * for.
   */
  private val nodeBounds =
    bounds(
      "plan-scaffold" to Rect(0f, 0f, 300f, 300f),
      "plan-column" to Rect(10f, 20f, 290f, 280f),
      "plan-a" to Rect(10f, 20f, 150f, 100f),
      "plan-box" to Rect(10f, 100f, 290f, 200f),
      "plan-n1" to Rect(20f, 110f, 280f, 190f),
      "plan-c" to Rect(10f, 200f, 150f, 280f),
    )

  private val slots =
    listOf(
      slot("plan-scaffold", "topBar", Rect(0f, 0f, 300f, 20f)),
      slot("plan-scaffold", "content", Rect(0f, 20f, 300f, 300f)),
      slot("plan-scaffold", "snackbarHost", Rect(0f, 280f, 300f, 300f)),
      slot("plan-column", "children", Rect(10f, 20f, 290f, 280f)),
      slot("plan-box", "children", Rect(20f, 110f, 280f, 190f)),
    )

  private fun dropPlan(x: Float, y: Float) =
    reducer.catalogDropPlan(state, "m3/text", slots, nodeBounds, x, y)

  private fun movePlan(nodeId: String, x: Float, y: Float) =
    reducer.canvasMovePlan(state, nodeId, slots, nodeBounds, x, y)

  @Test
  fun `a drag between a column's children names the seam it is between`() {
    // Above the first child's centre: first in the slot.
    assertEquals(0, dropPlan(150f, 30f)?.index)
    assertNull(dropPlan(150f, 30f)?.afterNodeId)
    // Below it, above the second's centre: after the first.
    assertEquals(1, dropPlan(150f, 60f)?.index)
    assertEquals("plan-a", dropPlan(150f, 60f)?.afterNodeId)
    // Past the last child's centre: appended.
    assertEquals(3, dropPlan(150f, 250f)?.index)
    assertEquals("plan-c", dropPlan(150f, 250f)?.afterNodeId)
  }

  @Test
  fun `a sparse single-slot container is hit anywhere in itself`() {
    // plan-column holds three children in its left half; the point is in the right half — inside
    // the container, outside every child. The drop is into the column, at the seam the pointer's
    // own coordinate picks.
    val plan = dropPlan(250f, 250f)

    assertEquals("plan-column", plan?.target?.nodeId)
    assertEquals(Rect(10f, 20f, 290f, 280f).toPixelBounds(), plan?.bounds)
    assertEquals(3, plan?.index)
    assertEquals("plan-c", plan?.afterNodeId)
  }

  @Test
  fun `a nested slot under the pointer wins, and its axis is the geometry's`() {
    val plan = dropPlan(150f, 150f)

    assertEquals("plan-box", plan?.target?.nodeId)
    // One child in a wide slot: the fallback axis is the slot's own shape, horizontal.
    assertEquals(UiBuilderDropAxis.Horizontal, plan?.axis)
    assertEquals(1, plan?.index)
    assertEquals("plan-n1", plan?.afterNodeId)
  }

  @Test
  fun `a column reads as vertical even where a single child would not`() {
    assertEquals(UiBuilderDropAxis.Vertical, dropPlan(150f, 60f)?.axis)
  }

  @Test
  fun `an empty slot is its own landing region, with no seam to name`() {
    // A layout primitive is what a top bar takes; the point is in the top bar's strip and nowhere
    // else that would accept it.
    val plan = reducer.catalogDropPlan(state, "layout/box", slots, nodeBounds, 150f, 10f)

    assertEquals("topBar", plan?.target?.slot)
    assertTrue(plan?.children?.isEmpty() == true)
    assertEquals(0, plan?.index)
    assertNull(plan?.afterNodeId)
  }

  @Test
  fun `the drop lands at the seam the pointer was shown`() {
    val landed =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent(
          "m3/text",
          ParentSlot("plan-column", "children"),
          afterNodeId = "plan-a",
        ),
      )

    assertEquals(
      listOf("plan-a", landed.selectedNodeId, "plan-box", "plan-c"),
      landed.document.nodes.getValue("plan-column").slots.getValue("children"),
    )
  }

  @Test
  fun `a seam the document no longer holds falls back to appending`() {
    val landed =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent(
          "m3/text",
          ParentSlot("plan-column", "children"),
          afterNodeId = "removed-while-dragging",
        ),
      )

    assertEquals(
      listOf("plan-a", "plan-box", "plan-c", landed.selectedNodeId),
      landed.document.nodes.getValue("plan-column").slots.getValue("children"),
    )
  }

  @Test
  fun `a move reads the seam the slot will have without the node it carries`() {
    val plan = movePlan("plan-box", 150f, 150f)

    // plan-box sits between plan-a and plan-c; the pointer at its own centre is the seam after
    // plan-a — the place it already is, which a release must treat as the no-op it is.
    assertEquals("plan-column", plan?.target?.nodeId)
    assertEquals("plan-a", plan?.afterNodeId)
    assertEquals(1, plan?.index)
  }

  @Test
  fun `a move onto its own subtree is no plan at all`() {
    // Every slot under the pointer is inside the scaffold being carried; the cycle rule takes them
    // all, and the canvas draws no marker rather than one the release would betray.
    assertNull(movePlan("plan-scaffold", 150f, 150f))
  }

  @Test
  fun `a move onto a slot with no room is no plan at all`() {
    // The content slot holds its one child, and nothing under this point would take the text
    // either: refused before a marker promises otherwise.
    assertNull(movePlan("plan-a", 295f, 290f))
  }

  @Test
  fun `the selection's path reads root to leaf and names the slots that are a choice`() {
    val path = reducer.selectionPath(reducer.initial(document, selectedNodeId = "plan-n1"))

    assertEquals(
      listOf("plan-scaffold", "plan-column", "plan-box", "plan-n1"),
      path.map(UiBuilderBreadcrumbEntry::nodeId),
    )
    // The scaffold declares three slots, so which one the column sits in is information; the
    // column and the box declare one each, and their children's slot names say nothing.
    assertEquals("content", path[1].inSlot)
    assertNull(path[2].inSlot)
    assertNull(path[3].inSlot)
    // What the layers panel would have called the leaf, not a second naming scheme.
    assertEquals("N1", path[3].label)
    assertEquals("m3/text", path[3].componentId)
  }

  @Test
  fun `an empty selection has no path`() {
    assertTrue(reducer.selectionPath(reducer.initial(document)).isEmpty())
  }

  /**
   * A screen in the shape the tests above read geometry from: a scaffold whose top bar and content
   * are filled, a column of three children in the content, and a box with one child inside the
   * column.
   */
  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "canvas-drop-plan-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Drop plan fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 320, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "scaffold",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "plan-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "plan-scaffold", "slot": "content"},
                "node": {"id": "plan-column", "componentId": "layout/column"}
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "node": {
                  "id": "plan-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
                }
              },
              {
                "operationId": "box",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-a",
                "node": {"id": "plan-box", "componentId": "layout/box"}
              },
              {
                "operationId": "n1",
                "type": "insertNode",
                "parent": {"nodeId": "plan-box", "slot": "children"},
                "node": {
                  "id": "plan-n1",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "N1"}}
                }
              },
              {
                "operationId": "c",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-box",
                "node": {
                  "id": "plan-c",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "C"}}
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject
  }
}
