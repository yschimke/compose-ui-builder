package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.ATTR_ORDER
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.FACE_ATTR_ORDER
import ee.schimke.composeai.uibuilder.export.wff.WffAttribute
import ee.schimke.composeai.uibuilder.export.wff.WffCData
import ee.schimke.composeai.uibuilder.export.wff.WffComment
import ee.schimke.composeai.uibuilder.export.wff.WffElement
import ee.schimke.composeai.uibuilder.export.wff.WffNode
import ee.schimke.composeai.uibuilder.export.wff.WffParseException
import ee.schimke.composeai.uibuilder.export.wff.WffText
import ee.schimke.composeai.uibuilder.export.wff.WffXmlParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Watch Face Format XML (`watchface.xml`) read into a `wff-watchface` design the builder can edit:
 * the inverse of [WatchFaceXmlExporter], and lossless. Importing a face and exporting it again
 * yields the same XML tree: element names, attribute order and values, text and comments.
 *
 * Each element with a `wff/` builtin becomes that node, its attributes the node's properties (named
 * as the attribute, with the original order kept in `wffAttrOrder`), its children the node's slots.
 * An element is only taken as a builtin if the exporter writes exactly that element back, checked
 * per element; anything else, and every element without a builtin (`Metadata`,
 * `UserConfigurations`, `BitmapFonts`, `Condition`, `Transform`, gradients, future elements…), is
 * kept as a `wff/raw` node holding the element's canonical XML and written back verbatim at the
 * same position. Comments, the licence header included, are `wff/raw` nodes too: in the scene's
 * `prolog` and `epilog` slots outside the root, in `face` and `faceAfter` between the `<WatchFace>`
 * children, and in place inside the `<Scene>`.
 *
 * What is not kept, because it is not part of the XML tree: the XML declaration (a `standalone` or
 * `encoding` attribute), a DOCTYPE, other processing instructions, and whitespace between elements
 * (the writer re-indents).
 *
 * XML carries no format version, so the caller states it ([Options.wffVersion]); it lands on the
 * scene as `wffVersion`.
 */
// TODO: replace the vendored `wff` parser with ee.schimke.watchface:wff-core once published.
object WatchFaceXmlImporter {
  data class Options(
    /** The Watch Face Format version the XML targets, held on the scene as `wffVersion`. */
    val wffVersion: Int = WatchFaceXmlExporter.DEFAULT_WFF_VERSION,
    val id: String = "imported-watch-face",
    val title: String = "Imported watch face",
  )

  sealed interface Result {
    /**
     * @param warnings what was kept as raw XML rather than as a builtin: still lossless, but not
     *   editable as a node.
     */
    data class Imported(val document: UiBuilderDocument, val warnings: List<String>) : Result

    /** @param line,column 1-based, set when the XML is malformed. */
    data class Failed(
      val reasons: List<String>,
      val line: Int? = null,
      val column: Int? = null,
    ) : Result
  }

  const val SCENE_ID = "scene"

  fun import(xml: String, options: Options = Options()): Result =
    try {
      Importer(options).run(xml)
    } catch (e: WffParseException) {
      Result.Failed(listOf("xml: ${e.message}"), e.line, e.column)
    } catch (e: Exception) {
      Result.Failed(listOf("import failed: ${e::class.simpleName}: ${e.message}"))
    }

  private val BUILTIN_TAGS =
    setOf(
      "Group",
      "PartDraw",
      "PartImage",
      "PartText",
      "AnalogClock",
      "HourHand",
      "MinuteHand",
      "SecondHand",
      "DigitalClock",
      "TimeText",
      "ComplicationSlot",
      "Variant",
    )

  private val RESERVED_SCENE = setOf("width", "height", "clipShape", "clockType", "previewTime")
  private val CANONICAL_NUMBER = Regex("-?(0|[1-9][0-9]{0,14})(\\.[0-9]{1,15})?")

