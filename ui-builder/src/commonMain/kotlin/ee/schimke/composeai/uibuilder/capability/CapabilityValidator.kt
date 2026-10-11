package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.export.UiBuilderArgumentBindings
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.inspectUiBuilderArgumentBindings
import ee.schimke.composeai.uibuilder.export.optionalString
import ee.schimke.composeai.uibuilder.export.propertyMatches
import ee.schimke.composeai.uibuilder.export.stateBindingMatchesCatalog
import ee.schimke.composeai.uibuilder.export.stateSelectionIssue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
    val bindings = inspectUiBuilderArgumentBindings(document)
    bindings.issues.forEach { failure ->
      document.nodes[failure.nodeId]?.let { node ->
        issues +=
          issue(CapabilityIssueCode.INVALID_PROPERTY_VALUE, node, failure.message, failure.field)
      }
    }
    document.nodes.values.sortedBy(UiBuilderNode::id).forEach { node ->
      validateNode(document, node, issues, bindings)
      unsupportedWrapperIssues(node, issues)
      stateSelectionIssue(node, document.stateVariables)?.let {
        issues += issue(CapabilityIssueCode.INVALID_PROPERTY_VALUE, node, it, SHOW_BY_STATE)
      }
    }
    return CapabilityValidationResult(issues, wasmStatuses(document))
  }

  /**
   * A property whose wrapper type is not a known wrapper type.
   *
   * **Document-wide, unlike the write-time rules below.** The vocabulary is closed and every writer
   * refuses unknown wrappers, so no legitimately committed design can hold one; this catches
   * documents that bypassed the writers (e.g. a hand-authored fixture through
   * `UiBuilderReducer.replay`), which otherwise fail only at the server's deserializer with a
   * wire-format message (see #901).
   */
  private fun unsupportedWrapperIssues(
    node: UiBuilderNode,
    issues: MutableList<CapabilityValidationIssue>,
  ) {
    val declared = catalog.componentsById[node.componentId]?.propertiesByName ?: return
    node.properties.forEach { (property, encoded) ->
      if (property !in declared) return@forEach
      val wrapper =
        ((encoded as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
          ?: return@forEach
      if (wrapper in PropertyValueKinds.WRAPPER_TYPES) return@forEach
      issues +=
        issue(
          CapabilityIssueCode.INVALID_PROPERTY_TYPE,
          node,
          "property $property uses unsupported wrapper type `$wrapper`",
          property,
        )
    }
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
   * A property with `allowedValues` (e.g. `m3/text.style`) must be written as `enum`, not `string`;
   * both render, but the Compose export only resolves the enum form (see #339). `jsonType` is the
   * carried scalar, not the wrapper's name.
   *
   * Asked **only on a write**: older designs holding the other spelling must stay editable, and the
   * export reads the legacy spelling through the enum table.
   */
  fun writeWrapperIssue(
    node: UiBuilderNode,
    property: String,
    encodedValue: JsonObject,
    /**
     * The keys the design's own `assets` map pins, which resolve as the catalog's registry does.
     */
    pinnedAssetKeys: Set<String> = emptySet(),
  ): CapabilityValidationIssue? {
    val declared =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(property) ?: return null
    val wrapper = (encodedValue["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (declared.allowedValues.isNotEmpty()) {
      if (wrapper != "string") return null
      return issue(
        CapabilityIssueCode.INVALID_PROPERTY_TYPE,
        node,
        "property $property is one of this component's allowed values, so it takes the `enum` " +
          "wrapper rather than `string`",
        property,
      )
    }
    return writeValueIssue(node, property, wrapper, encodedValue, pinnedAssetKeys)
  }

  /**
   * The value-kind half of the write rule: does this value have a type the canvas can draw? The
   * rule [PropertyValueKinds] states and the server's catalog executor mirrors (e.g. a colour
   * property needs a colour wrapper; an unknown token or unresolved `asset/image` key would crash
   * the renderer, see #484).
   *
   * Write-only, like the wrapper rule: older designs stay editable and the canvas draws a
   * placeholder.
   */
  private fun writeValueIssue(
    node: UiBuilderNode,
    property: String,
    wrapper: String?,
    encodedValue: JsonObject,
    pinnedAssetKeys: Set<String>,
  ): CapabilityValidationIssue? {
    val value = (encodedValue["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    when {
      PropertyValueKinds.isColour(property) -> {
        if (wrapper == "string") {
          return issue(
            CapabilityIssueCode.INVALID_PROPERTY_TYPE,
            node,
            "property $property is a colour, which is written as a `#RRGGBB` literal or as a " +
              "theme role — a `color` or `colorToken` wrapper, not `string`",
            property,
          )
        }
        if (wrapper != "color" && wrapper != "colorToken") return null
        if (value == null || PropertyValueKinds.isDrawableColour(value)) return null
        return issue(
          CapabilityIssueCode.INVALID_PROPERTY_VALUE,
          node,
          "property $property is `$value`, which is neither a `#RRGGBB` literal nor one of the " +
            "theme roles the canvas draws (${PropertyValueKinds.CANVAS_COLOR_TOKENS.joinToString(", ")})",
          property,
        )
      }
      PropertyValueKinds.isAssetKey(property) -> {
        if (wrapper != "assetKey" && wrapper != "string") return null
        val registry = PropertyValueKinds.declaredAssetKeys(catalog.statusSemantics) ?: return null
        if (value == null || value in registry || value in pinnedAssetKeys) return null
        return issue(
          CapabilityIssueCode.INVALID_PROPERTY_VALUE,
          node,
          "property $property is `$value`, which no asset this design or its catalog can draw " +
            "resolves; the catalog declares ${registry.joinToString(", ")}, and a picture of your " +
            "own is pinned under a key by the design's asset lane before the node names it",
          property,
        )
      }
    }
    return null
  }

  /**
   * [writeWrapperIssue] for every property of a node that arrives whole, for an insert; the
   * document-wide pass after an insert asks none of the write-time questions.
   */
  /**
   * Whether [property] may be unset on [node]: refused for a required one, in the document-wide
   * pass's words, so the author hears it at the operation. Undeclared properties may always be
   * removed.
   */
  fun removeIssue(node: UiBuilderNode, property: String): CapabilityValidationIssue? {
    val declared =
      catalog.componentsById[node.componentId]?.propertiesByName?.get(property) ?: return null
    if (!declared.required) return null
    return issue(
      CapabilityIssueCode.MISSING_REQUIRED_PROPERTY,
      node,
      "required property $property cannot be unset; give it a value or delete the node",
      property,
    )
  }

  fun insertIssue(
    node: UiBuilderNode,
    pinnedAssetKeys: Set<String> = emptySet(),
  ): CapabilityValidationIssue? =
    node.properties.entries.firstNotNullOfOrNull { (property, encoded) ->
      (encoded as? JsonObject)?.let { writeWrapperIssue(node, property, it, pinnedAssetKeys) }
    }

  private fun validateNode(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    issues: MutableList<CapabilityValidationIssue>,
    bindings: UiBuilderArgumentBindings,
  ) {
    // A placement (`design/component-instance`) has no catalog capability of its own; it takes its
    // body's, which decides its modifiers and (via [placedCapability] in `validateSlotChild`) which
    // slots accept it. Unknown components, unwritable bindings and missing arguments are
    // `CapabilityComposeCodeExporter`'s to refuse.
    val capability =
      if (node.componentId == DESIGN_COMPONENT_INSTANCE_COMPONENT_ID) {
        val placed = document.placedCapability(node, catalog) ?: return
        validateModifiers(node, placed, issues)
        return
      } else {
        catalog.componentsById[node.componentId]
      }
    if (capability == null) {
      issues +=
        issue(
          CapabilityIssueCode.UNKNOWN_COMPONENT,
          node,
          "component ${node.componentId} is not present in catalog ${catalog.benchmark.catalogSystemId}",
        )
      return
    }

    validateProperties(document, node, capability, issues, bindings)
    validateModifiers(node, capability, issues)
    validateSlots(document, node, capability, issues)
  }

  private fun validateProperties(
    document: UiBuilderDocument,
    node: UiBuilderNode,
    capability: ComponentCapability,
    issues: MutableList<CapabilityValidationIssue>,
    bindings: UiBuilderArgumentBindings,
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
        val argumentMatches =
          bindings.propertyMatches(node.id, name, encodedValue) { supplied ->
            stateBindingMatchesCatalog(
              supplied,
              property.jsonType,
              property.allowedValues,
              document.stateVariables,
              name,
            )
              ?: (property.acceptsType(supplied.unwrapPropertyValue()) &&
                (property.allowedValues.isEmpty() ||
                  supplied.unwrapPropertyValue() in property.allowedValues))
          }
        val bindingMatches =
          argumentMatches
            ?: stateBindingMatchesCatalog(
              encodedValue,
              property.jsonType,
              property.allowedValues,
              document.stateVariables,
              name,
              UiDrawing.indexKinds(document, node.id),
            )
        if (bindingMatches == false || (bindingMatches == null && !property.acceptsType(value))) {
          issues +=
            issue(
              CapabilityIssueCode.INVALID_PROPERTY_TYPE,
              node,
              "property $name does not match ${property.typeNames().joinToString(" or ")}",
              name,
            )
        } else if (
          bindingMatches == null &&
            property.allowedValues.isNotEmpty() &&
            value !in property.allowedValues
        ) {
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
        // An empty slot entry means the same as no key, so it is not a finding (inserts write an
        // entry for every slot declared at the time, and those keys outlive withdrawn slots). A
        // child in an undeclared slot is still refused below, since it would be silently dropped.
        if (children.isNotEmpty()) {
          issues +=
            issue(
              CapabilityIssueCode.UNKNOWN_SLOT,
              node,
              "slot $name is not declared by ${node.componentId}",
              name,
            )
        }
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
    val childCapability =
      (if (child.componentId == DESIGN_COMPONENT_INSTANCE_COMPONENT_ID)
        document.placedCapability(child, catalog)
      else catalog.componentsById[child.componentId]) ?: return
    if (!slot.accepts(childCapability)) {
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

/**
 * The value a property wrapper carries, or the wrapper itself when the wrapper *is* the value.
 *
 * Bindings are returned whole: `stateEquals` has a `value` key (its operand), and unwrapping it
 * would validate the operand as the property value. A whole binding matches the `object` a bindable
 * property declares.
 */
private fun JsonElement.unwrapPropertyValue(): JsonElement =
  when {
    this !is JsonObject -> this
    (this["type"] as? JsonPrimitive)?.contentOrNull in STATE_BINDING_WRAPPERS -> this
    else -> this["value"] ?: this
  }

/**
 * The wrapper spellings that mean "read this from a declared state variable" rather than a value.
 */
private val STATE_BINDING_WRAPPERS = setOf("state", "stateEquals", "expr", "system")

/** The wire's own id for a node that places a component defined by the design. */
private const val DESIGN_COMPONENT_INSTANCE_COMPONENT_ID = "design/component-instance"

/**
 * What a placement draws, as a capability: the component's body root, so slot and modifier rules
 * apply as to the body. Null when the design defines no such component; the export reports that by
 * name.
 */
private fun UiBuilderDocument.placedCapability(
  node: UiBuilderNode,
  catalog: CapabilityCatalog,
): ComponentCapability? {
  val key = node.component?.optionalString("componentKey") ?: return null
  val root = (components[key] as? JsonObject)?.optionalString("root") ?: return null
  val body = nodes[root] ?: return null
  return catalog.componentsById[body.componentId]
}

/**
 * The line count a `minLines` / `maxLines` property states outright, or null when it does not state
 * one (e.g. it is bound to state). Must not throw: the validator runs on every write and on
 * `canBindToState` probes. A bound count has nothing to compare; [acceptsType] decides whether the
 * binding is allowed.
 */
private fun JsonElement?.lineCountOrNull(): Long? =
  (this?.unwrapPropertyValue() as? JsonPrimitive)?.longOrNull

private fun PropertyCapability.typeNames(): Set<String> =
  when (jsonType) {
    is JsonArray -> jsonType.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    else -> setOf(jsonType.jsonPrimitive.content)
  }

/**
 * Whether [encoded] is a literal value [this] property accepts, by the rule [CapabilityValidator]
 * applies to a document: its type, and its allowed values when the property has any. A state
 * binding is not a literal, so it is not a value an insert can seed.
 */
internal fun PropertyCapability.acceptsSeedValue(encoded: JsonObject): Boolean {
  if ((encoded["type"] as? JsonPrimitive)?.contentOrNull in STATE_BINDING_WRAPPERS) return false
  val value = encoded.unwrapPropertyValue()
  return acceptsType(value) && (allowedValues.isEmpty() || value in allowedValues)
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
