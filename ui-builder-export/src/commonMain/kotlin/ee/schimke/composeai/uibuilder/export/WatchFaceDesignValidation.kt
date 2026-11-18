package ee.schimke.composeai.uibuilder.export

import ee.schimke.watchface.wff.WffCData
import ee.schimke.watchface.wff.WffComment
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffElementSpec
import ee.schimke.watchface.wff.WffParseException
import ee.schimke.watchface.wff.WffSchema
import ee.schimke.watchface.wff.WffText
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a `wff-watchface` design may hold, judged where the edit is made rather than where the XML
 * is written: the design service asks it for every write and every document, and the exporter asks
 * it again, so an edit the exporter would turn into invalid XML is refused with the same words
 * before it is committed.
 *
 * It knows the catalog's types ([WatchFaceCatalogShape]) and Watch Face Format's element table
 * (`ee.schimke.watchface:wff-core`): a raw node's XML must be one well-formed element or comment,
 * keep the element it replaces, and be allowed under its parent at the design's format version; a
 * number is a number (or an expression), a colour a `#RRGGBB` / `#AARRGGBB` literal (or an
 * expression), a size is not negative, a slot id is used once, and a complication type exists at
 * the format version.
 *
 * It judges what an edit can break. It does not require a node to be complete (a part image with no
 * `resource` yet is the editor's mid-edit state; the exporter refuses it), and it does not check
 * the face against the whole schema (the exporter does, at the version it writes).
 */
object WatchFaceDesignValidation {
  data class Issue(
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val field: String? = null,
  )

  /**
   * The code of a document-level finding about a value, as opposed to the structure: a host that
   * loads a stored design tolerates it, so a design saved before the rule still opens.
   */
  const val VALUE_ISSUE_CODE = "WFF_INVALID_VALUE"

  /** The complication types Watch Face Format names, with the version that introduced each. */
  private val COMPLICATION_TYPES: Map<String, Int> =
    mapOf(
      "SHORT_TEXT" to 1,
      "LONG_TEXT" to 1,
      "MONOCHROMATIC_IMAGE" to 1,
      "SMALL_IMAGE" to 1,
      "PHOTO_IMAGE" to 1,
      "RANGED_VALUE" to 1,
      "GOAL_PROGRESS" to 2,
      "WEIGHTED_ELEMENTS" to 2,
      "EMPTY" to 1,
    )

  private val COLOR_LITERAL = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
  private val PLAIN_NUMBER = Regex("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?")

  /** Layout and bookkeeping keys the importer keeps in a node's own properties or `wffExtras`. */
  private fun isLayoutKey(name: String): Boolean =
    name == WatchFaceXmlExporter.ATTR_ORDER ||
      name == WatchFaceXmlExporter.FACE_ATTR_ORDER ||
      name == WatchFaceXmlExporter.EXTRAS ||
      name == WatchFaceXmlExporter.CORE_AT ||
      name == WatchFaceXmlExporter.SHAPE_CHILD_ORDER ||
      name == WatchFaceXmlExporter.SLOT_CHILD_ORDER ||
      name == WatchFaceXmlExporter.TEMPLATE_LAYOUT ||
      name.endsWith("AttrOrder")

  // ---- A property's value ----------------------------------------------------------------------

  private val NON_NEGATIVE =
    setOf(
      "width",
      "height",
      "shapeWidth",
      "shapeHeight",
      "strokeWidth",
      "cornerRadiusX",
      "cornerRadiusY",
    )

  private fun isExpression(value: String) = '[' in value || '(' in value

  /**
   * What is wrong with [value] as the value of [component]'s [name], or null. [value] is the
   * literal as it would be written into the XML.
   */
  private fun valueIssue(component: String, name: String, value: String): String? {
    val declared = WatchFaceCatalogShape.DECLARED[component]?.get(name) ?: return null
    if (isExpression(value) && declared.type != "boolean") {
      // Expressions are WFF's own language; only a colour must still be one that can be written.
      return null
    }
    return when {
      declared.type == "integer" ->
        when {
          !PLAIN_NUMBER.matches(value) -> "$name is `$value`, which is not a number"
          value.toDoubleOrNull()?.let { it != kotlin.math.floor(it) } == true ->
            "$name is `$value`, which is not a whole number"
          name in NON_NEGATIVE && value.toDouble() < 0 -> "$name is `$value`; it cannot be negative"
          name == "slotId" && value.toDouble() < 0 -> "slotId is `$value`; it cannot be negative"
          else -> null
        }
      declared.type == "number" ->
        when {
          // Whole expressions of numbers (`2 * 3`) are fine; a word is not.
          !PLAIN_NUMBER.matches(value) && value.any { it.isLetter() } ->
            "$name is `$value`, which is not a number or an expression"
          !PLAIN_NUMBER.matches(value) -> null
          name in NON_NEGATIVE && value.toDouble() < 0 -> "$name is `$value`; it cannot be negative"
          name == "alpha" && value.toDouble() !in 0.0..255.0 ->
            "alpha is `$value`; it is between 0 and 255"
          else -> null
        }
      PropertyValueKinds.isColour(name) ->
        if (value.isEmpty() || COLOR_LITERAL.matches(value)) null
        else
          "$name is `$value`, which is neither a `#RRGGBB` / `#AARRGGBB` colour nor an expression"
      else -> null
    }
  }

  /**
   * The value problems of [node], as (field, message). A property the component does not declare, a
   * value of the wrong kind, a negative size, a malformed colour.
   */
  internal fun nodeIssues(node: UiBuilderNode): List<Pair<String, String>> {
    val component = node.componentId
    val declared = WatchFaceCatalogShape.DECLARED[component] ?: return emptyList()
    val issues = mutableListOf<Pair<String, String>>()
    for (name in node.properties.keys) {
      if (name !in declared && !isLayoutKey(name))
        issues += name to "property $name is not declared by $component"
    }
    val props = WatchFaceXmlExporter.Props(node, mutableListOf())
    for (name in declared.keys) {
      if (name == WatchFaceXmlExporter.EXTRAS || name == "xml" || !props.has(name)) continue
      val value = props.text(name) ?: continue
      valueIssue(component, name, value)?.let { issues += name to it }
    }
    return issues
  }

  // ---- The document ----------------------------------------------------------------------------

  /** The nodes reachable from the roots, in document order. */
  private fun placed(document: UiBuilderDocument): List<UiBuilderNode> {
    val seen = linkedSetOf<String>()
    val out = mutableListOf<UiBuilderNode>()
    fun walk(id: String) {
      val node = document.nodes[id] ?: return
      if (!seen.add(id)) return
      out += node
      node.slots.values.flatten().forEach(::walk)
    }
    document.roots.forEach(::walk)
    return out
  }

  /** The Watch Face Format version the design targets: its scene's `wffVersion`. */
  internal fun versionOf(document: UiBuilderDocument): Int {
    val scene = document.roots.singleOrNull()?.let { document.nodes[it] }
    val value =
      (((scene?.properties?.get("wffVersion") as? JsonObject)?.get("value")) as? JsonPrimitive)
        ?.contentOrNull
        ?.toDoubleOrNull()
        ?.toInt()
    return value ?: WatchFaceXmlExporter.DEFAULT_WFF_VERSION
  }

  /**
   * The problems no node shows alone: the complication slots' ids and types. [version] is the
   * format version they are judged at.
   */
  internal fun slotIssues(
    nodes: List<UiBuilderNode>,
    version: Int,
  ): List<Triple<String, String?, String>> {
    val issues = mutableListOf<Triple<String, String?, String>>()
    val byId = linkedMapOf<Long, MutableList<String>>()
    for (node in nodes) {
      if (node.componentId != WatchFaceXmlExporter.COMPLICATION_SLOT) continue
      val props = WatchFaceXmlExporter.Props(node, mutableListOf())
      props.int("slotId")?.let { byId.getOrPut(it.toLong()) { mutableListOf() } += node.id }
      for (field in listOf("supportedTypes", "defaultSystemProviderType")) {
        for (type in props.string(field)?.trim()?.split(Regex("\\s+")).orEmpty()) {
          val since = COMPLICATION_TYPES[type]
          when {
            since == null ->
              issues +=
                Triple(
                  node.id,
                  field,
                  "$field names `$type`, which is not a complication type " +
                    "(${COMPLICATION_TYPES.keys.joinToString(", ")})",
                )
            since > version ->
              issues +=
                Triple(
                  node.id,
                  field,
                  "$field names `$type`, which needs Watch Face Format version $since; this face " +
                    "targets version $version",
                )
          }
        }
      }
    }
    for ((slotId, ids) in byId) {
      if (ids.size > 1)
        issues +=
          Triple(
            ids.last(),
            "slotId",
            "slotId $slotId is already used by ${ids.first()}; each complication slot needs its own",
          )
    }
    return issues
  }

  /** The first reason [document] is not a watch face an edit may commit, or null. */
  fun check(document: UiBuilderDocument): Issue? {
    val root = document.roots.singleOrNull()?.let { document.nodes[it] }
    if (root == null || root.componentId != WatchFaceXmlExporter.SCENE)
      return Issue(
        "INVALID_ROOT",
        "a watch face has exactly one `${WatchFaceXmlExporter.SCENE}` root; it cannot be deleted " +
          "or replaced",
        root?.id,
      )
    val nodes = placed(document)
    for (node in nodes) {
      if (node.componentId == WatchFaceXmlExporter.RAW) {
        rawShapeIssue(node)?.let {
          return Issue(VALUE_ISSUE_CODE, it, node.id, "xml")
        }
        continue
      }
      nodeIssues(node).firstOrNull()?.let {
        return Issue(VALUE_ISSUE_CODE, it.second, node.id, it.first)
      }
    }
    slotIssues(nodes, versionOf(document)).firstOrNull()?.let { (id, field, message) ->
      return Issue(VALUE_ISSUE_CODE, message, id, field)
    }
    return null
  }

  /**
   * Every reason the Watch Face Format export would refuse [document], as issues with the node each
   * names: the same export and wff-core validation that [WatchFaceXmlExporter.export] runs, so a
   * design check and an export cannot disagree. Empty means the export succeeds.
   *
   * Unlike [check] this judges a finished design, not an edit: a part image with no `resource` yet
   * is reported here and not by [check], which would block the edit that fills it in.
   */
  fun exportIssues(document: UiBuilderDocument): List<Issue> =
    when (val result = WatchFaceXmlExporter.export(document)) {
      is WatchFaceXmlExporter.Result.Emitted -> emptyList()
      is WatchFaceXmlExporter.Result.Refused ->
        result.reasons.map { reason ->
          val node = NODE_REASON.find(reason)?.groupValues?.get(1)
          Issue("WFF_EXPORT_REFUSED", reason, node?.takeIf { it in document.nodes })
        }
    }

  private val NODE_REASON = Regex("^nodes\\.([^ .(:]+)")

  /**
   * The problem with writing [property] on [node] into [document] (the document as it is before the
   * write; the node as it will be), or null.
   */
  fun checkWrite(document: UiBuilderDocument, node: UiBuilderNode, property: String): Issue? {
    if (node.componentId == WatchFaceXmlExporter.RAW) {
      if (property != "xml") return null
      return rawEditIssue(document, node)?.let { Issue("INVALID_PROPERTY", it, node.id, "xml") }
    }
    val own = nodeIssues(node).filter { it.first == property }
    own.firstOrNull()?.let {
      return Issue("INVALID_PROPERTY", it.second, node.id, property)
    }
    // A slot's types depend on the face's version; a version change is judged by `check`.
    if (node.componentId == WatchFaceXmlExporter.COMPLICATION_SLOT && property in SLOT_FIELDS) {
      slotIssues(listOf(node), versionOf(document))
        .firstOrNull { it.second == property }
        ?.let {
          return Issue("INVALID_PROPERTY", it.third, node.id, property)
        }
    }
    return null
  }

  /**
   * What is wrong with deleting [nodeId] from [document]: its parent, in Watch Face Format,
   * requires at least as many children of that kind as it has left (a `ListConfiguration` needs a
   * `ListOption`, a `Condition` a `Compare` or `Default`). The scene root is judged by [check].
   * Null when the node is not required, or the format does not say.
   */
  fun checkDelete(document: UiBuilderDocument, nodeId: String): Issue? {
    val node = document.nodes[nodeId] ?: return null
    val parent =
      document.nodes.values.firstOrNull { candidate ->
        candidate.slots.any { (slot, ids) -> nodeId in ids && slot !in setOf("prolog", "epilog") }
      } ?: return null
    val slot = parent.slots.entries.first { nodeId in it.value }.key
    val parentTag =
      if (parent.componentId == WatchFaceXmlExporter.SCENE)
        if (slot == "content") "Scene" else "WatchFace"
      else tagOf(parent)
    val childTag = elementName(node) ?: return null
    parentTag ?: return null
    val siblings =
      parent.slots.values
        .flatten()
        .filter { it != nodeId }
        .mapNotNull { document.nodes[it]?.let(::elementName) }
    return requiredChildIssue(
        parentTag,
        childTag,
        siblings,
        parent.componentId !in WRITES_OWN_CHILDREN,
      )
      ?.let { Issue("INVALID_DELETE", it, nodeId) }
  }

  /**
   * The first reason an element [parentTag] holding [remaining] element children (by name) may not
   * lose one named [childTag], from the schema's minimum occurrences; null when it may. The one
   * place the schema's cardinality is read, so it can move to wff-core's own query.
   */
  internal fun requiredChildIssue(
    parentTag: String,
    childTag: String,
    remaining: List<String>,
    /** False when the parent also writes children of its own, so [remaining] is not all of them. */
    countsAll: Boolean = true,
  ): String? {
    val spec =
      WffSchema.named(parentTag).firstOrNull { WffSchema.child(it, childTag) != null }
        ?: return null
    // `mayRemoveChild` takes the children as they are before the removal, the child included.
    val before = remaining + childTag
    if (countsAll) {
      if (WffSchema.mayRemoveChild(spec, childTag, before)) return null
    } else {
      // The parent's own children are not counted, so only the child's own minimum is known.
      val required =
        WffSchema.requiredChildren(spec).firstOrNull {
          WffSchema.elements.getValue(it.id).name == childTag
        }
      if (required == null || remaining.count { it == childTag } >= required.minOccurs) return null
    }
    return "<$parentTag> requires its <$childTag> children (at least " +
      "${WffSchema.requiredChildren(spec).firstOrNull { WffSchema.elements.getValue(it.id).name == childTag }?.minOccurs ?: 1}" +
      ", or ${spec.minChildren} children in all); deleting this one leaves too few"
  }

  /** The elements that write children of their own (`Text`, the bounds, an `Image`, a `Font`). */
  private val WRITES_OWN_CHILDREN =
    setOf(
      WatchFaceXmlExporter.PART_TEXT,
      WatchFaceXmlExporter.PART_IMAGE,
      WatchFaceXmlExporter.TIME_TEXT,
      WatchFaceXmlExporter.COMPLICATION_SLOT,
      WatchFaceXmlExporter.SCENE,
    )

  /** The XML element name a node is written as, or null for a comment or an unnamed one. */
  private fun elementName(node: UiBuilderNode): String? =
    if (node.componentId == WatchFaceXmlExporter.RAW)
      rawXml(node)?.let { parseRaw(it).first?.element?.name }
    else tagOf(node)

  private val SLOT_FIELDS = setOf("supportedTypes", "defaultSystemProviderType")

  // ---- Raw XML ---------------------------------------------------------------------------------

  /** The node a raw `xml` holds: one element or comment, or why not. */
  private fun parseRaw(xml: String): Pair<RawParse?, String?> {
    if (xml.isBlank()) return null to "xml: the element or comment to write is required"
    val children =
      try {
        WffXmlParser.parse("<r>$xml</r>").root.children
      } catch (e: WffParseException) {
        // The wrapper is ours: do not name it.
        val message = (e.message ?: "malformed").replace("</r>", "the end of the xml")
        return null to "xml is not well formed: $message"
      }
    return when (val single = children.singleOrNull()) {
      is WffElement -> RawParse(single) to null
      is WffComment -> RawParse(null) to null
      else -> null to "xml must be exactly one element or comment"
    }
  }

  /** What [parseRaw] found, as the element (null for a comment). */
  private class RawParse(val element: WffElement?)

  /** A raw node's `xml` as held in its properties, or null when it holds none. */
  private fun rawXml(node: UiBuilderNode): String? =
    ((node.properties["xml"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull

  /** Shape only: well formed, one element or comment. What a document may hold from an import. */
  private fun rawShapeIssue(node: UiBuilderNode): String? {
    val xml = rawXml(node) ?: return "xml: the element or comment to write is required"
    return parseRaw(xml).second
  }

  /** The XML tag a node of [component] is, where it has one tag; null otherwise. */
  private fun tagOf(node: UiBuilderNode): String? =
    when (node.componentId) {
      WatchFaceXmlExporter.SCENE -> "Scene"
      WatchFaceXmlExporter.GROUP -> "Group"
      WatchFaceXmlExporter.PART_DRAW -> "PartDraw"
      WatchFaceXmlExporter.PART_IMAGE -> "PartImage"
      WatchFaceXmlExporter.PART_TEXT -> "PartText"
      WatchFaceXmlExporter.ANALOG_CLOCK -> "AnalogClock"
      WatchFaceXmlExporter.DIGITAL_CLOCK -> "DigitalClock"
      WatchFaceXmlExporter.TIME_TEXT -> "TimeText"
      WatchFaceXmlExporter.COMPLICATION_SLOT -> "ComplicationSlot"
      WatchFaceXmlExporter.CONDITION -> "Condition"
      WatchFaceXmlExporter.COMPARE -> "Compare"
      WatchFaceXmlExporter.DEFAULT -> "Default"
      WatchFaceXmlExporter.COMPLICATION -> "Complication"
      WatchFaceXmlExporter.VARIANT -> "Variant"
      WatchFaceXmlExporter.LAUNCH -> "Launch"
      WatchFaceXmlExporter.TRANSFORM -> "Transform"
      WatchFaceXmlExporter.GYRO -> "Gyro"
      WatchFaceXmlExporter.CLOCK_HAND ->
        when (WatchFaceXmlExporter.Props(node, mutableListOf()).string("type")) {
          "hour" -> "HourHand"
          "minute" -> "MinuteHand"
          "second" -> "SecondHand"
          else -> null
        }
      WatchFaceXmlExporter.SHAPE ->
        WatchFaceXmlExporter.SHAPE_TAGS[
            WatchFaceXmlExporter.Props(node, mutableListOf()).string("shape")]
      WatchFaceXmlExporter.FILL -> "Fill"
      WatchFaceXmlExporter.STROKE -> "Stroke"
      WatchFaceXmlExporter.USER_CONFIGURATIONS -> "UserConfigurations"
      WatchFaceXmlExporter.COLOR_CONFIGURATION -> "ColorConfiguration"
      WatchFaceXmlExporter.COLOR_OPTION -> "ColorOption"
      WatchFaceXmlExporter.LIST_CONFIGURATION -> "ListConfiguration"
      WatchFaceXmlExporter.LIST_OPTION -> "ListOption"
      WatchFaceXmlExporter.BOOLEAN_CONFIGURATION -> "BooleanConfiguration"
      WatchFaceXmlExporter.FLAVORS -> "Flavors"
      WatchFaceXmlExporter.FLAVOR -> "Flavor"
      WatchFaceXmlExporter.FLAVOR_CONFIGURATION -> "Configuration"
      WatchFaceXmlExporter.SCENE_BOOLEAN_CONFIGURATION -> "BooleanConfiguration"
      WatchFaceXmlExporter.SCENE_BOOLEAN_OPTION -> "BooleanOption"
      WatchFaceXmlExporter.SCENE_LIST_CONFIGURATION -> "ListConfiguration"
      WatchFaceXmlExporter.SCENE_LIST_OPTION -> "ListOption"
      else -> null
    }

  /**
   * What is wrong with the raw [node]'s new `xml` as an edit of [document]: malformed, not one
   * element or comment, a different element from the one it replaces, not allowed under its parent,
   * or newer than the face's format version.
   */
  private fun rawEditIssue(document: UiBuilderDocument, node: UiBuilderNode): String? {
    val xml = rawXml(node) ?: return "xml: the element or comment to write is required"
    val (parsed, problem) = parseRaw(xml)
    if (parsed == null) return problem
    val element = parsed.element
    val before = document.nodes[node.id]?.let(::rawXml)?.let { parseRaw(it).first }
    if (before != null) {
      val was = before.element
      if ((was == null) != (element == null))
        return "xml: this node holds ${if (was == null) "a comment" else "<${was.name}>"}; " +
          "it cannot become ${if (element == null) "a comment" else "<${element.name}>"}"
      if (was != null && element != null && was.name != element.name)
        return "xml: this node holds a <${was.name}> element; the edit writes <${element.name}>. " +
          "Keep the element, or delete the node and add the one you want"
    }
    if (element == null) return null
    val parent =
      document.nodes.values.firstOrNull { candidate ->
        candidate.slots.any { (slot, ids) -> node.id in ids && slot !in setOf("prolog", "epilog") }
      }
    val parentSlot = parent?.slots?.entries?.firstOrNull { node.id in it.value }?.key
    val parentTag =
      when {
        parent == null -> null
        parent.componentId == WatchFaceXmlExporter.SCENE ->
          if (parentSlot == "content") "Scene" else "WatchFace"
        else -> tagOf(parent)
      }
    val version = WffVersion.fromNumber(versionOf(document)) ?: WffVersion.V1
    if (parentTag != null) {
      val parents = WffSchema.named(parentTag)
      val allowing = parents.firstOrNull { WffSchema.child(it, element.name) != null }
      if (allowing == null) {
        return if (WffSchema.isKnownName(element.name))
          "xml: <${element.name}> is not allowed inside <$parentTag>"
        else "xml: <${element.name}> is not a Watch Face Format element"
      }
      subtreeIssue(allowing, element, version)?.let {
        return it
      }
    } else if (!WffSchema.isKnownName(element.name)) {
      return "xml: <${element.name}> is not a Watch Face Format element"
    }
    return null
  }

  /** Errors in [element] (allowed under [parent]) at [version], as the validator would report. */
  private fun subtreeIssue(
    parent: WffElementSpec,
    element: WffElement,
    version: WffVersion,
  ): String? {
    val spec = WffSchema.child(parent, element.name) ?: return null
    val since = WffSchema.childSince(parent, element.name)!!
    val path = "<${element.name}>"
    if (since > version)
      return "xml: $path needs Watch Face Format version ${since.number}; this face targets " +
        "version ${version.number}"
    for (attribute in element.attributes) {
      val known = spec.attribute(attribute.name) ?: continue
      if (known.sinceVersion > version)
        return "xml: attribute `${attribute.name}` of $path needs Watch Face Format version " +
          "${known.sinceVersion.number}; this face targets version ${version.number}"
    }
    for (required in spec.attributes.filter { it.required }) if (
      element.attr(required.name) == null
    )
      return "xml: $path is missing the required attribute `${required.name}`"
    if (
      !spec.allowsText &&
        element.children.any { (it is WffText && it.text.isNotBlank()) || it is WffCData }
    )
      return "xml: $path does not allow text content"
    for (child in element.childElements) {
      if (WffSchema.child(spec, child.name) == null) {
        if (WffSchema.isKnownName(child.name))
          return "xml: <${child.name}> is not allowed inside $path"
        continue
      }
      subtreeIssue(spec, child, version)?.let {
        return it
      }
    }
    return null
  }
}
