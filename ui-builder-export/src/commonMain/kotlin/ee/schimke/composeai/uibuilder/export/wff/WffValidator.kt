// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffValidator.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

internal enum class WffSeverity {
  ERROR,
  WARNING,
}

internal enum class WffDiagnosticCode {
  UNEXPECTED_ROOT,
  UNKNOWN_ELEMENT,
  UNKNOWN_ATTRIBUTE,
  ELEMENT_TOO_NEW,
  ATTRIBUTE_TOO_NEW,
  MISSING_REQUIRED_ATTRIBUTE,
  DISALLOWED_CHILD,
  DISALLOWED_TEXT,
  /** An attribute or text value that its XSD type does not allow (enum, number, colour, ...). */
  INVALID_VALUE,
  /** An enumeration value newer than the target version. */
  VALUE_TOO_NEW,
  /** An expression that does not parse (unbalanced brackets, dangling operator, ...). */
  INVALID_EXPRESSION,
  UNKNOWN_DATA_SOURCE,
  DATA_SOURCE_TOO_NEW,
  UNKNOWN_FUNCTION,
  FUNCTION_TOO_NEW,
  /** A child the XSD requires is absent, or the element has fewer children than it needs. */
  MISSING_REQUIRED_CHILD,
  /** More children than the XSD allows. */
  TOO_MANY_CHILDREN,
  /** A value the XSD requires to be unique (a `slotId`, a `Metadata` key) appears twice. */
  DUPLICATE_ID,
}

/** One finding. [path] looks like `/WatchFace/Scene/Group[2]/PartText` (`[n]` is 1-based). */
internal data class WffDiagnostic(
  val severity: WffSeverity,
  val code: WffDiagnosticCode,
  val path: String,
  val message: String,
)

/**
 * Structural, value and version checks against [WffSchema], which is generated from Google's XSDs.
 * It is an early, readable approximation of the XSD validator the packager runs (that one stays the
 * authority); everything here carries the node path.
 *
 * - unknown element: WARNING, kept as passthrough, its subtree is not inspected;
 * - unknown attribute on a known element: WARNING (`xmlns` declarations are ignored);
 * - element or attribute newer than the target version: ERROR;
 * - missing required attribute: ERROR;
 * - child not allowed in its parent (but known elsewhere in the schema): ERROR;
 * - attribute and text values against their XSD type (enumeration, integer/float and bounds, colour
 *   and other patterns, expression syntax): ERROR; an enumeration value, data source or function
 *   newer than the target is an ERROR too, so [minimumVersion] accounts for them;
 * - an expression naming a data source or function the format does not define: ERROR (names under
 *   `CONFIGURATION.`, `COMPLICATION.` and `REFERENCE.` are defined by the face);
 * - fewer or more children than the XSD's content model allows (a `Template` without a `Parameter`,
 *   a `ListOption` with two children): ERROR;
 * - duplicate `slotId`, `Metadata` key, `BitmapFont` or `Reference` name: ERROR.
 */
internal object WffValidator {
  internal fun check(doc: WffDocument, targetVersion: WffVersion): List<WffDiagnostic> {
    val out = mutableListOf<WffDiagnostic>()
    val root = doc.root
    val spec = WffSchema.root
    val path = "/${root.name}"
    if (root.name != spec.name) {
      out +=
        WffDiagnostic(
          WffSeverity.ERROR,
          WffDiagnosticCode.UNEXPECTED_ROOT,
          path,
          "Root element must be <${spec.name}>, found <${root.name}>",
        )
      return out
    }
    visit(root, spec, spec.sinceVersion, path, targetVersion, out, HashMap())
    return out
  }

  internal fun errors(doc: WffDocument, targetVersion: WffVersion): List<WffDiagnostic> =
    check(doc, targetVersion).filter { it.severity == WffSeverity.ERROR }

