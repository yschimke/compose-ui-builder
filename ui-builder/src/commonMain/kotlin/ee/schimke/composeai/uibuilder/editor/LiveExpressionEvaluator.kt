package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.WffConfigurationState
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import ee.schimke.composeai.uibuilder.canvas.WffSimulatedState
import ee.schimke.composeai.uibuilder.canvas.WffValue
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.isWatchFace
import ee.schimke.composeai.uibuilder.canvas.wffConfigurationValues
import ee.schimke.composeai.uibuilder.canvas.wffConfigurations
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiExpressions
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The live preview of an expression typed into the inspector: its current value or an error. */
internal sealed interface LiveExpressionResult {
  data class Value(val formatted: String) : LiveExpressionResult

  data class Error(val message: String) : LiveExpressionResult
}

/**
 * Evaluates a general UI Builder formula (Remote Compose and Compose documents) against
 * [document]'s declared state defaults, device geometry, and fixed time. Pure client-side
 * evaluation, never throws.
 */
internal fun evaluateLiveFormula(
  document: UiBuilderDocument,
  formula: String,
  fieldLabel: String = "formula",
): LiveExpressionResult? {
  val trimmed = formula.trim()
  if (trimmed.isEmpty()) return null
  val parsed =
    try {
      UiExpressions.parseFormula(trimmed, document.stateVariables.keys)
    } catch (failure: UiExpressions.FormulaError) {
      return LiveExpressionResult.Error(failure.message ?: "Invalid formula")
    }
  val scope = UiExpressions.Scope.of(document)
  return when (val checked = UiExpressions.check(parsed, scope, fieldLabel)) {
    is UiExpressions.Checked.Issue -> LiveExpressionResult.Error(checked.message)
    is UiExpressions.Checked.Ok -> {
      if (UiExpressions.readsCanvas(checked.expr)) {
        return LiveExpressionResult.Value("(depends on canvas size)")
      }
      val fixedTime = (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull.orEmpty()
      val stateValues =
        document.stateVariables.mapValues { (_, declaration) ->
          ((declaration as? kotlinx.serialization.json.JsonObject)?.get("initialValue")
              as? JsonPrimitive)
            ?.contentOrNull
        }
      val env =
        UiExpressions.Environment(
          state = stateValues,
          clock = UiExpressions.Clock.of(fixedTime),
          device = UiExpressions.Device.of(document.environment),
        )
      val evaluated =
        runCatching { UiExpressions.evaluate(checked.expr, env) }.getOrNull()
          ?: return LiveExpressionResult.Error("Could not evaluate")
      LiveExpressionResult.Value(formatEvaluatedValue(evaluated))
    }
  }
}

/**
 * Evaluates a Watch Face Format property string containing `[SOURCE]` expressions or
 * `[CONFIGURATION.<id>.<n>]` colour references against [document]'s active configurations,
 * [simulated] watch state, and optional [scrubbedTime]. Returns null for plain literals that have
 * no WFF source references.
 */
internal fun evaluateLiveWffProperty(
  document: UiBuilderDocument,
  raw: String,
  simulated: WffSimulatedState = WffSimulatedState(),
  scrubbedTime: WffScrubTime? = null,
): LiveExpressionResult? {
  if (!document.isWatchFace()) return null
  val trimmed = raw.trim()
  if (!trimmed.contains('[') || !trimmed.contains(']')) return null

  val configs = document.wffConfigurations()
  val configState = WffConfigurationState(configs)
  configState.resolveColour(trimmed)?.let { hex ->
    return LiveExpressionResult.Value(hex)
  }

  val fixedTime = (document.environment["fixedTime"] as? JsonPrimitive)?.contentOrNull.orEmpty()
  val scene = document.roots.firstNotNullOfOrNull { document.nodes[it] }
  val previewTime =
    ((scene?.properties?.get("previewTime") as? kotlinx.serialization.json.JsonObject)?.get("value")
        as? JsonPrimitive)
      ?.contentOrNull
      .orEmpty()
  val base = WffPreviewContext.of(previewTime, fixedTime)
  val timed = if (scrubbedTime != null) base.at(scrubbedTime) else base
  val context = timed.withConfiguration(document.wffConfigurationValues()).withSimulated(simulated)

  val value = evaluateWffExpression(trimmed, context)
  return when (value) {
    is WffValue.Num -> LiveExpressionResult.Value(formatNumber(value.value))
    is WffValue.Str -> LiveExpressionResult.Value("\"${value.value}\"")
    null -> LiveExpressionResult.Error("Unrecognized or incomplete WFF expression")
  }
}

private fun formatEvaluatedValue(value: Any): String =
  when (value) {
    is Double -> formatNumber(value)
    is Float -> formatNumber(value.toDouble())
    is String -> "\"$value\""
    else -> value.toString()
  }

private fun formatNumber(value: Double): String {
  if (!value.isFinite()) return value.toString()
  val rounded = value.roundToLong()
  return if (kotlin.math.abs(value - rounded.toDouble()) < 1e-6) {
    rounded.toString()
  } else {
    val hundredths = (value * 100.0).roundToLong() / 100.0
    hundredths.toString()
  }
}

/** Inline live evaluation readout shown under a formula or WFF expression field. */
@Composable
internal fun LiveExpressionReadout(
  result: LiveExpressionResult?,
  fieldLabel: String,
  modifier: Modifier = Modifier,
) {
  if (result == null) return
  val text =
    when (result) {
      is LiveExpressionResult.Value -> "= ${result.formatted}"
      is LiveExpressionResult.Error -> result.message
    }
  val color =
    when (result) {
      is LiveExpressionResult.Value -> MaterialTheme.colorScheme.primary
      is LiveExpressionResult.Error -> MaterialTheme.colorScheme.error
    }
  Text(
    text = text,
    modifier =
      modifier.padding(top = 2.dp).semantics {
        contentDescription = "$fieldLabel live evaluation: $text"
      },
    color = color,
    fontFamily = FontFamily.Monospace,
    style = MaterialTheme.typography.labelSmall,
  )
}
