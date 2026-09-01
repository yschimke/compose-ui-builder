package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UiBuilderSemanticActionControllerTest {
  @Test
  fun `overlapping nodes activate only the exact semantic target`() {
    var first = 0
    var second = 0
    val controller =
      controller(
        "first" to UiBuilderSemanticActionEntry(activate = { first++ }),
        "second" to UiBuilderSemanticActionEntry(activate = { second++ }),
      )
    val result = controller.dispatch(action("second", "activate"), snapshot("first", "second"))
    assertIs<UiBuilderSemanticActionResult.Applied>(result)
    assertEquals(0, first)
    assertEquals(1, second)
  }

  @Test
  fun `disabled and clipped nodes reject activation`() {
    val controller = controller("target" to UiBuilderSemanticActionEntry(activate = {}))
    val disabled = snapshot("target", enabled = false)
    assertEquals(
      "ACTION_DISABLED",
      assertIs<UiBuilderSemanticActionResult.Rejected>(
          controller.dispatch(action("target", "activate"), disabled)
        )
        .code,
    )
    val clipped = snapshot("target", bounds = UiBuilderPixelBounds(120f, 0f, 10f, 10f))
    assertEquals(
      "ACTION_NOT_VISIBLE",
      assertIs<UiBuilderSemanticActionResult.Rejected>(
          controller.dispatch(action("target", "activate"), clipped)
        )
        .code,
    )
  }

  @Test
  fun `vertical scroll uses the registered Compose scroll state`() {
    var delta = 0f
    val controller =
      controller(
        "list" to
          UiBuilderSemanticActionEntry(
            scrollBy = { value ->
              delta += value
              value
            }
          )
      )
    assertIs<UiBuilderSemanticActionResult.Applied>(
      controller.dispatch(action("list", "scrollBy", 240.0), snapshot("list"))
    )
    assertEquals(240f, delta)
  }

  @Test
  fun `stale snapshot and unavailable action reject`() {
    val controller = controller()
    assertEquals(
      "STALE_DOCUMENT",
      assertIs<UiBuilderSemanticActionResult.Rejected>(
          controller.dispatch(
            action("target", "activate"),
            snapshot("target").copy(documentRevision = 2),
          )
        )
        .code,
    )
    assertEquals(
      "ACTION_NOT_AVAILABLE",
      assertIs<UiBuilderSemanticActionResult.Rejected>(
          controller.dispatch(action("target", "activate"), snapshot("target"))
        )
        .code,
    )
  }

  private fun controller(vararg entries: Pair<String, UiBuilderSemanticActionEntry>) =
    UiBuilderSemanticActionController().also {
      it.install(entries.toMap(), UiBuilderPixelBounds(0f, 0f, 100f, 100f))
    }

  private fun action(nodeId: String, kind: String, deltaY: Double? = null) =
    CatalogRuntimeAction(
      documentId = "design",
      documentRevision = 1,
      nodeId = nodeId,
      kind = kind,
      deltaX = if (kind == "scrollBy") 0.0 else null,
      deltaY = deltaY,
    )

  private fun snapshot(
    vararg nodeIds: String,
    enabled: Boolean = true,
    bounds: UiBuilderPixelBounds = UiBuilderPixelBounds(0f, 0f, 50f, 50f),
  ) =
    UiBuilderInspectionSnapshot(
      documentId = "design",
      documentRevision = 1,
      generation =
        UiBuilderInspectionGeneration(
          "design@1",
          expectedAuthoredNodeIds = nodeIds.toList(),
          expectedAuthoredTextNodeIds = emptyList(),
          measuredNodeIds = nodeIds.toList(),
          measuredTextNodeIds = emptyList(),
        ),
      nodes =
        nodeIds.map { nodeId ->
          UiBuilderNodeInspection(
            nodeId,
            "m3/button",
            bounds,
            semantics =
              UiBuilderSemanticsInspection(
                role = "button",
                enabled = enabled,
                actions = listOf("click"),
              ),
          )
        },
      slots = emptyList(),
    )
}
