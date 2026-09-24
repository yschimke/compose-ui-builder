package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A saved revision as Figma should build it: `compose-ui-builder-figma-scene/v1`.
 *
 * The tree is snapshot-shaped on purpose — a [FigmaSnapshotNode] per builder node, its `id` the
 * builder id and its [FigmaSnapshotNode.stamp] set — so a plugin builds exactly what it will later
 * read back, and export followed by import is checkable without Figma in the loop. See
 * `docs/design/UI_BUILDER_FIGMA_INTEGRATION.md` § Export.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FigmaScene(
  /** Always written: a reader in another language checks it before anything else. */
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val schema: String = SCHEMA,
  val designId: String,
  val revision: Int,
  val catalog: String,
  val root: FigmaSnapshotNode,
  val diagnostics: List<FigmaSceneDiagnostic> = emptyList(),
) {
  fun encode(): String = FigmaJson.encodeToString(serializer(), this)

  /** The scene read back as though Figma had built it unchanged. */
  fun asSnapshot(): FigmaSnapshot = FigmaSnapshot(root = root)

  companion object {
    const val SCHEMA: String = "compose-ui-builder-figma-scene/v1"

    fun parse(text: String): FigmaScene =
      FigmaJson.decodeFromString(serializer(), text).also {
        require(it.schema == SCHEMA) { "expected $SCHEMA, found ${it.schema}" }
      }
  }
}

@Serializable
data class FigmaSceneDiagnostic(val code: String, val nodeId: String, val message: String) {
  companion object {
    /** A catalog component the map has no Figma counterpart for: a labelled frame stands in. */
    const val UNMAPPED_COMPONENT: String = "unmapped-component"
    /**
     * A mapped component whose slot content the kit instance cannot hold — an icon where the kit
     * has a label property, or more than one child. A frame stands in, so nothing is dropped.
     */
    const val NOT_REPRESENTABLE: String = "not-representable"
  }
}

