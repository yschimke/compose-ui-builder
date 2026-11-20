package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What a user configuration offers: a colour theme, a list of layouts, or an on/off switch. */
internal enum class WffConfigurationKind {
  COLOR,
  LIST,
  BOOLEAN,
}

/** One option of a configuration: its id, its name, and (for a colour theme) its colours. */
internal class WffConfigurationOption(
  val id: String,
  val displayName: String,
  val colors: List<String>,
)

/**
 * A configuration a face declares under `<UserConfigurations>`, as the preview needs it. A
 * [defaultValue] the face does not state is the first option (`TRUE` for a boolean), as the watch
 * would start with.
 */
internal class WffConfiguration(
  val id: String,
  val displayName: String,
  val kind: WffConfigurationKind,
  val options: List<WffConfigurationOption>,
  val defaultValue: String,
) {
  /** The option [value] names, if the configuration has one. */
  fun option(value: String): WffConfigurationOption? = options.firstOrNull { it.id == value }
}

/** The face's configurations, in the order it declares them; empty for any other design. */
internal fun UiBuilderDocument.wffConfigurations(): List<WffConfiguration> {
  val scene = roots.firstNotNullOfOrNull { nodes[it] } ?: return emptyList()
  if (scene.componentId != WatchFaceXmlExporter.SCENE) return emptyList()
  val declared =
    listOf("face", "faceAfter")
      .flatMap { scene.slots[it].orEmpty() }
      .mapNotNull { nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.USER_CONFIGURATIONS }
      .flatMap { it.slots["content"].orEmpty() }
      .mapNotNull { nodes[it] }
  return declared.mapNotNull { node ->
    val kind =
      when (node.componentId) {
        WatchFaceXmlExporter.COLOR_CONFIGURATION -> WffConfigurationKind.COLOR
        WatchFaceXmlExporter.LIST_CONFIGURATION -> WffConfigurationKind.LIST
        WatchFaceXmlExporter.BOOLEAN_CONFIGURATION -> WffConfigurationKind.BOOLEAN
        else -> return@mapNotNull null
      }
    val id = node.wffValue("id")
    if (id.isEmpty()) return@mapNotNull null
    val options =
      when (kind) {
        WffConfigurationKind.BOOLEAN ->
          listOf("TRUE", "FALSE").map { WffConfigurationOption(it, it, emptyList()) }
        else ->
          node.slots["options"]
            .orEmpty()
            .mapNotNull { nodes[it] }
            .filter {
              it.componentId == WatchFaceXmlExporter.COLOR_OPTION ||
                it.componentId == WatchFaceXmlExporter.LIST_OPTION
            }
            .map { option ->
              val optionId = option.wffValue("id")
              WffConfigurationOption(
                optionId,
                option.wffValue("displayName").ifEmpty { optionId },
                option.wffValue("colors").split(' ').filter { it.isNotEmpty() },
              )
            }
      }
    val stated = node.wffValue("defaultValue")
    WffConfiguration(
      id = id,
      displayName = node.wffValue("displayName").ifEmpty { id },
      kind = kind,
      options = options,
      defaultValue = stated.ifEmpty { options.firstOrNull()?.id.orEmpty() },
    )
  }
}

/**
 * One `<Flavor>` preset: its id, display name, and the configuration id → optionId values it sets.
 */
internal data class WffFlavor(
  val id: String,
  val displayName: String,
  val configurations: Map<String, String>,
)

/** The face's `<Flavors>` presets, in declared order; empty when the face declares none. */
internal fun UiBuilderDocument.wffFlavors(): List<WffFlavor> {
  val scene = roots.firstNotNullOfOrNull { nodes[it] } ?: return emptyList()
  if (scene.componentId != WatchFaceXmlExporter.SCENE) return emptyList()
  val flavorNodes =
    listOf("face", "faceAfter")
      .flatMap { scene.slots[it].orEmpty() }
      .mapNotNull { nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.USER_CONFIGURATIONS }
      .flatMap { it.slots["content"].orEmpty() }
      .mapNotNull { nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.FLAVORS }
      .flatMap { it.slots["content"].orEmpty() }
      .mapNotNull { nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.FLAVOR }
  return flavorNodes.mapNotNull { flavor ->
    val id = flavor.wffValue("id")
    if (id.isEmpty()) return@mapNotNull null
    val configs =
      flavor.slots["content"]
        .orEmpty()
        .mapNotNull { nodes[it] }
        .filter { it.componentId == WatchFaceXmlExporter.FLAVOR_CONFIGURATION }
        .mapNotNull { entry ->
          val configId = entry.wffValue("id")
          val optionId = entry.wffValue("optionId")
          if (configId.isEmpty() || optionId.isEmpty()) null else configId to optionId
        }
        .toMap()
    WffFlavor(
      id = id,
      displayName = flavor.wffValue("displayName").ifEmpty { id },
      configurations = configs,
    )
  }
}