  /**
   * Lowest [WffVersion] that supports every known element, attribute, enumeration value, data
   * source and expression function in [doc].
   */
  internal fun minimumVersion(doc: WffDocument): WffVersion {
    var min = WffVersion.V1
    fun walk(e: WffElement, spec: WffElementSpec, since: WffVersion) {
      if (since > min) min = since
      for (a in e.attributes) {
        val s = spec.attribute(a.name) ?: continue
        if (s.sinceVersion > min) min = s.sinceVersion
        s.rule?.let { r ->
          val res = WffValueChecker.check(r, a.value)
          if (res.accepted && res.requiredVersion > min) min = res.requiredVersion
        }
      }
      spec.textRule?.let { r ->
        val res = WffValueChecker.check(r, e.textContent())
        if (res.accepted && res.requiredVersion > min) min = res.requiredVersion
      }
      for (c in e.childElements) {
        val cs = WffSchema.child(spec, c.name) ?: continue
        walk(c, cs, WffSchema.childSince(spec, c.name)!!)
      }
    }
    if (doc.root.name == WffSchema.root.name)
      walk(doc.root, WffSchema.root, WffSchema.root.sinceVersion)
    return min
  }

  private fun visit(
    e: WffElement,
    spec: WffElementSpec,
    since: WffVersion,
    path: String,
    target: WffVersion,
    out: MutableList<WffDiagnostic>,
    documentIds: MutableMap<String, String>,
  ) {
    if (since > target) {
      out +=
        WffDiagnostic(
          WffSeverity.ERROR,
          WffDiagnosticCode.ELEMENT_TOO_NEW,
          path,
          "<${e.name}> requires WFF version ${since.number}, target is ${target.number}",
        )
    }

    for (attr in e.attributes) {
      val s = spec.attribute(attr.name)
      val attrPath = "$path/@${attr.name}"
      when {
        s == null ->
          if (attr.name != "xmlns" && !attr.name.startsWith("xmlns:")) {
            out +=
              WffDiagnostic(
                WffSeverity.WARNING,
                WffDiagnosticCode.UNKNOWN_ATTRIBUTE,
                attrPath,
                "Unknown attribute '${attr.name}' on <${e.name}> (kept as passthrough)",
              )
          }
        s.sinceVersion > target ->
          out +=
            WffDiagnostic(
              WffSeverity.ERROR,
              WffDiagnosticCode.ATTRIBUTE_TOO_NEW,
              attrPath,
              "Attribute '${attr.name}' on <${e.name}> requires WFF version " +
                "${s.sinceVersion.number}, target is ${target.number}",
            )
        else ->
          s.rule?.let {
            checkValue(
              it,
              attr.value,
              "attribute '${attr.name}' on <${e.name}>",
              attrPath,
              target,
              out,
            )
          }
      }
    }
    spec.textRule?.let {
      checkValue(it, e.textContent(), "the text of <${e.name}>", path, target, out)
    }
    for (s in spec.attributes) {
      if (s.required && e.attr(s.name) == null) {
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.MISSING_REQUIRED_ATTRIBUTE,
            path,
            "<${e.name}> is missing required attribute '${s.name}'",
          )
      }
    }

