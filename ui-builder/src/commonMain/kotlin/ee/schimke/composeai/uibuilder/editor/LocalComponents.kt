package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.componentRootOf
import ee.schimke.composeai.uibuilder.export.KOTLIN_HARD_KEYWORDS
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Local components: a subtree of this design, named, drawn wherever a placement names it.
 *
 * The document model, the canvas and both exporters already speak this
 * ([`UI_BUILDER_REPETITION_AND_COMPONENTS.md`](../../../../../../../../docs/design/UI_BUILDER_REPETITION_AND_COMPONENTS.md)
 * §2): `components[key] = {name, root}` holds the definition, a `design/component-instance` node
 * with `component = {componentKey, arguments}` is a placement, and a body property reads an
 * argument as `{"type": "binding", "value": "<key>"}`. What this file adds is the editor's half —
 * making one from a selection, listing the ones a design has, and swapping one for a catalog
 * component once the app ships it — as pure functions of the document, so the reducer, the palette
 * and the inspector ask one place.
 */
internal const val COMPONENT_INSTANCE_ID = "design/component-instance"

/** One component this design defines, as the palette and the inspector show it. */
data class EditorLocalComponent(
  val key: String,
  /** The composable's name — what the export writes as `@Composable fun <name>`. */
  val name: String,
  val rootNodeId: String,
  /** The catalog component the body is made of at its root, for the palette tile's icon. */
  val rootComponentId: String?,
  /** How many placements the design holds, so an unused definition reads as one. */
  val placements: Int,
  /** The argument keys the body reads, sorted — the generated function's parameters. */
  val parameters: List<String>,
  /**
   * A catalog component this one has graduated into, when the catalog now ships one under the same
   * name — the cue to swap the design-only copy for the app's own.
   */
  val publishedAs: ComponentCapability? = null,
)

/**
 * One component a project publishes to its shared library, as the palette lists it — the server's
 * `GET /api/ui-builder/v1/component-library` row. Its body is fetched only when it is placed.
 */
data class EditorLibraryComponent(
  val system: String,
  val componentId: String,
  /** `project/<id>`: what the drift report calls it, so its rows select this entry. */
  val paletteId: String,
  val title: String,
  val description: String? = null,
)

/**
 * One published component, fetched to be placed: its declaration and the nodes its body is made of,
 * and the digest a design records beside it so a later read can tell it has drifted.
 */
data class EditorLibrarySymbol(
  val component: EditorLibraryComponent,
  val digest: String,
  /** `{name, root, description?}`, the declaration as published. */
  val declaration: JsonObject,
  val nodes: Map<String, UiBuilderNode>,
)

/** Where [declaration] says it was imported from: system, component id and digest, or null. */
internal fun componentSource(declaration: JsonElement?): Triple<String, String, String>? {
  val source = (declaration as? JsonObject)?.get("source") as? JsonObject ?: return null
  fun field(name: String) = (source[name] as? JsonPrimitive)?.contentOrNull
  return Triple(
    field("system") ?: return null,
    field("componentId") ?: return null,
    field("digest") ?: return null,
  )
}

/** One component verb the context menu offers for the selection. */
data class EditorComponentAction(val label: String, val event: UiBuilderEditorEvent)

/** One argument of the selected placement, as the inspector edits it. */
data class EditorComponentArgument(
  val key: String,
  /** The wire wrapper type the value holds: `string`, `color`, `bool`, … */
  val type: String,
  /** The scalar the inspector edits, as text. */
  val value: String,
)

internal fun componentDeclarationName(declaration: JsonElement?): String? =
  ((declaration as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

internal fun UiBuilderNode.placementKey(): String? =
  if (componentId != COMPONENT_INSTANCE_ID) null
  else (component?.get("componentKey") as? JsonPrimitive)?.contentOrNull

internal fun UiBuilderNode.placementArguments(): JsonObject =
  component?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())

/** The nodes a component's body is made of, root first. */
internal fun UiBuilderDocument.componentBody(key: String): Set<String> =
  componentRootOf(components[key])?.let(::subtreeOf).orEmpty()

/** The component whose body [nodeId] belongs to, or null for a node of the screen itself. */
internal fun UiBuilderDocument.owningComponent(nodeId: String): String? =
  components.keys.firstOrNull { nodeId in componentBody(it) }

/** Every argument key a body reads, mapped to the first `node.property` that reads it. */
internal fun UiBuilderDocument.bodyBindings(key: String): Map<String, Pair<String, String>> {
  val reads = mutableMapOf<String, Pair<String, String>>()
  componentBody(key).sorted().forEach { nodeId ->
    nodes[nodeId]?.properties?.forEach { (property, value) ->
      value.bindingKey()?.let { if (it !in reads) reads[it] = nodeId to property }
    }
  }
  // Sorted by key, which is the generated function's parameter order.
  return reads.entries.sortedBy { it.key }.associate { it.key to it.value }
}

internal fun JsonElement.bindingKey(): String? =
  (this as? JsonObject)
    ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "binding" }
    ?.let { (it["value"] as? JsonPrimitive)?.contentOrNull }

internal fun binding(key: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("binding"), "value" to JsonPrimitive(key)))

