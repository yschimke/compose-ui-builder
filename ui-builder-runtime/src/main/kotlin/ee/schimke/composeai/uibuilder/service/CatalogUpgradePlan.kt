package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AddCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeIssueSeverityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeIssueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignModifierV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.RemoveCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.ReplaceCatalogUpgradeChangeV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What moving a design from one catalog's vocabulary to another's would cost it.
 *
 * A replacing catalog may spell components differently; an unknown component cannot be drawn. The
 * mapping is `statusSemantics.supersedes` on the target catalog, keyed by the old id:
 * ```jsonc
 * "supersedes": {
 *   "<old component id>": {
 *     "componentId": "<what it becomes>",
 *     "properties":  { "<old name>": "<new name>" },   // a property the target spells differently
 *     "slots":       { "<old slot>": "<new slot>" },   // a slot the target spells differently
 *     "modifiers":   { "<old property>": "background" }, // a property the target states as a modifier
 *     "setProperties": { "<name>": "<value>" }        // a value the old id implied, e.g. a style
 *   }
 * }
 * ```
 *
 * The catalog owns this knowledge (`docs/design/UI_BUILDER_CATALOG_CONTRACT.md`); no module here
 * names a catalog. Rules never list drops: undeclared properties fall out with a `WARNING`. The
 * candidate is a proposal, and the stored document keeps dropped values.
 */
internal data class CatalogUpgradeOutcome(
  /** The document as it would be under the target catalog. Never stored by planning it. */
  val candidate: DesignDocumentV1,
  /** Every difference between the stored document and [candidate], as JSON Pointer paths. */
  val changes: List<CatalogUpgradeChangeV1>,
  /** What a person needs to know before accepting it. `ERROR` means the move cannot be made. */
  val issues: List<CatalogUpgradeIssueV1>,
)

/** One `supersedes` entry, as the target catalog declared it. */
internal data class ComponentSuccessor(
  val componentId: String,
  val properties: Map<String, String>,
  val slots: Map<String, String>,
  val modifiers: Map<String, String>,
  val variants: VariantSuccessor?,
  /**
   * Values the old id implied and the successor states as a property: the reverse of [variants].
   * `m3/outlined-card` becoming `m3/card` with `style = outlined` is one, since the id is the only
   * place the old design said it was outlined.
   */
  val setProperties: Map<String, String> = emptyMap(),
)

/** A legacy selector whose value chooses one of several concrete successor components. */
internal data class VariantSuccessor(
  val property: String,
  val components: Map<String, String>,
)

/**
 * The modifiers an upgrade may write, by the name a catalog states. Closed: an unconstructible
 * modifier must not be silently skipped, since its value is what the move preserves.
 */
private val MODIFIER_WRITERS: Map<String, (UiValueV1) -> DesignModifierV1> =
  mapOf("background" to { color -> BackgroundModifierV1(color) })

/** Whether an upgrade can write the [modifier] a catalog states a property becomes. */
internal fun canWriteUpgradeModifier(modifier: String): Boolean = modifier in MODIFIER_WRITERS

private const val SUPERSEDES_KEY = "supersedes"

/**
 * The successors [catalog] declares, ignoring malformed entries (a published file must not break a
 * design's only repair path).
 */
internal fun successors(catalog: CatalogCapabilityV1): Map<String, ComponentSuccessor> {
  val declared = catalog.statusSemantics[SUPERSEDES_KEY] as? JsonObject ?: return emptyMap()
  return declared.entries
    .mapNotNull { (from, entry) ->
      val rule = entry as? JsonObject ?: return@mapNotNull null
      val componentId = rule["componentId"]?.stringOrNull() ?: return@mapNotNull null
      from to
        ComponentSuccessor(
          componentId = componentId,
          properties = rule.stringMap("properties"),
          slots = rule.stringMap("slots"),
          modifiers = rule.stringMap("modifiers"),
          variants =
            (rule["variants"] as? JsonObject)?.let { variants ->
              val property = variants["property"]?.stringOrNull() ?: return@let null
              VariantSuccessor(property, variants.stringMap("components"))
            },
          setProperties = rule.stringMap("setProperties"),
        )
    }
    .toMap()
}

