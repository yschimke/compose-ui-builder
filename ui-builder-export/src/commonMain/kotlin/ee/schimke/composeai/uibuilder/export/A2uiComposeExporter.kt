package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The Kotlin an Android app writes to draw an A2UI design with AndroidX's A2UI libraries: a
 * `@Composable` building `material3-a2ui`'s basic catalog, feeding the design's messages to an
 * `A2uiMessageProcessor` and drawing the surface with `A2uiSurface` — the smallest program before
 * an app adds a transport. [A2uiDocumentExporter] writes what an agent sends.
 *
 * The payload is [A2uiDocumentExporter.lower] rendered as Kotlin literals, so both exports refuse
 * and carry the same thing. `Image`, `Video`, `AudioPlayer` and `urlOpener` are left to the app as
 * `TODO` lambdas, and the catalog id is read from the built catalog (`catalog.id`).
 */
object A2uiComposeExporter {

  sealed interface Result {
    /** [source] declares one composable, [composableName], taking a `Modifier`. */
    data class Emitted(val source: String, val composableName: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /**
   * @param packageName the package the file declares, or null for the Code pane's snippet.
   * @param surfaceId the surface the messages create and fill; the design's id by default, as in
   *   [A2uiDocumentExporter.export].
   */
  fun export(
    document: UiBuilderDocument,
    packageName: String? = null,
    surfaceId: String = document.id,
  ): Result {
    val lowered =
      when (val lowered = A2uiDocumentExporter.lower(document)) {
        is A2uiDocumentExporter.Lowered.Refused -> return Result.Refused(lowered.reasons)
        is A2uiDocumentExporter.Lowered.Components -> lowered
      }
    val name = document.screenIdentifier()
    val surface = kotlinString(surfaceId)
    val source = buildString {
      appendLine("// Generated from a Compose UI builder design. Do not edit by hand.")
      appendLine()
      if (packageName != null) {
        appendLine("package $packageName")
        appendLine()
      }
      imports(hasData = lowered.data.isNotEmpty()).forEach { appendLine("import $it") }
      appendLine()
      appendLine("@Composable")
      appendLine("fun $name(modifier: Modifier = Modifier) {")
      appendLine("  val catalog = remember {")
      appendLine("    materialA2uiBasicCatalogV1(")
      appendLine("      image =")
      appendLine(
        "        MaterialA2uiBasicCatalogV1Defaults.image { url, contentDescription, contentScale, imageModifier, onError ->"
      )
      appendLine("          // TODO: draw the image at `url` with the app's image loader.")
      appendLine("        },")
      appendLine("      video =")
      appendLine(
        "        MaterialA2uiBasicCatalogV1Defaults.video { url, videoModifier, onError ->"
      )
      appendLine("          // TODO: play the video at `url` with the app's player.")
      appendLine("        },")
      appendLine("      audioPlayer =")
      appendLine(
        "        MaterialA2uiBasicCatalogV1Defaults.audioPlayer { url, contentDescription, audioModifier, onError ->"
      )
      appendLine("          // TODO: play the audio at `url` with the app's player.")
      appendLine("        },")
      appendLine("      urlOpener = { url ->")
      appendLine("        // TODO: open `url`, which an `openUrl` action asks for.")
      appendLine("      },")
      appendLine("      messageFormatter = { pattern, _, _ -> pattern },")
      appendLine("      localeProvider = A2uiLocaleProvider.Default,")
      appendLine("    )")
      appendLine("  }")
      appendLine(
        "  val processor = remember(catalog) { A2uiMessageProcessor(catalogs = listOf(catalog)) }"
      )
      appendLine("  LaunchedEffect(processor) {")
      appendLine("    launch { processor.collectMessages() }")
      appendLine("    processor.processMessage(A2uiCreateSurfaceMessage($surface, catalog.id))")
      if (lowered.data.isNotEmpty()) {
        appendLine("    processor.processMessage(")
        appendLine("      A2uiUpdateDataModelMessage(")
        appendLine("        $surface,")
        appendLine("        \"/\",")
        appendLine("        ${literal(lowered.data, 8)},")
        appendLine("      )")
        appendLine("    )")
      }
      appendLine("    processor.processMessage(")
      appendLine("      A2uiUpdateComponentsMessage(")
      appendLine("        $surface,")
      appendLine("        listOf(")
      lowered.components.forEach { component -> appendPayload(component, indent = 10) }
      appendLine("        ),")
      appendLine("      )")
      appendLine("    )")
      appendLine("  }")
      appendLine("  val surfaces by processor.activeSurfaces.collectAsState()")
      appendLine(
        "  surfaces.firstOrNull()?.let { surface -> A2uiSurface(surfaceModel = surface, modifier = modifier) }"
      )
      appendLine("}")
    }
    return Result.Emitted(source, name)
  }

  private fun imports(hasData: Boolean): List<String> =
    listOfNotNull(
        "androidx.a2ui.compose.ui.A2uiMessageProcessor",
        "androidx.a2ui.model.catalog.functions.A2uiLocaleProvider",
        "androidx.a2ui.model.protocol.A2uiComponentPayload",
        "androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage",
        "androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage",
        "androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage".takeIf { hasData },
        "androidx.compose.material3.a2ui.A2uiSurface",
        "androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults",
        "androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1",
        "androidx.compose.runtime.Composable",
        "androidx.compose.runtime.LaunchedEffect",
        "androidx.compose.runtime.collectAsState",
        "androidx.compose.runtime.getValue",
        "androidx.compose.runtime.remember",
        "androidx.compose.ui.Modifier",
        "kotlinx.coroutines.launch",
      )
      .sorted()

  /** One `A2uiComponentPayload(…)`: the component's id and type, and everything else as a map. */
  private fun StringBuilder.appendPayload(component: JsonObject, indent: Int) {
    val pad = " ".repeat(indent)
    val id = (component["id"] as JsonPrimitive).content
    val type = (component["component"] as JsonPrimitive).content
    val properties = JsonObject(component - "id" - "component")
    appendLine("${pad}A2uiComponentPayload(")
    appendLine("$pad  id = ${kotlinString(id)},")
    appendLine("$pad  type = ${kotlinString(type)},")
    appendLine("$pad  properties = ${literal(properties, indent + 2)},")
    appendLine("$pad),")
  }

  /**
   * [value] as a Kotlin expression with the runtime value `kotlinx.serialization` would decode
   * (`Map<String, Any?>`, `List<Any?>`, `String`, number, `Boolean`, `null`). One line when it
   * fits, otherwise one entry per line at [indent].
   */
  internal fun literal(value: JsonElement, indent: Int = 0): String {
    val inline = inlineLiteral(value)
    if (indent + inline.length <= INLINE_WIDTH) return inline
    val pad = " ".repeat(indent)
    return when (value) {
      is JsonObject ->
        value.entries.joinToString(separator = "", prefix = "mapOf(\n", postfix = "$pad)") {
          (key, member) ->
          "$pad  ${kotlinString(key)} to ${literal(member, indent + 2)},\n"
        }
      is JsonArray ->
        value.joinToString(separator = "", prefix = "listOf(\n", postfix = "$pad)") { element ->
          "$pad  ${literal(element, indent + 2)},\n"
        }
      else -> inline
    }
  }

  private fun inlineLiteral(value: JsonElement): String =
    when (value) {
      is JsonNull -> "null"
      is JsonObject ->
        if (value.isEmpty()) "emptyMap<String, Any?>()"
        else
          value.entries.joinToString(prefix = "mapOf(", postfix = ")") { (key, member) ->
            "${kotlinString(key)} to ${inlineLiteral(member)}"
          }
      is JsonArray ->
        if (value.isEmpty()) "emptyList<Any?>()"
        else value.joinToString(prefix = "listOf(", postfix = ")") { inlineLiteral(it) }
      is JsonPrimitive ->
        when {
          value.isString -> kotlinString(value.content)
          value.booleanOrNull != null -> value.content
          else -> kotlinNumber(value.content)
        }
    }

  /**
   * A JSON number as a Kotlin literal of the same value: `Int` bare, wider integers with `L`,
   * anything with a fraction or exponent as a `Double`.
   */
  private fun kotlinNumber(content: String): String =
    when {
      content.toIntOrNull() != null -> content
      content.toLongOrNull() != null -> "${content}L"
      else -> content
    }

  /**
   * [value] as a Kotlin string literal: quoted, with every character that means something escaped.
   */
  internal fun kotlinString(value: String): String = buildString {
    append('"')
    for (char in value) {
      when (char) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '$' -> append("\\$")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        else ->
          if (char < ' ' || char == ' ' || char == ' ') {
            append("\\u")
            append(char.code.toString(16).padStart(4, '0'))
          } else append(char)
      }
    }
    append('"')
  }

  private const val INLINE_WIDTH = 96
}