    if (!spec.allowsText) {
      val hasText =
        e.children.any { (it is WffText && !it.text.isXmlWhitespace()) || it is WffCData }
      if (hasText) {
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.DISALLOWED_TEXT,
            path,
            "<${e.name}> does not allow text content",
          )
      }
    }

    val counts = e.childElements.groupingBy { it.name }.eachCount()
    val seen = HashMap<String, Int>()
    val childCounts = HashMap<String, Int>()
    val uniqueSeen = HashMap<String, String>()
    for (child in e.childElements) {
      val n = (seen[child.name] ?: 0) + 1
      seen[child.name] = n
      val childPath = path + "/" + child.name + if ((counts[child.name] ?: 0) > 1) "[$n]" else ""
      val childSpec = WffSchema.child(spec, child.name)
      if (childSpec != null) childCounts[childSpec.id] = (childCounts[childSpec.id] ?: 0) + 1
      checkUnique(e, child, childPath, uniqueSeen, documentIds, out)
      when {
        childSpec != null ->
          visit(
            child,
            childSpec,
            WffSchema.childSince(spec, child.name)!!,
            childPath,
            target,
            out,
            documentIds,
          )
        WffSchema.isKnownName(child.name) ->
          out +=
            WffDiagnostic(
              WffSeverity.ERROR,
              WffDiagnosticCode.DISALLOWED_CHILD,
              childPath,
              "<${child.name}> is not allowed inside <${e.name}>",
            )
        else ->
          out +=
            WffDiagnostic(
              WffSeverity.WARNING,
              WffDiagnosticCode.UNKNOWN_ELEMENT,
              childPath,
              "Unknown element <${child.name}> (kept as passthrough)",
            )
      }
    }
    checkOccurrences(e, spec, path, childCounts, out)
  }

  private fun checkValue(
    rule: WffValueRule,
    value: String,
    what: String,
    path: String,
    target: WffVersion,
    out: MutableList<WffDiagnostic>,
  ) {
    val r = WffValueChecker.check(rule, value)
    if (!r.accepted) {
      val code =
        if (r.reason?.startsWith("invalid expression") == true) WffDiagnosticCode.INVALID_EXPRESSION
        else WffDiagnosticCode.INVALID_VALUE
      out +=
        WffDiagnostic(
          WffSeverity.ERROR,
          code,
          path,
          "Invalid value '${abbreviate(value)}' for $what: ${r.reason}",
        )
      return
    }
    for ((code, message) in r.problems) {
      out += WffDiagnostic(WffSeverity.ERROR, code, path, "In $what: $message")
    }
    for (need in r.needs) {
      if (need.version > target) {
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            need.code,
            path,
            "In $what: ${need.message}, target is ${target.number}",
          )
      }
    }
  }

  private fun abbreviate(value: String): String =
    if (value.length <= 80) value else value.take(77) + "..."

  /** The child counts the XSD's content model demands of [e]. */
  private fun checkOccurrences(
    e: WffElement,
    spec: WffElementSpec,
    path: String,
    childCounts: Map<String, Int>,
    out: MutableList<WffDiagnostic>,
  ) {
    var reportedMissing = false
    for (c in spec.children) {
      val n = childCounts[c.id] ?: 0
      val name = WffSchema.elements.getValue(c.id).name
      if (n < c.minOccurs) {
        reportedMissing = true
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.MISSING_REQUIRED_CHILD,
            path,
            "<${e.name}> requires at least ${c.minOccurs} <$name> (found $n)",
          )
      } else if (c.maxOccurs != WffChildSpec.UNBOUNDED && n > c.maxOccurs) {
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.TOO_MANY_CHILDREN,
            path,
            "<${e.name}> allows at most ${c.maxOccurs} <$name> (found $n)",
          )
      }
    }
    val total = e.childElements.size
    if (spec.children.isNotEmpty()) {
      if (!reportedMissing && total < spec.minChildren) {
        val options =
          spec.children.joinToString(", ") { "<${WffSchema.elements.getValue(it.id).name}>" }
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.MISSING_REQUIRED_CHILD,
            path,
            "<${e.name}> needs at least ${spec.minChildren} child element(s) (found $total); " +
              "allowed: $options",
          )
      }
      if (spec.maxChildren != WffChildSpec.UNBOUNDED && total > spec.maxChildren) {
        val hint =
          if (spec.maxChildren == 1 && e.name in WRAP_HINT)
            " (wrap several elements in one <Group>)"
          else ""
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.TOO_MANY_CHILDREN,
            path,
            "<${e.name}> allows at most ${spec.maxChildren} child element(s) (found $total)$hint",
          )
      }
    }
  }

  private val WRAP_HINT = setOf("ListOption", "BooleanOption")

  /**
   * Values the XSD declares unique (`xs:unique`): `slotId` among the scene's children, `Metadata`
   * keys, `BitmapFont` names, and `Reference` names across the document.
   */
  private fun checkUnique(
    parent: WffElement,
    child: WffElement,
    childPath: String,
    siblings: MutableMap<String, String>,
    documentIds: MutableMap<String, String>,
    out: MutableList<WffDiagnostic>,
  ) {
    fun claim(scope: MutableMap<String, String>, key: String, attr: String, value: String) {
      val first = scope["$key=$value"]
      if (first == null) {
        scope["$key=$value"] = childPath
      } else {
        out +=
          WffDiagnostic(
            WffSeverity.ERROR,
            WffDiagnosticCode.DUPLICATE_ID,
            "$childPath/@$attr",
            "Duplicate $attr '$value' on <${child.name}> (first used at $first)",
          )
      }
    }
    if (parent.name == "Scene")
      child.attr("slotId")?.let { claim(siblings, "slotId", "slotId", it) }
    if (parent.name == "WatchFace" && child.name == "Metadata") {
      child.attr("key")?.let { claim(siblings, "key", "key", it) }
    }
    if (parent.name == "BitmapFonts")
      child.attr("name")?.let { claim(siblings, "font", "name", it) }
    if (child.name == "Reference")
      child.attr("name")?.let { claim(documentIds, "reference", "name", it) }
  }
}