/** The components this design defines, in name order, with what the catalog now ships of them. */
internal fun UiBuilderDocument.localComponents(
  catalog: CapabilityCatalog
): List<EditorLocalComponent> {
  val placementCounts = nodes.values.mapNotNull { it.placementKey() }.groupingBy { it }.eachCount()
  return components.entries
    .mapNotNull { (key, declaration) ->
      val root = componentRootOf(declaration) ?: return@mapNotNull null
      val name = componentDeclarationName(declaration) ?: key
      EditorLocalComponent(
        key = key,
        name = name,
        rootNodeId = root,
        rootComponentId = nodes[root]?.componentId,
        placements = placementCounts[key] ?: 0,
        parameters = bodyBindings(key).keys.toList(),
        publishedAs = catalog.publishedComponentNamed(name),
      )
    }
    .sortedWith(compareBy({ it.name.lowercase() }, { it.key }))
}

/**
 * The catalog component that ships what a local component named [name] drew, if there is one.
 *
 * Matched on the composable's name — the catalog's `code.symbol`, as a component pack records an
 * app's own `@Composable fun InboxEmail` — and then on the display name with its spaces taken out.
 * Never on a prefix or a likeness: a swap rewrites every placement, and a guess that is nearly
 * right is a design quietly drawing a different component.
 */
internal fun CapabilityCatalog.publishedComponentNamed(name: String): ComponentCapability? {
  val wanted = name.lowercase()
  return paletteComponents.firstOrNull { component ->
    component.code?.symbol?.substringAfterLast('.')?.lowercase() == wanted
  }
    ?: paletteComponents.firstOrNull { component ->
      component.displayName.filterNot(Char::isWhitespace).lowercase() == wanted
    }
}

/**
 * A Kotlin function name from what somebody typed: `inbox email` → `InboxEmail`.
 *
 * The export keeps the authored name as the composable's name, so it has to be a legal one before
 * it reaches the document, rather than a refusal at the first export.
 */
internal fun componentFunctionName(raw: String): String? {
  val words = raw.split(Regex("[^A-Za-z0-9]+")).filter(String::isNotEmpty)
  val name = words.joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
  if (name.isEmpty() || !name.first().isLetter() || name in KOTLIN_HARD_KEYWORDS) return null
  return name
}

/** A key no other component uses, from the function name: `InboxEmail` → `inbox-email`. */
internal fun UiBuilderDocument.freshComponentKey(name: String): String {
  val base = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
  if (base !in components) return base
  var suffix = 2
  while ("$base-$suffix" in components) suffix++
  return "$base-$suffix"
}

/** The name a new component starts with: the root's display name, `Component` if that is taken. */
internal fun UiBuilderDocument.suggestedComponentName(
  root: UiBuilderNode,
  catalog: CapabilityCatalog,
): String {
  val taken = components.values.mapNotNull(::componentDeclarationName).toSet()
  val base =
    componentFunctionName(
      "My " + (catalog.componentsById[root.componentId]?.displayName ?: "Component")
    ) ?: "MyComponent"
  if (base !in taken) return base
  var suffix = 2
  while ("$base$suffix" in taken) suffix++
  return "$base$suffix"
}

/**
 * The parameter a promoted text becomes, named after what it says when that reads as a name.
 *
 * A row a designer filled with `Sender`, `Subject` and `Preview` exports as `InboxEmail(sender = …,
 * subject = …, preview = …)`, which is the call somebody would have written. One word only: real
 * content — `Ada Lovelace`, `10:42`, a sentence — is what one placement says rather than what the
 * slot is, and `adaLovelace` is a worse parameter than the property's own name, numbered from the
 * second.
 */
internal fun parameterNameFor(property: String, content: String?, taken: Set<String>): String {
  val fromContent =
    content
      ?.trim()
      ?.takeIf { word ->
        word.isNotEmpty() && word.first().isLetter() && word.all(Char::isLetterOrDigit)
      }
      ?.lowercase()
      ?.takeIf { it.length <= 24 && it !in KOTLIN_HARD_KEYWORDS && it !in RESERVED_PARAMETERS }
  val base = fromContent ?: property
  if (base !in taken) return base
  var suffix = 2
  while ("$base$suffix" in taken) suffix++
  return "$base$suffix"
}

/**
 * Modifiers that speak to the slot a node sits in. A component's body root draws inside its
 * placement's `Box`, so one of these left on it would silently stop meaning anything.
 */
internal val PARENT_SCOPED_MODIFIERS =
  setOf("weight", "align", "alignHorizontal", "alignVertical", "matchParentSize")

/** Names the generated function already uses for itself. */
private val RESERVED_PARAMETERS = setOf("modifier", "content")

/**
 * Why [name] cannot be a parameter of a component that already has [existing], or null when it can:
 * a plain lower-camel Kotlin name, not one the generated function already uses, not taken.
 */
internal fun componentParameterRefusal(name: String, existing: Set<String>): String? =
  when {
    name.isEmpty() -> "A parameter needs a name"
    !name.first().isLowerCase() || !name.all(Char::isLetterOrDigit) ->
      "`$name` is not a parameter name: start with a lower-case letter, then letters and digits"
    name in KOTLIN_HARD_KEYWORDS || name in RESERVED_PARAMETERS ->
      "`$name` is a name Kotlin or the generated function already uses"
    Regex("(argument|capture)\\d+").matches(name) -> "`$name` is a name the export generates"
    name in existing -> "This component already has a parameter called `$name`"
    else -> null
  }

/** What a placement draws as a capability — its body root's — for the slot rules to ask about. */
internal fun UiBuilderDocument.placedCapability(
  key: String,
  catalog: CapabilityCatalog,
): ComponentCapability? =
  componentRootOf(components[key])?.let(nodes::get)?.componentId?.let(catalog.componentsById::get)