  private fun literal(type: String, value: JsonPrimitive): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  /** An attribute value as a property: a canonical number as a number, anything else a string. */
  private fun typed(value: String): JsonObject {
    if (
      CANONICAL_NUMBER.matches(value) &&
        WatchFaceXmlExporter.formatNumber(value.toDouble()) == value
    ) {
      return if ('.' in value) literal("float", JsonPrimitive(value.toDouble()))
      else literal("int", JsonPrimitive(value.toLong()))
    }
    return literal("string", JsonPrimitive(value))
  }

  private class Candidate(
    val component: String,
    val props: LinkedHashMap<String, JsonElement>,
    val slotted: List<WffNode>,
  )

  private class Importer(val options: Options) {
    val nodes = LinkedHashMap<String, UiBuilderNode>()
    var counter = 0
    val unmodelled = linkedMapOf<String, Int>()
    val inexact = linkedMapOf<String, Int>()

    fun run(xml: String): Result {
      if (options.wffVersion !in WatchFaceXmlExporter.WFF_VERSIONS)
        return Result.Failed(
          listOf(
            "wffVersion ${options.wffVersion} is not supported; use ${WatchFaceXmlExporter.WFF_VERSIONS}",
          ),
        )
      val parsed = WffXmlParser.parse(xml)
      val face = parsed.root
      if (face.name != "WatchFace")
        return Result.Failed(listOf("root: expected <WatchFace>, found <${face.name}>"))
      if (face.children.any { it is WffText || it is WffCData })
        return Result.Failed(listOf("WatchFace: text content is not Watch Face Format"))
      val sceneIndex = face.children.indexOfFirst { it is WffElement && it.name == "Scene" }
      val scenes = face.children.count { it is WffElement && it.name == "Scene" }
      if (scenes != 1)
        return Result.Failed(listOf("WatchFace: expected exactly one <Scene>, found $scenes"))
      val sceneElement = face.children[sceneIndex] as WffElement
      if (sceneElement.children.any { it is WffText || it is WffCData })
        return Result.Failed(listOf("Scene: text content is not Watch Face Format"))

      val props = linkedMapOf<String, JsonElement>()
      face.attributes.forEach { attribute ->
        val name =
          if (attribute.name in setOf("width", "height", "clipShape")) attribute.name
          else "face:${attribute.name}"
        props[name] = typed(attribute.value)
      }
      props[FACE_ATTR_ORDER] = order(face.attributes)
      for (attribute in sceneElement.attributes) {
        if (attribute.name in RESERVED_SCENE || attribute.name in setOf("wffVersion", "verbatim"))
          return Result.Failed(listOf("Scene: attribute `${attribute.name}` is not supported"))
        props[attribute.name] = typed(attribute.value)
      }
      props[ATTR_ORDER] = order(sceneElement.attributes)
      props["wffVersion"] = literal("int", JsonPrimitive(options.wffVersion))
      props["verbatim"] = literal("bool", JsonPrimitive(true))

      val content = sceneElement.children.map { place(it, WatchFaceXmlExporter.SCENE_CONTENT) }
      val before = face.children.subList(0, sceneIndex).map(::raw)
      val after = face.children.subList(sceneIndex + 1, face.children.size).map(::raw)
      val slots = buildMap {
        if (parsed.prolog.isNotEmpty()) put(WatchFaceXmlExporter.PROLOG, parsed.prolog.map(::raw))
        if (before.isNotEmpty()) put(WatchFaceXmlExporter.FACE, before)
        put("content", content)
        if (after.isNotEmpty()) put(WatchFaceXmlExporter.FACE_AFTER, after)
        if (parsed.epilog.isNotEmpty()) put(WatchFaceXmlExporter.EPILOG, parsed.epilog.map(::raw))
      }
      nodes[SCENE_ID] =
        UiBuilderNode(SCENE_ID, WatchFaceXmlExporter.SCENE, JsonObject(props), slots = slots)
      val document = document(options)

      // The guarantee, checked whole: what the exporter writes for this design is this XML tree.
      val rebuilt = WatchFaceXmlExporter.build(document, WatchFaceXmlExporter.Options())
      return when {
        rebuilt is WatchFaceXmlExporter.Built.Refused ->
          Result.Failed(listOf("import would not export: ") + rebuilt.reasons)
        (rebuilt as WatchFaceXmlExporter.Built.Tree).document != parsed ->
          Result.Failed(listOf("import would not be lossless: the exported tree differs"))
        else -> Result.Imported(document, warnings())
      }
    }

