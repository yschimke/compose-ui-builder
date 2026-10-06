package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.canvas.topLevelNodes
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.DesignToken
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.math.round
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What a token reads as in one document: what its bound properties hold, together. */
sealed interface DesignTokenValue {
  /** Nothing bound holds a value: the design system's own value is drawn. */
  data object Unset : DesignTokenValue

  /** Every bound property that holds a value holds this one. */
  data class Set(val value: JsonPrimitive) : DesignTokenValue

  /** The bound properties disagree: the token was applied, then one of them was edited by hand. */
  data object Mixed : DesignTokenValue
}

/** One row of the Theme panel's token section. */
data class EditorDesignTokenRow(
  val token: DesignToken,
  val value: DesignTokenValue,
  /** How many properties in this design the token would write; 0 means it has nowhere to land. */
  val targetCount: Int,
)

/**
 * The properties [this] token writes in [document]: its theme bindings on the design's top-level
 * nodes — the theme host, as [themeHost] reads it, one level under a board — and its component
 * bindings on every node of that component. Only where the catalog declares the property, and never
 * one a state binding or a component argument holds: that value is not the token's to replace.
 */
internal fun DesignToken.targets(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): List<TunableTarget.Property> {
  val topLevel = document.topLevelNodes.map { it.id }.toSet()
  return bindings
    .flatMap { binding ->
      document.nodes.values
        .filter { node ->
          node.componentId == binding.componentId &&
            (!binding.theme || node.id in topLevel) &&
            catalog.componentsById[node.componentId]
              ?.propertiesByName
              ?.containsKey(binding.property) == true
        }
        .filter { node ->
          val held = node.properties[binding.property] as? JsonObject
          val wrapper = (held?.get("type") as? JsonPrimitive)?.contentOrNull
          wrapper !in STATE_VALUE_TYPES && held?.bindingKey() == null
        }
        .map { TunableTarget.Property(it.id, binding.property) }
    }
    .distinct()
}

/** What [this] token reads as in [document]; see [DesignTokenValue]. */
internal fun DesignToken.valueIn(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): DesignTokenValue {
  val held =
    targets(document, catalog)
      .mapNotNull { target ->
        val encoded = document.nodes[target.nodeId]?.properties?.get(target.property) as? JsonObject
        (encoded?.get("value") as? JsonPrimitive)?.normalised(kind)
      }
      .distinct()
  return when (held.size) {
    0 -> DesignTokenValue.Unset
    1 -> DesignTokenValue.Set(held.single())
    else -> DesignTokenValue.Mixed
  }
}

/** Numbers compare as numbers, so `8` and `8.0` written by different editors read as one value. */
private fun JsonPrimitive.normalised(kind: DesignTokenKind): JsonPrimitive =
  if (kind == DesignTokenKind.Number && !isString) doubleOrNull?.let(::JsonPrimitive) ?: this
  else this

/** [value] as this token's literal, or why it is not one. */
internal fun DesignToken.parse(value: String): Result<JsonPrimitive> = runCatching {
  val trimmed = value.trim()
  when (kind) {
    DesignTokenKind.Number -> {
      val number =
        requireNotNull(trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }) {
          "$label must be a number"
        }
      val low = minimum ?: Double.NEGATIVE_INFINITY
      val high = maximum ?: Double.POSITIVE_INFINITY
      require(number in low..high) {
        "$label must be ${formatTunableValue(low)}..${formatTunableValue(high)}"
      }
      require(!integer || number % 1.0 == 0.0) { "$label must be a whole number" }
      JsonPrimitive(number)
    }
    DesignTokenKind.Color -> {
      require(trimmed.isNotEmpty() && PropertyValueKinds.isDrawableColour(trimmed)) {
        "$label must be #RRGGBB, #AARRGGBB or a theme colour role"
      }
      JsonPrimitive(trimmed)
    }
  }
}

/**
 * The writes that set [this] token to [value] in [document] — or, for a null [value], that reset
 * it: to the catalog's [DesignToken.default] where it has one, and otherwise by unsetting every
 * bound property so the design system's own value is drawn again. A required property is the one
 * that cannot be unset, so a reset leaves it holding what it holds.
 */
internal fun DesignToken.writes(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  value: JsonPrimitive?,
): List<DesignOperation> {
  val written = value ?: default
  return targets(document, catalog).mapNotNull { target ->
    val node = document.nodes.getValue(target.nodeId)
    val property =
      catalog.componentsById.getValue(node.componentId).propertiesByName.getValue(target.property)
    val held = node.properties[target.property] as? JsonObject
    if (written == null) {
      return@mapNotNull if (held == null || property.required) null
      else DesignOperation.RemoveNodeProperty(target.nodeId, target.property)
    }
    val encoded = encode(written, held, property.typeNames())
    if (held == encoded) null
    else DesignOperation.SetProperty(target.nodeId, target.property, encoded)
  }
}

/** [value] in the wrapper the export reads for this kind, keeping an existing `int` an `int`. */
private fun DesignToken.encode(
  value: JsonPrimitive,
  held: JsonObject?,
  declared: Set<String>,
): JsonObject =
  when (kind) {
    DesignTokenKind.Color -> literal(colourWrapper(value), value)
    DesignTokenKind.Number -> {
      val heldWrapper = (held?.get("type") as? JsonPrimitive)?.contentOrNull
      val whole = heldWrapper == "int" || (heldWrapper == null && "integer" in declared)
      val number = value.jsonPrimitive.doubleOrNull ?: 0.0
      if (whole) literal("int", JsonPrimitive(round(number).toLong()))
      else literal(heldWrapper ?: "float", JsonPrimitive(number))
    }
  }

/**
 * A tunable over [this] number token: the token's range, its current value as the default, and
 * every property it binds as a target. The midpoint of the range stands in for a token that is
 * unset and has no default, since "the design system's own value" is not a number to start from.
 */
internal fun DesignToken.tunable(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
  name: String,
): DesignTunable? {
  if (kind != DesignTokenKind.Number) return null
  val low = minimum ?: return null
  val high = maximum ?: return null
  val current =
    (valueIn(document, catalog) as? DesignTokenValue.Set)?.value?.doubleOrNull
      ?: default?.doubleOrNull
      ?: ((low + high) / 2).let { if (integer) round(it) else it }
  return DesignTunable(
    name = name,
    minimum = low,
    maximum = high,
    default = current.coerceIn(low, high),
    integer = integer,
    targets = targets(document, catalog),
    token = id,
  )
}

/**
 * [tunables] with each token tunable's targets re-read from [document], so a list added after the
 * slider was made is tuned with the rest — a token is a statement about every node of a component,
 * not about the ones there were when somebody pressed Tune.
 */
internal fun List<DesignTunable>.withTokenTargets(
  document: UiBuilderDocument,
  catalog: CapabilityCatalog,
): List<DesignTunable> {
  if (none { it.token != null }) return this
  val tokens = catalog.designTokens.associateBy(DesignToken::id)
  return map { tunable ->
    val token = tunable.token?.let(tokens::get) ?: return@map tunable
    val targets = token.targets(document, catalog)
    if (targets == tunable.targets) tunable else tunable.copy(targets = targets)
  }
}
