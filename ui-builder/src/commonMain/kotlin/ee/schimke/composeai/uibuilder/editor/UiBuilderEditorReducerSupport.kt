package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.AcceptedCommand
import ee.schimke.composeai.uibuilder.AcceptedUndo
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.ParentSlot
import ee.schimke.composeai.uibuilder.PropertyTarget
import ee.schimke.composeai.uibuilder.StarterContent
import ee.schimke.composeai.uibuilder.StarterNode
import ee.schimke.composeai.uibuilder.StructuralChangeKind
import ee.schimke.composeai.uibuilder.canvas.DEFAULT_PICKED_HOUR
import ee.schimke.composeai.uibuilder.canvas.DEFAULT_PICKED_MINUTE
import ee.schimke.composeai.uibuilder.canvas.DEFAULT_SELECTED_DATE
import ee.schimke.composeai.uibuilder.canvas.topLevelNodes
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import ee.schimke.composeai.uibuilder.capability.accepts
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.stateSelection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val THEME_PRIMARY = "themePrimaryColor"
internal const val THEME_BACKGROUND = "themeBackgroundColor"
internal const val THEME_SURFACE = "themeSurfaceColor"
internal const val THEME_CONTENT = "themeContentColor"
internal const val THEME_TYPE_SCALE = "themeTypeScale"
internal const val THEME_CORNER_RADIUS = "themeCornerRadiusDp"
/**
 * The property-value shapes that read a state variable rather than holding a value.
 *
 * `stateEquals` is one too — the fixture's filter chips use it for `selected` — so treating only
 * `state` as a binding would report a chip as unbound and refuse to unbind it.
 */
internal val STATE_VALUE_TYPES = setOf("state", "stateEquals")

internal val THEME_PROPERTIES =
  setOf(
    THEME_PRIMARY,
    THEME_BACKGROUND,
    THEME_SURFACE,
    THEME_CONTENT,
    THEME_TYPE_SCALE,
    THEME_CORNER_RADIUS,
  )

/**
 * The `m3/surface` this design hangs its theme on, or null.
 *
 * [topLevelNodes] rather than `roots` so that wrapping a themed screen in a board keeps its theme:
 * the board is a container the editor put there, and the surface under it is still the top of the
 * design. The renderer asks the same question the same way.
 */
internal fun UiBuilderDocument.themeHost(): UiBuilderNode? = topLevelNodes.firstOrNull {
  it.componentId == "m3/surface"
}

internal fun UiBuilderNode.stringValue(name: String, fallback: String): String =
  properties[name]?.jsonObject?.get("value")?.primitiveOrNull()?.content ?: fallback

internal fun UiBuilderNode.floatValue(name: String, fallback: Float): Float =
  properties[name]?.jsonObject?.get("value")?.primitiveOrNull()?.doubleOrNull?.toFloat() ?: fallback

internal fun String.isArgbColor(): Boolean =
  startsWith("#") &&
    length in setOf(7, 9) &&
    drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

const val EDITOR_ACTOR_ID = "wasm-editor"
const val EDITOR_CLIENT_ID = "interactive-canvas"

internal fun UiBuilderEditorState.undoTargetOperationId(
  actorId: String = EDITOR_ACTOR_ID
): String? =
  collaboration.acceptedCommands.values
    .asSequence()
    .filter { it.command.actorId == actorId }
    .filter { it.command.operationId !in collaboration.compensatedOperationIds }
    .maxByOrNull(AcceptedCommand::committedRevision)
    ?.command
    ?.operationId

internal fun UiBuilderEditorState.redoTargetUndoId(actorId: String = EDITOR_ACTOR_ID): String? =
  collaboration.undoRecords.values
    .asSequence()
    .filter { it.command.actorId == actorId && it.redoneBy == null }
    .maxByOrNull(AcceptedUndo::committedRevision)
    ?.command
    ?.operationId

internal fun ComponentCapability.editorKind(): EditorComponentKind =
  when (role) {
    "Scaffold" -> EditorComponentKind.Scaffold
    "Container" -> EditorComponentKind.Container
    else -> EditorComponentKind.Composable
  }

internal fun SlotCapability.hasRoom(childCount: Int): Boolean =
  cardinality.max?.let { childCount < it } ?: true

internal fun ComponentCapability.slot(name: String): SlotCapability? =
  slotsByName[name]
    ?: dynamicSlots?.let {
      SlotCapability(
        name = name,
        cardinality = it.cardinality,
        ordered = it.ordered,
        acceptedRoles = it.acceptedRoles,
        acceptedTraits = it.acceptedTraits,
      )
    }

/**
 * Whether this row survives the insert panel's search.
 *
 * Everything the row shows is matchable — the name, the catalog id, the kind and family headings it
 * sits under, and its variant labels — because those are what a person reads off the panel and so
 * what they type at it. "outlined" finds the Card whose outlined variant it names; "selection"
 * finds the shelf.
 */
internal fun EditorCatalogItem.matches(needle: String): Boolean =
  needle.isEmpty() ||
    displayName.lowercase().contains(needle) ||
    componentId.lowercase().contains(needle) ||
    kind.label.lowercase().contains(needle) ||
    group.lowercase().contains(needle) ||
    variants.any { it.label.lowercase().contains(needle) || it.value.lowercase().contains(needle) }

/** Exact names lead search results; matches found only through a shelf or variant follow them. */
internal fun EditorCatalogItem.searchRank(needle: String): Int =
  when {
    displayName.lowercase() == needle -> 0
    displayName.lowercase().startsWith(needle) -> 1
    componentId.lowercase() == needle -> 2
    componentId.lowercase().contains(needle) -> 3
    variants.any {
      it.label.lowercase().contains(needle) || it.value.lowercase().contains(needle)
    } -> 4
    kind.label.lowercase().contains(needle) -> 5
    else -> 6
  }

/**
 * A catalog enum value as a person reads it: `filledTonal` → "Filled tonal".
 *
 * Derived rather than authored. A second table of display names beside the catalog's own values is
 * a table that goes stale silently — a renamed value keeps its old label and nothing says so —
 * whereas splitting the camel case is wrong in the same visible way for every value or for none.
 */