/**
 * The move from [document]'s current pin to [targetPin], judged against [target]. Pure: the caller
 * validates [CatalogUpgradeOutcome.candidate] with the same validator as every write.
 */
internal fun planCatalogUpgrade(
  document: DesignDocumentV1,
  target: CatalogCapabilityV1,
  targetPin: CatalogReferenceV1,
): CatalogUpgradeOutcome {
  val rules = successors(target)
  val declared = target.components.associateBy { it.componentId }
  val changes = mutableListOf<CatalogUpgradeChangeV1>()
  val issues = mutableListOf<CatalogUpgradeIssueV1>()
  val nodes =
    document.nodes.mapValues { (nodeId, node) ->
      val rule = rules[node.componentId]
      val componentId =
        rule?.variants?.let { variant ->
          node.properties[variant.property]?.literalStringOrNull()?.let(variant.components::get)
        } ?: rule?.componentId ?: node.componentId
      val component = declared[componentId]
      if (component == null && componentId !in document.components) {
        // No successor and not declared by the target: `ERROR`, since deleting content is never the
        // repair.
        issues +=
          CatalogUpgradeIssueV1(
            CatalogUpgradeIssueSeverityV1.ERROR,
            "UNKNOWN_COMPONENT",
            nodePath(nodeId),
            "the target catalog does not declare ${node.componentId}, and states no successor " +
              "for it",
          )
        return@mapValues node
      }
      if (rule != null) {
        changes +=
          ReplaceCatalogUpgradeChangeV1(
            "${nodePath(nodeId)}/componentId",
            JsonPrimitive(node.componentId),
            JsonPrimitive(componentId),
          )
      }
      val names = component?.properties?.mapTo(mutableSetOf()) { it.name } ?: mutableSetOf()
      val properties = mutableMapOf<String, UiValueV1>()
      val modifiers = node.modifiers.toMutableList()
      node.properties.forEach { (name, value) ->
        if (name == rule?.variants?.property) {
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.INFO,
              "VARIANT_BECOMES_COMPONENT",
              propertyPath(nodeId, name),
              "$name selects $componentId and is no longer stored as a property",
            )
          return@forEach
        }
        val asModifier = rule?.modifiers?.get(name)
        if (asModifier != null) {
          val write = MODIFIER_WRITERS[asModifier]
          if (write == null) {
            // The catalog asked for a modifier this build cannot write. Blocking, because the
            // alternative is dropping the value the rule exists to carry across.
            issues +=
              CatalogUpgradeIssueV1(
                CatalogUpgradeIssueSeverityV1.ERROR,
                "UNSUPPORTED_MODIFIER",
                propertyPath(nodeId, name),
                "this build cannot write the `$asModifier` modifier the target catalog states " +
                  "$name becomes",
              )
            properties[name] = value
            return@forEach
          }
          val modifier = write(value)
          modifiers += modifier
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          changes += AddCatalogUpgradeChangeV1("${nodePath(nodeId)}/modifiers", modifier.encoded())
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.INFO,
              "PROPERTY_BECOMES_MODIFIER",
              propertyPath(nodeId, name),
              "$name is a `$asModifier` modifier on $componentId, and moves onto the node's chain",
            )
          return@forEach
        }
        val renamed = rule?.properties?.get(name) ?: name
        if (component != null && renamed !in names) {
          // Undeclared by the target: a warning that export would stop writing it; the stored value
          // stays.
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.WARNING,
              "PROPERTY_NOT_DECLARED",
              propertyPath(nodeId, name),
              "$componentId does not declare $renamed; the value stays in the stored design and " +
                "is left out of the upgraded one",
            )
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          return@forEach
        }
        properties[renamed] = value
        if (renamed != name) {
          changes += RemoveCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          changes += AddCatalogUpgradeChangeV1(propertyPath(nodeId, renamed), value.encoded())
        }
      }
      rule?.setProperties?.forEach { (name, literal) ->
        // The old id said it; the rule writes it, over whatever the node held under that name.
        val value = EnumValueV1(literal)
        val previous = properties.put(name, value)
        if (previous == value) return@forEach
        changes +=
          if (previous == null)
            AddCatalogUpgradeChangeV1(propertyPath(nodeId, name), value.encoded())
          else
            ReplaceCatalogUpgradeChangeV1(
              propertyPath(nodeId, name),
              previous.encoded(),
              value.encoded(),
            )
        issues +=
          CatalogUpgradeIssueV1(
            CatalogUpgradeIssueSeverityV1.INFO,
            "COMPONENT_BECOMES_VARIANT",
            propertyPath(nodeId, name),
            "${node.componentId} becomes $componentId with $name = $literal",
          )
      }
      val slots = mutableMapOf<String, List<String>>()
      node.slots.forEach { (slot, children) ->
        val to = rule?.slots?.get(slot) ?: slot
        // A slot rename landing on a slot the node already fills would merge two lists of children
        // into one. A catalog can declare that by mistake, so it is reported rather than merged.
        if (to in slots) {
          issues +=
            CatalogUpgradeIssueV1(
              CatalogUpgradeIssueSeverityV1.ERROR,
              "SLOT_COLLISION",
              slotPath(nodeId, slot),
              "the target catalog renames $slot onto $to, which this node already fills",
            )
          return@forEach
        }
        slots[to] = children
        if (to != slot) {
          changes += RemoveCatalogUpgradeChangeV1(slotPath(nodeId, slot), children.encoded())
          changes += AddCatalogUpgradeChangeV1(slotPath(nodeId, to), children.encoded())
        }
      }
      // Including `componentId`: the rename is the point of the move.
      node.copy(
        componentId = componentId,
        properties = properties,
        modifiers = modifiers,
        slots = slots,
      )
    }
  // A property the target requires and the design never set gets the generator's placeholder, with
  // a warning, so the move is not blocked.
  val (filledCandidate, filled) =
    document.copy(catalogPin = targetPin, nodes = nodes).withRequiredPlaceholders(target)
  filled.forEach { (nodeId, property, value) ->
    changes += AddCatalogUpgradeChangeV1(propertyPath(nodeId, property), value.encoded())
    issues +=
      CatalogUpgradeIssueV1(
        CatalogUpgradeIssueSeverityV1.WARNING,
        "REQUIRED_PROPERTY_FILLED",
        propertyPath(nodeId, property),
        "the target catalog requires $property, which this design never set; it is given " +
          "${value.encoded()} -- change it if that is not what was meant",
      )
  }
  changes +=
    ReplaceCatalogUpgradeChangeV1("/catalogPin", document.catalogPin.encoded(), targetPin.encoded())
  return CatalogUpgradeOutcome(
    candidate = filledCandidate,
    changes = changes,
    issues = issues,
  )
}

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun UiValueV1.literalStringOrNull(): String? =
  ((encoded() as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringMap(key: String): Map<String, String> =
  (this[key] as? JsonObject)
    ?.entries
    ?.mapNotNull { (from, to) -> to.stringOrNull()?.let { from to it } }
    ?.toMap()
    .orEmpty()

private fun nodePath(nodeId: String) = "/nodes/$nodeId"

private fun propertyPath(nodeId: String, property: String) =
  "${nodePath(nodeId)}/properties/$property"

private fun slotPath(nodeId: String, slot: String) = "${nodePath(nodeId)}/slots/$slot"

/**
 * The wire shape of a value for a human-readable change; `encodeDefaults` so it matches the stored
 * design's spelling.
 */
private val json = Json { encodeDefaults = true }

private fun UiValueV1.encoded(): JsonElement =
  json.encodeToJsonElement(UiValueV1.serializer(), this)

private fun DesignModifierV1.encoded(): JsonElement =
  json.encodeToJsonElement(DesignModifierV1.serializer(), this)

private fun List<String>.encoded(): JsonElement = JsonArray(map { JsonPrimitive(it) })

/** The whole reference: source and target usually differ only in revision. */
private fun CatalogReferenceV1.encoded(): JsonElement =
  json.encodeToJsonElement(CatalogReferenceV1.serializer(), this)
