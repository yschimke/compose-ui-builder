package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.ATTR_ORDER
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.FACE_ATTR_ORDER
import ee.schimke.composeai.uibuilder.export.wff.WffAttribute
import ee.schimke.composeai.uibuilder.export.wff.WffCData
import ee.schimke.composeai.uibuilder.export.wff.WffComment
import ee.schimke.composeai.uibuilder.export.wff.WffDiagnosticCode
import ee.schimke.composeai.uibuilder.export.wff.WffDocument
import ee.schimke.composeai.uibuilder.export.wff.WffElement
import ee.schimke.composeai.uibuilder.export.wff.WffNode
import ee.schimke.composeai.uibuilder.export.wff.WffParseException
import ee.schimke.composeai.uibuilder.export.wff.WffText
import ee.schimke.composeai.uibuilder.export.wff.WffValidator
import ee.schimke.composeai.uibuilder.export.wff.WffVersion
import ee.schimke.composeai.uibuilder.export.wff.WffXmlParser
import kotlinx.serialization.json.Json
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

  /** An import holds the XML it is given, valid or not; the export is what judges the face. */
  private val LENIENT = WatchFaceXmlExporter.Options(lenient = true)

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
      "Condition",
      "Compare",
      "Default",
      "Launch",
      "Complication",
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

  private fun componentOf(tag: String): String? =
    when (tag) {
      "Group" -> WatchFaceXmlExporter.GROUP
      "AnalogClock" -> WatchFaceXmlExporter.ANALOG_CLOCK
      "DigitalClock" -> WatchFaceXmlExporter.DIGITAL_CLOCK
      "HourHand",
      "MinuteHand",
      "SecondHand" -> WatchFaceXmlExporter.CLOCK_HAND
      "Variant" -> WatchFaceXmlExporter.VARIANT
      "TimeText" -> WatchFaceXmlExporter.TIME_TEXT
      "PartText" -> WatchFaceXmlExporter.PART_TEXT
      "PartImage" -> WatchFaceXmlExporter.PART_IMAGE
      "PartDraw" -> WatchFaceXmlExporter.PART_DRAW
      "ComplicationSlot" -> WatchFaceXmlExporter.COMPLICATION_SLOT
      "Condition" -> WatchFaceXmlExporter.CONDITION
      "Compare" -> WatchFaceXmlExporter.COMPARE
      "Default" -> WatchFaceXmlExporter.DEFAULT
      "Launch" -> WatchFaceXmlExporter.LAUNCH
      "Complication" -> WatchFaceXmlExporter.COMPLICATION
      else -> null
    }

  /**
   * A node's properties as the catalog declares them. A value goes in a property of its own when
   * the catalog declares one that accepts it; anything else (an attribute with no property, a value
   * of the wrong kind or outside the allowed values, the attribute orders) is held in the declared
   * `wffExtras` string, which the exporter reads back wherever the property is absent.
   */
  private class Sink(component: String) {
    private val declared = WatchFaceCatalogShape.DECLARED.getValue(component)
    private val props = linkedMapOf<String, JsonElement>()
    private val extras = linkedMapOf<String, String>()

    fun put(name: String, value: String): Boolean {
      if (name in props || name in extras) return false
      val property = declared[name]
      val literal =
        when (property?.type) {
          // The service refuses a colour written as `string`: it is a `color` of a drawable value.
          "string" ->
            if (PropertyValueKinds.isColour(name)) {
              if (value.isNotEmpty() && PropertyValueKinds.isDrawableColour(value))
                literal("color", JsonPrimitive(value))
              else null
            } else literal("string", JsonPrimitive(value))
          "number",
          "integer" ->
            typed(value).takeIf {
              val type = (it["type"] as JsonPrimitive).content
              type == "int" || (type == "float" && property.type == "number")
            }
          else -> null
        }
      if (literal != null && (property!!.allowed.isEmpty() || value in property.allowed))
        props[name] = literal
      else extras[name] = value
      return true
    }

    fun order(key: String, attributes: List<WffAttribute>) {
      extras[key] = attributes.joinToString(" ") { it.name }
    }

    /** A value with no property of its own, held in `wffExtras` under [key]. */
    fun extra(key: String, value: String) {
      extras[key] = value
    }

    fun finish(): LinkedHashMap<String, JsonElement> {
      if (extras.isNotEmpty())
        props[WatchFaceXmlExporter.EXTRAS] =
          literal(
            "string",
            JsonPrimitive(
              Json.encodeToString(
                JsonObject.serializer(),
                JsonObject(extras.mapValues { JsonPrimitive(it.value) }),
              ),
            ),
          )
      return props
    }
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

    /** Elements kept raw though they have a builtin, by element and the reason, with a count. */
    val inexact = linkedMapOf<String, Int>()

    /** Why the last [candidate] gave none: set at each place it declines. */
    var why: String? = null

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

      val sink = Sink(WatchFaceXmlExporter.SCENE)
      for (attribute in face.attributes) {
        val name =
          if (attribute.name in setOf("width", "height", "clipShape")) attribute.name
          else "face:${attribute.name}"
        sink.put(name, attribute.value)
      }
      sink.order(FACE_ATTR_ORDER, face.attributes)
      for (attribute in sceneElement.attributes) {
        if (
          attribute.name in RESERVED_SCENE ||
            attribute.name in setOf("wffVersion", "verbatim") ||
            !sink.put(attribute.name, attribute.value)
        )
          return Result.Failed(listOf("Scene: attribute `${attribute.name}` is not supported"))
      }
      sink.order(ATTR_ORDER, sceneElement.attributes)
      val props = sink.finish()
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
      val rebuilt = WatchFaceXmlExporter.build(document, LENIENT)
      return when {
        rebuilt is WatchFaceXmlExporter.Built.Refused ->
          Result.Failed(listOf("import would not export: ") + rebuilt.reasons)
        (rebuilt as WatchFaceXmlExporter.Built.Tree).document != parsed ->
          Result.Failed(listOf("import would not be lossless: the exported tree differs"))
        else -> Result.Imported(document, warnings() + versionWarnings(parsed))
      }
    }

    /** What the XML needs that the stated format version does not have. */
    private fun versionWarnings(parsed: WffDocument): List<String> {
      val target = WffVersion.fromNumber(options.wffVersion) ?: return emptyList()
      val needed = WffValidator.minimumVersion(parsed)
      if (needed <= target) return emptyList()
      val first =
        WffValidator.errors(parsed, target)
          .filter {
            it.code == WffDiagnosticCode.ELEMENT_TOO_NEW ||
              it.code == WffDiagnosticCode.ATTRIBUTE_TOO_NEW
          }
          .take(3)
          .joinToString("; ") { it.message }
      return listOf(
        "the XML needs Watch Face Format version ${needed.number}, but this design targets " +
          "version ${target.number}: export is refused until wffVersion is raised ($first)",
      )
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
      for ((reason, count) in inexact) add(
        "kept as raw XML, not modelled as a wff/ element: $reason (x$count)",
      )
    }

    private fun MutableMap<String, Int>.count(name: String) {
      this[name] = (this[name] ?: 0) + 1
    }

    fun newId() = "n${++counter}"

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
          inexact.count("<${node.name}>: ${why ?: "the exporter writes it differently"}")
        } else unmodelled.count(node.name)
      }
      return raw(node)
    }

    private fun builtin(element: WffElement, allowed: Set<String>): String? {
      why = null
      val candidate = candidate(element) ?: return null
      if (candidate.component !in allowed) {
        why = "not valid here: ${candidate.component} does not go in this parent"
        return null
      }
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
          why = "a child (${nodes.getValue(childId).componentId}) fits none of its slots"
          return null
        }
        placed.getOrPut(slot) { mutableListOf() } += childId
      }
      val id = newId()
      nodes[id] =
        UiBuilderNode(id, candidate.component, JsonObject(candidate.props), slots = placed)
      val difference = writesBack(id, candidate.component, element)
      if (difference != null) {
        discard(mark)
        why = difference
        return null
      }
      return id
    }

    private fun discard(mark: Int) {
      nodes.keys.toList().drop(mark).forEach(nodes::remove)
    }

    /**
     * Null when the exporter writes [element] from node [id], alone in a scaffold; else what it
     * writes differently.
     */
    private fun writesBack(id: String, component: String, element: WffElement): String? {
      val parent =
        when (component) {
          WatchFaceXmlExporter.CLOCK_HAND -> WatchFaceXmlExporter.ANALOG_CLOCK to "hands"
          WatchFaceXmlExporter.TIME_TEXT -> WatchFaceXmlExporter.DIGITAL_CLOCK to "texts"
          WatchFaceXmlExporter.COMPARE,
          WatchFaceXmlExporter.DEFAULT -> WatchFaceXmlExporter.CONDITION to "branches"
          WatchFaceXmlExporter.COMPLICATION -> WatchFaceXmlExporter.COMPLICATION_SLOT to "variants"
          WatchFaceXmlExporter.LAUNCH -> WatchFaceXmlExporter.GROUP to "variants"
          else -> null
        }
      val empty = literal("string", JsonPrimitive(""))
      val parentProperties = buildMap {
        put(ATTR_ORDER, empty)
        // A slot refuses to write without these; its own attributes are not under test.
        if (parent?.first == WatchFaceXmlExporter.COMPLICATION_SLOT) {
          put("slotId", literal("int", JsonPrimitive(0)))
          put("supportedTypes", literal("string", JsonPrimitive("SHORT_TEXT")))
        }
      }
      val scaffold = buildMap {
        putAll(nodes)
        if (parent != null)
          put(
            "verify-parent",
            UiBuilderNode(
              "verify-parent",
              parent.first,
              JsonObject(parentProperties),
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
      val built = WatchFaceXmlExporter.build(document, LENIENT)
      if (built !is WatchFaceXmlExporter.Built.Tree)
        return "the exporter refuses it: " +
          (built as WatchFaceXmlExporter.Built.Refused).reasons.firstOrNull().orEmpty()
      val scene = built.document.root.children.filterIsInstance<WffElement>().firstOrNull()
      // The element under test is the last child of its scaffold parent: a slot writes its bounds
      // element first.
      val written =
        if (parent != null) (scene?.children?.singleOrNull() as? WffElement)?.children?.lastOrNull()
        else scene?.children?.singleOrNull()
      return if (written == element) null
      else (written as? WffElement)?.let { difference(it, element) } ?: "it is not written back"
    }

    /** The first place [written] and the face's [element] disagree, in words. */
    private fun difference(written: WffElement, element: WffElement, path: String = ""): String {
      val here = "$path<${element.name}>"
      if (written.name != element.name) return "$here is written as <${written.name}>"
      val got = written.attributes.map { it.name }
      val want = element.attributes.map { it.name }
      if (got != want)
        return "$here attributes are written as [${got.joinToString(" ")}] but the face has " +
          "[${want.joinToString(" ")}]"
      written.attributes
        .zip(element.attributes)
        .firstOrNull { it.first != it.second }
        ?.let {
          return "$here attribute `${it.second.name}` is written as `${it.first.value}` but the " +
            "face has `${it.second.value}`"
        }
      val gotChildren = written.children
      val wantChildren = element.children
      for (index in 0 until maxOf(gotChildren.size, wantChildren.size)) {
        val g = gotChildren.getOrNull(index)
        val w = wantChildren.getOrNull(index)
        if (g == w) continue
        if (g is WffElement && w is WffElement && g.name == w.name)
          return difference(g, w, "$here ")
        fun label(n: WffNode?) =
          when (n) {
            null -> "nothing"
            is WffElement -> "<${n.name}>"
            is WffText -> "text"
            is WffCData -> "CDATA"
            is WffComment -> "a comment"
          }
        return "$here child ${index + 1} is written as ${label(g)} but the face has ${label(w)}"
      }
      return "$here differs"
    }

    // ---- Element to node ---------------------------------------------------------------------

    private fun candidate(e: WffElement): Candidate? {
      val component = componentOf(e.name) ?: return null
      val sink = Sink(component)

      fun no(reason: String): Candidate? {
        why = reason
        return null
      }

      fun put(name: String, value: String): Boolean = sink.put(name, value)

      /** Every attribute of [e], as a property named as it is, and their order. */
      fun top(): Boolean {
        if (!e.attributes.all { put(it.name, it.value) }) return false
        sink.order(ATTR_ORDER, e.attributes)
        return true
      }

      /**
       * The attributes of an inner element: those with a property of their own by [named], the rest
       * as `prefix:name`, and their order under [key]. An attribute in [expected] with the value
       * the exporter computes needs nothing kept; one that differs is kept as `prefix:name`.
       */
      fun inner(
        element: WffElement,
        key: String,
        prefix: String,
        named: Map<String, String> = emptyMap(),
        expected: Map<String, String?> = emptyMap(),
      ): Boolean {
        for (attribute in element.attributes) {
          if (attribute.name in expected) {
            if (attribute.value == expected[attribute.name]) continue
            if (!put("$prefix:${attribute.name}", attribute.value)) return false
            continue
          }
          val name = named[attribute.name] ?: "$prefix:${attribute.name}"
          if (!put(name, attribute.value)) return false
        }
        sink.order(key, element.attributes)
        return true
      }

      /** The one `<tag>` child with only attributes (no children): else why not. */
      fun leaf(element: WffElement): String? =
        if (element.children.isEmpty()) null
        else
          "<${element.name}> has " +
            element.children.joinToString(", ") {
              when (it) {
                is WffElement -> "a <${it.name}> child"
                is WffText -> "text"
                is WffCData -> "CDATA"
                is WffComment -> "a comment"
              }
            }

      /**
       * Everything but the child at [core] (all of [cores]), when each is an element or comment.
       */
      fun rest(cores: Set<Int>): List<WffNode>? {
        val others = e.children.filterIndexed { index, _ -> index !in cores }
        val bad = others.firstOrNull { it !is WffElement && it !is WffComment }
        if (bad != null) {
          why = "<${e.name}> has " + (if (bad is WffCData) "CDATA" else "text") + " content"
          return null
        }
        return others
      }

      fun indexOf(vararg names: String): Int =
        e.children.indexOfFirst { it is WffElement && it.name in names }

      /** Records where the core part sat among the slot children, when it was not first. */
      fun coreAt(index: Int) {
        if (index > 0) sink.extra(WatchFaceXmlExporter.CORE_AT, index.toString())
      }

      val fonts = mapOf("family" to "family", "size" to "fontSize", "color" to "color")
      return when (e.name) {
        "Group",
        "AnalogClock",
        "DigitalClock" -> {
          if (!top()) return no("an attribute clashes with another")
          rest(emptySet())?.let { Candidate(component, sink.finish(), it) }
        }
        "HourHand",
        "MinuteHand",
        "SecondHand" -> {
          val type = e.name.removeSuffix("Hand").lowercase()
          if (!top() || !put("type", type)) return no("an attribute clashes with another")
          rest(emptySet())?.let { Candidate(component, sink.finish(), it) }
        }
        "Variant" ->
          if (e.children.isEmpty() && top()) Candidate(component, sink.finish(), emptyList())
          else no("<Variant> has children")
        "Condition",
        "Default" ->
          if (!top()) no("an attribute clashes with another")
          else rest(emptySet())?.let { Candidate(component, sink.finish(), it) }
        "Compare",
        "Complication" ->
          if (!top()) no("an attribute clashes with another")
          else rest(emptySet())?.let { Candidate(component, sink.finish(), it) }
        "Launch" ->
          if (e.children.isNotEmpty()) no(leaf(e) ?: "<Launch> has children")
          else if (!top()) no("an attribute clashes with another")
          else Candidate(component, sink.finish(), emptyList())
        "TimeText" -> {
          val at = indexOf("Font")
          if (at < 0) return no("<TimeText> has no <Font> child")
          val font = e.children[at] as WffElement
          leaf(font)?.let {
            return no(it)
          }
          if (!top() || !inner(font, "fontAttrOrder", "font", fonts))
            return no("an attribute clashes with another")
          coreAt(at)
          rest(setOf(at))?.let { Candidate(component, sink.finish(), it) }
        }
        "PartText" -> {
          val at = indexOf("Text")
          if (at < 0) return no("<PartText> has no <Text> child")
          val text = e.children[at] as WffElement
          val font =
            (text.children.singleOrNull() as? WffElement)?.takeIf { it.name == "Font" }
              ?: return no("<Text> does not hold exactly one <Font>")
          val template =
            (font.children.singleOrNull() as? WffElement)?.takeIf { it.name == "Template" }
              ?: return no("<Font> does not hold exactly one <Template>")
          if (template.attributes.isNotEmpty())
            return no("<Template> has attributes (${template.attributes.joinToString { it.name }})")
          val items = template.children
          val main = items.indexOfFirst { it is WffText || it is WffCData }
          val badParameter = items.firstOrNull {
            it is WffComment ||
              (it is WffElement &&
                (it.name != "Parameter" ||
                  it.attributes.map { a -> a.name } != listOf("expression") ||
                  it.children.isNotEmpty() ||
                  '\n' in it.attributes.single().value))
          }
          if (badParameter != null)
            return no(
              "<Template> holds " +
                (if (badParameter is WffElement)
                  "<${badParameter.name}> that is not a plain <Parameter expression>"
                else "a comment") +
                ", not text and <Parameter expression> elements",
            )
          val parameters = items.filterIsInstance<WffElement>()
          // The simple shape is the main text then the parameters; anything else is kept as a
          // layout.
          val layout = items.mapIndexed { index, item ->
            when {
              item is WffElement -> "p"
              index == main -> if (item is WffCData) "M" else "m"
              item is WffCData -> "c" + item.text
              else -> "t" + (item as WffText).text
            }
          }
          val simple =
            layout == (if (main >= 0) listOf("m") else emptyList()) + parameters.map { "p" }
          val fontShell = font.copy(children = emptyList())
          val textShell = text.copy(children = emptyList())
          if (
            !top() ||
              !inner(textShell, "textAttrOrder", "text", mapOf("align" to "align")) ||
              !inner(fontShell, "fontAttrOrder", "font", fonts) ||
              !put(
                "text",
                when (val node = items.getOrNull(main)) {
                  is WffText -> node.text
                  is WffCData -> node.text
                  else -> ""
                },
              ) ||
              (parameters.isNotEmpty() &&
                !put(
                  "parameters",
                  parameters.joinToString("\n") { (it as WffElement).attributes.single().value },
                ))
          )
            return no("an attribute clashes with another")
          if (!simple)
            sink.extra(
              WatchFaceXmlExporter.TEMPLATE_LAYOUT,
              kotlinx.serialization.json.JsonArray(layout.map { JsonPrimitive(it) }).toString(),
            )
          coreAt(at)
          rest(setOf(at))?.let { Candidate(component, sink.finish(), it) }
        }
        "PartImage" -> {
          val at = indexOf("Image")
          if (at < 0) return no("<PartImage> has no <Image> child")
          val image = e.children[at] as WffElement
          leaf(image)?.let {
            return no(it)
          }
          if (!top() || !inner(image, "imageAttrOrder", "image", mapOf("resource" to "resource")))
            return no("an attribute clashes with another")
          coreAt(at)
          rest(setOf(at))?.let { Candidate(component, sink.finish(), it) }
        }
        "PartDraw" -> {
          val tags = WatchFaceXmlExporter.SHAPE_TAGS.entries.associate { (k, v) -> v to k }
          val at = e.children.indexOfFirst { it is WffElement && it.name in tags }
          if (!top()) return no("an attribute clashes with another")
          if (at < 0) {
            // A part with no shape of its own: an empty PartDraw, or only overrides.
            put("shape", "none")
            return rest(emptySet())?.let { Candidate(component, sink.finish(), it) }
          }
          val shape = e.children[at] as WffElement
          val kind = tags.getValue(shape.name)
          val badChild =
            shape.children.firstOrNull {
              it !is WffElement || it.name != "Stroke" && it.name != "Fill"
            }
          if (badChild != null)
            return no(
              "<${shape.name}> has " +
                ((badChild as? WffElement)?.let { "a <${it.name}> child" } ?: "text or a comment"),
            )
          val strokes = shape.childElements("Stroke")
          val fills = shape.childElements("Fill")
          if (strokes.size > 1 || fills.size > 1)
            return no("<${shape.name}> has more than one <Stroke> or <Fill>")
          (strokes + fills)
            .firstNotNullOfOrNull { leaf(it) }
            ?.let {
              return no(it)
            }
          put("shape", kind)
          val partExpected =
            mapOf("x" to "0", "y" to "0", "width" to e.attr("width"), "height" to e.attr("height"))
          val sizeExpected =
            if (kind in setOf("ellipse", "rectangle", "round-rectangle")) partExpected
            else emptyMap()
          val named =
            when (kind) {
              "round-rectangle" ->
                mapOf("cornerRadiusX" to "cornerRadiusX", "cornerRadiusY" to "cornerRadiusY")
              "arc" ->
                mapOf(
                  "centerX" to "centerX",
                  "centerY" to "centerY",
                  "width" to "shapeWidth",
                  "height" to "shapeHeight",
                  "startAngle" to "startAngle",
                  "endAngle" to "endAngle",
                  "direction" to "direction",
                )
              "line" ->
                mapOf(
                  "startX" to "startX",
                  "startY" to "startY",
                  "endX" to "endX",
                  "endY" to "endY",
                )
              else -> emptyMap()
            }
          if (
            !inner(
              shape.copy(children = emptyList()),
              "shapeAttrOrder",
              "shape",
              named,
              sizeExpected,
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
            return no("an attribute clashes with another")
          val fillFirst =
            shape.children
              .indexOfFirst { (it as WffElement).name == "Fill" }
              .let { f ->
                f >= 0 &&
                  shape.children
                    .indexOfFirst { (it as WffElement).name == "Stroke" }
                    .let { it >= 0 && f < it }
              }
          if (fillFirst) sink.extra(WatchFaceXmlExporter.SHAPE_CHILD_ORDER, "fill stroke")
          coreAt(at)
          rest(setOf(at))?.let { Candidate(component, sink.finish(), it) }
        }
        "ComplicationSlot" -> {
          val boxAt = indexOf("BoundingOval", "BoundingBox", "BoundingRoundBox")
          if (boxAt < 0)
            return no(
              "<ComplicationSlot> has no <BoundingOval>, <BoundingBox> or <BoundingRoundBox>",
            )
          val policyAt = indexOf("DefaultProviderPolicy")
          if (policyAt >= 0 && kotlin.math.abs(policyAt - boxAt) != 1)
            return no("<DefaultProviderPolicy> and the bounds element are not next to each other")
          val box = e.children[boxAt] as WffElement
          val policy = if (policyAt >= 0) e.children[policyAt] as WffElement else null
          (listOfNotNull(box, policy))
            .firstNotNullOfOrNull { leaf(it) }
            ?.let {
              return no(it)
            }
          val provider =
            mapOf(
              "defaultSystemProvider" to "defaultSystemProvider",
              "defaultSystemProviderType" to "defaultSystemProviderType",
            )
          val boxExpected =
            mapOf("x" to "0", "y" to "0", "width" to e.attr("width"), "height" to e.attr("height"))
          if (
            !top() ||
              (box.name == "BoundingBox" && !put("boundsType", "RECTANGLE")) ||
              (box.name == "BoundingRoundBox" && !put("boundsType", "ROUND_RECTANGLE")) ||
              !inner(box, "boundsAttrOrder", "bounds", expected = boxExpected) ||
              (policy != null && !inner(policy, "policyAttrOrder", "policy", provider))
          )
            return no("an attribute clashes with another")
          if (policy != null && policyAt < boxAt)
            sink.extra(WatchFaceXmlExporter.SLOT_CHILD_ORDER, "policy bounds")
          coreAt(minOf(boxAt, if (policyAt >= 0) policyAt else boxAt))
          rest(setOfNotNull(boxAt, policyAt.takeIf { it >= 0 }))?.let {
            Candidate(component, sink.finish(), it)
          }
        }
        else -> null
      }
    }
  }
}
