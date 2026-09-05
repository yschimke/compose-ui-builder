package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class CapabilityIssueCode {
  UNKNOWN_COMPONENT,
  UNKNOWN_PROPERTY,
  MISSING_REQUIRED_PROPERTY,
  INVALID_PROPERTY_TYPE,
  INVALID_PROPERTY_VALUE,
  MALFORMED_MODIFIER,
  UNKNOWN_MODIFIER,
  UNKNOWN_SLOT,
  SLOT_CARDINALITY,
  UNKNOWN_CHILD,
  INCOMPATIBLE_SLOT_CHILD,
}

data class CapabilityValidationIssue(
  val code: CapabilityIssueCode,
  val message: String,
  val nodeId: String,
  val componentId: String,
  val field: String? = null,
)

data class WasmNodeStatus(
  val nodeId: String,
  val componentId: String,
  val platformSupported: JsonElement,
  val adapterStatus: WasmAdapterStatus,
  val notes: String? = null,
)

data class CapabilityCoverage(
  val referencedComponentIds: Set<String>,
  val missingComponentIds: Set<String>,
  val wasmStatuses: List<WasmNodeStatus>,
) {
  val hasCompleteComponentCoverage: Boolean
    get() = missingComponentIds.isEmpty()

  val plannedOrUnsupported: List<WasmNodeStatus>
    get() = wasmStatuses.filter { it.adapterStatus != WasmAdapterStatus.SUPPORTED }
}

data class CapabilityValidationResult(
  val issues: List<CapabilityValidationIssue>,
  val wasmStatuses: List<WasmNodeStatus>,
) {
  val structurallyValid: Boolean
    get() = issues.isEmpty()

  val wasmRenderable: Boolean
    get() =
      structurallyValid &&
        wasmStatuses.all {
          it.adapterStatus == WasmAdapterStatus.SUPPORTED &&
            (it.platformSupported as? JsonPrimitive)?.booleanOrNull == true
        }

  val plannedOrUnsupported: List<WasmNodeStatus>
    get() = wasmStatuses.filter { it.adapterStatus != WasmAdapterStatus.SUPPORTED }
}

/** Production entry point for validating an immutable document against a serialized catalog. */
fun validateCapabilities(
  document: UiBuilderDocument,
  catalogSource: String,
): CapabilityValidationResult =
  CapabilityValidator(CapabilityCatalogParser.parse(catalogSource)).validate(document)

class CapabilityValidator(private val catalog: CapabilityCatalog) {
  fun coverage(document: UiBuilderDocument): CapabilityCoverage {
    val referenced = document.nodes.values.map(UiBuilderNode::componentId).toSet()
    val missing = referenced.filterTo(linkedSetOf()) { it !in catalog.componentsById }
    return CapabilityCoverage(referenced, missing, wasmStatuses(document))
  }

  fun validate(document: UiBuilderDocument): CapabilityValidationResult {
    val issues = mutableListOf<CapabilityValidationIssue>()
    document.nodes.values.sortedBy(UiBuilderNode::id).forEach { node ->
      validateNode(document, node, issues)
    }
    return CapabilityValidationResult(issues, wasmStatuses(document))
  }

  private fun wasmStatuses(document: UiBuilderDocument): List<WasmNodeStatus> =
    document.nodes.values.sortedBy(UiBuilderNode::id).mapNotNull { node ->
      catalog.componentsById[node.componentId]?.wasm?.let { wasm ->
        WasmNodeStatus(
          nodeId = node.id,
          componentId = node.componentId,
          platformSupported = wasm.platformSupported,
          adapterStatus = wasm.adapterStatus,
          notes = wasm.notes,
        )
      }
    }

