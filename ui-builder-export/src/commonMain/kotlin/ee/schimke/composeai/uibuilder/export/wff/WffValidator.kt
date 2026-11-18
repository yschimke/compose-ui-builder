// Vendored from yschimke/watchface-tools wff-core (commit f1d35e9):
//   wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffValidator.kt
// Only visibility (public -> internal) and the package differ.
// TODO: replace with ee.schimke.watchface:wff-core once published
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
}

/** One finding. [path] looks like `/WatchFace/Scene/Group[2]/PartText` (`[n]` is 1-based). */
internal data class WffDiagnostic(
  val severity: WffSeverity,
  val code: WffDiagnosticCode,
  val path: String,
  val message: String,
)

/**
 * Structural and version checks against [WffSchema]. Phase 0 scope: element/attribute presence,
 * nesting and format version. Attribute *values* and expressions are not checked here (that is the
 * job of the XSD-based validator module).
 *
 * - unknown element: WARNING, kept as passthrough, its subtree is not inspected;
 * - unknown attribute on a known element: WARNING (`xmlns` declarations are ignored);
 * - element or attribute newer than the target version: ERROR;
 * - missing required attribute: ERROR;
 * - child not allowed in its parent (but known elsewhere in the schema): ERROR.
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
    visit(root, spec, spec.sinceVersion, path, targetVersion, out)
    return out
  }

  internal fun errors(doc: WffDocument, targetVersion: WffVersion): List<WffDiagnostic> =
    check(doc, targetVersion).filter { it.severity == WffSeverity.ERROR }

  /** Lowest [WffVersion] that supports every known element and attribute in [doc]. */
  internal fun minimumVersion(doc: WffDocument): WffVersion {
    var min = WffVersion.V1
    fun walk(e: WffElement, spec: WffElementSpec, since: WffVersion) {
      if (since > min) min = since
      for (a in e.attributes) {
        val s = spec.attribute(a.name) ?: continue
        if (s.sinceVersion > min) min = s.sinceVersion
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
      }
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
    for (child in e.childElements) {
      val n = (seen[child.name] ?: 0) + 1
      seen[child.name] = n
      val childPath = path + "/" + child.name + if ((counts[child.name] ?: 0) > 1) "[$n]" else ""
      val childSpec = WffSchema.child(spec, child.name)
      when {
        childSpec != null ->
          visit(child, childSpec, WffSchema.childSince(spec, child.name)!!, childPath, target, out)
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
  }
}
