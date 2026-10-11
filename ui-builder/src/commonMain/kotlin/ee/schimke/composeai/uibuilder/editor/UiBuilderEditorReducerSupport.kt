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
import ee.schimke.composeai.uibuilder.capability.acceptsSeedValue
import ee.schimke.composeai.uibuilder.capability.insertContent
import ee.schimke.composeai.uibuilder.capability.textComponentFor
import ee.schimke.composeai.uibuilder.componentRootOf
import ee.schimke.composeai.uibuilder.export.MOTION_ENTERS
import ee.schimke.composeai.uibuilder.export.MOTION_EXITS
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.RemoteModifierVocabulary
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiExpressions
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
import kotlinx.serialization.json.putJsonObject

internal const val THEME_PRIMARY = "themePrimaryColor"
internal const val THEME_BACKGROUND = "themeBackgroundColor"
internal const val THEME_SURFACE = "themeSurfaceColor"
internal const val THEME_CONTENT = "themeContentColor"
internal const val THEME_TYPE_SCALE = "themeTypeScale"
internal const val THEME_CORNER_RADIUS = "themeCornerRadiusDp"
/**
 * Property-value shapes that read a state variable rather than holding a value; `stateEquals`
 * counts too (filter chips' `selected`).
 */
internal val STATE_VALUE_TYPES = setOf("state", "stateEquals")

/** Wrappers whose value the player computes; unbinding one restores a literal. */
internal val COMPUTED_VALUE_TYPES = setOf(UiExpressions.EXPR, UiExpressions.SYSTEM)

/**
 * One computed value of each scalar kind a property can hold; a property accepting any of them
 * offers a formula.
 */
internal val FORMULA_PROBES: List<JsonObject> =
  listOf(
    JsonObject(
      mapOf("type" to JsonPrimitive(UiExpressions.SYSTEM), "value" to JsonPrimitive("time.hour"))
    ),
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(UiExpressions.EXPR),
        "op" to JsonPrimitive("not"),
        "args" to
          kotlinx.serialization.json.JsonArray(
            listOf(
              JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(false)))
            )
          ),
      )
    ),
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(UiExpressions.EXPR),
        "op" to JsonPrimitive("select"),
        "args" to
          kotlinx.serialization.json.JsonArray(
            listOf(
              JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(true))),
              JsonObject(
                mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FFFFFFFF"))
              ),
              JsonObject(
                mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FF000000"))
              ),
            )
          ),
      )
    ),
  )

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
 * Theme-host properties the Theme panel owns (typefaces, default text style), hidden from the
 * Properties inspector only. [UiBuilderEditorReducer.propertyFields] still returns them because
 * agent `CommitProperty`s are validated against that list.
 */
internal val THEME_PANEL_PROPERTIES: Set<String> =
  ThemeTypefaces.PROPERTIES + ThemeTextStyle.PROPERTY

/** [fields] as the Properties inspector lists them: without [THEME_PANEL_PROPERTIES]. */
internal fun inspectorPropertyFields(fields: List<EditorPropertyField>): List<EditorPropertyField> =
  fields.filterNot {
    it.name in THEME_PANEL_PROPERTIES
  }

/** Whether [fields] belong to a theme host, whose typefaces the Theme panel sets. */
internal fun ownsThemePanelProperties(fields: List<EditorPropertyField>): Boolean = fields.any {
  it.name in THEME_PANEL_PROPERTIES
}

/**
 * The `m3/surface` this design hangs its theme on, or null. Uses [topLevelNodes] so a themed screen
 * wrapped in a board keeps its theme, as the renderer does.
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
 * Whether this row survives the insert panel's search: name, catalog id, kind and family headings,
 * and variant labels are all matchable.
 */
internal fun EditorCatalogItem.matches(needle: String): Boolean =
  matchesOwnName(needle) || variants.any { it.matches(needle) }

/**
 * Whether [needle] finds the component itself — name, catalog id, kind or family — rather than only
 * one of its variants. Quick insert lists a component row only then; a variant-only match is a
 * variant row, so Enter inserts the variant that was typed.
 */