internal fun variantLabel(value: String): String =
  value
    // Sentence case, not title case: "Filled tonal" beside "Filled" and "Outlined" reads as three
    // variants of one component, where "Filled Tonal" reads as a proper noun that wandered in.
    .replace(Regex("([a-z0-9])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2].lowercase()}" }
    .replaceFirstChar(Char::uppercaseChar)

/** The set with [value] removed if it was there and added if it was not. */
internal fun <T> Set<T>.toggled(value: T): Set<T> =
  if (value in this) this - value else this + value

/** The frame a catalog thumbnail's component is inserted into, and drawn at before scaling. */
internal const val PREVIEW_FRAME_WIDTH_DP = 176

internal const val PREVIEW_FRAME_HEIGHT_DP = 128

internal const val PREVIEW_FRAME_CELL_ID = "catalog-thumbnail-cell"

/** The unconstrained cell a drag ghost's component is inserted into. */
internal const val DRAG_GHOST_CELL_ID = "drag-ghost-cell"

private fun ComponentCapability.defaultNode(
  nodeId: String,
  document: UiBuilderDocument,
): UiBuilderNode =
  UiBuilderNode(
    id = nodeId,
    componentId = componentId,
    properties =
      JsonObject(
        properties.filter(PropertyCapability::required).associate { property ->
          property.name to property.defaultEncodedValue(componentId, nodeId, document)
        }
      ),
    modifiers = JsonArray(emptyList()),
    slots = slots.associate { it.name to emptyList() },
  )

/**
 * A neutral literal for this property: the first allowed value, or an empty one of its own type.
 *
 * Typed rather than always the empty string, because `""` is not a boolean and a validator that
 * refuses it would make unbinding a boolean impossible.
 */
internal fun PropertyCapability.literalDefault(): JsonObject {
  allowedValues.firstOrNull()?.let {
    return it.asLiteral(this)
  }
  val types = typeNames() - "null"
  // Membership, not equality. A property declared ["boolean", "string"] — which is how the catalog
  // spells "a flag or the name of a state variable" — would otherwise unbind to `""`, and an empty
  // string on such a property reads as a variable name rather than as off.
  return when {
    "boolean" in types -> literal("bool", JsonPrimitive(false))
    "number" in types || "integer" in types -> literal("float", JsonPrimitive(0))
    else -> JsonPrimitive("").asLiteral(this)
  }
}

/** The catalog's own id for a loop over the design's rows. */
private const val FOR_EACH_COMPONENT_ID = "layout/for-each"

/** The trait every `a2ui-catalog` component carries and every one of its slots accepts. */
private const val A2UI_COMPONENT_TRAIT = "A2uiComponent"
private const val A2UI_TEXT = "a2ui/Text"

/** The rows a freshly inserted `layout/for-each` carries: three, each naming one `label`. */
private fun starterRows(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive("list"),
      "values" to
        JsonArray(
          listOf("Row one", "Row two", "Row three").map { label ->
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("object"),
                "fields" to JsonObject(mapOf("label" to literal("string", JsonPrimitive(label)))),
              )
            )
          }
        ),
    )
  )

private fun PropertyCapability.defaultEncodedValue(
  componentId: String,
  nodeId: String,
  document: UiBuilderDocument,
): JsonObject {
  allowedValues.firstOrNull()?.let { value ->
    return value.asLiteral(this)
  }
  return when (name) {
    "text" -> literal("string", JsonPrimitive("New text"))
    "assetKey" -> literal("assetKey", JsonPrimitive("editor.placeholder"))
    "iconKey" -> literal("enum", JsonPrimitive("addCircle"))
    "layoutMode" -> literal("enum", JsonPrimitive("adaptive"))
    "mainPaneVisible",
    "supportingPaneVisible" -> literal("bool", JsonPrimitive(true))
    "columns" ->
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("adaptiveGrid"),
          "minimumCellWidthDp" to JsonPrimitive(362),
        )
      )
    "scrollStateKey" -> literal("string", JsonPrimitive("$nodeId-scroll"))
    // Three rows, each a dictionary with one key, because a loop inserted with no data is a loop
    // that draws nothing — and a designer's first question of one is what a row looks like. The
    // key is what the starter template binds, so the insert draws three cells rather than three
    // copies of a default.
    //
    // Matched on the component as well as the name: this defaulting serves every catalog and every
    // enabled pack, and a pack declaring a required scalar `data` would otherwise be inserted with
    // a list of dictionaries in it — a node `CapabilityValidator` rejects, from a palette action
    // that simply fails.
    "data" ->
      if (componentId == FOR_EACH_COMPONENT_ID) starterRows() else JsonPrimitive("").asLiteral(this)
    "itemWidthDp" -> literal("float", JsonPrimitive(128.0))
    "expanded",
    "selected",
    "visible" -> literal("bool", JsonPrimitive(false))
    // A picker inserted with no date is a picker showing epoch zero or, worse, today — and "today"
    // is a render that changes overnight. The same fixed day the renderer falls back to, so the
    // node carries the value the canvas draws rather than leaving the inspector empty and the two
    // to agree by accident.
    "selectedDate" -> literal("string", JsonPrimitive(DEFAULT_SELECTED_DATE))
    "hour" -> literal("float", JsonPrimitive(DEFAULT_PICKED_HOUR))
    "minute" -> literal("float", JsonPrimitive(DEFAULT_PICKED_MINUTE))
    "is24Hour" -> literal("bool", JsonPrimitive(true))
    // A property that takes text as well as a state read starts as text: a literal draws and
    // exports as it is, where a read of a variable the design does not declare does neither.
    "value" ->
      if ("string" in typeNames()) literal("string", JsonPrimitive(""))
      else
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("state"),
            "variable" to JsonPrimitive(document.stateVariables.keys.firstOrNull() ?: "value"),
          )
        )
    "selectedIndex" -> literal("int", JsonPrimitive(0))
    "startColor" -> literal("color", JsonPrimitive("#00000000"))
    "endColor" -> literal("color", JsonPrimitive("#FF000000"))
    else -> JsonPrimitive("").asLiteral(this)
  }
}

/**
 * Append the operations that insert this component and everything it arrives holding.
 *
 * Two kinds of child come out of here and the difference matters. A **required-slot fill** is what
 * keeps the document valid: a slot with a minimum has to have children or the validator refuses the
 * whole batch, so one is picked from the slot's accepted traits. **Starter content** is what makes
 * the insert look like something somebody designed — see [StarterContent] for why an empty
 * container is the wrong thing to hand an operator. Starter content is checked against the catalog
 * here and dropped when it does not check out, so it can improve an insert and can never break one.
 *
 * @param starter the authored seed for *this* node, when it is itself a piece of starter content.
 *   Its properties are set over the component's defaults and the slots it names are authored
 *   exactly; slots it does not name expand the ordinary way.
 * @param seedStarterContent false to fill required slots and nothing more. Wrapping a selection
 *   passes false: the container is about to be handed the real children, and starter content there
 *   is a subtree that has to be deleted again in the same batch.
 * @param presetProperties values chosen at the moment of insertion — today, the variant picked off
 *   the palette row. They are written over this node's starter content rather than instead of it,
 *   so an outlined Card still arrives holding the two lines of text a Card arrives holding, and
 *   they reach only this node: a variant is a fact about what was inserted, not about its children.
 */