  /**
   * Whether one property **write** spells its wrapper the way the catalog's declaration asks for.
   *
   * A question [validate] deliberately does not ask. The catalog declares `m3/text.style` as
   * `jsonType: "string"` with an `allowedValues` list, and both
   * `{"type":"enum","value":"bodyLarge"}` and `{"type":"string","value":"bodyLarge"}` carry the
   * same string inside — so both were accepted, both committed, and both rendered, because the
   * renderer reads either through `string(name)`. They then diverged completely downstream: the
   * Compose export resolves the first through the catalog's enum table and refused the second with
   * `` `Text`.`style` is androidx.compose.ui.text.TextStyle, which Text is not `` — two unrelated
   * refusals for one authored intent, neither of them mentioning the wrapper (issue #339).
   *
   * `enum` is the canonical spelling, because that is what a property with `allowedValues` means:
   * the value is one of an enumeration the catalog names, not free text that happens to match.
   * `jsonType` describes the JSON scalar the wrapper carries — a string — and is not the wrapper's
   * own name; every checked-in fixture and the inspector's own editor control already agree.
   *
   * Asked **only on a write**, and that is the load-bearing part. Designs committed before this
   * rule existed hold the other spelling, they render correctly, and they must keep being editable:
   * a document-wide rule would reject an unrelated node move on a design whose `style` was written
   * a year ago. So the rule bites where a spelling is chosen and nowhere else, and the export reads
   * the legacy spelling through the enum table instead of refusing it.
   */
  fun writeWrapperIssue(
    node: UiBuilderNode,
    property: String,
    encodedValue: JsonObject,
  ): CapabilityValidationIssue? {
    val declared = catalog.componentsById[node.componentId]?.propertiesByName?.get(property)
    if (declared == null || declared.allowedValues.isEmpty()) return null
    val wrapper = (encodedValue["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (wrapper != "string") return null
    return issue(
      CapabilityIssueCode.INVALID_PROPERTY_TYPE,
      node,
      "property $property is one of this component's allowed values, so it takes the `enum` " +
        "wrapper rather than `string`",
      property,
    )
  }

  private fun validateNode(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    val capability = catalog.componentsById[node.componentId]
    if (capability == null) {
      issues +=
        issue(
          CapabilityIssueCode.UNKNOWN_COMPONENT,
          node,
          "component ${node.componentId} is not present in catalog ${catalog.benchmark.catalogSystemId}",
        )
      return
    }

    validateProperties(node, capability, issues)
    validateModifiers(node, capability, issues)
    validateSlots(document, node, capability, issues)
  }

  private fun validateProperties(
    node: UiBuilderNode,
    capability: ComponentCapability,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    node.properties.forEach { (name, encodedValue) ->
      val property = capability.propertiesByName[name]
      if (property == null) {
        issues +=
          issue(
            CapabilityIssueCode.UNKNOWN_PROPERTY,
            node,
            "property $name is not declared by ${node.componentId}",
            name,
          )
      } else {
        val value = encodedValue.unwrapPropertyValue()
        if (!property.acceptsType(value)) {
          issues +=
            issue(
              CapabilityIssueCode.INVALID_PROPERTY_TYPE,
              node,
              "property $name does not match ${property.typeNames().joinToString(" or ")}",
              name,
            )
        } else if (property.allowedValues.isNotEmpty() && value !in property.allowedValues) {
          issues +=
            issue(
              CapabilityIssueCode.INVALID_PROPERTY_VALUE,
              node,
              "property $name has a value outside its allowed values",
              name,
            )
        }
      }
    }

    capability.properties.filter(PropertyCapability::required).forEach { property ->
      if (property.name !in node.properties) {
        issues +=
          issue(
            CapabilityIssueCode.MISSING_REQUIRED_PROPERTY,
            node,
            "required property ${property.name} is missing",
            property.name,
          )
      }
    }

    val minLines = node.properties["minLines"].lineCountOrNull()
    val maxLines = node.properties["maxLines"].lineCountOrNull()
    if (minLines != null && maxLines != null && minLines > maxLines) {
      listOf("minLines", "maxLines").forEach { field ->
        issues +=
          issue(
            CapabilityIssueCode.INVALID_PROPERTY_VALUE,
            node,
            "minLines must not exceed maxLines",
            field,
          )
      }
    }
  }

  private fun validateModifiers(
    node: UiBuilderNode,
    capability: ComponentCapability,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    node.modifiers.forEachIndexed { index, modifier ->
      val modifierObject = modifier as? JsonObject
      val type = modifierObject?.get("type") as? JsonPrimitive
      val modifierName = type?.takeIf(JsonPrimitive::isString)?.content
      when {
        modifierName == null ->
          issues +=
            issue(
              CapabilityIssueCode.MALFORMED_MODIFIER,
              node,
              "modifier at index $index has no string type",
              "modifiers[$index]",
            )
        modifierName !in capability.modifierCapabilities ->
          issues +=
            issue(
              CapabilityIssueCode.UNKNOWN_MODIFIER,
              node,
              "modifier $modifierName is not declared by ${node.componentId}",
              modifierName,
            )
      }
    }
  }

  private fun validateSlots(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    capability: ComponentCapability,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    node.slots.forEach { (name, children) ->
      val slot = capability.slot(name)
      if (slot == null) {
        issues +=
          issue(
            CapabilityIssueCode.UNKNOWN_SLOT,
            node,
            "slot $name is not declared by ${node.componentId}",
            name,
          )
      } else {
        validateCardinality(node, slot, children.size, issues)
        children.forEach { childId -> validateSlotChild(document, node, slot, childId, issues) }
      }
    }

    capability.slots
      .filter { it.name !in node.slots }
      .forEach { slot -> validateCardinality(node, slot, 0, issues) }
  }

  private fun ComponentCapability.slot(name: String): SlotCapability? =
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

  private fun validateCardinality(
    node: UiBuilderNode,
    slot: SlotCapability,
    count: Int,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    if (count < slot.cardinality.min || slot.cardinality.max?.let { count > it } == true) {
      val maximum = slot.cardinality.max?.toString() ?: "unbounded"
      issues +=
        issue(
          CapabilityIssueCode.SLOT_CARDINALITY,
          node,
          "slot ${slot.name} has $count children; expected ${slot.cardinality.min}..$maximum",
          slot.name,
        )
    }
  }

  private fun validateSlotChild(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    slot: SlotCapability,
    childId: String,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    val child = document.nodes[childId]
    if (child == null) {
      issues +=
        issue(
          CapabilityIssueCode.UNKNOWN_CHILD,
          node,
          "slot ${slot.name} references unknown child $childId",
          slot.name,
        )
      return
    }
    val childCapability = catalog.componentsById[child.componentId] ?: return
    val acceptsAnyContent = "AnyContent" in slot.acceptedTraits
    val acceptsRole = childCapability.role in slot.acceptedRoles
    val acceptsTrait = childCapability.traits.any(slot.acceptedTraits::contains)
    val constrainsChildren = slot.acceptedRoles.isNotEmpty() || slot.acceptedTraits.isNotEmpty()
    if (constrainsChildren && !acceptsAnyContent && !acceptsRole && !acceptsTrait) {
      issues +=
        issue(
          CapabilityIssueCode.INCOMPATIBLE_SLOT_CHILD,
          node,
          "slot ${slot.name} does not accept ${child.componentId}",
          slot.name,
        )
    }
  }

  private fun issue(
    code: CapabilityIssueCode,
    node: UiBuilderNode,
    message: String,
    field: String? = null,
  ) = CapabilityValidationIssue(code, message, node.id, node.componentId, field)
}

private fun JsonElement.unwrapPropertyValue(): JsonElement =
  (this as? JsonObject)?.get("value") ?: this

/**
 * The line count a `minLines` / `maxLines` property states outright, or null when it does not state
 * one.
 *
 * Null covers the case that matters: a property **bound to state** is an object with no `value` key
 * (`{"type":"state","variable":…}`), so [unwrapPropertyValue] hands the object straight back. Read
 * with `.jsonPrimitive` that threw, and it threw out of the validator — which is called on every
 * write, and on every keystroke in the inspector through `canBindToState`, whose whole job is to
 * probe a property with exactly such a binding. Any text node was enough to take the editor down.
 *
 * Skipping the min-vs-max comparison is the right answer rather than a way around the crash: a
 * bound line count has no value at author time to compare. Whether the binding is *allowed* on the
 * property at all is a separate question, and one [acceptsType] already answers.
 */
private fun JsonElement?.lineCountOrNull(): Long? =
  (this?.unwrapPropertyValue() as? JsonPrimitive)?.longOrNull

private fun PropertyCapability.typeNames(): Set<String> =
  when (jsonType) {
    is JsonArray -> jsonType.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    else -> setOf(jsonType.jsonPrimitive.content)
  }

private fun PropertyCapability.acceptsType(value: JsonElement): Boolean =
  typeNames().any { type ->
    when (type) {
      "null" -> value is JsonNull
      "object" -> value is JsonObject
      "array" -> value is JsonArray
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
      "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
      "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
      else -> false
    }
  }