internal fun EditorCatalogItem.matchesOwnName(needle: String): Boolean =
  needle.isEmpty() ||
    displayName.lowercase().contains(needle) ||
    componentId.lowercase().contains(needle) ||
    kind.label.lowercase().contains(needle) ||
    group.lowercase().contains(needle)

/** Whether a search for [needle] finds this variant by its label or its catalog value. */
internal fun EditorCatalogVariant.matches(needle: String): Boolean =
  label.lowercase().contains(needle) || value.lowercase().contains(needle)

/** Exact names lead search results; matches found only through a shelf or variant follow them. */
internal fun EditorCatalogItem.searchRank(needle: String): Int =
  when {
    displayName.lowercase() == needle -> 0
    displayName.lowercase().startsWith(needle) -> 1
    componentId.lowercase() == needle -> 2
    componentId.lowercase().contains(needle) -> 3
    variants.any { it.matches(needle) } -> 4
    kind.label.lowercase().contains(needle) -> 5
    else -> 6
  }

/**
 * A catalog enum value as a person reads it: `filledTonal` → "Filled tonal". Derived, so it cannot
 * go stale.
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

/**
 * How many components the insert panel's "Recently used" shelf keeps. A handful: enough for the
 * three or four a screen is being built from, few enough that the shelf stays a shortcut rather
 * than a second catalog pushing the real shelves below the fold.
 */
internal const val RECENT_COMPONENT_LIMIT = 6

/** The heading of that shelf. */
internal const val RECENT_SHELF = "Recently used"

/**
 * That shelf's identity: its lazy key and the entry its collapsed state is remembered under. Not
 * [RECENT_SHELF], because a catalog may name a shelf of its own "Recently used"; a catalog's shelf
 * names are labels a person reads, and none starts with a NUL.
 */
internal const val RECENT_SHELF_ID = "\u0000editor/recently-used"

/** The heading of the reader's pinned components. */
internal const val PINNED_SHELF = "Pinned"

/** That shelf's identity, apart from its label for the same reason as [RECENT_SHELF_ID]. */
internal const val PINNED_SHELF_ID = "\u0000editor/pinned"

/**
 * [componentId] moved to the front of a most-recently-used list, each id at most once, trimmed to
 * [limit]. Inserting the same Text five times is one entry, not five — the shelf answers "what was
 * I just using", and a run of one component would otherwise push everything else off it.
 */
internal fun List<String>.withRecentComponent(
  componentId: String,
  limit: Int = RECENT_COMPONENT_LIMIT,
): List<String> = (listOf(componentId) + filter { it != componentId }).take(limit)

/** The frame a catalog thumbnail's component is inserted into, and drawn at before scaling. */
internal const val PREVIEW_FRAME_WIDTH_DP = 176

internal const val PREVIEW_FRAME_HEIGHT_DP = 128

internal const val PREVIEW_FRAME_CELL_ID = "catalog-thumbnail-cell"

/** The frame's container where the catalog has one, and the slot every frame holds its child in. */
internal const val FRAME_BOX = "layout/box"

internal const val FRAME_SLOT = "children"

/** The list containers that frame a single child without spreading it, preferred over the rest. */
internal val FRAME_PREFERRED = setOf("box", "column")

/**
 * A larger thumbnail frame for components the default one crops (date pickers, clock dials,
 * dialogs), at Material's design size, then scaled down.
 */
internal val PREVIEW_ROOMY_FRAME = PreviewFrameSize(widthDp = 360, heightDp = 640)

/** The roomy frame for a watch: a large round screen, which Wear pickers and dialogs fill. */
internal val PREVIEW_ROOMY_WEAR_FRAME = PreviewFrameSize(widthDp = 240, heightDp = 240)

