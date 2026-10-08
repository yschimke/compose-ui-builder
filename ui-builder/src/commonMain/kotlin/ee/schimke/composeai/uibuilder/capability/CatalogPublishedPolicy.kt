package ee.schimke.composeai.uibuilder.capability

import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.composeai.uibuilder.StarterNode
import ee.schimke.composeai.uibuilder.export.CatalogBuilderRoles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * What a catalog states about its own components that this build otherwise answers from a Kotlin
 * table: the role a component plays, the range a property is edited over, and what a component
 * arrives holding when it is inserted.
 *
 * All three are read out of [CapabilityCatalog.statusSemantics] — the catalog's published policy,
 * which reaches the editor verbatim — rather than from new wire fields, because the wire types are
 * released from another repository and these are builder presentation, not design data. Each reader
 * is consulted **before** the Kotlin table it replaces and falls back to it, so a catalog that
 * publishes none of this is edited exactly as before. `UI_BUILDER_CATALOG_CONTRACT.md` § Catalog-
 * published editor policy is the contract.
 */
internal object CatalogPublishedPolicy {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * A component's published policy: `statusSemantics.components[id]`, or a builtin's
   * `statusSemantics.builtins[id]`.
   */
  fun componentPolicy(statusSemantics: JsonObject, componentId: String): JsonObject? =
    (statusSemantics["components"] as? JsonObject)?.get(componentId) as? JsonObject
      ?: (statusSemantics["builtins"] as? JsonObject)?.get(componentId) as? JsonObject

  /**
   * The editor a catalog states for [property] of [componentId]: the `editor` object of the
   * matching `propertyCapabilities` entry (a builtin's `properties`). Null when it states none, or
   * states one this build cannot honour — a number range with no finite bounds, or bounds the wrong
   * way round — so a bad declaration costs the catalog its range, never the property.
   */
  fun editorFor(
    statusSemantics: JsonObject,
    componentId: String,
    property: String,
  ): PropertyEditorCapability? {
    val policy = componentPolicy(statusSemantics, componentId) ?: return null
    val declared =
      ((policy["propertyCapabilities"] ?: policy["properties"]) as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == property }
        ?.get("editor") as? JsonObject ?: return null
    val editor =
      runCatching { json.decodeFromJsonElement<PropertyEditorCapability>(declared) }.getOrNull()
        ?: return null
    return editor.takeIf { it.isHonourable() }
  }

  private fun PropertyEditorCapability.isHonourable(): Boolean {
    if (control == null && objectKind == null) return false
    if (control != PropertyEditorControl.NUMBER) return true
    val min = minimum ?: return false
    val max = maximum ?: return false
    if (!min.isFinite() || !max.isFinite() || min > max) return false
    return step?.let { it.isFinite() && it > 0 } ?: true
  }

  /**
   * What a catalog says [componentId] arrives holding: `insertContent`, shaped like [StarterNode] —
   *
   * ```json
   * "insertContent": {
   *   "properties": { "checked": {"type": "boolean", "value": true} },
   *   "slots": { "label": [ {"componentId": "acme/text",
   *                          "properties": {"text": {"type": "string", "value": "Checkbox"}}} ] }
   * }
   * ```
   *
   * Null when it says nothing, or says something that does not decode; the insert then falls back
   * to the Kotlin starter table, and the catalog check that already guards that table
   * (`resolveStarterChildren`) guards this too.
   */
  fun insertContentFor(statusSemantics: JsonObject, componentId: String): StarterNode? {
    val declared =
      componentPolicy(statusSemantics, componentId)?.get("insertContent") as? JsonObject
        ?: return null
    return declared.toStarterNode(componentId)
  }

  private fun JsonObject.toStarterNode(componentId: String): StarterNode? {
    val properties =
      (this["properties"] as? JsonObject)?.mapValues { (_, value) ->
        value as? JsonObject ?: return null
      } ?: emptyMap()
    val slots =
      (this["slots"] as? JsonObject)?.mapValues { (_, children) ->
        (children as? JsonArray)?.map { child -> child.toChildStarterNode() ?: return null }
          ?: return null
      } ?: emptyMap()
    return StarterNode(componentId, properties, slots)
  }

  private fun JsonElement.toChildStarterNode(): StarterNode? {
    val child = this as? JsonObject ?: return null
    val id = (child["componentId"] as? JsonPrimitive)?.contentOrNull ?: return null
    return child.toStarterNode(id)
  }
}

/** [CatalogPublishedPolicy.insertContentFor] for this catalog. */
internal fun CapabilityCatalog.insertContent(componentId: String): StarterNode? =
  CatalogPublishedPolicy.insertContentFor(statusSemantics, componentId)

/** The components of this catalog that carry [role], one of [CatalogBuilderRoles]. */
internal fun CapabilityCatalog.componentsWithRole(role: String): List<ComponentCapability> =
  components.filter {
    role in it.traits
  }

/**
 * The catalog's own scrolling containers, by [CatalogBuilderRoles.VERTICAL_SCROLLER] and
 * [CatalogBuilderRoles.HORIZONTAL_SCROLLER]. The editor reads them beside the ids it already knew.
 */
internal data class CatalogScrollers(val vertical: Set<String>, val horizontal: Set<String>) {
  companion object {
    val NONE: CatalogScrollers = CatalogScrollers(emptySet(), emptySet())
  }
}

internal val CapabilityCatalog.scrollers: CatalogScrollers
  get() =
    CatalogScrollers(
      vertical =
        componentsWithRole(CatalogBuilderRoles.VERTICAL_SCROLLER).mapTo(mutableSetOf()) {
          it.componentId
        },
      horizontal =
        componentsWithRole(CatalogBuilderRoles.HORIZONTAL_SCROLLER).mapTo(mutableSetOf()) {
          it.componentId
        },
    )

/** The current catalog's [CatalogScrollers], provided beside the catalog's component ids. */
internal val LocalUiBuilderCatalogScrollers =
  staticCompositionLocalOf<CatalogScrollers> { CatalogScrollers.NONE }

/**
 * The component a slot accepting text is filled with, by [CatalogBuilderRoles.TEXT]: the one in
 * [ownerId]'s namespace first, then any. Null when no text component in this catalog carries the
 * role, which is every catalog today; the caller then falls back to the id it knew.
 */
internal fun CapabilityCatalog.textComponentFor(
  slot: SlotCapability,
  ownerId: String,
): ComponentCapability? {
  val candidates = componentsWithRole(CatalogBuilderRoles.TEXT).filter(slot::accepts)
  val namespace = ownerId.substringBefore('/') + "/"
  return candidates.firstOrNull { it.componentId.startsWith(namespace) } ?: candidates.firstOrNull()
}
