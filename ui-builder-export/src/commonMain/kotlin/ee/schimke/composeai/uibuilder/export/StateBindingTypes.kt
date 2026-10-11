package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.*

/**
 * Whether a state reference produces a value admitted by a catalog property; null means it is not a
 * state reference and literal validation applies.
 *
 * Properties admitting objects keep their binding vocabulary. Scalar-only properties may read
 * matching scalar state, nullable only if they admit null. Allowed-values properties cannot take
 * unconstrained state. Shared by the browser validator and the persistent service.
 */
fun stateBindingMatchesCatalog(
  value: JsonElement,
  jsonType: JsonElement,
  allowedValues: List<JsonElement>,
  declarations: Map<String, JsonElement>,
  propertyName: String,
  /** The row fields and loop indices in scope, which a computed value may read. */
  bindingKinds: (String) -> UiValueKind? = { null },
): Boolean? {
  val binding = value as? JsonObject ?: return null
  val kind = (binding["type"] as? JsonPrimitive)?.content
  // A text's axes and features are a literal list every lane parses; none reads one from state.
  if (
    propertyName in FontSettings.PROPERTIES &&
      (kind == "state" || kind == "stateEquals" || UiExpressions.isComputed(binding))
  )
    return false
  if (UiExpressions.isComputed(binding))
    return computedMatchesCatalog(
      binding,
      jsonType,
      allowedValues,
      declarations,
      propertyName,
      bindingKinds,
    )
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
 * Whether a computed value (`expr`/`system`) may fill a property of [jsonType]: number into number,
 * Boolean into flag, anything printable into text, colour only into colour. An expression that does
 * not type against declared state never matches.
 */
private fun computedMatchesCatalog(
  value: JsonObject,
  jsonType: JsonElement,
  allowedValues: List<JsonElement>,
  declarations: Map<String, JsonElement>,
  propertyName: String,
  bindingKinds: (String) -> UiValueKind?,
): Boolean {
  val scope =
    UiExpressions.Scope(
      declarations
        .mapNotNull { (name, declaration) ->
          UiExpressions.Scope.stateKind(declaration as? JsonObject)?.let { name to it }
        }
        .toMap(),
      bindingKinds,
    )
  val checked = UiExpressions.check(value, scope) as? UiExpressions.Checked.Ok ?: return false
  val types =
    when (jsonType) {
      is JsonArray -> jsonType.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
      is JsonPrimitive -> setOf(jsonType.content)
      else -> emptySet()
    }
  // `object` only admits a binding wrapper; the scalar types beside it say what the property holds,
  // and an object-only property holds nothing a formula can produce.
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
