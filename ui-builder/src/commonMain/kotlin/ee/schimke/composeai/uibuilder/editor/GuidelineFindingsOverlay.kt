package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFinding
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineState
import ee.schimke.composeai.uibuilder.guidelines.LocalDesignGuidelineCheck
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot

/**
 * The guidelines findings over the design: an outline on each node a finding names and a badge on
 * its corner. The findings are the panel's — this person's latest run, else the design's recorded
 * one — and the overlay follows the "Show findings on the canvas" switch there.
 *
 * Composables rather than a canvas pass, because a badge is clicked (it selects the node) and read
 * out (it names the rule and what is wrong).
 */
@Composable
internal fun GuidelineFindingsOverlay(
  inspection: UiBuilderInspectionSnapshot?,
  /** Where the frame's own top-left is in root space, as for the presence overlay. */
  frameOrigin: Offset,
  drawScale: Float,
  onNodeSelected: (String) -> Unit,
) {
  val controller = LocalDesignGuidelineCheck.current ?: return
  val shown by controller.overlay.collectAsState()
  if (!shown) return
  val state by controller.state.collectAsState()
  val shared by controller.shared.collectAsState()
  val result = (state as? DesignGuidelineState.Ready)?.result ?: shared ?: return
  val bounds =
    inspection?.nodes?.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }?.toMap()
      ?: return
  // One mark per node, carrying every finding about it, the warnings first.
  val byNode =
    result.findings
      .flatMap { finding -> finding.nodeIds.map { it to finding } }
      .groupBy({ it.first }, { it.second })
      .filterKeys { it in bounds }
  if (byNode.isEmpty()) return
  val density = LocalDensity.current
  Box(Modifier.fillMaxSize()) {
    byNode.forEach { (nodeId, findings) ->
      val box = bounds.getValue(nodeId)
      val warning = findings.any { it.rule.severity == "warning" }
      val color =
        if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
      with(density) {
        val x = ((box.x - frameOrigin.x) / drawScale).toDp()
        val y = ((box.y - frameOrigin.y) / drawScale).toDp()
        val w = (box.width / drawScale).toDp()
        val h = (box.height / drawScale).toDp()
        Box(Modifier.offset(x, y).size(w, h).border(2.dp, color, RoundedCornerShape(4.dp)))
        Box(
          Modifier.offset(x + w - BADGE_SIZE / 2, y - BADGE_SIZE / 2)
            .size(BADGE_SIZE)
            .background(color, CircleShape)
            .clickable { onNodeSelected(nodeId) }
            .semantics { contentDescription = describe(nodeId, findings) },
          contentAlignment = Alignment.Center,
        ) {
          Text(
            if (findings.size > 1) "${findings.size}" else "!",
            style = MaterialTheme.typography.labelSmall,
            color =
              if (warning) MaterialTheme.colorScheme.onTertiary
              else MaterialTheme.colorScheme.surface,
          )
        }
      }
    }
  }
}

private fun describe(nodeId: String, findings: List<DesignGuidelineFinding>): String =
  "Guidelines on $nodeId: " + findings.joinToString("; ") { "${it.rule.id}, ${it.reason}" }

private val BADGE_SIZE = 18.dp
