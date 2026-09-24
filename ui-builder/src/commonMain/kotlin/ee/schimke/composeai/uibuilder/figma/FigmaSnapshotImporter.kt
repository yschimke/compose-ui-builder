package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Something the import could not carry across exactly, named so a person can act on it.
 *
 * [figmaNodeId] is the Figma node it is about and [nodeId] the builder node it became, if any: an
 * `unmapped-instance` or `unsupported-node` becomes a sized placeholder box, so the builder id is
 * where to build the real thing.
 */
data class FigmaImportDiagnostic(
  val code: String,
  val figmaNodeId: String,
  val nodeId: String?,
  val message: String,
) {
  companion object {
    const val UNMAPPED_INSTANCE: String = "unmapped-instance"
    const val UNSUPPORTED_NODE: String = "unsupported-node"
    const val ABSOLUTE_LAYOUT: String = "absolute-layout"
    const val UNMAPPED_TOKEN: String = "unmapped-token"
    const val DROPPED_PROPERTY: String = "dropped-property"
    const val DROPPED_MODIFIER: String = "dropped-modifier"
    const val HIDDEN: String = "hidden"
  }
}

/**
 * An import: the operation log, in the `compose-ui-builder-operations/v1-candidate` shape that
 * `UiBuilderReducer.replay` reads, the Figma node each builder node came from, and what could not
 * be carried across.
 */
data class FigmaImportResult(
  val operations: JsonObject,
  val figmaNodeIds: Map<String, String>,
  val diagnostics: List<FigmaImportDiagnostic>,
)

/**
 * Figma frame → builder operations. See `docs/design/UI_BUILDER_FIGMA_INTEGRATION.md` § Import.
 *
 * One walk, depth first. An instance the [map] names becomes that catalog component; one it does
 * not becomes a placeholder box of the instance's size and an [FigmaImportDiagnostic] — never a
 * component the map did not name, for the reason the reference overlay refuses to promote a piece
 * with no provenance. Frames become Row, Column or Box by their auto layout. Every value written is
 * checked against the [catalog] first, so the result replays and validates or says why not.
 */
