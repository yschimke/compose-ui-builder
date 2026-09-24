package ee.schimke.composeai.uibuilder.figma

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Figma frame as a plugin read it: `compose-ui-builder-figma-snapshot/v1`.
 *
 * Deliberately close to the plugin API and deliberately not Figma's REST document — the REST tree
 * carries neither `layoutSizingHorizontal` nor shared plugin data, and the stamp that makes a round
 * trip possible lives in the second. Every field a reader does not need is optional, so a snapshot
 * written by hand for a test, by the design-parity plugin, or by an agent through the Figma MCP all
 * parse. See `docs/design/UI_BUILDER_FIGMA_INTEGRATION.md`.
 */
@Serializable
data class FigmaSnapshot(
  val schema: String = SCHEMA,
  val source: FigmaSnapshotSource = FigmaSnapshotSource(),
  val root: FigmaSnapshotNode,
) {
  companion object {
    const val SCHEMA: String = "compose-ui-builder-figma-snapshot/v1"

    fun parse(text: String): FigmaSnapshot =
      FigmaJson.decodeFromString(serializer(), text).also {
        require(it.schema == SCHEMA) { "expected $SCHEMA, found ${it.schema}" }
      }
  }
}

@Serializable
data class FigmaSnapshotSource(val fileKey: String? = null, val nodeId: String? = null)

/**
 * One node. Positions are relative to the parent, in Figma pixels — which are dp at the 1x frame
 * sizes every Android kit uses.
 */
@Serializable
data class FigmaSnapshotNode(
  val id: String,
  val type: String,
  val name: String = "",
  val visible: Boolean = true,
  val x: Double = 0.0,
  val y: Double = 0.0,
  val width: Double = 0.0,
  val height: Double = 0.0,
  val layout: FigmaAutoLayout? = null,
  val sizing: FigmaSizing = FigmaSizing(),
  val fill: FigmaPaint? = null,
  val stroke: FigmaStroke? = null,
  val cornerRadius: Double = 0.0,
  /** Whether a fill is an image. The picture itself is not in the snapshot. */
  val imageFill: Boolean = false,
  val text: FigmaText? = null,
  val instance: FigmaInstance? = null,
  val stamp: FigmaStamp? = null,
  val children: List<FigmaSnapshotNode> = emptyList(),
)

/** Auto layout. `mode` is `HORIZONTAL`, `VERTICAL` or `NONE`, as Figma spells `layoutMode`. */
@Serializable
data class FigmaAutoLayout(
  val mode: String = NONE,
  val itemSpacing: Double = 0.0,
  val padding: FigmaPadding = FigmaPadding(),
  /** `MIN`, `CENTER`, `MAX` or `SPACE_BETWEEN`. */
  val primaryAxisAlign: String = "MIN",
  /** `MIN`, `CENTER` or `MAX`. */
  val counterAxisAlign: String = "MIN",
) {
  companion object {
    const val HORIZONTAL: String = "HORIZONTAL"
    const val VERTICAL: String = "VERTICAL"
    const val NONE: String = "NONE"
  }
}

@Serializable
data class FigmaPadding(
  val left: Double = 0.0,
  val top: Double = 0.0,
  val right: Double = 0.0,
  val bottom: Double = 0.0,
) {
  val isZero: Boolean
    get() = left == 0.0 && top == 0.0 && right == 0.0 && bottom == 0.0
}

/** `layoutSizingHorizontal` / `layoutSizingVertical`: `FIXED`, `HUG` or `FILL`. */
@Serializable
data class FigmaSizing(val horizontal: String = FIXED, val vertical: String = FIXED) {
  companion object {
    const val FIXED: String = "FIXED"
    const val HUG: String = "HUG"
    const val FILL: String = "FILL"
  }
}

/**
 * A solid paint: `color` as `#AARRGGBB`, and the name of the variable it is bound to, if any. A
 * bound variable is what lets an import say "surface" rather than a hex that stops following the
 * theme.
 */
@Serializable data class FigmaPaint(val color: String? = null, val variable: String? = null)

