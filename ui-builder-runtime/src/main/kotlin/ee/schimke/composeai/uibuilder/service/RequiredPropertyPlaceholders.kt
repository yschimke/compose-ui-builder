package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
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
 * The mirror of [undeclaredProperties]: when a catalog starts requiring a property (e.g.
 * `m3/primary-tab-row`'s `selectedTabIndex`), older designs fail validation and cannot even be
 * upgraded. The placeholder is what the generator writes for an unset required scalar: the first
 * allowed value, else the type's zero — a real, editable value that the move reports.
 *
 * Scalars only: required objects or lists stay a blocking refusal.
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
 */
internal fun requiredPlaceholder(property: PropertyCapabilityV1): UiValueV1? {
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