/** The size a thumbnail's component is laid out in, before it is scaled down to its tile. */
internal data class PreviewFrameSize(val widthDp: Int, val heightDp: Int) {
  companion object {
    val Default: PreviewFrameSize =
      PreviewFrameSize(PREVIEW_FRAME_WIDTH_DP, PREVIEW_FRAME_HEIGHT_DP)
  }
}

/**
 * This thumbnail document with its frame resized: both the environment window (read by
 * screen-size-aware components) and the frame cell's `size` modifier.
 */
internal fun UiBuilderDocument.inPreviewFrame(frame: PreviewFrameSize): UiBuilderDocument {
  val cell = nodes[PREVIEW_FRAME_CELL_ID] ?: return this
  val size =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive("size"),
        "widthDp" to JsonPrimitive(frame.widthDp),
        "heightDp" to JsonPrimitive(frame.heightDp),
      )
    )
  return copy(
    environment =
      JsonObject(
        environment +
          mapOf(
            "widthDp" to JsonPrimitive(frame.widthDp),
            "heightDp" to JsonPrimitive(frame.heightDp),
          )
      ),
    nodes =
      nodes +
        (PREVIEW_FRAME_CELL_ID to
          cell.copy(
            modifiers =
              JsonArray(
                cell.modifiers.map {
                  if ((it as? JsonObject)?.get("type") == JsonPrimitive("size")) size else it
                }
              )
          )),
  )
}

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
 * A neutral literal for this property: the first allowed value, or an empty one of its own type
 * (`""` is not a boolean).
 */