@Serializable
data class FigmaStroke(
  val color: String? = null,
  val variable: String? = null,
  val weight: Double = 1.0,
)

@Serializable
data class FigmaText(
  val characters: String,
  /** The text style's name, when the node uses one. */
  val style: String? = null,
  val fontSize: Double? = null,
  val fontWeight: Int? = null,
  val italic: Boolean = false,
  /** `LEFT`, `CENTER`, `RIGHT` or `JUSTIFIED`. */
  val textAlign: String? = null,
)

/**
 * What an instance instantiates. [properties] merges the variant axes of the main component with
 * the instance's own component properties, keys stripped of Figma's `#12:0` suffix.
 */
@Serializable
data class FigmaInstance(
  val componentSet: String? = null,
  val component: String = "",
  val properties: Map<String, JsonPrimitive> = emptyMap(),
)

/**
 * The identity a node exported by this editor carries back, read from shared plugin data under
 * [NAMESPACE].
 */
@Serializable
data class FigmaStamp(val designId: String, val nodeId: String, val revision: Int) {
  companion object {
    const val NAMESPACE: String = "composeUiBuilder"
  }
}

/**
 * Which Figma component is which catalog component: `compose-ui-builder-figma-map/v1`.
 *
 * The first [FigmaComponentRule] that matches an instance wins, so the more specific rule goes
 * first.
 */
@Serializable
data class FigmaComponentMap(
  val schema: String = SCHEMA,
  val catalog: String,
  val components: List<FigmaComponentRule> = emptyList(),
  /** Figma variable name → catalog colour token. */
  val colorVariables: Map<String, String> = emptyMap(),
  /** Figma text style name → catalog typography value (`m3/text.style`). */
  val textStyles: Map<String, String> = emptyMap(),
) {
  fun ruleFor(instance: FigmaInstance): FigmaComponentRule? = components.firstOrNull {
    it.matches(instance)
  }

  companion object {
    const val SCHEMA: String = "compose-ui-builder-figma-map/v1"

    fun parse(text: String): FigmaComponentMap =
      FigmaJson.decodeFromString(serializer(), text).also {
        require(it.schema == SCHEMA) { "expected $SCHEMA, found ${it.schema}" }
      }
  }
}

@Serializable
data class FigmaComponentRule(
  /** The component set's name, or a standalone component's. */
  val componentSet: String,
  /** Property values the instance must carry for this rule to apply. */
  @SerialName("when") val whenProperties: Map<String, JsonPrimitive> = emptyMap(),
  val componentId: String,
  /** Catalog properties this rule always sets. */
  val constants: Map<String, JsonPrimitive> = emptyMap(),
  /** Catalog property → the Figma property it is read from. */
  val properties: Map<String, FigmaPropertyRule> = emptyMap(),
  /** An `m3/text` child in a slot, for the label a kit carries as a text property. */
  val text: FigmaTextRule? = null,
) {
  fun matches(instance: FigmaInstance): Boolean {
    val name = instance.componentSet ?: instance.component
    if (!name.equals(componentSet, ignoreCase = true)) return false
    return whenProperties.all { (key, expected) ->
      instance.property(key)?.let { sameValue(it, expected) } == true
    }
  }
}

/**
 * One catalog property, read from Figma property [from]. With [values], the Figma value is
 * translated through it and a value it does not list leaves the property unset; without, the value
 * passes through.
 */
@Serializable
data class FigmaPropertyRule(val from: String, val values: Map<String, JsonPrimitive>? = null)

/**
 * The label: an `m3/text` child in [slot], its text from Figma property [from], or from the
 * instance's first text layer when [from] is absent.
 */
@Serializable data class FigmaTextRule(val slot: String, val from: String? = null)

internal val FigmaJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** Figma property keys match case-insensitively; values match by their text. */
internal fun FigmaInstance.property(key: String): JsonPrimitive? =
  properties.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

internal fun sameValue(a: JsonPrimitive, b: JsonPrimitive): Boolean =
  a.content.trim().equals(b.content.trim(), ignoreCase = true)
