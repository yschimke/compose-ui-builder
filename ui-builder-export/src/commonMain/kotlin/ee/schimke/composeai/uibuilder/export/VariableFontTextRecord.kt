package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The record components a Material 3 screen's variable font texts resolve through.
 *
 * Each text calls a declaration flexpress generates at export into the screen's own package, so no
 * catalog records it; one component per [VariableFontText.Request] says what the generator may
 * write there, the way [ScreenTheme]'s `MaterialTheme` record does for the theme. Its parameters
 * are the declaration's: each animated axis a `() -> Float`, then `fontSize`, `modifier` and
 * `color`.
 */
internal object VariableFontTextRecord {
  /** The text's size when the design sets none. */
  const val DEFAULT_SIZE_SP: Double = 32.0

  /** The id the projection gives a node calling [functionName]. */
  fun componentId(functionName: String): String = "${VariableFontText.M3_ID}@$functionName"

  /** [record] with a component for each of [requests], declared in [packageName]. */
  fun with(
    record: ComponentRecordFile,
    requests: List<VariableFontText.Request>,
    packageName: String = ScreenExportGate.PACKAGE_NAME,
  ): ComponentRecordFile {
    if (requests.isEmpty()) return record
    val file = buildJsonObject {
      put("schemaVersion", 2)
      put("module", "ui-builder")
      put("variant", "variable-font-text")
      put("components", JsonArray(requests.map { component(it, packageName) }))
    }
    val generated = json.decodeFromJsonElement(ComponentRecordFile.serializer(), file)
    return record.copy(components = record.components + generated.components)
  }

  private fun component(request: VariableFontText.Request, packageName: String): JsonObject {
    val name = request.functionName
    return buildJsonObject {
      put("canonicalId", "ui-builder/$packageName.${name}Kt.$name")
      putJsonArray("componentIds") { add(componentId(name)) }
      putJsonObject("symbol") {
        put("jvmOwner", "$packageName.${name}Kt")
        put("callable", "$packageName.$name")
        put("name", name)
        put("origin", "LIBRARY")
      }
      putJsonArray("parameters") {
        request.spec.animated.forEach { tag ->
          addJsonObject {
            put("name", tag.lowercase())
            put("type", "() -> Float")
            put("hasDefault", false)
            put("typeFqn", "kotlin.Function0")
            put("lambdaReturnTypeFqn", "kotlin.Float")
          }
        }
        parameter("fontSize", "TextUnit", "androidx.compose.ui.unit.TextUnit", default = false)
        parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true)
        parameter("color", "Color", "androidx.compose.ui.graphics.Color", default = true)
      }
      putJsonArray("slots") {}
      putJsonObject("code") {
        put("call", "$name()")
        putJsonArray("imports") {}
      }
      put("signatureKnown", true)
    }
  }

  private fun kotlinx.serialization.json.JsonArrayBuilder.parameter(
    name: String,
    type: String,
    typeFqn: String,
    default: Boolean,
  ) {
    add(
      buildJsonObject {
        put("name", name)
        put("type", type)
        put("hasDefault", JsonPrimitive(default))
        put("typeFqn", typeFqn)
      }
    )
  }

  private val json = Json { ignoreUnknownKeys = true }
}