    private fun document(options: Options) =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = options.id,
        title = options.title,
        revision = 0,
        catalogPin =
          buildJsonObject {
            put("systemId", WatchFaceXmlExporter.CATALOG_SYSTEM_ID)
            put("catalogRevision", "wff-v1")
            put("capabilityDigest", "candidate")
            put("nativeRuntimeId", "candidate")
          },
        environment =
          buildJsonObject {
            put("widthDp", 227)
            put("heightDp", 227)
            put("density", 2.0)
            put("theme", "dark")
            put("dynamicColor", false)
            put("locale", "en-US")
            put("fontScale", 1)
            put("layoutDirection", "ltr")
            put("windowPosture", "flat")
            put("browserZoomPercent", 100)
            put("fixedTime", "2024-05-16T10:08:32Z")
            put("animations", "settled")
            put("networkAccess", false)
            put(
              "exportDevices",
              kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id:wearos_large_round"))),
            )
          },
        stateVariables = JsonObject(emptyMap()),
        roots = listOf(SCENE_ID),
        nodes = nodes,
      )

    private fun warnings(): List<String> = buildList {
      if (unmodelled.isNotEmpty())
        add(
          "kept as raw XML, with no wff/ element: " +
            unmodelled.entries.joinToString { "${it.key} x${it.value}" },
        )
      if (inexact.isNotEmpty())
        add(
          "kept as raw XML, because a wff/ element cannot write them back exactly: " +
            inexact.entries.joinToString { "${it.key} x${it.value}" },
        )
    }

    private fun MutableMap<String, Int>.count(name: String) {
      this[name] = (this[name] ?: 0) + 1
    }

    fun newId() = "n${++counter}"

    fun order(attributes: List<WffAttribute>): JsonObject =
      literal("string", JsonPrimitive(attributes.joinToString(" ") { it.name }))

    fun raw(node: WffNode): String {
      val id = newId()
      nodes[id] =
        UiBuilderNode(
          id,
          WatchFaceXmlExporter.RAW,
          JsonObject(
            mapOf("xml" to literal("string", JsonPrimitive(WatchFaceXmlExporter.rawXml(node)))),
          ),
        )
      return id
    }

    /** [node] as the builtin it is exactly, if it is one [allowed] here, else as raw XML. */
    fun place(node: WffNode, allowed: Set<String>): String {
      if (node is WffElement) {
        if (node.name in BUILTIN_TAGS) {
          builtin(node, allowed)?.let {
            return it
          }
          inexact.count(node.name)
        } else unmodelled.count(node.name)
      }
      return raw(node)
    }

    private fun builtin(element: WffElement, allowed: Set<String>): String? {
      val candidate = candidate(element) ?: return null
      if (candidate.component !in allowed) return null
      val mark = nodes.size
      val slots = WatchFaceXmlExporter.slotsOf(candidate.component)
      val accepted = slots.values.flatten().toSet()
      val childIds = candidate.slotted.map { place(it, accepted) }
      val placed = linkedMapOf<String, MutableList<String>>()
      for (childId in childIds) {
        val component = nodes.getValue(childId).componentId
        val slot =
          slots.entries
            .firstOrNull { component == WatchFaceXmlExporter.RAW || component in it.value }
            ?.key
        if (slot == null) {
          discard(mark)
          return null
        }
        placed.getOrPut(slot) { mutableListOf() } += childId
      }
      val id = newId()
      nodes[id] =
        UiBuilderNode(id, candidate.component, JsonObject(candidate.props), slots = placed)
      if (!writesBack(id, candidate.component, element)) {
        discard(mark)
        return null
      }
      return id
    }

    private fun discard(mark: Int) {
      nodes.keys.toList().drop(mark).forEach(nodes::remove)
    }