internal fun ComponentCapability.appendDefaultSubtree(
  catalog: CapabilityCatalog,
  document: UiBuilderDocument,
  nodeId: String,
  /** Null inserts this node as the document's root, which is what `InsertNode` already means. */
  parent: ParentSlot?,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
  starter: StarterNode? = null,
  seedStarterContent: Boolean = true,
  presetProperties: Map<String, JsonObject> = emptyMap(),
  componentPath: Set<String> = emptySet(),
  depth: Int = 0,
): String? {
  if (depth >= 32) return "required-slot defaults exceed the maximum depth of 32"
  if (componentId in componentPath) {
    return "required-slot default cycle: ${(componentPath + componentId).joinToString(" -> ")}"
  }
  // A seeded node takes the authored values when it came from the table, and a node inserted
  // straight from the palette takes the component's own — which is the same mechanism reaching the
  // root of the insert rather than only its children.
  val seededProperties =
    (starter
        ?: if (seedStarterContent)
          StarterNode(componentId, StarterContent.propertiesFor(componentId))
        else null)
      .withPresets(componentId, presetProperties)
  operations +=
    DesignOperation.InsertNode(
      defaultNode(nodeId, document).withStarterProperties(this, seededProperties),
      parent,
      afterNodeId,
    )
  slots.forEach { slot ->
    val children =
      plannedChildren(slot, starter, seedStarterContent, catalog)
        ?: return "catalog has no safe required-slot default for $componentId.${slot.name}"
    children.forEachIndexed { index, (child, childStarter) ->
      val childId = "$nodeId-${slot.name}-${index + 1}"
      val error =
        child.appendDefaultSubtree(
          catalog = catalog,
          document = document,
          nodeId = childId,
          parent = ParentSlot(nodeId, slot.name),
          afterNodeId = if (index == 0) null else "$nodeId-${slot.name}-$index",
          operations = operations,
          starter = childStarter,
          seedStarterContent = seedStarterContent,
          componentPath = componentPath + componentId,
          depth = depth + 1,
        )
      if (error != null) return error
    }
  }
  return null
}

/**
 * This seed with [presets] written over it, or a seed made of the presets when there was none.
 *
 * Over rather than under: the preset is the choice just made on the palette row, and the starter
 * table is a default from months ago. Null in and no presets stays null, which is what keeps a
 * plain insert on exactly the path it was on before variants existed.
 */
private fun StarterNode?.withPresets(
  componentId: String,
  presets: Map<String, JsonObject>,
): StarterNode? {
  if (presets.isEmpty()) return this
  val base = this ?: StarterNode(componentId)
  return base.copy(properties = base.properties + presets)
}

/**
 * What goes in [slot]: the authored seed, this component's starter content, or the required fill.
 *
 * Null means the slot has a minimum and nothing in the catalog can satisfy it — the one case that
 * refuses the insert. An empty list is the ordinary answer for an optional slot nobody seeded.
 */
private fun ComponentCapability.plannedChildren(
  slot: SlotCapability,
  starter: StarterNode?,
  seedStarterContent: Boolean,
  catalog: CapabilityCatalog,
): List<Pair<ComponentCapability, StarterNode?>>? {
  // A node that came from the table has already said what its slots hold, so its answer is used
  // even when it is deliberately empty — that is how an item card opts out of the two-line starter
  // `m3/card` would otherwise seed into it.
  starter?.slots?.get(slot.name)?.let { authored ->
    resolveStarterChildren(authored, slot, catalog)?.let {
      return it
    }
  }
  if (seedStarterContent && starter?.slots?.containsKey(slot.name) != true) {
    StarterContent.forComponent(componentId)[slot.name]?.let { seeded ->
      resolveStarterChildren(seeded, slot, catalog)?.let {
        return it
      }
    }
  }
  if (slot.cardinality.min == 0) return emptyList()
  val child = defaultChildFor(slot, catalog) ?: return null
  return List(slot.cardinality.min) { child to null }
}

/**
 * [children] resolved against the catalog, or null when the seed does not fit the slot.
 *
 * Null degrades to the required-slot fill rather than refusing, because a stale table entry — a
 * component that left the catalog, a slot whose accepted traits narrowed — must not be able to make
 * a component uninsertable. `StarterContentTest` is what turns that silent degradation into a
 * failing test.
 */
private fun resolveStarterChildren(
  children: List<StarterNode>,
  slot: SlotCapability,
  catalog: CapabilityCatalog,
): List<Pair<ComponentCapability, StarterNode?>>? {
  if (children.size < slot.cardinality.min) return null
  if (slot.cardinality.max?.let { children.size > it } == true) return null
  return children.map { child ->
    val capability = catalog.componentsById[child.componentId] ?: return null
    if (!slot.accepts(capability)) return null
    capability to child
  }
}

/**
 * This node with the seed's property values written over the component's own defaults.
 *
 * Values the component does not declare, and values outside a property's allowed set, are dropped
 * rather than written: they would fail catalog validation, and validation failure rejects the whole
 * insert — the one outcome starter content is never allowed to cause.
 */
private fun UiBuilderNode.withStarterProperties(
  capability: ComponentCapability,
  starter: StarterNode?,
): UiBuilderNode {
  val seeded =
    starter?.properties?.filter { (name, encoded) ->
      val property = capability.propertiesByName[name] ?: return@filter false
      // A structured value — an `object` or `list` wrapper — has no single `value` to check
      // against an allowed set, and a property with such a set is never structured.
      val value = encoded["value"] ?: return@filter property.allowedValues.isEmpty()
      property.allowedValues.isEmpty() || value in property.allowedValues
    }
  if (seeded.isNullOrEmpty()) return this
  return copy(properties = JsonObject(properties + seeded))
}

private fun defaultChildFor(
  slot: SlotCapability,
  catalog: CapabilityCatalog,
): ComponentCapability? {
  val preferredId =
    when {
      // Text before icon, for a slot that accepts both. `m3/button` is such a slot, and with the
      // icon branch first every button inserted from the palette arrived holding an icon rather
      // than a label. A slot that means an icon says `IconContent` and not `TextContent`.
      // An A2UI slot takes A2UI components and nothing else, and a text is what an agent puts
      // in a button, a card or a modal first.
      A2UI_COMPONENT_TRAIT in slot.acceptedTraits -> A2UI_TEXT
      "SearchInput" in slot.acceptedTraits -> "m3/search-input-field"
      "TextContent" in slot.acceptedTraits -> "m3/text"
      "IconContent" in slot.acceptedTraits -> "m3/icon"
      "Leaf" in slot.acceptedRoles -> "m3/text"
      else -> "layout/box"
    }
  return catalog.componentsById[preferredId]?.takeIf(slot::accepts)
}

/**
 * Every node reachable from [nodeId], detached from this document.
 *
 * A map rather than a node, because a subtree's children live in the document's flat `nodes` and
 * would be lost by copying the root alone.
 */
