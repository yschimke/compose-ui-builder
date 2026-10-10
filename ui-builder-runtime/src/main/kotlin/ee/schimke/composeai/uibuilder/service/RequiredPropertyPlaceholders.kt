package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A required property a stored node lacks, and the placeholder that stands in for it.
 *
 * ## Why this exists
 *
 * The mirror of [undeclaredProperties]: a catalog that starts REQUIRING a property strands every
 * design written before it did. `PrimaryTabRow(selectedTabIndex: Int)` has no Compose default, so
 * the record-built `m3/primary-tab-row` marks `selectedTabIndex` required, and every tab row drawn
 * against the Kotlin catalog that left it optional failed validation outright. On preview.coo.ee
 * that read `invalid stored design google-play-tablet: required property selectedTabIndex is
 * missing` for a design whose pin still resolved, and blocked the automatic catalog recovery
 * (#1054) for the four whose pin no longer did -- their upgrade previews came back BLOCKED on the
 * same missing property, so nothing ever moved them.
 *
 * Unlike an undeclared property there is no stored value to leave in place, so tolerance alone
 * cannot help: something has to be written. What is written is what the generator already writes at
 * the call site for an unset required scalar (`PrimaryTabRow(selectedTabIndex = 0)`): the first
 * value the catalog allows, else the type's zero. That is a real, editable value, and the move that
 * writes it reports it, so nobody mistakes the placeholder for the author's choice.
 *
 * Scalars only. A required object or list has no zero that means anything, so it gets no
 * placeholder and stays a blocking refusal -- the honest answer for a value nobody can guess.
 */
internal data class FilledRequiredProperty(
  val nodeId: String,
  val property: String,
  val value: UiValueV1,
)

/**
 * [this] with every required scalar property its nodes lack filled by [requiredPlaceholder], and
 * what was filled. Only nodes whose component [catalog] declares are touched: an unknown component
 * is a different, blocking defect, and a design-defined component is not the catalog's to judge.
 */
internal fun DesignDocumentV1.withRequiredPlaceholders(
  catalog: CatalogCapabilityV1
): Pair<DesignDocumentV1, List<FilledRequiredProperty>> {
  val declared = catalog.components.associateBy { it.componentId }
  val filled = mutableListOf<FilledRequiredProperty>()
  val nodes = nodes.mapValues { (nodeId, node) ->
    val component = declared[node.componentId] ?: return@mapValues node
    val missing =
      component.properties
        .filter { it.required && it.name !in node.properties }
        .mapNotNull { property -> requiredPlaceholder(property)?.let { property.name to it } }
    if (missing.isEmpty()) return@mapValues node
    missing.forEach { (name, value) -> filled += FilledRequiredProperty(nodeId, name, value) }
    node.copy(properties = node.properties + missing)
  }
  return if (filled.isEmpty()) this to emptyList() else copy(nodes = nodes) to filled
}

/**
 * The value an unset required [property] is given: the first of its `allowedValues` that is a
 * scalar, else the zero of its first scalar JSON type (`integer` before `number` before `boolean`
 * before `string`), else null when it has no scalar type at all.
 *
 * A property whose NAME gives its value a kind is answered by that kind, not by its JSON type: a
 * `string` zero passed `validate` but is refused by `validateWrite` and the export. A colour is the
 * first of its `allowedValues` as a colour token, else the theme's `onSurface` role, a value the
 * canvas draws and the export writes. An asset key has no value anybody could guess that resolves,
 * so it gets none and stays a blocking refusal.
 */
internal fun requiredPlaceholder(property: PropertyCapabilityV1): UiValueV1? {
  if (CurrentM3UiBuilderCatalogExecutor.isColourProperty(property.name)) {
    val allowed =
      property.allowedValues.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { it.isString } }
    return ColorTokenValueV1(allowed?.content ?: PLACEHOLDER_COLOUR_TOKEN)
  }
  if (ee.schimke.composeai.uibuilder.export.PropertyValueKinds.isAssetKey(property.name)) {
    return null
  }
  property.allowedValues
    .firstNotNullOfOrNull { allowedScalar(it) }
    ?.let {
      return it
    }
  if (property.allowedValues.isNotEmpty()) return null
  val types =
    when (val type = property.jsonType) {
      is JsonPrimitive -> listOfNotNull(type.contentOrNull)
      is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      else -> emptyList()
    }
  return when {
    "integer" in types -> IntegerValueV1(0)
    "number" in types -> DecimalValueV1(0.0)
    "boolean" in types -> BooleanValueV1(false)
    "string" in types -> StringValueV1("")
    else -> null
  }
}

private fun allowedScalar(value: JsonElement): UiValueV1? {
  val primitive = value as? JsonPrimitive ?: return null
  return when {
    primitive.isString -> EnumValueV1(primitive.content)
    primitive.booleanOrNull != null -> BooleanValueV1(primitive.booleanOrNull!!)
    primitive.longOrNull != null -> IntegerValueV1(primitive.longOrNull!!)
    primitive.doubleOrNull != null -> DecimalValueV1(primitive.doubleOrNull!!)
    else -> null
  }
}

/**
 * One of [CurrentM3UiBuilderCatalogExecutor.CANVAS_COLOR_TOKENS], so the placeholder always draws.
 */
private const val PLACEHOLDER_COLOUR_TOKEN = "onSurface"
