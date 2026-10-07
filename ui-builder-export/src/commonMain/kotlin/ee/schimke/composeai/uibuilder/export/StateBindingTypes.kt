package ee.schimke.composeai.uibuilder.export

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
  if (UiExpressions.isComputed(binding))
    return computedMatchesCatalog(binding, jsonType, allowedValues, declarations, propertyName)
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

/**
 * Whether a computed value (`expr`/`system`) may fill a property of [jsonType].
 *
 * The same question [stateBindingMatchesCatalog] answers for a state read, asked of the type the
 * expression produces: a number into a number, a Boolean into a flag, anything printable into text,
 * a colour only into a colour. An expression that does not type against the declared state is not a
 * match at all — the canvas could not evaluate it.
 */
private fun computedMatchesCatalog(
  value: JsonObject,
  jsonType: JsonElement,
  allowedValues: List<JsonElement>,
  declarations: Map<String, JsonElement>,
  propertyName: String,
): Boolean {
  val scope =
    UiExpressions.Scope(
      declarations
        .mapNotNull { (name, declaration) ->
          UiExpressions.Scope.stateKind(declaration as? JsonObject)?.let { name to it }
        }
        .toMap()
    )
  val checked = UiExpressions.check(value, scope) as? UiExpressions.Checked.Ok ?: return false
  val types =
    when (jsonType) {
      is JsonArray -> jsonType.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
      is JsonPrimitive -> setOf(jsonType.content)
      else -> emptySet()
    }
  // `object` is how a catalog admits a binding wrapper beside a scalar — `["number", "object"]` —
  // so
  // it says nothing about which kind the property holds. The scalar types beside it do, and a
  // property that is only `object` (a structural value such as `showByState`) holds none a formula
  // can produce.
  if (allowedValues.isNotEmpty() || PropertyValueKinds.isAssetKey(propertyName)) return false
  val kind = checked.expr.kind
  if (PropertyValueKinds.isColour(propertyName)) return kind == UiValueKind.COLOR
  return when (kind) {
    UiValueKind.STRING -> "string" in types
    UiValueKind.BOOL -> "boolean" in types
    UiValueKind.INT -> "integer" in types || "number" in types
    UiValueKind.FLOAT -> "number" in types
    UiValueKind.COLOR -> false
  } || ("string" in types && kind != UiValueKind.COLOR)
}