/**
 * What this node says, for a layer row — or null when it says nothing.
 *
 * A panel of twelve rows all reading "Text" cannot be scanned, which is why every design tool names
 * a text layer after its content. The property is found **through the catalog** rather than from a
 * list of names this file guesses at.
 *
 * The signal is `required`. A component's required free-text property is the thing it exists to
 * carry — `m3/text` requires `text` — while its optional ones are configuration and plumbing that
 * happen to be strings: `m3/card` declares `shape` and `stableKey`, and a card named after its
 * stable key is worse than one named "Card". That distinction is the catalog's own, which is why
 * this reads it rather than keeping a list of blessed property names.
 *
 * `contentDescription` is the one name-by-name exception, and it earns it: it is defined as the
 * node's human-readable name, so an image that has one is better named by it than by "Image".
 *
 * Free-text means a lone `string` with no `allowedValues` — an enum is a setting, and a colour is
 * not something anyone recognises a layer by.
 */
private val IDENTITY_PROPERTY_SUFFIXES = listOf("Key", "Id", "Base64", "Url")

internal fun UiBuilderNode.contentLabel(capability: ComponentCapability): String? {
  fun freeText(property: PropertyCapability) =
    property.allowedValues.isEmpty() &&
      property.typeNames() - "null" == setOf("string") &&
      !property.name.endsWith("Color", ignoreCase = true) &&
      // `required` is necessary and not sufficient, which the first cut of this got wrong twice.
      // A component can require a string it needs in order to work rather than one a person would
      // recognise it by, and in this catalog most do: `asset/image` requires `assetKey` and the
      // three lazy containers require `scrollStateKey` — so the layers panel offered an asset key
      // and a scroll key as layer names. Only `m3/text.text` was content.
      //
      // `remote-compose/document` used to require `documentBase64` and be the worst of them, a
      // base64 blob as a layer name. It now requires neither of its two sources — a node carries
      // bytes or a URL — so it no longer reaches this at all; both suffixes stay listed because
      // the rule is about the kind of string, not about which component happens to require one.
      //
      // The name carries the kind, the same way it does for a `…Dp` dimension: a key, an id or a
      // payload is plumbing whatever its type. Excluding them by suffix leaves `text` and any
      // future genuine content property alone, and an image falls through to its
      // `contentDescription`, which is the thing that was always the better name for it.
      IDENTITY_PROPERTY_SUFFIXES.none { property.name.endsWith(it, ignoreCase = true) }

  fun valueOf(name: String) =
    (properties[name] as? JsonObject)
      ?.get("value")
      ?.primitiveOrNull()
      ?.contentOrNull
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  val required = capability.properties.filter { it.required && freeText(it) }
  return (required.firstNotNullOfOrNull { valueOf(it.name) }
      ?: capability.properties
        .firstOrNull { it.name == "contentDescription" && freeText(it) }
        ?.let { valueOf(it.name) })
    // One line in a layer row. A paragraph pasted into a Text would otherwise push the type column
    // off the panel, so it is cut here rather than relying on the row to ellipsize it.
    ?.let { if (it.length <= 40) it else it.take(39).trimEnd() + "…" }
}

/**
 * The selected nodes that are not inside another selected node, in selection order.
 *
 * An ancestor carries its descendants, whether it is being deleted or copied, so a descendant
 * selected alongside its ancestor is not a separate target. Emitting one for a delete would target
 * a node that no longer exists and would over-count what its slot loses; copying one would put the
 * same subtree on the clipboard twice and paste it twice.
 */
internal fun UiBuilderEditorState.selectionRoots(): List<String> {
  val present = selection.filter(document.nodes::containsKey)
  return present.filterNot { nodeId ->
    generateSequence(document.location(nodeId)?.nodeId) { document.location(it)?.nodeId }
      .any { it in present }
  }
}

internal fun UiBuilderDocument.clip(nodeIds: List<String>): EditorClipboard {
  val collected = linkedMapOf<String, UiBuilderNode>()
  fun visit(id: String) {
    val node = nodes[id] ?: return
    if (collected.put(id, node) != null) return
    node.slots.values.flatten().forEach(::visit)
  }
  // Tree order, not the order they were clicked. `paste` walks `rootNodeIds` and lays them down
  // in sequence, so a selection built by shift-clicking D and then B would arrive as C, D, B and
  // silently reorder what was copied.
  val ordered = nodeIds.sortedBy(treeIndex())
  ordered.forEach(::visit)
  return EditorClipboard(rootNodeIds = ordered, nodes = collected)
}

/**
 * The same clipboard, told where the cut took its roots from.
 *
 * Read before the delete is applied, off the document the subtrees are still in. The run's first
 * root in tree order carries the position, and the sibling behind it has to be one nothing is
 * cutting — cutting `B, C` out of `A, B, C` puts the run back after `A`, not after the `B` that
 * left with it.
 */
internal fun EditorClipboard.withOriginOf(
  document: UiBuilderDocument,
  roots: List<String>,
): EditorClipboard {
  val first = rootNodeIds.firstOrNull() ?: return this
  val parent = document.location(first) ?: return this
  val siblings = document.children(parent)
  val before = siblings.take(siblings.indexOf(first).coerceAtLeast(0))
  return copy(origin = EditorClipboardOrigin(parent, before.lastOrNull { it !in roots }))
}

/** Position in the flattened tree, for the operations whose result is an order. */
internal fun UiBuilderDocument.treeIndex(): (String) -> Int {
  val order = mutableMapOf<String, Int>()
  fun visit(nodeId: String) {
    order[nodeId] = order.size
    nodes[nodeId]?.slots?.values?.flatten()?.forEach(::visit)
  }
  roots.forEach(::visit)
  return { order[it] ?: Int.MAX_VALUE }
}

/**
 * An id [preferred] that this document does not already hold.
 *
 * Pasting twice from one clipboard would otherwise produce the same id twice, and the second insert
 * would be rejected as a duplicate — so the paste that looks identical to the first silently fails.
 *
 * [client] because the sequence is *local*. Two people wrapping a selection in a Row as their own
 * first operation both reach sequence 1 and both propose `editor-layout-row-001`, for different
 * selections. Operation ids are already qualified; node ids were not, so the server accepted one
 * and rejected the other as a duplicate insertion — losing that person's edit, in the one situation
 * this editor exists to handle well.
 *
 * The qualifier is the **operation id prefix**, not `clientId`. `clientId` defaults to a constant
 * and the live host reads it from configuration, so two browsers can carry the same one; the
 * operation prefix is `clientId` plus a per-page nonce, which is precisely what already makes
 * operation ids unique between clients. Long ids are the price, and they are generated names shown
 * beside a human label rather than read on their own.
 */