    /** True when the exporter writes [element] from node [id], alone in a scaffold. */
    private fun writesBack(id: String, component: String, element: WffElement): Boolean {
      val parent =
        when (component) {
          WatchFaceXmlExporter.CLOCK_HAND -> WatchFaceXmlExporter.ANALOG_CLOCK to "hands"
          WatchFaceXmlExporter.TIME_TEXT -> WatchFaceXmlExporter.DIGITAL_CLOCK to "texts"
          else -> null
        }
      val empty = literal("string", JsonPrimitive(""))
      val scaffold = buildMap {
        putAll(nodes)
        if (parent != null)
          put(
            "verify-parent",
            UiBuilderNode(
              "verify-parent",
              parent.first,
              JsonObject(mapOf(ATTR_ORDER to empty)),
              slots = mapOf(parent.second to listOf(id)),
            ),
          )
        put(
          "verify-scene",
          UiBuilderNode(
            "verify-scene",
            WatchFaceXmlExporter.SCENE,
            JsonObject(
              mapOf(
                "verbatim" to literal("bool", JsonPrimitive(true)),
                FACE_ATTR_ORDER to empty,
                ATTR_ORDER to empty,
              ),
            ),
            slots = mapOf("content" to listOf(if (parent != null) "verify-parent" else id)),
          ),
        )
      }
      val document = document(options).copy(roots = listOf("verify-scene"), nodes = scaffold)
      val built =
        WatchFaceXmlExporter.build(document, WatchFaceXmlExporter.Options())
          as? WatchFaceXmlExporter.Built.Tree ?: return false
      val scene = built.document.root.children.filterIsInstance<WffElement>().firstOrNull()
      val written =
        if (parent != null)
          (scene?.children?.singleOrNull() as? WffElement)?.children?.singleOrNull()
        else scene?.children?.singleOrNull()
      return written == element
    }

    // ---- Element to node ---------------------------------------------------------------------