internal fun PropertyCapability.literalDefault(): JsonObject {
  allowedValues.firstOrNull()?.let {
    return it.asLiteral(this)
  }
  val types = typeNames() - "null"
  // Membership, not equality: ["boolean", "string"] means "a flag or a state variable name", and
  // `""` there would read as a variable name.
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
    "listPaneVisible",
    "detailPaneVisible",
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
    // Three one-key rows so an inserted loop draws something; the key is what the starter template
    // binds. Matched on component too, since another pack's scalar `data` would reject a list.
    "data" ->
      if (componentId == FOR_EACH_COMPONENT_ID) starterRows() else JsonPrimitive("").asLiteral(this)
    "itemWidthDp" -> literal("float", JsonPrimitive(128.0))
    "expanded",
    "selected",
    "visible" -> literal("bool", JsonPrimitive(false))
    // A fixed day (the renderer's fallback), not today, so the render is stable and inspector and
    // canvas agree.
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
 * Append the operations that insert this component and its children.
 *
 * A **required-slot fill** keeps the document valid (picked from the slot's accepted traits).
 * **Starter content** (see [StarterContent]) makes the insert look designed; it is checked against
 * the catalog and dropped if invalid, so it can never break an insert.
 *
 * @param starter the authored seed for this node when it is itself starter content: its properties
 *   override defaults and the slots it names are exact.
 * @param seedStarterContent false to only fill required slots (wrapping a selection, whose real
 *   children are about to arrive).
 * @param presetProperties values chosen at insertion (the palette variant), written over this
 *   node's starter content only.
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
  // Seeded nodes take the authored values; a palette insert takes the component's own.
  val seededProperties =
    (starter
        ?: if (seedStarterContent)
          StarterNode(
            componentId,
            catalog.insertContent(componentId)?.properties?.takeIf { published ->
              // All or nothing: a published seed with one value this component cannot take is
              // not the catalog's intent half-applied, so the table answers instead.
              published.isNotEmpty() &&
                published.all { (name, encoded) ->
                  propertiesByName[name]?.acceptsSeedValue(encoded) == true
                }
            } ?: StarterContent.propertiesFor(componentId),
          )
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
 * This seed with [presets] written over it, or a seed of the presets alone. Null with no presets
 * stays null, keeping plain inserts on the original path.
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
 * What goes in [slot]: the authored seed, starter content, or the required fill. Null means a
 * required slot nothing can satisfy (refusing the insert); empty is normal for an unseeded optional
 * slot.
 */
private fun ComponentCapability.plannedChildren(
  slot: SlotCapability,
  starter: StarterNode?,
  seedStarterContent: Boolean,
  catalog: CapabilityCatalog,
): List<Pair<ComponentCapability, StarterNode?>>? {
  // An authored seed's answer is used even when empty, which is how an item card opts out of
  // `m3/card`'s starter.
  starter?.slots?.get(slot.name)?.let { authored ->
    resolveStarterChildren(authored, slot, catalog)?.let {
      return it
    }
  }
  if (seedStarterContent && starter?.slots?.containsKey(slot.name) != true) {
    // The catalog's own seed first, then this build's table: a published seed that does not fit
    // the slot degrades to the table's, exactly as a stale table entry degrades to the fill.
    listOfNotNull(
        catalog.insertContent(componentId)?.slots?.get(slot.name),
        StarterContent.forComponent(componentId)[slot.name],
      )
      .firstNotNullOfOrNull { seeded -> resolveStarterChildren(seeded, slot, catalog) }
      ?.let {
        return it
      }
  }
  if (slot.cardinality.min == 0) return emptyList()
  val child = defaultChildFor(slot, catalog, componentId) ?: return null
  return List(slot.cardinality.min) { child to null }
}

/**
 * [children] resolved against the catalog, or null when the seed does not fit. Null degrades to the
 * required fill so a stale table entry can never make a component uninsertable;
 * `StarterContentTest` catches that.
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
 * This node with the seed's property values over the component's defaults. Undeclared or disallowed
 * values are dropped, since a validation failure would reject the insert.
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
      // The validator's own type rule as well, so a seed value of the wrong type — a catalog's
      // published seed, or a child of one — is dropped here rather than refusing the insert.
      (property.allowedValues.isEmpty() || value in property.allowedValues) &&
        property.acceptsSeedValue(encoded)
    }
  if (seeded.isNullOrEmpty()) return this
  return copy(properties = JsonObject(properties + seeded))
}

private fun defaultChildFor(
  slot: SlotCapability,
  catalog: CapabilityCatalog,
  /** The component that owns [slot]; its namespace is searched before Material 3's. */
  ownerId: String,
): ComponentCapability? {
  val preferredName =
    when {
      // Text before icon for slots accepting both, so buttons arrive with a label. A2UI slots take
      // only A2UI components, with their own (non-lower-case) ids.
      A2UI_COMPONENT_TRAIT in slot.acceptedTraits ->
        return catalog.textComponentFor(slot, ownerId)
          ?: catalog.componentsById[A2UI_TEXT]?.takeIf(slot::accepts)
      "SearchInput" in slot.acceptedTraits -> "search-input-field"
      "TextContent" in slot.acceptedTraits ->
        catalog.textComponentFor(slot, ownerId)?.let {
          return it
        } ?: "text"
      "IconContent" in slot.acceptedTraits -> "icon"
      "Leaf" in slot.acceptedRoles ->
        catalog.textComponentFor(slot, ownerId)?.let {
          return it
        } ?: "text"
      else -> return catalog.componentsById["layout/box"]?.takeIf(slot::accepts)
    }
  // The owner's namespace first, e.g. `wear-m3/text` for Wear slots.
  return listOf("${ownerId.substringBefore('/')}/$preferredName", "m3/$preferredName")
    .firstNotNullOfOrNull { id -> catalog.componentsById[id]?.takeIf(slot::accepts) }
}

/**
 * Every node reachable from [nodeId], detached from this document. A map because children live in
 * the document's flat `nodes`.
 */
/**
 * What this node says, for a layer row, or null.
 *
 * Found through the catalog: a component's required free-text property (a lone `string` with no
 * `allowedValues`) is what it exists to carry, e.g. `m3/text.text`. Optional strings are
 * configuration. `contentDescription` is the one by-name exception, as the node's human-readable
 * name.
 */
private val IDENTITY_PROPERTY_SUFFIXES = listOf("Key", "Id", "Base64", "Url")

internal fun UiBuilderNode.contentLabel(capability: ComponentCapability): String? {
  fun freeText(property: PropertyCapability) =
    property.allowedValues.isEmpty() &&
      property.typeNames() - "null" == setOf("string") &&
      !property.name.endsWith("Color", ignoreCase = true) &&
      // `required` is not sufficient: required plumbing strings (`assetKey`, `scrollStateKey`,
      // `documentBase64`) are excluded by suffix, letting images fall through to
      // `contentDescription`.
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
 * The selected nodes not inside another selected node, in selection order. A descendant selected
 * with its ancestor is not a separate target for delete or copy.
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
  val declarations = linkedMapOf<String, JsonObject>()
  fun visit(id: String) {
    val node = nodes[id] ?: return
    if (collected.put(id, node) != null) return
    node.slots.values.flatten().forEach(::visit)
    // A placement travels with its component, body and all, and so does every component that
    // body places in turn.
    node.placementKey()?.let { key ->
      val declaration = components[key] as? JsonObject ?: return@let
      if (declarations.put(key, declaration) == null) componentRootOf(declaration)?.let(::visit)
    }
  }
  // Tree order, not click order, so a paste does not reorder what was copied.
  val ordered = nodeIds.sortedBy(treeIndex())
  ordered.forEach(::visit)
  return EditorClipboard(rootNodeIds = ordered, nodes = collected, components = declarations)
}

/**
 * The same clipboard, told where the cut came from. Read before the delete: the first root in tree
 * order carries the position, anchored after a sibling that is not also being cut.
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
 * An id near [preferred] that this document does not already hold, so repeated pastes do not
 * collide.
 *
 * Qualified by [client], the operation id prefix (`clientId` plus a per-page nonce): sequences are
 * local, so two clients' first wraps would otherwise propose the same node id and one edit would be
 * rejected. `clientId` alone is not unique across browsers.
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
 * Properties identifying an instance rather than content: the exporter turns
 * `stableKey`/`scrollStateKey` into a lazy item `key(…)`, which must be unique.
 * `assetKey`/`iconKey` name content and must be kept.
 */
private val INSTANCE_IDENTITY_PROPERTIES = setOf("stableKey", "scrollStateKey")

/**
 * Every string a copy's identity must not collide with: node ids and identity keys already in use
 * (a copy's `stableKey` is its node id, which a sibling's key may already equal). Read with a safe
 * cast since wire data may hold anything.
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
          // A copy is a new instance: cloned `stableKey`s collide in Compose `key(…)` and cloned
          // `scrollStateKey`s share scroll position.
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
      // Allocated, not concatenated, since concatenation can collide with existing ids.
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
 * The wrapper a write to this property should carry, given the node's existing one. Each node keeps
 * its encoded type, except a plain `string` on an enumerated property, which is rewritten to the
 * canonical `enum` (see `CapabilityValidator.writeWrapperIssue`), migrating legacy designs on edit.
 * Semantic tokens like `typographyToken` are kept.
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
 * Object-valued shapes the inspector can author and their numeric edges. Closed on purpose: other
 * `"object"` properties (e.g. `itemSpans`) stay unsupported.
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
 * The whole object value with one edge replaced, or null when the shape is not authored here. Every
 * edge is written (missing edges take their minimum), since the wire carries the whole value.
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
 * `color` for a literal (or nothing), `colorToken` for a theme role: the wrappers the export reads.
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
 * The types a person authors: the declaration minus `"object"`, which is the shape a state binding
 * takes (`["number", "object"]` means a number or a state read). A property declared only
 * `"object"` stays `Unsupported`.
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
  return EditorNumberBounds(minimum, maximum, step, authored == setOf("integer") || editor.integer)
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
 * The primitive a wire value holds, or null for an array or object. `jsonPrimitive` would throw,
 * and the inspector must still draw a document with `INVALID_PROPERTY_TYPE`.
 */
internal fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/**
 * [nodeId] and everything under it, guarded against cycles since the edited document may contain
 * one.
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
 * The layout modifiers worth a menu row, in order: those whose value is mostly their presence.
 * `size` and `clip` need a form; `padding` is offered at a typical value the inspector can change.
 */
internal val MENU_MODIFIERS: List<MenuModifier> by lazy {
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
      // A way in and out of its own as a "Show by state" branch brings it in or takes it away.
      MenuModifier("animateEnterExit", "Animate in and out") {
        buildJsonObject {
          put("type", "animateEnterExit")
          put("enter", "slideInBottom")
          put("exit", "fadeOut")
        }
      },
    )
    // Then every Remote call the typed modifiers above do not already write.
    .let { typed -> typed + remoteCallMenuModifiers(REMOTE_CONTENT_MODIFIERS) }
}

/**
 * The scope a node's parent puts it in, deciding whether alignment or weight means anything:
 * `align`/`weight` are parent-supplied receivers, so offering them elsewhere would apply nothing.
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

/** The timing and the ways in and out a motion modifier may name, each optional. */
private val MOTION_MODIFIER_FIELDS: List<ModifierField> =
  listOf(
    ModifierField("durationMs", "Duration (ms)"),
    ModifierField("easing", "Easing", UiExpressions.EASINGS),
    ModifierField("enter", "Enter", MOTION_ENTERS),
    ModifierField("exit", "Exit", MOTION_EXITS),
  )

/**
 * The values each modifier carries and their labels. Closed, like [EDITOR_OBJECT_VALUE_EDGES]:
 * shapes and colours have no one-line control, while alignments are a choice.
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
    "sharedElement" to listOf(ModifierField("key", "Shared key")) + MOTION_MODIFIER_FIELDS,
    "animateEnterExit" to MOTION_MODIFIER_FIELDS,
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
  /**
   * The key toggles and fields use: the type, or `remoteCall:<name>` for a remote call (see
   * [modifierKey]).
   */
  val key: String = type,
  val build: () -> JsonObject,
) {
  fun offeredIn(scope: EditorLayoutScope?): Boolean = scopes.isEmpty() || scope in scopes
}