internal fun UiBuilderDocument.freshNodeId(
  preferred: String,
  client: String,
  sequence: Int,
): String {
  val qualifier = client.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "c" }
  val numbered = "$preferred-$qualifier-${sequence.toString().padStart(3, '0')}"
  if (numbered !in nodes) return numbered
  var suffix = 2
  while ("$numbered-$suffix" in nodes) suffix++
  return "$numbered-$suffix"
}

/**
 * The properties that identify an *instance* rather than name content.
 *
 * Taken from what the Compose exporter does with them: `stableKey`, falling back to
 * `scrollStateKey`, becomes a lazy item's `key(…)`, and two siblings sharing one is a runtime
 * failure in Compose rather than a cosmetic clash. `assetKey` and `iconKey` look similar and are
 * the opposite — they name catalog content that every copy should keep — which is why this is the
 * exporter's list and not a suffix rule.
 */
private val INSTANCE_IDENTITY_PROPERTIES = setOf("stableKey", "scrollStateKey")

/**
 * Every string a copy's identity must not collide with: the node ids, and the identity keys already
 * in use.
 *
 * The ids alone are not enough. A copy takes its own node id as its `stableKey`, and that key is an
 * arbitrary string a sibling may already hold — duplicating `card` next to a node keyed
 * `card-copy-001` produced two siblings under one key, which the exporter turns into two
 * `key("card-copy-001")` groups and Compose rejects at runtime. The values are read with a safe
 * cast because a document arriving over the wire may hold anything here.
 */