    private fun candidate(e: WffElement): Candidate? {
      val props = linkedMapOf<String, JsonElement>()

      fun put(name: String, value: String): Boolean {
        if (name in props) return false
        props[name] = typed(value)
        return true
      }

      /** Every attribute of [e], as a property named as it is, and their order. */
      fun top(): Boolean {
        if (!e.attributes.all { put(it.name, it.value) }) return false
        props[ATTR_ORDER] = order(e.attributes)
        return true
      }

      /**
       * The attributes of an inner element: those with a property of their own by [named], the rest
       * as `prefix:name`, and their order under [key].
       */
      fun inner(
        element: WffElement,
        key: String,
        prefix: String,
        named: Map<String, String> = emptyMap(),
        computed: Set<String> = emptySet(),
      ): Boolean {
        if (element.children.isNotEmpty() && key != "textAttrOrder" && key != "shapeAttrOrder")
          return false
        for (attribute in element.attributes) {
          if (attribute.name in computed) continue
          val name = named[attribute.name] ?: "$prefix:${attribute.name}"
          if (!put(name, attribute.value)) return false
        }
        props[key] = order(element.attributes)
        return true
      }

      fun rest(from: Int): List<WffNode>? =
        e.children.drop(from).takeIf { children ->
          children.all { it is WffElement || it is WffComment }
        }

      fun child(index: Int, vararg names: String): WffElement? =
        (e.children.getOrNull(index) as? WffElement)?.takeIf { it.name in names }

      val bounds = setOf("x", "y", "width", "height")
      return when (e.name) {
        "Group",
        "AnalogClock",
        "DigitalClock" -> {
          val component =
            when (e.name) {
              "Group" -> WatchFaceXmlExporter.GROUP
              "AnalogClock" -> WatchFaceXmlExporter.ANALOG_CLOCK
              else -> WatchFaceXmlExporter.DIGITAL_CLOCK
            }
          if (!top()) null else rest(0)?.let { Candidate(component, props, it) }
        }
        "HourHand",
        "MinuteHand",
        "SecondHand" -> {
          val type = e.name.removeSuffix("Hand").lowercase()
          if (!top() || !put("type", type)) null
          else rest(0)?.let { Candidate(WatchFaceXmlExporter.CLOCK_HAND, props, it) }
        }
        "Variant" ->
          if (e.children.isEmpty() && top())
            Candidate(WatchFaceXmlExporter.VARIANT, props, emptyList())
          else null
        "TimeText" -> {
          val font = child(0, "Font") ?: return null
          val fonts = mapOf("family" to "family", "size" to "fontSize", "color" to "color")
          if (!top() || !inner(font, "fontAttrOrder", "font", fonts)) null
          else rest(1)?.let { Candidate(WatchFaceXmlExporter.TIME_TEXT, props, it) }
        }
        "PartText" -> {
          val text = child(0, "Text") ?: return null
          val font =
            (text.children.singleOrNull() as? WffElement)?.takeIf { it.name == "Font" }
              ?: return null
          val template =
            (font.children.singleOrNull() as? WffElement)?.takeIf {
              it.name == "Template" && it.attributes.isEmpty()
            } ?: return null
          val content = template.children
          if (content.any { it !is WffText } || content.size > 1) return null
          val fonts = mapOf("family" to "family", "size" to "fontSize", "color" to "color")
          val fontShell = font.copy(children = emptyList())
          val textShell = text.copy(children = emptyList())
          if (
            !top() ||
              !inner(textShell, "textAttrOrder", "text", mapOf("align" to "align")) ||
              !inner(fontShell, "fontAttrOrder", "font", fonts) ||
              !put("text", (content.singleOrNull() as? WffText)?.text.orEmpty())
          )
            null
          else rest(1)?.let { Candidate(WatchFaceXmlExporter.PART_TEXT, props, it) }
        }
        "PartImage" -> {
          val image = child(0, "Image") ?: return null
          val named = mapOf("resource" to "resource", "tintColor" to "tintColor")
          if (!top() || !inner(image, "imageAttrOrder", "image", named)) null
          else rest(1)?.let { Candidate(WatchFaceXmlExporter.PART_IMAGE, props, it) }
        }
        "PartDraw" -> {
          val shape = child(0, "Ellipse", "Rectangle") ?: return null
          val strokes = shape.children.filterIsInstance<WffElement>().filter { it.name == "Stroke" }
          val fills = shape.children.filterIsInstance<WffElement>().filter { it.name == "Fill" }
          if (
            strokes.size > 1 || fills.size > 1 || strokes.size + fills.size != shape.children.size
          )
            return null
          if (
            !top() ||
              !put("shape", shape.name.lowercase()) ||
              !inner(
                shape.copy(children = emptyList()),
                "shapeAttrOrder",
                "shape",
                computed = bounds,
              ) ||
              strokes.any {
                !inner(
                  it,
                  "strokeAttrOrder",
                  "stroke",
                  mapOf("color" to "strokeColor", "thickness" to "strokeWidth"),
                )
              } ||
              fills.any { !inner(it, "fillAttrOrder", "fill", mapOf("color" to "fillColor")) }
          )
            null
          else rest(1)?.let { Candidate(WatchFaceXmlExporter.PART_DRAW, props, it) }
        }
        "ComplicationSlot" -> {
          val box = child(0, "BoundingOval", "BoundingBox") ?: return null
          val policy = child(1, "DefaultProviderPolicy")
          val provider =
            mapOf(
              "defaultSystemProvider" to "defaultSystemProvider",
              "defaultSystemProviderType" to "defaultSystemProviderType",
            )
          if (
            !top() ||
              (box.name == "BoundingBox" && !put("boundsType", "RECTANGLE")) ||
              !inner(box, "boundsAttrOrder", "bounds", computed = bounds) ||
              (policy != null && !inner(policy, "policyAttrOrder", "policy", provider))
          )
            null
          else
            rest(if (policy != null) 2 else 1)?.let {
              Candidate(WatchFaceXmlExporter.COMPLICATION_SLOT, props, it)
            }
        }
        else -> null
      }
    }
  }
}