/** The types on a node's chain, for asking whether it already carries one. */
internal fun UiBuilderNode.modifierTypes(): Set<String> =
  modifiers.mapNotNull { (it as? JsonObject)?.optionalStringValue("type") }.toSet()

/** The menu keys on a node's chain: types, and `remoteCall:<name>` for each Remote call. */
internal fun UiBuilderNode.modifierKeys(): Set<String> =
  modifiers.mapNotNull { (it as? JsonObject)?.let(::modifierKey) }.toSet()

/**
 * A modifier's menu key: its type, or `remoteCall:<name>` for a call from the Remote vocabulary.
 */
internal fun modifierKey(modifier: JsonObject): String? {
  val type = modifier.optionalStringValue("type") ?: return null
  return if (type == RemoteModifierVocabulary.TYPE)
    "$REMOTE_CALL_KEY_PREFIX${modifier.optionalStringValue("name").orEmpty()}"
  else type
}

internal const val REMOTE_CALL_KEY_PREFIX: String = "remoteCall:"

/**
 * One menu entry per generated `RemoteModifier` call no typed modifier writes, added with its
 * simplest overload and starting values for required arguments.
 */
private fun remoteCallMenuModifiers(typed: Set<String>): List<MenuModifier> {
  // Calls that only mean something with context a single node does not have (a lazy list's
  // parent, a macro, an enter/exit transition pair, a bare `clip` with no authorable shape).
  val notOffered =
    setOf(
      "alignByBaseline",
      "animateEnterExit",
      "animationSpec",
      "clip",
      "fillParentMaxHeight",
      "fillParentMaxSize",
      "fillParentMaxWidth",
      "includeMacro",
      "sharedBounds",
    )
  return RemoteModifierVocabulary.modifiers.values
    .filter { it.name !in typed && it.name !in notOffered }
    .map { call ->
      val simplest = call.overloads.minBy { o -> o.parameters.count { !it.optional } }
      MenuModifier(
        RemoteModifierVocabulary.TYPE,
        "Remote: ${call.name}",
        key = "$REMOTE_CALL_KEY_PREFIX${call.name}",
      ) {
        buildJsonObject {
          put("type", RemoteModifierVocabulary.TYPE)
          put("name", call.name)
          putJsonObject("args") {
            simplest.parameters
              .filterNot { it.optional }
              .forEach { parameter -> put(parameter.name, remoteCallStartingValue(parameter)) }
          }
        }
      }
    }
}

