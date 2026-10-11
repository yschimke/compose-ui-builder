package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderInstancePath
import ee.schimke.composeai.uibuilder.preview.tabRowSelectionPreviewDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RenderNode` carries a [UiBuilderInstancePath] rather than a bare id, so repeated drawings
 * (loops, component instances) can be told apart. With no repetition, every path must equal the
 * node id, so bounds keys, inspection entries and comment anchors are unchanged.
 */
@OptIn(ExperimentalComposeUiApi::class)
class InstancePathBoundsTest {

  @Test
  fun `every measured box is reported under its authored node id`() {
    val document = tabRowSelectionPreviewDocument
    var snapshot: UiBuilderInspectionSnapshot? = null

    renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) {
      UiBuilderSurface(document = document, onInspectionSnapshot = { snapshot = it })
    }

    val measured = checkNotNull(snapshot) { "the canvas published no inspection snapshot" }
    val measuredIds = measured.nodes.filter { it.bounds != null }.map { it.nodeId }.sorted()

    assertEquals(document.nodes.keys.sorted(), measuredIds)
    assertTrue(measuredIds.none { it.contains('#') || it.contains('/') }, "$measuredIds")
  }

  /**
   * A node id may contain the path's punctuation (`a/b`); it must not be re-parsed from the
   * rendered string, or the box would be keyed under a name nothing matches.
   */
  @Test
  fun `a node id carrying path punctuation is measured under itself`() {
    val document = tabRowSelectionPreviewDocument.withPunctuatedIds()
    var snapshot: UiBuilderInspectionSnapshot? = null

    renderComposeScene(FRAME_PX, FRAME_PX, Density(1f)) {
      UiBuilderSurface(document = document, onInspectionSnapshot = { snapshot = it })
    }

    val measured = checkNotNull(snapshot) { "the canvas published no inspection snapshot" }
    assertEquals(
      document.nodes.keys.sorted(),
      measured.nodes.filter { it.bounds != null }.map { it.nodeId }.sorted(),
    )
  }

  /** Every id given the punctuation a path renders with, slots and roots kept in step. */
  private fun UiBuilderDocument.withPunctuatedIds(): UiBuilderDocument {
    fun rename(id: String) = "section/$id#0"
    return copy(
      roots = roots.map(::rename),
      nodes =
        nodes.entries.associate { (id, node) ->
          rename(id) to
            node.copy(
              id = rename(id),
              slots = node.slots.mapValues { (_, children) -> children.map(::rename) },
            )
        },
    )
  }

  /** The same claim from the renderer's end: with no repeat above, the box key is the id itself. */
  @Test
  fun `a path built the way the renderer builds one is the node id`() {
    val row = UiBuilderInstancePath.of("root")
    val tab = row.child("tab-1")

    assertEquals("tab-1", tab.value)
    assertEquals("tab-1", tab.nodeId)
  }

  private companion object {
    const val FRAME_PX = 400
  }
}
