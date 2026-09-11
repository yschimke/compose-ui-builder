package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiMeasureLayout
import androidx.compose.ui.unit.Constraints

internal data class CanvasExtentInputs(
  val document: UiBuilderDocument,
  val state: Map<String, String?>,
)

internal val LocalCanvasExtentInputs =
  staticCompositionLocalOf<((CanvasExtentInputs) -> Unit)?> { null }

/**
 * Measures the editor's unrolled content, then gives it a finite height to fill.
 *
 * The surrounding surface supplies the frame height as a minimum. Measuring only against infinity
 * loses `fillMaxHeight` and `fillMaxSize`, including a selected state branch containing no text.
 * Keep one composition so interaction state and inspection belong to the content we actually draw.
 * Only the second measurement is placed, so the measurement probe publishes no node bounds.
 *
 * This is the sole multi-measure boundary, outside the authored tree. It performs at most two
 * measurements, never nests recursively per node, and always probes with an unbounded height so a
 * previously long branch can shrink again. The renderer still unrolls scrollable content here; the
 * separate device pane retains its normal bounded composition.
 */
@Suppress("DEPRECATION")
@Composable
internal fun CanvasExtentLayout(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val inputs = remember { mutableStateOf<CanvasExtentInputs?>(null) }
  val updateInputs = remember { { value: CanvasExtentInputs -> inputs.value = value } }
  // A child constrained to the previous extent cannot resize its parent by itself. Re-probe when
  // the document or interaction state changes, including a branch switch without a saved revision.
  val policy = remember(inputs.value) { CanvasExtentMeasurePolicy() }
  CompositionLocalProvider(LocalCanvasExtentInputs provides updateInputs) {
    MultiMeasureLayout(
      modifier = modifier,
      content = { Box(content = content) },
      measurePolicy = policy,
    )
  }
}

private class CanvasExtentMeasurePolicy : MeasurePolicy {
  override fun MeasureScope.measure(
    measurables: List<Measurable>,
    constraints: Constraints,
  ): MeasureResult {
    val child = measurables.single()
    val placed =
      if (constraints.hasBoundedHeight) {
        child.measure(constraints)
      } else {
        val natural =
          child.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val extent = maxOf(constraints.minHeight, natural.height)
        child.measure(constraints.copy(minHeight = extent, maxHeight = extent))
      }
    return layout(placed.width, placed.height) { placed.placeRelative(0, 0) }
  }
}
