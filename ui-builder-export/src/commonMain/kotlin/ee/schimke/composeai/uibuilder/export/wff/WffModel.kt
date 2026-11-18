// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffModel.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/** A single XML attribute. Attribute order within an element is significant and preserved. */
internal data class WffAttribute(val name: String, val value: String)

/** Any node that can appear inside a [WffElement]. */
internal sealed interface WffNode

/**
 * A generic, lossless XML element: name, ordered attributes and ordered children. The typed schema
 * ([WffSchema]) is a view over this tree, so elements the schema does not know survive a round trip
 * untouched.
 */
internal data class WffElement(
  val name: String,
  val attributes: List<WffAttribute> = emptyList(),
  val children: List<WffNode> = emptyList(),
) : WffNode {
  /** Value of attribute [name], or null. */
  internal fun attr(name: String): String? = attributes.firstOrNull { it.name == name }?.value

  internal val childElements: List<WffElement>
    get() = children.filterIsInstance<WffElement>()

  internal fun childElements(name: String): List<WffElement> =
    children.filterIsInstance<WffElement>().filter { it.name == name }

  internal fun firstChild(name: String): WffElement? =
    children.firstOrNull { it is WffElement && it.name == name } as WffElement?

  /** Concatenated text and CDATA content of direct children. */
  internal fun textContent(): String = buildString {
    for (c in children) {
      when (c) {
        is WffText -> append(c.text)
        is WffCData -> append(c.text)
        else -> {}
      }
    }
  }

  /** Sets (or appends) an attribute, keeping the position of an existing one. */
  internal fun withAttribute(name: String, value: String): WffElement {
    val i = attributes.indexOfFirst { it.name == name }
    val next =
      if (i < 0) attributes + WffAttribute(name, value)
      else attributes.toMutableList().also { it[i] = WffAttribute(name, value) }
    return copy(attributes = next)
  }

  internal fun withoutAttribute(name: String): WffElement =
    copy(attributes = attributes.filterNot { it.name == name })

  internal fun withChildren(children: List<WffNode>): WffElement = copy(children = children)

  internal fun addChild(child: WffNode): WffElement = copy(children = children + child)
}

/** Character data. Stored unescaped. */
internal data class WffText(val text: String) : WffNode

/** `<!-- text -->`; [text] is the raw content between the delimiters. */
internal data class WffComment(val text: String) : WffNode

/** `<![CDATA[ text ]]>`; [text] is the raw content. */
internal data class WffCData(val text: String) : WffNode

/**
 * A parsed document: the root element plus comments before and after it (watch faces usually carry
 * a licence header there). The XML declaration is not stored; the writer always emits `<?xml
 * version="1.0" encoding="utf-8"?>`.
 */
internal data class WffDocument(
  val root: WffElement,
  val prolog: List<WffComment> = emptyList(),
  val epilog: List<WffComment> = emptyList(),
) {
  internal fun withRoot(root: WffElement): WffDocument = copy(root = root)
}

/** Thrown for malformed XML. [line] and [column] are 1-based. */
internal class WffParseException(message: String, val line: Int, val column: Int) :
  IllegalArgumentException("$message (line $line, column $column)")
