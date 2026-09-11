package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import kotlinx.serialization.json.*

/**
 * Whether a state reference produces a value admitted by a catalog property. Null means the value
 * is not a state reference and the ordinary literal validation applies.
 *
 * Catalogs that explicitly admit objects retain their existing binding vocabulary. Scalar-only
 * properties may also read matching scalar state, but cannot read nullable state unless they admit
 * null. An allowed-values property cannot take unconstrained dynamic state: its JSON scalar alone
 * does not describe the enum, token or call-site choice the catalog requires.
 *
 * Shared by the browser validator and persistent service so an editor binding can cross MCP/HTTP.
 */
fun stateBindingMatchesCatalog(
  value: JsonElement,
  jsonType: JsonElement,
  allowedValues: List<JsonElement>,
  declarations: Map<String, JsonElement>,
  propertyName: String,
): Boolean? {
  val binding = value as? JsonObject ?: return null
  val kind = (binding["type"] as? JsonPrimitive)?.content
  if (kind != "state" && kind != "stateEquals") return null
  val types =
    when (jsonType) {
      is JsonArray -> jsonType.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
      is JsonPrimitive -> setOf(jsonType.content)
      else -> emptySet()
    }
  if ("object" in types) return true
  if (PropertyValueKinds.isColour(propertyName) || PropertyValueKinds.isAssetKey(propertyName))
    return false
  if (allowedValues.isNotEmpty()) return false
  val variable = (binding["variable"] as? JsonPrimitive)?.content ?: return false
  val declaration = declarations[variable] as? JsonObject ?: return false
  if (kind == "stateEquals") return "boolean" in types
  val initial = declaration["initialValue"] as? JsonPrimitive
  val nullable = (declaration["nullable"] as? JsonPrimitive)?.booleanOrNull ?: (initial is JsonNull)
  if (nullable && "null" !in types) return false
  val stateType =
    (declaration["valueType"] as? JsonPrimitive)?.content
      ?: when {
        initial == null || initial is JsonNull -> return false
        initial.isString -> "string"
        initial.booleanOrNull != null -> "bool"
        initial.longOrNull != null -> "int"
        initial.doubleOrNull != null -> "float"
        else -> return false
      }
  return when (stateType) {
    "string" -> "string" in types
    "bool" -> "boolean" in types
    "int" -> "integer" in types || "number" in types
    "float" -> "number" in types
    else -> false
  }
}