/**
 * [this] drawn with [selection] (configuration id to option id) in place of each configuration's
 * default, for a preview. A copy for drawing only: the design, and so its exported XML, keeps the
 * `defaultValue` its author wrote. A choice that names no option of its configuration is ignored.
 */
internal fun UiBuilderDocument.withWffConfigurationSelection(
  selection: Map<String, String>,
): UiBuilderDocument {
  if (selection.isEmpty()) return this
  val chosen =
    wffConfigurations()
      .filter { configuration ->
        selection[configuration.id]?.let { configuration.option(it) } != null
      }
      .associateBy { it.id }
  if (chosen.isEmpty()) return this
  val declarations =
    nodes.values
      .filter {
        it.componentId == WatchFaceXmlExporter.COLOR_CONFIGURATION ||
          it.componentId == WatchFaceXmlExporter.LIST_CONFIGURATION ||
          it.componentId == WatchFaceXmlExporter.BOOLEAN_CONFIGURATION
      }
      .filter { chosen.containsKey(it.wffValue("id")) }
  if (declarations.isEmpty()) return this
  val replaced = declarations.associate { node ->
    node.id to
      node.copy(
        properties =
          JsonObject(
            node.properties +
              ("defaultValue" to
                buildJsonObject {
                  put("type", "string")
                  put("value", selection.getValue(node.wffValue("id")))
                }),
          ),
      )
  }
  return copy(nodes = nodes + replaced)
}

/**
 * The configurations of the face being drawn and the values they hold now: what a
 * `[CONFIGURATION.<id>.<n>]` colour reads and which option of a switch container is shown.
 */
internal class WffConfigurationState(configurations: List<WffConfiguration>) {
  private val byId = configurations.associateBy { it.id }

  /** The option id selected for configuration [id], or null for one the face does not declare. */
  fun selected(id: String): String? =
    byId[id]?.let { configuration ->
      configuration.defaultValue.takeIf { configuration.option(it) != null }
        ?: configuration.options.firstOrNull()?.id
    }

  /**
   * [value] with a `[CONFIGURATION.<id>.<n>]` reference replaced by the selected colour option's
   * `n`th colour (counting from 0); null for anything else, or a reference that does not resolve.
   */
  fun resolveColour(value: String): String? {
    val match = REFERENCE.matchEntire(value.trim()) ?: return null
    val configuration = byId[match.groupValues[1]] ?: return null
    if (configuration.kind != WffConfigurationKind.COLOR) return null
    val option = selected(configuration.id)?.let(configuration::option) ?: return null
    // An index that is not an Int cannot name a colour; it resolves to nothing, not an exception.
    return match.groupValues[2].toIntOrNull()?.let(option.colors::getOrNull)
  }

  private companion object {
    val REFERENCE = Regex("""\[CONFIGURATION\.([^.\]]+)\.(\d+)]""")
  }
}

internal val LocalWffConfigurations = compositionLocalOf { WffConfigurationState(emptyList()) }

/** Editor-owned watch-face configuration preview selection, for the live expression evaluator. */
internal val LocalWffConfigurationSelection = compositionLocalOf { emptyMap<String, String>() }

/** A property of a watch face node, from the property itself or else the import's `wffExtras`. */
internal fun UiBuilderNode.wffValue(name: String): String {
  val own = string(name)
  // A property that is set, empty included, is authoritative: `wffExtras` only holds what the node
  // has no property for, so a literal committed over a reference is not shadowed by a stale one.
  if (own.isNotEmpty() || name in properties) return own
  val extras = string("wffExtras")
  if (extras.isEmpty()) return ""
  val parsed = runCatching { Json.parseToJsonElement(extras) as? JsonObject }.getOrNull()
  return (parsed?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * A watch face colour property: a `[CONFIGURATION.<id>.<n>]` reference resolved against the
 * selected option, anything else as [color] reads it.
 */
@Composable
internal fun UiBuilderNode.wffColor(name: String, fallback: Color): Color {
  val resolved = LocalWffConfigurations.current.resolveColour(wffValue(name))
  return if (resolved != null && resolved.startsWith("#")) Color(parseArgb(resolved))
  else color(name, fallback)
}