private fun remoteCallStartingValue(parameter: RemoteModifierVocabulary.Parameter): JsonObject =
  buildJsonObject {
    when (parameter.kind) {
      "color" -> {
        put("type", "colorToken")
        put("value", "primary")
      }
      "bool" -> {
        put("type", "bool")
        put("value", true)
      }
      "int" -> {
        put("type", "int")
        put("value", 0)
      }
      "string" -> {
        put("type", "string")
        put("value", "")
      }
      else -> {
        put("type", "float")
        put("value", 1)
      }
    }
  }

/**
 * The editable arguments of a `remoteCall` from its resolved overload: numbers and dp as numeric
 * fields, booleans as a choice. Colours and strings keep their starting value.
 */
internal fun remoteCallFields(modifier: JsonObject): List<ModifierField> {
  val name = modifier.optionalStringValue("name") ?: return emptyList()
  val args = (modifier["args"] as? JsonObject)?.keys.orEmpty()
  val overload =
    RemoteModifierVocabulary.resolve(name, args)
      ?: RemoteModifierVocabulary.modifiers[name]?.overloads?.firstOrNull()
      ?: return emptyList()
  return overload.parameters.mapNotNull { parameter ->
    when (parameter.kind) {
      "float",
      "dp",
      "int" -> ModifierField(parameter.name, parameter.name.humanLabel())
      "bool" -> ModifierField(parameter.name, parameter.name.humanLabel(), listOf("true", "false"))
      else -> null
    }
  }
}