class FigmaSnapshotImporter(
  private val catalog: CapabilityCatalog,
  private val map: FigmaComponentMap,
) {
  fun import(
    snapshot: FigmaSnapshot,
    designId: String,
    title: String = snapshot.root.name.ifBlank { designId },
    environment: JsonObject = defaultEnvironment(snapshot.root),
  ): FigmaImportResult = Walk(snapshot, designId, title, environment).run()

  private inner class Walk(
    private val snapshot: FigmaSnapshot,
    private val designId: String,
    private val title: String,
    private val environment: JsonObject,
  ) {
    private val operations = mutableListOf<JsonObject>()
    private val diagnostics = mutableListOf<FigmaImportDiagnostic>()
    private val figmaIds = linkedMapOf<String, String>()
    private val usedIds = mutableSetOf<String>()

    fun run(): FigmaImportResult {
      operations += buildJsonObject {
        put("operationId", "create")
        put("type", "createDesign")
        put("title", title)
        put("catalogPin", catalogPin())
        put("environment", environment)
        put("stateVariables", JsonObject(emptyMap()))
      }
      visit(snapshot.root, parent = null, parentLayout = null, after = null, isRoot = true)
      val fixture = buildJsonObject {
        put("schema", "compose-ui-builder-operations/v1-candidate")
        put("documentSchema", "compose-ui-builder-document/v1-candidate")
        put("designId", designId)
        snapshot.source.let { source ->
          if (source.fileKey != null || source.nodeId != null) {
            put(
              "source",
              buildJsonObject {
                source.fileKey?.let { put("figmaFileKey", it) }
                source.nodeId?.let { put("figmaNodeId", it) }
              },
            )
          }
        }
        put("operations", JsonArray(operations))
      }
      return FigmaImportResult(fixture, figmaIds.toMap(), diagnostics.toList())
    }

    /** Inserts [node] and returns the builder id it became, or null when it was skipped. */
    private fun visit(
      node: FigmaSnapshotNode,
      parent: Pair<String, String>?,
      parentLayout: FigmaAutoLayout?,
      after: String?,
      isRoot: Boolean = false,
    ): String? {
      if (!node.visible) {
        diagnostics += diagnostic(FigmaImportDiagnostic.HIDDEN, node, null, "hidden layer skipped")
        return null
      }
      val id = idFor(node)
      val placement = Placement(parentLayout, isRoot)
      val instance = node.instance
      return when {
        instance != null -> instance(node, instance, id, parent, after, placement)
        node.type == "TEXT" && node.text != null ->
          text(node, node.text, id, parent, after, placement)
        node.imageFill -> placeholder(node, id, parent, after, placement, "image fill")
        node.type in CONTAINER_TYPES -> container(node, id, parent, after, placement)
        node.type in SHAPE_TYPES && node.children.isEmpty() ->
          shape(node, id, parent, after, placement)
        else -> placeholder(node, id, parent, after, placement, "${node.type.lowercase()} layer")
      }
    }

    private fun container(
      node: FigmaSnapshotNode,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
    ): String {
      val layout = node.layout ?: FigmaAutoLayout()
      val componentId =
        when (layout.mode) {
          FigmaAutoLayout.HORIZONTAL -> "layout/row"
          FigmaAutoLayout.VERTICAL -> "layout/column"
          else -> "layout/box"
        }
      val properties = buildMap {
        when (componentId) {
          "layout/row" -> {
            if (layout.itemSpacing != 0.0) put("horizontalSpacingDp", float(layout.itemSpacing))
            arrangement(layout.primaryAxisAlign, horizontal = true)?.let {
              put("horizontalArrangement", enum(it))
            }
            put(
              "verticalAlignment",
              enum(crossAlignment(layout.counterAxisAlign, horizontal = true)),
            )
          }
          "layout/column" -> {
            if (layout.itemSpacing != 0.0) put("verticalSpacingDp", float(layout.itemSpacing))
            arrangement(layout.primaryAxisAlign, horizontal = false)?.let {
              put("verticalArrangement", enum(it))
            }
            put(
              "horizontalAlignment",
              enum(crossAlignment(layout.counterAxisAlign, horizontal = false)),
            )
          }
        }
      }
      val modifiers = buildList {
        addAll(placementModifiers(node, placement, intrinsic = false))
        background(node)?.let(::add)
        border(node)?.let(::add)
        if (!layout.padding.isZero) add(padding(layout.padding))
      }
      insert(node, id, componentId, properties, modifiers, parent, after)
      if (componentId == "layout/box" && node.children.isNotEmpty()) {
        diagnostics +=
          diagnostic(
            FigmaImportDiagnostic.ABSOLUTE_LAYOUT,
            node,
            id,
            "frame has no auto layout; its ${node.children.size} children keep their positions " +
              "through offset modifiers",
          )
      }
      var previous: String? = null
      node.children.forEach { child ->
        visit(child, id to CHILDREN_SLOT, layout, previous)?.let { previous = it }
      }
      return id
    }

    private fun instance(
      node: FigmaSnapshotNode,
      instance: FigmaInstance,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
    ): String {
      val rule = map.ruleFor(instance)
      val component = rule?.let { catalog.componentsById[it.componentId] }
      if (rule == null || component == null) {
        val name = listOfNotNull(instance.componentSet, instance.component).joinToString(" / ")
        val why =
          if (rule == null) "no map rule for instance of $name"
          else "map names ${rule.componentId}, which catalog ${map.catalog} does not declare"
        diagnostics += diagnostic(FigmaImportDiagnostic.UNMAPPED_INSTANCE, node, id, why)
        return box(node, id, parent, after, placement)
      }
      val properties = linkedMapOf<String, JsonElement>()
      rule.constants.forEach { (name, value) ->
        propertyValue(component, name, value, node, id)?.let { properties[name] = it }
      }
      rule.properties.forEach { (name, propertyRule) ->
        val raw = instance.property(propertyRule.from) ?: return@forEach
        val translated =
          propertyRule.values?.entries?.firstOrNull { sameValue(JsonPrimitive(it.key), raw) }?.value
            ?: if (propertyRule.values == null) raw else null
        if (translated == null) {
          diagnostics +=
            diagnostic(
              FigmaImportDiagnostic.DROPPED_PROPERTY,
              node,
              id,
              "${propertyRule.from}=${raw.content} has no value for ${rule.componentId}.$name",
            )
          return@forEach
        }
        propertyValue(component, name, translated, node, id)?.let { properties[name] = it }
      }
      val modifiers = placementModifiers(node, placement, intrinsic = true)
      insert(node, id, rule.componentId, properties, modifiers, parent, after)

      rule.text?.let { textRule ->
        val label =
          textRule.from?.let { instance.property(it)?.content }
            ?: node.firstText()?.text?.characters
        if (label != null) {
          val labelId = uniqueId("$id-label")
          figmaIds[labelId] = node.id
          operations +=
            insertOperation(
              labelId,
              "m3/text",
              mapOf("text" to string(label)),
              emptyList(),
              id to textRule.slot,
              null,
            )
        }
      }
      return id
    }

    private fun text(
      node: FigmaSnapshotNode,
      text: FigmaText,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
    ): String {
      val properties = linkedMapOf<String, JsonElement>("text" to string(text.characters))
      val style = text.style?.let { map.textStyles[it] }
      if (style != null) {
        properties["style"] = enum(style)
      } else {
        if (text.style != null) {
          diagnostics +=
            diagnostic(
              FigmaImportDiagnostic.UNMAPPED_TOKEN,
              node,
              id,
              "text style ${text.style} is not in the map; kept its size and weight instead",
            )
        }
        text.fontSize?.let { properties["fontSizeSp"] = float(it) }
        // A mapped style carries its own size and weight, and Figma reports both either way.
        text.fontWeight?.let { weight ->
          fontWeight(weight)?.let { properties["fontWeight"] = enum(it) }
        }
      }
      if (text.italic) properties["fontStyle"] = enum("italic")
      when (text.textAlign) {
        "CENTER" -> properties["textAlign"] = enum("center")
        "RIGHT" -> properties["textAlign"] = enum("end")
        "JUSTIFIED" -> properties["textAlign"] = enum("justify")
      }
      colour(node.fill, node, id)?.let { properties["color"] = it }
      val modifiers = placementModifiers(node, placement, intrinsic = true, keepFixedWidth = true)
      insert(node, id, "m3/text", properties, modifiers, parent, after)
      return id
    }

    private fun shape(
      node: FigmaSnapshotNode,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
    ): String = box(node, id, parent, after, placement)

    private fun placeholder(
      node: FigmaSnapshotNode,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
      what: String,
    ): String {
      diagnostics +=
        diagnostic(
          FigmaImportDiagnostic.UNSUPPORTED_NODE,
          node,
          id,
          "$what has no catalog component; kept as a ${dp(node.width)}×${dp(node.height)} placeholder",
        )
      return box(node, id, parent, after, placement, paint = false)
    }

    /** A sized `layout/box`: a shape, or the hole an unmapped layer leaves. */
    private fun box(
      node: FigmaSnapshotNode,
      id: String,
      parent: Pair<String, String>?,
      after: String?,
      placement: Placement,
      paint: Boolean = true,
    ): String {
      val modifiers = buildList {
        addAll(placementModifiers(node, placement.copy(forceSize = true), intrinsic = false))
        if (paint) {
          background(node)?.let(::add)
          border(node)?.let(::add)
        }
      }
      insert(node, id, "layout/box", emptyMap(), modifiers, parent, after)
      return id
    }

    private fun insert(
      node: FigmaSnapshotNode,
      id: String,
      componentId: String,
      properties: Map<String, JsonElement>,
      modifiers: List<JsonObject>,
      parent: Pair<String, String>?,
      after: String?,
    ) {
      val capability = catalog.componentsById[componentId]
      val allowed = modifiers.filter { modifier ->
        val type = modifier["type"]?.jsonPrimitive?.content
        val ok = capability == null || type in capability.modifierCapabilities
        if (!ok) {
          diagnostics +=
            diagnostic(
              FigmaImportDiagnostic.DROPPED_MODIFIER,
              node,
              id,
              "$componentId does not take a $type modifier",
            )
        }
        ok
      }
      figmaIds[id] = node.id
      operations += insertOperation(id, componentId, properties, allowed, parent, after)
    }

    private fun insertOperation(
      id: String,
      componentId: String,
      properties: Map<String, JsonElement>,
      modifiers: List<JsonObject>,
      parent: Pair<String, String>?,
      after: String?,
    ): JsonObject = buildJsonObject {
      put("operationId", "insert-$id")
      put("type", "insertNode")
      put(
        "parent",
        parent?.let { (nodeId, slot) ->
          buildJsonObject {
            put("nodeId", nodeId)
            put("slot", slot)
          }
        } ?: JsonNull,
      )
      after?.let { put("afterNodeId", it) }
      put(
        "node",
        buildJsonObject {
          put("id", id)
          put("componentId", componentId)
          put("properties", JsonObject(properties))
          put("modifiers", JsonArray(modifiers))
        },
      )
    }

    /**
     * Sizing and position, first in the chain because Compose measures outside in. [intrinsic] is
     * for a component with its own size — a Button, a Text — where a fixed Figma size is the kit's
     * drawing of that size rather than a constraint the design adds.
     */
    private fun placementModifiers(
      node: FigmaSnapshotNode,
      placement: Placement,
      intrinsic: Boolean,
      keepFixedWidth: Boolean = false,
    ): List<JsonObject> = buildList {
      if (placement.isRoot) {
        add(modifier("fillMaxSize"))
        return@buildList
      }
      val parentMode = placement.parentLayout?.mode ?: FigmaAutoLayout.NONE
      if (parentMode == FigmaAutoLayout.NONE && placement.parentLayout != null) {
        if (node.x != 0.0 || node.y != 0.0) {
          add(
            buildJsonObject {
              put("type", "offset")
              put("xDp", dp(node.x))
              put("yDp", dp(node.y))
            }
          )
        }
      }
      val mainIsHorizontal = parentMode == FigmaAutoLayout.HORIZONTAL
      val mainIsVertical = parentMode == FigmaAutoLayout.VERTICAL
      val fillWidth = node.sizing.horizontal == FigmaSizing.FILL
      val fillHeight = node.sizing.vertical == FigmaSizing.FILL
      if ((fillWidth && mainIsHorizontal) || (fillHeight && mainIsVertical)) {
        add(
          buildJsonObject {
            put("type", "weight")
            put("weight", 1)
          }
        )
      }
      if (fillWidth && !mainIsHorizontal) add(modifier("fillMaxWidth"))
      if (fillHeight && !mainIsVertical) add(modifier("fillMaxHeight"))

      val fixedWidth =
        node.sizing.horizontal == FigmaSizing.FIXED &&
          (placement.forceSize || !intrinsic || keepFixedWidth)
      val fixedHeight =
        node.sizing.vertical == FigmaSizing.FIXED && (placement.forceSize || !intrinsic)
      when {
        fixedWidth && fixedHeight ->
          add(
            buildJsonObject {
              put("type", "size")
              put("widthDp", dp(node.width))
              put("heightDp", dp(node.height))
            }
          )
        fixedWidth ->
          add(
            buildJsonObject {
              put("type", "width")
              put("widthDp", dp(node.width))
            }
          )
        fixedHeight ->
          add(
            buildJsonObject {
              put("type", "height")
              put("heightDp", dp(node.height))
            }
          )
      }
    }

    private fun background(node: FigmaSnapshotNode): JsonObject? {
      val colour = colour(node.fill, node, null) ?: return null
      return buildJsonObject {
        put("type", "background")
        put("color", colour)
        if (node.cornerRadius > 0.0) put("shape", shapeValue(node.cornerRadius))
      }
    }

    private fun border(node: FigmaSnapshotNode): JsonObject? {
      val stroke = node.stroke ?: return null
      val colour = colour(FigmaPaint(stroke.color, stroke.variable), node, null) ?: return null
      return buildJsonObject {
        put("type", "border")
        put("widthDp", dp(stroke.weight))
        put("color", colour)
        if (node.cornerRadius > 0.0) put("shape", shapeValue(node.cornerRadius))
      }
    }

    /** A bound variable the map knows is a token; anything else is the literal colour. */
    private fun colour(paint: FigmaPaint?, node: FigmaSnapshotNode, id: String?): JsonObject? {
      paint ?: return null
      paint.variable?.let { variable ->
        map.colorVariables[variable]?.let { token ->
          return typed("colorToken", JsonPrimitive(token))
        }
        diagnostics +=
          diagnostic(
            FigmaImportDiagnostic.UNMAPPED_TOKEN,
            node,
            id,
            "colour variable $variable is not in the map; kept its literal value",
          )
      }
      val literal = paint.color?.takeIf { PropertyValueKinds.isDrawableColour(it) } ?: return null
      return typed("color", JsonPrimitive(literal))
    }

    /**
     * A catalog property value in the wrapper its capability demands, or null with a diagnostic
     * when the catalog would refuse it.
     */
    private fun propertyValue(
      component: ComponentCapability,
      name: String,
      value: JsonPrimitive,
      node: FigmaSnapshotNode,
      id: String,
    ): JsonObject? {
      val capability = component.propertiesByName[name]
      val refusal =
        when {
          capability == null -> "${component.componentId} has no property $name"
          capability.allowedValues.isNotEmpty() &&
            capability.allowedValues.none { (it as? JsonPrimitive)?.content == value.content } ->
            "${value.content} is not an allowed value of ${component.componentId}.$name"
          else -> null
        }
      if (refusal != null) {
        diagnostics += diagnostic(FigmaImportDiagnostic.DROPPED_PROPERTY, node, id, refusal)
        return null
      }
      return wrap(checkNotNull(capability), value)
    }

    private fun catalogPin(): JsonObject = buildJsonObject {
      put("systemId", map.catalog)
      put("catalogRevision", "candidate")
      put("capabilityDigest", "candidate")
      put("nativeRuntimeId", "candidate")
    }

    private fun idFor(node: FigmaSnapshotNode): String =
      uniqueId(node.stamp?.nodeId ?: figmaNodeId(node.id))

    private fun uniqueId(base: String): String {
      var candidate = base
      var suffix = 2
      while (!usedIds.add(candidate)) candidate = "$base-${suffix++}"
      return candidate
    }

    private fun diagnostic(
      code: String,
      node: FigmaSnapshotNode,
      nodeId: String?,
      message: String,
    ): FigmaImportDiagnostic =
      FigmaImportDiagnostic(code, node.id, nodeId, "${node.name.ifBlank { node.id }}: $message")
  }

  private data class Placement(
    val parentLayout: FigmaAutoLayout?,
    val isRoot: Boolean,
    val forceSize: Boolean = false,
  )

  companion object {
    const val CHILDREN_SLOT: String = "children"

    private val CONTAINER_TYPES = setOf("FRAME", "GROUP", "COMPONENT", "COMPONENT_SET", "SECTION")
    private val SHAPE_TYPES = setOf("RECTANGLE", "ELLIPSE")

    /** The builder id a Figma node takes when it carries no stamp: stable across re-imports. */
    fun figmaNodeId(figmaId: String): String =
      "figma-" + figmaId.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')

    /** The environment an import starts from: the root frame's size, the catalog's defaults. */
    fun defaultEnvironment(root: FigmaSnapshotNode): JsonObject = buildJsonObject {
      put("widthDp", dp(root.width))
      put("heightDp", dp(root.height))
      put("density", 1)
      put("theme", "light")
      put("dynamicColor", false)
      put("locale", "en-US")
      put("fontScale", 1)
      put("layoutDirection", "ltr")
      put("windowPosture", "flat")
      put("browserZoomPercent", 100)
      put("fixedTime", "2024-05-16T12:00:00Z")
      put("animations", "settled")
      put("networkAccess", false)
      put("exportDevices", buildJsonArray {})
    }

    internal fun arrangement(align: String, horizontal: Boolean): String? =
      when (align) {
        "MIN" -> null
        "CENTER" -> "center"
        "MAX" -> if (horizontal) "end" else "bottom"
        "SPACE_BETWEEN" -> "spaceBetween"
        else -> null
      }

    internal fun crossAlignment(align: String, horizontal: Boolean): String =
      when (align) {
        "CENTER" -> "center"
        "MAX" -> if (horizontal) "bottom" else "end"
        else -> if (horizontal) "top" else "start"
      }

    internal fun fontWeight(weight: Int): String? =
      when {
        weight <= 0 -> null
        weight < 450 -> "normal"
        weight < 550 -> "medium"
        weight < 650 -> "semiBold"
        else -> "bold"
      }

    /** Figma pixels as dp: whole numbers stay whole, the rest keep one decimal. */
    internal fun dp(value: Double): JsonPrimitive {
      val rounded = (value * 10).roundToLong() / 10.0
      return if (abs(rounded % 1.0) < 1e-9) JsonPrimitive(rounded.toLong())
      else JsonPrimitive(rounded)
    }

    private fun shapeValue(radius: Double): String = dp(radius).content

    private fun modifier(type: String): JsonObject = buildJsonObject { put("type", type) }

    private fun typed(type: String, value: JsonPrimitive): JsonObject = buildJsonObject {
      put("type", type)
      put("value", value)
    }

    private fun enum(value: String) = typed("enum", JsonPrimitive(value))

    private fun string(value: String) = typed("string", JsonPrimitive(value))

    private fun float(value: Double) = typed("float", JsonPrimitive(value))

    /**
     * The wrapper a capability demands for a value: an enumeration for a property with allowed
     * values, a colour for a colour-named property, otherwise by the value's own JSON type.
     */
    internal fun wrap(capability: PropertyCapability, value: JsonPrimitive): JsonObject {
      val name = capability.name
      return when {
        capability.allowedValues.isNotEmpty() -> typed("enum", JsonPrimitive(value.content))
        PropertyValueKinds.isColour(name) ->
          if (value.content.startsWith("#")) typed("color", JsonPrimitive(value.content))
          else typed("colorToken", JsonPrimitive(value.content))
        !value.isString && value.booleanOrNull != null -> typed("bool", value)
        !value.isString && value.doubleOrNull != null ->
          if (capability.jsonType.toString().contains("integer"))
            typed("int", JsonPrimitive(value.doubleOrNull!!.toLong()))
          else typed("float", JsonPrimitive(value.doubleOrNull!!))
        else -> typed("string", JsonPrimitive(value.content))
      }
    }

    private fun padding(padding: FigmaPadding): JsonObject = buildJsonObject {
      put("type", "padding")
      put("startDp", dp(padding.left))
      put("topDp", dp(padding.top))
      put("endDp", dp(padding.right))
      put("bottomDp", dp(padding.bottom))
    }

    private fun FigmaSnapshotNode.firstText(): FigmaSnapshotNode? =
      if (type == "TEXT" && text != null) this else children.firstNotNullOfOrNull { it.firstText() }
  }
}