internal fun UiBuilderDocument.takenIdentities(): MutableSet<String> =
  (nodes.keys +
      nodes.values.flatMap { node ->
        INSTANCE_IDENTITY_PROPERTIES.mapNotNull { name ->
          ((node.properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        }
      })
    .toMutableSet()

/** An id nothing in the document and nothing already allocated in this batch is using. */
internal fun freshCopyId(preferred: String, taken: MutableSet<String>): String {
  var candidate = preferred
  var suffix = 2
  while (!taken.add(candidate)) {
    candidate = "$preferred-$suffix"
    suffix++
  }
  return candidate
}

internal fun Map<String, UiBuilderNode>.appendDuplicateSubtree(
  sourceNodeId: String,
  copyNodeId: String,
  parent: ParentSlot?,
  afterNodeId: String?,
  operations: MutableList<DesignOperation>,
  taken: MutableSet<String>,
) {
  val source = getValue(sourceNodeId)
  val selection = source.stateSelection()
  val childCopies = mutableMapOf<String, String>()
  operations +=
    DesignOperation.InsertNode(
      node =
        source.copy(
          id = copyNodeId,
          // A copy is a new instance, so it gets a new identity. Cloning `stableKey` put two
          // children in one lazy slot under the same `key(…)`, which Compose refuses at runtime,
          // and cloning `scrollStateKey` made two scroll containers share a position.
          properties =
            JsonObject(
              source.properties.withFreshInstanceIdentity(copyNodeId) -
                (if (selection != null) setOf(SHOW_BY_STATE) else emptySet())
            ),
          slots = source.slots.mapValues { emptyList() },
        ),
      parent = parent,
      afterNodeId = afterNodeId,
    )
  source.slots.forEach { (slot, children) ->
    var previousCopyId: String? = null
    children.forEach { childId ->
      // Allocated, not concatenated. A subtree holding a sibling `x-y` and a grandchild `y` under
      // `x` produced one id twice, and a concatenation can land on an existing node even when the
      // root it hangs from is fresh — either way the collaboration reducer rejects the whole paste
      // as a duplicate.
      val childCopyId = freshCopyId("$copyNodeId-${childId.replace('/', '-')}", taken)
      childCopies[childId] = childCopyId
      appendDuplicateSubtree(
        sourceNodeId = childId,
        copyNodeId = childCopyId,
        parent = ParentSlot(copyNodeId, slot),
        afterNodeId = previousCopyId,
        operations = operations,
        taken = taken,
      )
      previousCopyId = childCopyId
    }
  }
  selection?.let {
    operations +=
      DesignOperation.SetProperty(
        copyNodeId,
        SHOW_BY_STATE,
        it
          .copy(
            cases = it.cases.mapKeys { (id, _) -> childCopies.getValue(id) },
            fallback = it.fallback?.let(childCopies::getValue),
          )
          .encode(),
      )
  }
}

private fun JsonObject.withFreshInstanceIdentity(copyNodeId: String): JsonObject {
  val present = INSTANCE_IDENTITY_PROPERTIES.filter { it in this }
  if (present.isEmpty()) return this
  return JsonObject(
    toMap() +
      present.associateWith { name ->
        val existing = this[name] as? JsonObject
        literal(
          existing?.get("type")?.primitiveOrNull()?.contentOrNull ?: "string",
          JsonPrimitive(copyNodeId),
        )
      }
  )
}

/**
 * The wrapper a write to this property should carry, given the one the node already holds.
 *
 * Every node keeps its own encoded type, because two nodes can hold one property as a literal and
 * as a token and rewriting either to the other's shape changes more than was asked. The one
 * exception is a **plain `string` on a property whose values the catalog enumerates**: `enum` is
 * the canonical wrapper there (see `CapabilityValidator.writeWrapperIssue`), `string` is the
 * spelling that used to be written for the same intent, and the two exported down unrelated paths.
 * Preserving it would mean a legacy design's `textAlign` could no longer be edited at all, so the
 * next edit rewrites it instead — which is what migrates such a design without a migration.
 *
 * A semantic token — `typographyToken` on a `style` — is a different claim about the same value and
 * is kept as it is.
 */
internal fun PropertyCapability.canonicalWrapper(existingType: String?): String? =
  if (allowedValues.isEmpty()) existingType else existingType?.takeUnless { it == "string" }

internal fun JsonElement.asLiteral(property: PropertyCapability): JsonObject {
  if (this is JsonNull) return literal("string", JsonPrimitive(""))
  val primitive = jsonPrimitive
  val type =
    when {
      primitive.booleanOrNull != null -> "bool"
      primitive.doubleOrNull != null -> "float"
      PropertyValueKinds.isColour(property.name) ->
        if (primitive.content.isEmpty() || primitive.content.startsWith("#")) "color"
        else "colorToken"
      // Read off the declaration rather than from a list of three property names, which is how
      // `textAlign` and `horizontalAlignment` came to be written as `string` in the first place.
      property.allowedValues.isNotEmpty() -> "enum"
      else -> "string"
    }
  return literal(type, primitive)
}

/** This element's string content, or null when it is not a primitive string. */
internal fun JsonElement.contentOrNullSafe(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun literal(type: String, value: JsonElement): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

/** One numeric edge of an object-valued property the editor knows how to author. */
internal data class EditorObjectEdge(
  val field: String,
  val label: String,
  val minimum: Double,
  val maximum: Double,
)

/**
 * The object-valued shapes the inspector can author, and the numeric edges each is made of.
 *
 * Closed on purpose. A property the catalog declares as `"object"` and this map does not name stays
 * unsupported, which is the safe answer: `itemSpans` is an arbitrary map of child id to span and
 * there is no honest four-field control for it.
 */
internal val EDITOR_OBJECT_VALUE_EDGES: Map<String, List<EditorObjectEdge>> =
  mapOf(
    "padding" to
      listOf(
        EditorObjectEdge("startDp", "start", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("topDp", "top", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("endDp", "end", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
        EditorObjectEdge("bottomDp", "bottom", 0.0, MAXIMUM_AUTHORED_EDGE_DP),
      ),
    "adaptiveGrid" to
      listOf(
        EditorObjectEdge("minimumCellWidthDp", "minimum cell width", 1.0, MAXIMUM_AUTHORED_EDGE_DP)
      ),
  )

private const val MAXIMUM_AUTHORED_EDGE_DP = 4096.0

/**
 * The whole object value with one edge replaced, or null when the shape is not one this editor
 * authors.
 *
 * Every edge is written, not just the one that changed: the wire carries a `padding` as four
 * numbers and a value missing one of them is a different value, so an edge absent from the document
 * takes its own minimum rather than disappearing.
 */
internal fun objectValueWithEdge(
  kind: String,
  existing: JsonObject?,
  edgeName: String,
  edgeValue: JsonElement,
): JsonObject? {
  val edges = EDITOR_OBJECT_VALUE_EDGES[kind] ?: return null
  if (edges.none { it.field == edgeName }) return null
  val carried = existing?.takeIf { it["type"]?.primitiveOrNull()?.contentOrNull == kind }
  return JsonObject(
    buildMap {
      put("type", JsonPrimitive(kind))
      edges.forEach { edge ->
        put(
          edge.field,
          if (edge.field == edgeName) edgeValue
          else carried?.get(edge.field) ?: JsonPrimitive(edge.minimum),
        )
      }
    }
  )
}

internal sealed interface PropertyDraft {
  data class Valid(val value: JsonElement) : PropertyDraft

  data class Invalid(val message: String) : PropertyDraft
}

internal fun EditorPropertyField.parseDraft(draft: String): PropertyDraft {
  return when (control) {
    EditorPropertyControl.Text -> PropertyDraft.Valid(JsonPrimitive(draft))
    EditorPropertyControl.Boolean ->
      draft.toBooleanStrictOrNull()?.let { PropertyDraft.Valid(JsonPrimitive(it)) }
        ?: PropertyDraft.Invalid("$label must be true or false")
    EditorPropertyControl.Enum ->
      if (draft in choices) PropertyDraft.Valid(JsonPrimitive(draft))
      else PropertyDraft.Invalid("$label must be one of ${choices.joinToString()}")
    EditorPropertyControl.Number -> {
      val bounds = numberBounds ?: return PropertyDraft.Invalid("$label has no safe editor range")
      val number = draft.toDoubleOrNull()
      when {
        number == null || !number.isFinite() -> PropertyDraft.Invalid("$label must be a number")
        bounds.integer && number % 1.0 != 0.0 ->
          PropertyDraft.Invalid("$label must be a whole number")
        number < bounds.minimum || number > bounds.maximum ->
          PropertyDraft.Invalid(
            "$label must be ${bounds.minimum.format()}..${bounds.maximum.format()}"
          )
        bounds.integer -> PropertyDraft.Valid(JsonPrimitive(number.toLong()))
        else -> PropertyDraft.Valid(JsonPrimitive(number))
      }
    }
    EditorPropertyControl.Color -> {
      val color = draft.trim()
      // Empty is the component's own default, which `startAccentColor` documents as "draws none";
      // clearing the field is how an author asks for it.
      if (color.isEmpty() || PropertyValueKinds.isDrawableColour(color) || color in choices)
        PropertyDraft.Valid(JsonPrimitive(color))
      else
        PropertyDraft.Invalid("$label must be #RRGGBB, #AARRGGBB, or a listed Material color token")
    }
    EditorPropertyControl.Unsupported ->
      PropertyDraft.Invalid("$label cannot be safely edited from its catalog metadata")
  }
}

/**
 * `color` for a literal (or nothing), `colorToken` for a theme role — the wrappers the export
 * reads.
 */
internal fun colourWrapper(value: JsonElement): String {
  val content = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
  return if (content.isEmpty() || content.startsWith("#")) "color" else "colorToken"
}

internal fun EditorPropertyField.defaultEncodedType(): String =
  when (control) {
    EditorPropertyControl.Text -> "string"
    EditorPropertyControl.Boolean -> "bool"
    EditorPropertyControl.Number -> if (numberBounds?.integer == true) "int" else "float"
    EditorPropertyControl.Enum -> if (name == "style") "typographyToken" else "enum"
    EditorPropertyControl.Color -> "color"
    EditorPropertyControl.Unsupported -> "string"
  }

internal fun PropertyCapability.typeNames(): Set<String> =
  when (jsonType) {
    is JsonArray -> jsonType.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    else -> setOf(jsonType.jsonPrimitive.content)
  }

/**
 * The types a *person* authors, which is the declaration minus the shape a binding takes.
 *
 * `["number", "object"]` is how the catalog spells "a number, or a read of a state variable" —
 * `["string", "object"]` the same for text — and the object half is written by
 * `BindPropertyToState`, never typed into a field. Judging the control by the whole declaration
 * made every such property `Unsupported`: a text field's `value` and a slider's whole range were
 * uneditable for exactly the reason `["boolean", "string"]` once made `m3/filter-chip.selected`
 * uneditable, which is the comment above the boolean branch.
 *
 * A property declared *only* `"object"` still has nothing to offer — `contentPadding`, a search
 * input's `value` — and correctly falls through to `Unsupported` here, because removing the object
 * leaves nothing behind.
 */
internal fun Set<String>.authoredTypes(): Set<String> = this - "object"

internal fun PropertyCapability.numberBounds(typeNames: Set<String>): EditorNumberBounds? {
  val authored = typeNames.authoredTypes()
  if (authored != setOf("number") && authored != setOf("integer")) return null
  val editor = editor
  val minimum = editor?.minimum ?: return null
  val maximum = editor.maximum ?: return null
  val step = editor.step ?: if (authored == setOf("integer")) 1.0 else 0.1
  if (!minimum.isFinite() || !maximum.isFinite() || !step.isFinite()) return null
  if (minimum > maximum || step <= 0.0) return null
  return EditorNumberBounds(minimum, maximum, step, authored == setOf("integer"))
}

internal fun String.humanLabel(): String =
  replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

internal fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

internal fun UiBuilderDocument.location(nodeId: String): ParentSlot? {
  nodes.values.forEach { parent ->
    parent.slots.forEach { (slot, children) ->
      if (nodeId in children) return ParentSlot(parent.id, slot)
    }
  }
  return null
}

/**
 * The primitive a wire value holds, or null where it holds an array or an object.
 *
 * `jsonPrimitive` throws on anything else, and everything read through these accessors came off the
 * wire: a property encoded as `{"type": "string", "value": []}` is exactly what
 * `INVALID_PROPERTY_TYPE` reports, so the inspector must be able to draw the document that holds
 * one rather than take the editor down before the Issues panel can name it.
 */
internal fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/**
 * [nodeId] and everything under it.
 *
 * Guarded against a cycle in the document rather than trusting it not to hold one: this is asked
 * about a document that is being edited, and a cycle is one of the things the inspector reports
 * rather than one the editor may assume away.
 */
internal fun UiBuilderDocument.subtreeOf(nodeId: String): Set<String> {
  val collected = mutableSetOf<String>()
  val pending = ArrayDeque(listOf(nodeId))
  while (pending.isNotEmpty()) {
    val next = pending.removeFirst()
    if (!collected.add(next)) continue
    nodes[next]?.slots?.values?.forEach(pending::addAll)
  }
  return collected
}

internal fun UiBuilderDocument.children(parent: ParentSlot?): List<String> =
  if (parent == null) roots else nodes.getValue(parent.nodeId).slots[parent.slot].orEmpty()

/**
 * The layout modifiers worth a menu row, in the order they are offered.
 *
 * A deliberate subset of what the wire carries: these are the ones whose whole value is their
 * presence, so a press says everything there is to say. `size` and `clip` need a number and a
 * shape, which is a form rather than a row, and `padding` is offered at one typical value the
 * inspector can then change — the same bargain the catalog's default content makes.
 */
internal val MENU_MODIFIERS: List<MenuModifier> =
  listOf(
    MenuModifier("fillMaxSize", "Fill the parent") {
      buildJsonObject { put("type", "fillMaxSize") }
    },
    MenuModifier("fillMaxWidth", "Fill the width") {
      buildJsonObject { put("type", "fillMaxWidth") }
    },
    MenuModifier("fillMaxHeight", "Fill the height") {
      buildJsonObject { put("type", "fillMaxHeight") }
    },
    MenuModifier("matchParentSize", "Match the parent's size") {
      buildJsonObject { put("type", "matchParentSize") }
    },
    MenuModifier("verticalScroll", "Scroll vertically") {
      buildJsonObject { put("type", "verticalScroll") }
    },
    MenuModifier("horizontalScroll", "Scroll horizontally") {
      buildJsonObject { put("type", "horizontalScroll") }
    },
    MenuModifier("padding", "Add padding") {
      buildJsonObject {
        put("type", "padding")
        put("startDp", 16)
        put("topDp", 16)
        put("endDp", 16)
        put("bottomDp", 16)
      }
    },
    MenuModifier("align", "Align in the box", EditorLayoutScope.Box) {
      buildJsonObject {
        put("type", "align")
        put("alignment", "center")
      }
    },
    MenuModifier("alignHorizontal", "Align across the column", EditorLayoutScope.Column) {
      buildJsonObject {
        put("type", "alignHorizontal")
        put("alignment", "centerHorizontally")
      }
    },
    MenuModifier("alignVertical", "Align across the row", EditorLayoutScope.Row) {
      buildJsonObject {
        put("type", "alignVertical")
        put("alignment", "centerVertically")
      }
    },
    MenuModifier(
      "weight",
      "Take the leftover space",
      EditorLayoutScope.Column,
      EditorLayoutScope.Row,
      EditorLayoutScope.Collapsible,
    ) {
      buildJsonObject {
        put("type", "weight")
        put("weight", 1)
      }
    },
    // Remote Compose's collapsible layouts hide the lowest priority first; a child given one is
    // offered to go before its siblings, which carry none and are kept longest.
    MenuModifier(
      "collapsiblePriority",
      "Hide first when short of room",
      EditorLayoutScope.Collapsible,
    ) {
      buildJsonObject {
        put("type", "collapsiblePriority")
        put("priority", 1)
      }
    },
    MenuModifier("sharedElement", "Animate between states") {
      buildJsonObject {
        put("type", "sharedElement")
        put("key", 1)
      }
    },
  )

/**
 * The scope a node's parent puts it in, which is what decides whether an alignment or a weight
 * means anything.
 *
 * `Modifier.align` and `Modifier.weight` are not properties of the child: they are receivers the
 * parent supplies, and Compose refuses them at compile time outside one. The renderer applies them
 * from the parent's call site for exactly that reason, so the menu has to read the parent too —
 * otherwise it offers a row a weight that nothing would ever apply.
 */
internal enum class EditorLayoutScope {
  Box,
  Column,
  Row,
  /** A Remote Compose collapsible column or row: its children take a weight and a priority. */
  Collapsible,
}

/** The containers whose children the renderer composes inside a scope, and which one. */
private val EDITOR_LAYOUT_SCOPES: Map<String, EditorLayoutScope> =
  mapOf(
    "layout/box" to EditorLayoutScope.Box,
    // A card's content slot is a `Box` — in the renderer, in the capability exporter and in the
    // record-driven projection alike — so its children align like a box's.
    "m3/card" to EditorLayoutScope.Box,
    "layout/column" to EditorLayoutScope.Column,
    "layout/row" to EditorLayoutScope.Row,
    "layout/collapsible-column" to EditorLayoutScope.Collapsible,
    "layout/collapsible-row" to EditorLayoutScope.Collapsible,
  )

internal fun UiBuilderDocument.scopeOf(nodeId: String): EditorLayoutScope? {
  val parent = location(nodeId)?.nodeId ?: return null
  return EDITOR_LAYOUT_SCOPES[nodes[parent]?.componentId]
}

/** The nine a `Box` child may sit at, and the three each axis of a row or a column allows. */
private val BOX_ALIGNMENTS =
  listOf(
    "topStart",
    "topCenter",
    "topEnd",
    "centerStart",
    "center",
    "centerEnd",
    "bottomStart",
    "bottomCenter",
    "bottomEnd",
  )

private val COLUMN_ALIGNMENTS = listOf("start", "centerHorizontally", "end")

private val ROW_ALIGNMENTS = listOf("top", "centerVertically", "bottom")

/**
 * The values each modifier carries, and what to call them.
 *
 * Closed, like [EDITOR_OBJECT_VALUE_EDGES] and for the same reason: a modifier whose value is a
 * shape or a colour has no honest one-line control, and offering an empty one is worse than
 * offering none. An alignment does — it is a closed list — so it is here as a choice.
 */
internal val MODIFIER_FIELDS: Map<String, List<ModifierField>> =
  mapOf(
    "padding" to
      listOf(
        ModifierField("startDp", "Start"),
        ModifierField("topDp", "Top"),
        ModifierField("endDp", "End"),
        ModifierField("bottomDp", "Bottom"),
      ),
    "size" to listOf(ModifierField("widthDp", "Width"), ModifierField("heightDp", "Height")),
    "width" to listOf(ModifierField("widthDp", "Width")),
    "height" to listOf(ModifierField("heightDp", "Height")),
    "widthIn" to listOf(ModifierField("minDp", "Min width"), ModifierField("maxDp", "Max width")),
    "heightIn" to
      listOf(
        ModifierField("minDp", "Min height"),
        ModifierField("maxDp", "Max height"),
      ),
    "aspectRatio" to listOf(ModifierField("ratio", "Ratio")),
    "offset" to listOf(ModifierField("xDp", "X"), ModifierField("yDp", "Y")),
    "zIndex" to listOf(ModifierField("zIndex", "Z")),
    "border" to listOf(ModifierField("widthDp", "Border")),
    "alpha" to listOf(ModifierField("alpha", "Alpha")),
    "shadow" to listOf(ModifierField("elevationDp", "Elevation")),
    "rotate" to listOf(ModifierField("degrees", "Degrees")),
    "scale" to listOf(ModifierField("scaleX", "Scale X"), ModifierField("scaleY", "Scale Y")),
    "weight" to listOf(ModifierField("weight", "Weight")),
    "collapsiblePriority" to listOf(ModifierField("priority", "Priority")),
    "sharedElement" to listOf(ModifierField("key", "Shared key")),
    "align" to listOf(ModifierField("alignment", "Align", BOX_ALIGNMENTS)),
    "alignHorizontal" to listOf(ModifierField("alignment", "Align", COLUMN_ALIGNMENTS)),
    "alignVertical" to listOf(ModifierField("alignment", "Align", ROW_ALIGNMENTS)),
  )

internal class ModifierField(
  val name: String,
  val label: String,
  val choices: List<String> = emptyList(),
)

internal class MenuModifier(
  val type: String,
  val label: String,
  /** The scopes this modifier is offered in, or none where any parent will do. */
  vararg val scopes: EditorLayoutScope,
  val build: () -> JsonObject,
) {
  fun offeredIn(scope: EditorLayoutScope?): Boolean = scopes.isEmpty() || scope in scopes
}

/** The types on a node's chain, for asking whether it already carries one. */
internal fun UiBuilderNode.modifierTypes(): Set<String> =
  modifiers.mapNotNull { (it as? JsonObject)?.optionalStringValue("type") }.toSet()

internal fun JsonObject.optionalStringValue(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

/**
 * The node an operation is about, for selecting it from the history.
 *
 * The first one it names, which is the only honest answer for a batch that touched several: a list
 * row selects one node, and the summary beside it already says how many things moved. Null for a
 * command that only wrote the environment, which belongs to no node.
 */
internal fun AcceptedCommand.subjectNodeId(): String? =
  command.operations.firstNotNullOfOrNull { operation ->
    when (operation) {
      is DesignOperation.InsertNode -> operation.node.id
      is DesignOperation.MoveNode -> operation.nodeId
      is DesignOperation.DeleteNode -> operation.nodeId
      is DesignOperation.RestoreNode -> operation.nodeId
      is DesignOperation.SetProperty -> operation.nodeId
      is DesignOperation.RemoveNodeProperty -> operation.nodeId
      is DesignOperation.SetModifiers -> operation.nodeId
      is DesignOperation.SetStateVariable -> null
      is DesignOperation.RemoveStateVariable -> null
      is DesignOperation.SetEventBinding -> operation.nodeId
      is DesignOperation.SetEnvironment -> null
    }
  }

/**
 * Everything this command moved, as before/after pairs.
 *
 * Structure last, and named by what it did rather than by a position: "added", "moved", "deleted"
 * is what somebody reading a history wants, and a stable position key is not something to show
 * anyone. The values either side come from the change records, which is the only place the *old*
 * value survives at all — the document holds the new one.
 */
internal fun AcceptedCommand.describeChanges(): List<EditorOperationChange> =
  propertyChanges.map {
    EditorOperationChange(
      label =
        when (it.address.target) {
          PropertyTarget.StateVariable -> "State ${it.address.property}"
          PropertyTarget.EventBinding -> "${it.address.property} actions"
          PropertyTarget.Property -> it.address.property
        },
      before = it.before?.displayValue(),
      after = it.afterValue?.displayValue(),
    )
  } +
    modifierChanges.map {
      EditorOperationChange(
        label = "layout",
        before = it.before.modifierSummary(),
        after = it.after.modifierSummary(),
      )
    } +
    environmentChanges.map {
      EditorOperationChange(
        label = it.field,
        before = it.before?.displayValue(),
        after = it.after?.displayValue(),
      )
    } +
    structuralChanges.map {
      EditorOperationChange(
        label =
          when (it.kind) {
            StructuralChangeKind.INSERT -> "added"
            StructuralChangeKind.MOVE -> "moved"
            StructuralChangeKind.DELETE -> "deleted"
            StructuralChangeKind.RESTORE -> "restored"
          },
        before = it.beforePosition?.parent?.readable(),
        after = it.afterPosition?.parent?.readable(),
      )
    }

private fun ParentSlot.readable(): String = "$nodeId.$slot"

/**
 * A typed property value as the value alone.
 *
 * Properties are `{"type": …, "value": …}` and a history that showed the wrapper would be showing
 * the storage rather than the change. Anything that is not that shape is printed as it stands,
 * because guessing is worse than being literal about an unfamiliar value.
 */
internal fun JsonElement.displayValue(): String {
  val value = (this as? JsonObject)?.get("value") ?: this
  return value.primitiveOrNull()?.content ?: value.toString()
}

/**
 * A modifier chain as the short line the layout panel would show for it.
 *
 * Type plus its own values, because a chain named by type alone cannot distinguish the padding
 * somebody just changed from the padding they had. Empty is stated rather than left blank: "no
 * layout modifiers" is a real end of a change and an empty cell reads as missing information.
 */
internal fun JsonArray.modifierSummary(): String {
  if (isEmpty()) return "none"
  return joinToString(", ") { element ->
    val modifier = element as? JsonObject ?: return@joinToString element.toString()
    val type = modifier.optionalStringValue("type") ?: return@joinToString modifier.toString()
    val values =
      modifier
        .filterKeys { it != "type" }
        .values
        .mapNotNull { it.primitiveOrNull()?.content }
        .joinToString(" ")
    if (values.isEmpty()) type else "$type $values"
  }
}
