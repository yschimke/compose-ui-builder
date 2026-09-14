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
 * ## Why this exists
 *
 * A catalog that replaces another does not always spell the same components the same way. The case
 * that forced it: a published catalog took over from a synthesised one that had BORROWED ids from a
 * neighbouring design system, so designs already authored against the borrowed spelling named
 * components the served catalog had never heard of. An undeclared property is survivable
 * ([UndeclaredCatalogProperties]); an unknown component is not, and should not be — there is
 * nothing to draw.
 *
 * ## The catalog says what it replaces, and this file says nothing
 *
 * The mapping is `statusSemantics.supersedes` on the TARGET catalog, keyed by the id a stored
 * document may still carry:
 * ```jsonc
 * "supersedes": {
 *   "<old component id>": {
 *     "componentId": "<what it becomes>",
 *     "properties":  { "<old name>": "<new name>" },   // a property the target spells differently
 *     "slots":       { "<old slot>": "<new slot>" },   // a slot the target spells differently
 *     "modifiers":   { "<old property>": "background" } // a property the target states as a modifier
 *   }
 * }
 * ```
 *
 * Not a table in this repository, and deliberately not:
 * `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` moves a catalog's knowledge into the catalog that
 * owns it, and `ui-builder-catalog-literals.sh` enforces that no module here learns a catalog's
 * name. A successor is exactly that knowledge — only the catalog that published the new component
 * can say which old one it stands in for, and it can say so in the same release that introduces it.
 *
 * ## Declared mappings, and everything else
 *
 * A rule states only what someone had to decide: the successor, the properties whose NAME changed,
 * and the properties the target states as modifiers instead. It never lists what is dropped. A
 * property the target simply does not declare falls out at the end with a `WARNING` naming it, and
 * that is deliberate — a list of drops would go stale the moment the target catalog changed, and a
 * catalog that later declares the property should start carrying it across with no edit anywhere.
 *
 * ## Nothing is thrown away
 *
 * The candidate is a proposal. The stored document is untouched until an apply lands, and a dropped
 * value stays in it either way, so a catalog that grows the property back restores the design
 * rather than a migration having to put it back.
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
private data class ComponentSuccessor(
  val componentId: String,
  val properties: Map<String, String>,
  val slots: Map<String, String>,
  val modifiers: Map<String, String>,
  val variants: VariantSuccessor?,
)

/** A legacy selector whose value chooses one of several concrete successor components. */
private data class VariantSuccessor(
  val property: String,
  val components: Map<String, String>,
)

/**
 * The modifiers an upgrade may write, by the name a catalog states.
 *
 * The builder's own vocabulary rather than any catalog's, which is why it is legitimately here —
 * but closed, because a modifier this build cannot construct must not be silently skipped: the
 * value it was carrying is the thing the move exists to preserve.
 */
private val MODIFIER_WRITERS: Map<String, (UiValueV1) -> DesignModifierV1> =
  mapOf("background" to { color -> BackgroundModifierV1(color) })

private const val SUPERSEDES_KEY = "supersedes"

/**
 * The successors [catalog] declares, ignoring anything malformed.
 *
 * A published file is data this runtime did not write, so a broken entry must not take a design's
 * only repair path down with it: an entry that does not name a `componentId` is not a rule, and the
 * node it would have moved is reported as unknown exactly as if nothing had been declared.
 */
private fun successors(catalog: CatalogCapabilityV1): Map<String, ComponentSuccessor> {
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
        )
    }
    .toMap()
}

/**
 * The move from [document]'s current pin to [targetPin], judged against [target].
 *
 * Pure: it reads the document and the catalog and returns what would happen. Nothing here writes,
 * validates or persists — the caller validates [CatalogUpgradeOutcome.candidate] against the target
 * catalog, because that is the same question every other write path asks and it should be answered
 * by the same validator rather than re-implemented here.
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
        // Nothing to move onto: the target declares no successor for this id and does not declare
        // the id itself. `ERROR` rather than a dropped node, because deleting somebody's content to
        // make a document validate is never the repair.
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
          // The drop that is not declared anywhere: whatever the target does not have a place for.
          // The value stays in the stored document -- only the candidate is without it -- so this
          // is a warning about what an export would stop writing, not a deletion.
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
      // `componentId` included, and it is the whole point of the move: without it the plan
      // reported a rename it never made, the candidate still named the component the target does
      // not declare, and every preview came back BLOCKED for the rename it had just described.
      node.copy(
        componentId = componentId,
        properties = properties,
        modifiers = modifiers,
        slots = slots,
      )
    }
  changes +=
    ReplaceCatalogUpgradeChangeV1("/catalogPin", document.catalogPin.encoded(), targetPin.encoded())
  return CatalogUpgradeOutcome(
    candidate = document.copy(catalogPin = targetPin, nodes = nodes),
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
 * The wire shape of a value, for a change a person reads rather than a document anything replays.
 *
 * `encodeDefaults` so a change record spells a value the same way the stored design does.
 */
private val json = Json { encodeDefaults = true }

private fun UiValueV1.encoded(): JsonElement =
  json.encodeToJsonElement(UiValueV1.serializer(), this)

private fun DesignModifierV1.encoded(): JsonElement =
  json.encodeToJsonElement(DesignModifierV1.serializer(), this)

private fun List<String>.encoded(): JsonElement = JsonArray(map { JsonPrimitive(it) })

/**
 * The whole reference, not its system id: source and target usually share the id and differ only in
 * the revision, so a change spelling ids alone would read as a change to nothing.
 */
private fun CatalogReferenceV1.encoded(): JsonElement =
  json.encodeToJsonElement(CatalogReferenceV1.serializer(), this)