/** The wire wrapper a `remoteCall` argument is written in, by its parameter kind. */
internal fun remoteCallArgumentType(name: String, parameter: String): String? =
  RemoteModifierVocabulary.modifiers[name]
    ?.overloads
    ?.flatMap { it.parameters }
    ?.firstOrNull { it.name == parameter }
    ?.kind
    ?.let {
      when (it) {
        "int" -> "int"
        "bool" -> "bool"
        "float",
        "dp" -> "float"
        else -> null
      }
    }

internal fun JsonObject.optionalStringValue(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

/**
 * The node an operation is about, for selecting it from the history: the first it names, or null
 * for an environment-only command.
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
      is DesignOperation.SetComponentArguments -> operation.nodeId
      is DesignOperation.SetStateVariable -> null
      is DesignOperation.RemoveStateVariable -> null
      is DesignOperation.SetEventBinding -> operation.nodeId
      is DesignOperation.SetEnvironment -> null
      is DesignOperation.DeclareComponent -> null
      is DesignOperation.RemoveComponent -> null
    }
  }

/**
 * Everything this command moved, as before/after pairs from the change records (the only place old
 * values survive). Structure changes last, named "added"/"moved"/"deleted".
 */
internal fun AcceptedCommand.describeChanges(): List<EditorOperationChange> =
  propertyChanges.map {
    EditorOperationChange(
      label =
        when (it.address.target) {
          PropertyTarget.StateVariable -> "State ${it.address.property}"
          PropertyTarget.EventBinding -> "${it.address.property} actions"
          PropertyTarget.Property -> it.address.property
          PropertyTarget.Component -> "Component ${it.address.property}"
          PropertyTarget.ComponentArguments -> "Arguments"
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

/** A typed property value as the value alone; unfamiliar shapes are printed literally. */
internal fun JsonElement.displayValue(): String {
  val value = (this as? JsonObject)?.get("value") ?: this
  return value.primitiveOrNull()?.content ?: value.toString()
}

/**
 * A modifier chain as a short line: types with their values, or an explicit "no layout modifiers".
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
