// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffSchema.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/**
 * Value kind of an attribute: a coarse label for tooling. What a value may actually be is
 * [WffAttributeSpec.rule], generated from the XSD type.
 */
internal enum class WffAttributeType {
  INT,
  FLOAT,
  COLOR,
  STRING,
  BOOLEAN,
  ENUM,
  /** Always an expression (`Compare.expression`, `Transform.value`, ...). */
  EXPRESSION,
  /** Name of a resource (`res/drawable`, `res/font`, ...) without extension. */
  RESOURCE,
  /** Identifier referenced elsewhere in the document. */
  ID,
}

/**
 * What an attribute (or text) value may be, as the XSD type says. Facets other than enumeration,
 * pattern and numeric bounds are not modelled. [WffValueChecker] evaluates a rule.
 */
internal sealed interface WffValueRule {
  /** One of [values]; a value in [since] needs at least that format version (`"X" to 2`). */
  data class Enumeration(
    val values: List<String>,
    val since: Map<String, Int> = emptyMap(),
  ) : WffValueRule

  /** The whole value matches [regex] (XSD patterns are anchored). */
  data class Pattern(val regex: String) : WffValueRule {
    internal val compiled: Regex by lazy { Regex(regex) }
  }

  /** A literal integer or float, optionally bounded. */
  data class Number(
    val integer: Boolean,
    val min: Double? = null,
    val minExclusive: Boolean = false,
    val max: Double? = null,
    val maxExclusive: Boolean = false,
  ) : WffValueRule

  /** An arithmetic expression (`[HOUR_0_23] * 15`): syntax, data sources and functions checked. */
  data object Expression : WffValueRule

  /** Any one of [members]. */
  data class Union(val members: List<WffValueRule>) : WffValueRule

  /** A whitespace-separated list whose items each match [item]. */
  data class ListOf(val item: WffValueRule) : WffValueRule

  /** Unconstrained text. */
  data object Anything : WffValueRule
}

internal data class WffAttributeSpec(
  val name: String,
  val type: WffAttributeType,
  val required: Boolean = false,
  val sinceVersion: WffVersion = WffVersion.V1,
  val enumValues: List<String> = emptyList(),
  /** Null when the XSD leaves the value unconstrained. */
  val rule: WffValueRule? = null,
)

/**
 * A child [id] allowed under some parent, and the format version that first allowed it there.
 * [minOccurs] and [maxOccurs] are how many times the XSD lets the child appear (`-1` for no limit
 * is [maxOccurs]'s default); they are the loosest bounds across versions, and a child that is one
 * of several alternatives has [minOccurs] 0.
 */
internal data class WffChildSpec(
  val id: String,
  val sinceVersion: WffVersion = WffVersion.V1,
  val minOccurs: Int = 0,
  val maxOccurs: Int = UNBOUNDED,
) {
  companion object {
    internal const val UNBOUNDED: Int = -1
  }
}

/**
 * One element as allowed in one context. The same XML name can have several specs (for example
 * `ListConfiguration` under `UserConfigurations` declares options, while under `Scene` it selects
 * layout), so specs have a unique [id] and parents list their children by id.
 */
internal data class WffElementSpec(
  val id: String,
  val name: String,
  val sinceVersion: WffVersion,
  val attributes: List<WffAttributeSpec>,
  val children: List<WffChildSpec>,
  val allowsText: Boolean = false,
  /** Bounds on the number of element children in total; [maxChildren] is -1 for no limit. */
  val minChildren: Int = 0,
  val maxChildren: Int = WffChildSpec.UNBOUNDED,
  /** For simple-content elements (`Expression`): what the text may be. */
  val textRule: WffValueRule? = null,
) {
  val childIds: List<String>
    get() = children.map { it.id }

  internal fun attribute(name: String): WffAttributeSpec? = attributes.firstOrNull {
    it.name == name
  }
}

/**
 * Table of Watch Face Format elements, their attributes and children, with the format version that
 * introduced each. Generated from Google's XSDs (github.com/google/watchface,
 * `third_party/wff/specification/documents/<version>`) by `tools/wff-xsd/generate_schema.py`; see
 * [GENERATED_SCHEMA]. Abstract XSD elements (`DrawElement`, `BoundingShape`, ...) are expanded to
 * the members of their substitution groups.
 */
internal object WffSchema {
  const val ROOT_ID: String = "WatchFace"

  internal val elements: Map<String, WffElementSpec> by lazy {
    GENERATED_SCHEMA.associateBy { it.id }
  }

  internal val root: WffElementSpec
    get() = elements.getValue(ROOT_ID)

  /** Every spec whose XML name is [name], across contexts. */
  internal fun named(name: String): List<WffElementSpec> =
    elements.values.filter { it.name == name }

  internal fun isKnownName(name: String): Boolean = elements.values.any { it.name == name }

  /** The spec for child [name] of [parent], or null when [parent] does not allow it. */
  internal fun child(parent: WffElementSpec, name: String): WffElementSpec? =
    childEdge(parent, name)?.let { elements.getValue(it.id) }

  /**
   * The version from which child [name] may appear under [parent]: the later of when the child
   * element exists and when [parent] started allowing it. Null when [parent] does not allow it.
   */
  internal fun childSince(parent: WffElementSpec, name: String): WffVersion? {
    val edge = childEdge(parent, name) ?: return null
    return maxOf(edge.sinceVersion, elements.getValue(edge.id).sinceVersion)
  }

  private fun childEdge(parent: WffElementSpec, name: String): WffChildSpec? =
    parent.children.firstOrNull { elements.getValue(it.id).name == name }
}
