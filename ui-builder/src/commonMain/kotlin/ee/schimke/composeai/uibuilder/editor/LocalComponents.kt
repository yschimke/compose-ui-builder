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
 * Local components: a named subtree of this design, drawn wherever a placement names it.
 *
 * The model is in
 * [`UI_BUILDER_REPETITION_AND_COMPONENTS.md`](../../../../../../../../docs/design/UI_BUILDER_REPETITION_AND_COMPONENTS.md)
 * §2: `components[key] = {name, root}` is the definition, a `design/component-instance` node with
 * `component = {componentKey, arguments}` is a placement, and body properties read arguments as
 * `{"type": "binding", "value": "<key>"}`. This file holds the editor's pure functions: making,
 * listing and graduating components.
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
   * A catalog component of the same name this one has graduated into: the cue to swap to the app's
   * own.
   */
  val publishedAs: ComponentCapability? = null,
  /** Where the project library holds this component, once it was imported or published there. */
  val source: EditorLibrarySource? = null,
  /** The library's drift report says it holds a newer version than this design's copy. */
  val newerInLibrary: Boolean = false,
)

/** A component's place in the project library: the version a design holds is [digest]. */
data class EditorLibrarySource(val system: String, val componentId: String, val digest: String)

/**
 * One of this design's components as a library publish sends it. [document] is the one-component
 * `DesignDocumentV1` a project would commit, so the server reads it with the same reader.
 */
data class EditorLibraryPublication(
  val componentKey: String,
  val system: String,
  val componentId: String,
  val title: String,
  val description: String?,
  /** Null publishes a new component; otherwise the library version this one replaces. */
  val replacesDigest: String?,
  val document: UiBuilderDocument,
)

/** What a publish came to. */
sealed interface EditorLibraryPublishResult {
  /** The library now holds this version; the design records [digest] beside its component. */
  data class Published(val digest: String) : EditorLibraryPublishResult

  /** Why not, in a sentence for the person who pressed the button. */
  data class Refused(val message: String) : EditorLibraryPublishResult
}

/**
 * One library component as the palette lists it (a `GET /api/ui-builder/v1/component-library` row).
 * Its body is fetched only when placed.
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
 * One published component fetched for placing: its declaration, body nodes, and the digest recorded
 * to detect drift.
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
        source =
          componentSource(declaration)?.let { (system, componentId, digest) ->
            EditorLibrarySource(system, componentId, digest)
          },
      )
    }
    .sortedWith(compareBy({ it.name.lowercase() }, { it.key }))
}

/**
 * The catalog component that ships what a local component named [name] drew, if any. Matched
 * exactly on the catalog's `code.symbol`, then on the display name without spaces; never fuzzily,
 * since a swap rewrites every placement.
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
 * A legal Kotlin function name from typed text (`inbox email` → `InboxEmail`), since the export
 * uses it as the composable's name.
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
 * The parameter a promoted text becomes: named after its content when that is one word (`Sender` →
 * `sender`), else the property's own name, numbered from the second.
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
 * Modifiers addressed to the parent slot; meaningless on a body root drawn inside its placement's
 * `Box`.
 */
internal val PARENT_SCOPED_MODIFIERS =
  setOf("weight", "align", "alignHorizontal", "alignVertical", "matchParentSize")

/** Names the generated function already uses for itself. */
private val RESERVED_PARAMETERS = setOf("modifier", "content")

/**
 * Why [name] cannot be a parameter alongside [existing], or null: it must be a plain lower-camel
 * Kotlin name not used by the generated function or taken.
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

/**
 * Why [key] cannot be published to the project library, or null. Mirrors the server's read rules: a
 * body may use only its catalog's components, not other components.
 */
internal fun UiBuilderDocument.libraryPublicationRefusal(key: String): String? {
  val declaration = components[key] as? JsonObject ?: return "This design has no component $key"
  val name = componentDeclarationName(declaration) ?: key
  if (catalogSystem() == null) return "This design does not say which catalog it is drawn with"
  val nested = componentBody(key).mapNotNull { nodes[it]?.placementKey() }.toSet()
  if (nested.isNotEmpty()) {
    val names = nested.map { componentDeclarationName(components[it]) ?: it }.sorted()
    return "$name places ${names.joinToString()}; a published component uses only catalog components"
  }
  return null
}

/**
 * [key] as a publish sends it: a one-component document, under its previous import/publish id or
 * else its own key.
 */
internal fun UiBuilderDocument.libraryPublication(key: String): EditorLibraryPublication? {
  if (libraryPublicationRefusal(key) != null) return null
  val declaration = components[key] as? JsonObject ?: return null
  val root = componentRootOf(declaration) ?: return null
  val system = catalogSystem() ?: return null
  val name = componentDeclarationName(declaration) ?: key
  val recorded = componentSource(declaration)?.takeIf { (from, _, _) -> from == system }
  val componentId =
    recorded?.second
      ?: key.takeIf(LIBRARY_COMPONENT_ID::matches)
      ?: freshComponentKey(name).takeIf(LIBRARY_COMPONENT_ID::matches)
      ?: return null
  val body = componentBody(key)
  val bodyNodes = nodes.filterKeys { it in body }
  // Only the assets the body draws: the registry can hold a whole screen's embedded pictures.
  val drawn = bodyNodes.values.joinToString { it.properties.toString() }
  val description = (declaration["description"] as? JsonPrimitive)?.contentOrNull
  return EditorLibraryPublication(
    componentKey = key,
    system = system,
    componentId = componentId,
    title = name,
    description = description,
    replacesDigest = recorded?.third,
    document =
      UiBuilderDocument(
        schema = schema,
        id = componentId,
        title = name,
        revision = 0,
        catalogPin = catalogPin,
        environment = environment,
        stateVariables = JsonObject(emptyMap()),
        roots = listOf(root),
        nodes = bodyNodes,
        assets = JsonObject(assets.filterKeys { "\"$it\"" in drawn }),
        components = JsonObject(mapOf(componentId to JsonObject(declaration - "source"))),
      ),
  )
}

/** The declaration [declaration] with [source] recorded as where the library holds it. */
internal fun withLibrarySource(declaration: JsonObject, source: EditorLibrarySource): JsonObject =
  JsonObject(
    declaration +
      ("source" to
        JsonObject(
          mapOf(
            "system" to JsonPrimitive(source.system),
            "componentId" to JsonPrimitive(source.componentId),
            "digest" to JsonPrimitive(source.digest),
          )
        ))
  )

private fun UiBuilderDocument.catalogSystem(): String? =
  (catalogPin["systemId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

/** The id rule the library's index applies; anything else would be dropped on its first read. */
private val LIBRARY_COMPONENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