/** A node's measured box, in dp, relative to the design's root. */
data class FigmaBounds(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Builder document → Figma scene: [FigmaSnapshotImporter] run backwards over the same map.
 *
 * Row and Column become auto-layout frames with the spacing, arrangement and alignment the importer
 * reads. A component the map names becomes an instance of the kit component with the variant
 * properties its rule implies — constants and value tables inverted — and a single text child
 * becomes the kit's label property. A component the map does not name becomes a frame named for it,
 * holding its children, so a designer sees what goes there rather than a gap.
 *
 * Sizes come from the document where it states them (`size`, `width`, `height`) and otherwise from
 * [bounds], the measured layout, when the caller has one.
 */
class FigmaSceneExporter(private val map: FigmaComponentMap) {
  fun export(
    document: UiBuilderDocument,
    bounds: Map<String, FigmaBounds> = emptyMap(),
  ): FigmaScene {
    val diagnostics = mutableListOf<FigmaSceneDiagnostic>()
    val context = Context(document, bounds, diagnostics)
    val roots = document.roots.mapNotNull { document.nodes[it] }
    val root =
      if (roots.size == 1) context.node(roots.single(), parent = null, slot = null, isRoot = true)
      else
        FigmaSnapshotNode(
          id = document.id,
          type = "FRAME",
          name = document.title,
          width = document.environmentNumber("widthDp"),
          height = document.environmentNumber("heightDp"),
          children = roots.map { context.node(it, parent = null, slot = null, isRoot = false) },
        )
    return FigmaScene(
      designId = document.id,
      revision = document.revision,
      catalog = map.catalog,
      root = root,
      diagnostics = diagnostics,
    )
  }

  private inner class Context(
    val document: UiBuilderDocument,
    val bounds: Map<String, FigmaBounds>,
    val diagnostics: MutableList<FigmaSceneDiagnostic>,
  ) {
    fun node(
      node: UiBuilderNode,
      parent: UiBuilderNode?,
      slot: String?,
      isRoot: Boolean,
    ): FigmaSnapshotNode {
      val stamp =
        FigmaStamp(
          designId = document.id,
          nodeId = node.id,
          revision = document.revision,
          componentId = node.componentId,
          slot = slot,
        )
      val base =
        FigmaSnapshotNode(
          id = node.id,
          type = "FRAME",
          name = node.id,
          stamp = stamp,
        )
      val placed = base.placed(node, parent, isRoot)
      return when (node.componentId) {
        "layout/row" -> placed.container(node, FigmaAutoLayout.HORIZONTAL)
        "layout/column" -> placed.container(node, FigmaAutoLayout.VERTICAL)
        "layout/box" -> placed.container(node, FigmaAutoLayout.NONE)
        "m3/text" -> placed.text(node)
        else -> placed.component(node)
      }
    }

    private fun FigmaSnapshotNode.container(node: UiBuilderNode, mode: String): FigmaSnapshotNode {
      val properties = node.properties
      val horizontal = mode == FigmaAutoLayout.HORIZONTAL
      val layout =
        FigmaAutoLayout(
          mode = mode,
          itemSpacing =
            properties.number(if (horizontal) "horizontalSpacingDp" else "verticalSpacingDp")
              ?: 0.0,
          padding = node.padding(),
          primaryAxisAlign =
            primaryAlign(
              properties.enumValue(
                if (horizontal) "horizontalArrangement" else "verticalArrangement"
              )
            ),
          counterAxisAlign =
            counterAlign(
              properties.enumValue(if (horizontal) "verticalAlignment" else "horizontalAlignment")
            ),
        )
      return copy(
        layout = layout,
        fill = node.modifier("background")?.paint(),
        stroke = node.modifier("border")?.stroke(),
        cornerRadius = node.cornerRadius(),
        children = children(node),
      )
    }

    private fun FigmaSnapshotNode.text(node: UiBuilderNode): FigmaSnapshotNode {
      val properties = node.properties
      val style = properties.enumValue("style")
      return copy(
        type = "TEXT",
        name = properties.stringValue("text")?.take(40) ?: node.id,
        text =
          FigmaText(
            characters = properties.stringValue("text").orEmpty(),
            style =
              style?.let { value -> map.textStyles.entries.firstOrNull { it.value == value }?.key },
            fontSize = properties.number("fontSizeSp"),
            fontWeight = properties.enumValue("fontWeight")?.let(::weightNumber),
            italic = properties.enumValue("fontStyle") == "italic",
            textAlign =
              when (properties.enumValue("textAlign")) {
                "center" -> "CENTER"
                "end" -> "RIGHT"
                "justify" -> "JUSTIFIED"
                else -> null
              },
          ),
        fill = (properties["color"] as? JsonObject)?.let(::paintOf),
      )
    }

    private fun FigmaSnapshotNode.component(node: UiBuilderNode): FigmaSnapshotNode {
      val rule = map.components.firstOrNull { it.represents(node) }
      if (rule == null) {
        diagnostics +=
          FigmaSceneDiagnostic(
            FigmaSceneDiagnostic.UNMAPPED_COMPONENT,
            node.id,
            "${node.componentId} has no Figma counterpart in the map; a frame stands in",
          )
        return standIn(node)
      }
      val unheld = node.slots.filter { (slot, ids) -> ids.isNotEmpty() && slot != rule.text?.slot }
      if (unheld.isNotEmpty()) {
        diagnostics +=
          FigmaSceneDiagnostic(
            FigmaSceneDiagnostic.NOT_REPRESENTABLE,
            node.id,
            "${node.componentId}.${unheld.keys.joinToString()} holds content the kit instance " +
              "has no slot for; a frame stands in",
          )
        return standIn(node)
      }
      val label =
        rule.text?.let { textRule ->
          val children = node.slots[textRule.slot].orEmpty().mapNotNull { document.nodes[it] }
          val label = children.singleOrNull()?.takeIf { it.componentId == "m3/text" }
          if (children.isNotEmpty() && (label == null || textRule.from == null)) {
            diagnostics +=
              FigmaSceneDiagnostic(
                FigmaSceneDiagnostic.NOT_REPRESENTABLE,
                node.id,
                "${node.componentId}.${textRule.slot} holds content the kit instance cannot; a " +
                  "frame stands in",
              )
            return standIn(node)
          }
          label
        }
      val properties = linkedMapOf<String, JsonPrimitive>()
      properties.putAll(rule.whenProperties)
      rule.properties.forEach { (name, propertyRule) ->
        val value = node.properties[name]?.primitiveValue() ?: return@forEach
        val figma =
          propertyRule.values
            ?.entries
            ?.firstOrNull { sameValue(it.value, value) }
            ?.key
            ?.let(::JsonPrimitive) ?: if (propertyRule.values == null) value else null
        figma?.let { properties[propertyRule.from] = it }
      }
      val from = rule.text?.from
      if (label != null && from != null) {
        properties[from] = JsonPrimitive(label.properties.stringValue("text").orEmpty())
      }
      return copy(
        type = "INSTANCE",
        name = node.id,
        instance =
          FigmaInstance(
            componentSet = rule.componentSet,
            component =
              properties.entries.joinToString(", ") { (key, value) -> "$key=${value.content}" },
            properties = properties,
          ),
        stamp = stamp?.copy(labelNodeId = label?.id),
      )
    }

    /** A frame named for a component Figma has no instance of, holding its children. */
    private fun FigmaSnapshotNode.standIn(node: UiBuilderNode): FigmaSnapshotNode =
      copy(
        name = node.componentId,
        layout = FigmaAutoLayout(mode = FigmaAutoLayout.VERTICAL, padding = node.padding()),
        fill = node.modifier("background")?.paint(),
        stroke = node.modifier("border")?.stroke(),
        cornerRadius = node.cornerRadius(),
        children = children(node),
      )

    private fun children(node: UiBuilderNode): List<FigmaSnapshotNode> =
      node.slots.flatMap { (slot, ids) ->
        ids
          .mapNotNull { document.nodes[it] }
          .map { child -> node(child, node, slot, isRoot = false) }
      }

    /** Size, position and sizing mode, from the modifiers and the measured layout. */
    private fun FigmaSnapshotNode.placed(
      node: UiBuilderNode,
      parent: UiBuilderNode?,
      isRoot: Boolean,
    ): FigmaSnapshotNode {
      if (isRoot) {
        return copy(
          name = document.title,
          width = document.environmentNumber("widthDp"),
          height = document.environmentNumber("heightDp"),
          sizing = FigmaSizing(FigmaSizing.FIXED, FigmaSizing.FIXED),
        )
      }
      val measured = bounds[node.id]
      val parentBounds = parent?.let { bounds[it.id] }
      val size = node.modifier("size")
      val width = size?.number("widthDp") ?: node.modifier("width")?.number("widthDp")
      val height = size?.number("heightDp") ?: node.modifier("height")?.number("heightDp")
      val parentMode =
        when (parent?.componentId) {
          "layout/row" -> FigmaAutoLayout.HORIZONTAL
          "layout/column" -> FigmaAutoLayout.VERTICAL
          else -> FigmaAutoLayout.NONE
        }
      val weighted = node.modifier("weight") != null
      val fillWidth =
        node.modifier("fillMaxWidth") != null ||
          node.modifier("fillMaxSize") != null ||
          (weighted && parentMode == FigmaAutoLayout.HORIZONTAL)
      val fillHeight =
        node.modifier("fillMaxHeight") != null ||
          node.modifier("fillMaxSize") != null ||
          (weighted && parentMode == FigmaAutoLayout.VERTICAL)
      val offset = node.modifier("offset")
      return copy(
        x = offset?.number("xDp") ?: measured?.let { it.x - (parentBounds?.x ?: 0.0) } ?: 0.0,
        y = offset?.number("yDp") ?: measured?.let { it.y - (parentBounds?.y ?: 0.0) } ?: 0.0,
        width = width ?: measured?.width ?: 0.0,
        height = height ?: measured?.height ?: 0.0,
        sizing =
          FigmaSizing(
            horizontal = sizing(fillWidth, width != null),
            vertical = sizing(fillHeight, height != null),
          ),
      )
    }
  }

  /** Whether [rule] is the one that produced a node like this: its component and every constant. */
  private fun FigmaComponentRule.represents(node: UiBuilderNode): Boolean =
    componentId == node.componentId &&
      constants.all { (name, value) ->
        node.properties[name]?.primitiveValue()?.let { sameValue(it, value) } == true
      }

  private fun paintOf(colour: JsonObject): FigmaPaint? {
    val value = colour.optionalString("value") ?: return null
    return when (colour.optionalString("type")) {
      "colorToken" ->
        if (value.startsWith("#")) FigmaPaint(color = value)
        else
          FigmaPaint(
            variable = map.colorVariables.entries.firstOrNull { it.value == value }?.key ?: value
          )
      else -> FigmaPaint(color = value)
    }
  }

  private fun JsonObject.paint(): FigmaPaint? = (this["color"] as? JsonObject)?.let(::paintOf)

  private fun JsonObject.stroke(): FigmaStroke? {
    val paint = paint() ?: return null
    return FigmaStroke(paint.color, paint.variable, number("widthDp") ?: 1.0)
  }

  private companion object {
    fun sizing(fill: Boolean, fixed: Boolean): String =
      when {
        fill -> FigmaSizing.FILL
        fixed -> FigmaSizing.FIXED
        else -> FigmaSizing.HUG
      }

    fun primaryAlign(value: String?): String =
      when (value) {
        "center" -> "CENTER"
        "end",
        "bottom" -> "MAX"
        "spaceBetween",
        "spaceAround",
        "spaceEvenly" -> "SPACE_BETWEEN"
        else -> "MIN"
      }

    fun counterAlign(value: String?): String =
      when (value) {
        "center",
        "centerVertically",
        "centerHorizontally" -> "CENTER"
        "end",
        "bottom" -> "MAX"
        else -> "MIN"
      }

    fun weightNumber(value: String): Int? =
      when (value) {
        "normal" -> 400
        "medium" -> 500
        "semiBold" -> 600
        "bold" -> 700
        else -> null
      }

    fun UiBuilderNode.modifier(type: String): JsonObject? =
      modifiers.firstOrNull { (it as? JsonObject)?.optionalString("type") == type } as? JsonObject

    fun UiBuilderNode.padding(): FigmaPadding =
      modifier("padding")?.let {
        FigmaPadding(
          left = it.number("startDp") ?: 0.0,
          top = it.number("topDp") ?: 0.0,
          right = it.number("endDp") ?: 0.0,
          bottom = it.number("bottomDp") ?: 0.0,
        )
      } ?: FigmaPadding()

    fun UiBuilderNode.cornerRadius(): Double =
      (modifier("background") ?: modifier("clip"))?.optionalString("shape")?.toDoubleOrNull() ?: 0.0

    fun JsonObject.number(name: String): Double? =
      (this[name] as? JsonPrimitive)?.doubleOrNull
        ?: (this[name] as? JsonObject)?.get("value")?.jsonPrimitive?.doubleOrNull

    fun JsonObject.enumValue(name: String): String? =
      (this[name] as? JsonObject)?.optionalString("value")

    fun JsonObject.stringValue(name: String): String? =
      (this[name] as? JsonObject)?.optionalString("value")

    /** A property's value as a bare primitive, whichever literal wrapper carries it. */
    fun JsonElement.primitiveValue(): JsonPrimitive? {
      val value = (this as? JsonObject)?.get("value") as? JsonPrimitive ?: return null
      return value.booleanOrNull?.let(::JsonPrimitive) ?: value
    }

    fun UiBuilderDocument.environmentNumber(name: String): Double =
      (environment[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0
  }
}
