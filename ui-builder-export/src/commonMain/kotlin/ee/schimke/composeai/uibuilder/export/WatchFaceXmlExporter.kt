package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.watchface.wff.WffAttribute
import ee.schimke.watchface.wff.WffCData
import ee.schimke.watchface.wff.WffComment
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffNode
import ee.schimke.watchface.wff.WffParseException
import ee.schimke.watchface.wff.WffText
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import ee.schimke.watchface.wff.WffXmlWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Wear OS Watch Face Format (WFF) projection: a design in the `wff-watchface` catalog to a
 * `<WatchFace>` XML document. Deterministic (fixed attribute order, two-space indent), and total:
 * anything it cannot write is refused by node id and component, never dropped.
 *
 * The vocabulary mirrors WFF elements one to one (see the `wff/?` constants), with the WFF
 * attribute names as property names, positioned absolutely in pixels of the face's `width` ?
 * `height` (the [SCENE] node's properties, 450 ? 450 unless stated).
 *
 * The WFF version (1?5, [DEFAULT_WFF_VERSION] = 2) is the `wffVersion` property of the [SCENE]
 * node, or [Options.wffVersion] when the host states one. It is a node property, not an
 * `environment` entry, because `DesignEnvironmentV1` (compose-preview-contracts) is a closed type
 * that drops unknown keys on the wire.
 *
 * Bitmap resources (`resource="?"`) are named, not bundled: this writes the XML only. Packaging the
 * drawables (and the DWF resource bundle) is the host's job.
 */
object WatchFaceXmlExporter {
  const val CATALOG_SYSTEM_ID = "wff-watchface"
  const val PREFIX = "wff/"
  const val SCENE = "wff/scene"
  const val GROUP = "wff/group"
  const val PART_DRAW = "wff/part-draw"
  const val PART_IMAGE = "wff/part-image"
  const val PART_TEXT = "wff/part-text"
  const val ANALOG_CLOCK = "wff/analog-clock"
  const val CLOCK_HAND = "wff/clock-hand"
  const val DIGITAL_CLOCK = "wff/digital-clock"
  const val TIME_TEXT = "wff/time-text"
  const val COMPLICATION_SLOT = "wff/complication-slot"
  const val VARIANT = "wff/variant"
  const val CONDITION = "wff/condition"
  const val COMPARE = "wff/compare"
  const val DEFAULT = "wff/default"
  const val LAUNCH = "wff/launch"
  const val COMPLICATION = "wff/complication"

  /**
   * A `<Transform>`: one attribute of its parent (`target`) driven by an expression (`value`), set
   * to it (`mode` TO, the default) or added to the static attribute (BY). `rotateWith` names a
   * preset expression instead of a `value`; see [ROTATE_WITH].
   */
  const val TRANSFORM = "wff/transform"

  /** A `<Gyro>`: its parent's position, scale, angle and alpha offset by the wrist's tilt. */
  const val GYRO = "wff/gyro"

  /**
   * The "Rotate with" presets of a [TRANSFORM], each the `angle` expression it writes. `custom` (or
   * no `rotateWith`) writes the transform's own `value`.
   */
  val ROTATE_WITH: Map<String, String> =
    linkedMapOf(
      "hour" to "([HOUR_0_11]+[MINUTE]/60)*30",
      "minute" to "([MINUTE]+[SECOND]/60)*6",
      "second-ticking" to "[SECOND]*6",
      "second-sweep" to "[SECOND_MILLISECOND]*6",
    )
  const val ROTATE_WITH_CUSTOM = "custom"

  /** The parent attributes a [TRANSFORM] may drive, as the inspector offers them. */
  val TRANSFORM_TARGETS: List<String> =
    listOf("angle", "x", "y", "alpha", "width", "height", "pivotX", "pivotY", "scaleX", "scaleY")

  /** The attributes of a `<Gyro>`, each an expression of the tilt. */
  val GYRO_ATTRIBUTES: List<String> = listOf("x", "y", "scaleX", "scaleY", "angle", "alpha")

  /** One shape of a `PartDraw` (`Ellipse`, `Rectangle`, ?) and its `Fill` and `Stroke`. */
  const val SHAPE = "wff/shape"
  const val FILL = "wff/fill"
  const val STROKE = "wff/stroke"

  /** The theme and user-configuration plumbing that sits before the `<Scene>`. */
  const val USER_CONFIGURATIONS = "wff/user-configurations"
  const val COLOR_CONFIGURATION = "wff/color-configuration"
  const val COLOR_OPTION = "wff/color-option"
  const val LIST_CONFIGURATION = "wff/list-configuration"
  const val LIST_OPTION = "wff/list-option"
  const val BOOLEAN_CONFIGURATION = "wff/boolean-configuration"
  const val FLAVORS = "wff/flavors"
  const val FLAVOR = "wff/flavor"
  const val FLAVOR_CONFIGURATION = "wff/flavor-configuration"

  /**
   * The configuration-driven containers of the `<Scene>`: a `BooleanConfiguration` or
   * `ListConfiguration` holding one `BooleanOption` / `ListOption` per value, each holding the
   * parts shown while that option is selected. They share their tag with the declarations under
   * `<UserConfigurations>` ([BOOLEAN_CONFIGURATION], [LIST_CONFIGURATION]) and are told apart by
   * where they sit.
   */
  const val SCENE_BOOLEAN_CONFIGURATION = "wff/scene-boolean-configuration"
  const val SCENE_BOOLEAN_OPTION = "wff/scene-boolean-option"
  const val SCENE_LIST_CONFIGURATION = "wff/scene-list-configuration"
  const val SCENE_LIST_OPTION = "wff/scene-list-option"

  /**
   * An element this vocabulary has no builtin for (or cannot write exactly), held as its canonical
   * XML in the `xml` property and written back verbatim at the same position. A comment is held the
   * same way.
   */
  const val RAW = "wff/raw"

  const val DEFAULT_WFF_VERSION = 2
  val WFF_VERSIONS: IntRange = 1..5
  const val DEFAULT_SIZE = 450

  /** What a host may state about one export. */
  data class Options(
    /** Overrides the scene's `wffVersion` when set. */
    val wffVersion: Int? = null,
    /**
     * Skip the checks that judge the face rather than write it: [WatchFaceDesignValidation] and the
     * format schema. The importer asks for this, so that any XML it reads, invalid or not, is held
     * and written back as it was; an export never does.
     */
    internal val lenient: Boolean = false,
  )

  sealed interface Result {
    /**
     * [notes] are informational: what the export adjusted to write valid XML (a fractional integer
     * attribute rounded), never a reason to refuse.
     */
    data class Emitted(
      val xml: String,
      val wffVersion: Int,
      val notes: List<String> = emptyList(),
    ) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /** Refuses wire-only semantics WFF cannot carry, as the Lovelace projection does. */
  fun export(document: DesignDocumentV1, options: Options = Options()): Result {
    val unsupported = buildList {
      if (document.tokenBindings.isNotEmpty())
        add("tokenBindings: Watch Face Format has no design token bindings")
      document.nodes.forEach { (id, node) ->
        if (node.predicate != null) add("nodes.$id.predicate: use a wff/variant instead")
        if (node.accessibility != null)
          add("nodes.$id.accessibility: Watch Face Format has no accessibility metadata here")
        if (node.assetBindings.isNotEmpty())
          add("nodes.$id.assetBindings: name a bitmap `resource` instead")
        if (node.tokenBindings.isNotEmpty())
          add("nodes.$id.tokenBindings: Watch Face Format has no design token bindings")
      }
    }
    return if (unsupported.isNotEmpty()) Result.Refused(unsupported)
    else export(document.toUiBuilderDocument(), options)
  }

  fun export(document: UiBuilderDocument, options: Options = Options()): Result =
    when (val built = build(document, options)) {
      is Built.Tree ->
        Result.Emitted(WffXmlWriter.write(built.document), built.wffVersion, built.notes)
      is Built.Refused -> Result.Refused(built.reasons)
    }

  /** The XML tree an export writes, before it is written: what [WatchFaceXmlImporter] compares. */
  internal sealed interface Built {
    data class Tree(
      val document: WffDocument,
      val wffVersion: Int,
      val notes: List<String> = emptyList(),
    ) : Built

    data class Refused(val reasons: List<String>) : Built
  }

  /**
   * The scene's slots besides `content`: the comments around the root (`prolog`, `epilog`) and the
   * `<WatchFace>` children before (`face`) and after (`faceAfter`) the `<Scene>`, which only [RAW]
   * nodes may fill.
   */
  internal const val PROLOG = "prolog"
  internal const val FACE = "face"
  internal const val FACE_AFTER = "faceAfter"
  internal const val EPILOG = "epilog"
  private val FRAME_SLOTS = listOf(PROLOG, FACE, FACE_AFTER, EPILOG)

  /** The slots of [component], in the order they are written, with what each accepts. */
  internal fun slotsOf(component: String): Map<String, Set<String>> =
    ELEMENTS[component]?.slots.orEmpty()

  internal fun build(document: UiBuilderDocument, options: Options = Options()): Built {
    val reasons = mutableListOf<String>()
    fun refuse(id: String, component: String?, why: String) {
      reasons += "nodes.$id${component?.let { " (`$it`)" }.orEmpty()}: $why"
    }
    if (document.stateVariables.isNotEmpty())
      reasons += "stateVariables: Watch Face Format values come from the watch, not design state"
    if (document.roots.size != 1) {
      return Built.Refused(reasons + "roots: a watch face requires exactly one `$SCENE` root")
    }
    val rootId = document.roots.single()
    val root =
      document.nodes[rootId] ?: return Built.Refused(reasons + "roots: unknown node $rootId")
    if (root.componentId != SCENE) {
      refuse(rootId, root.componentId, "the root of a watch face must be `$SCENE`")
      return Built.Refused(reasons)
    }

    val scene = Props(root, reasons)
    val placedNodes = mutableListOf(root)
    if (!options.lenient)
      WatchFaceDesignValidation.nodeIssues(root).forEach { refuse(rootId, SCENE, it.second) }
    val width = scene.int("width") ?: DEFAULT_SIZE
    val height = scene.int("height") ?: DEFAULT_SIZE
    if (width <= 0 || height <= 0) refuse(rootId, SCENE, "width and height must be positive")
    val version = options.wffVersion ?: scene.int("wffVersion") ?: DEFAULT_WFF_VERSION
    if (version !in WFF_VERSIONS)
      refuse(rootId, SCENE, "wffVersion $version is not supported; use ${WFF_VERSIONS}")
    val verbatim = scene.bool("verbatim") == true

    val seen = mutableSetOf<String>()
    fun element(id: String, parent: String, frame: Pair<Int, Int> = width to height): WffNode? {
      val node = document.nodes[id]
      if (node == null) {
        refuse(parent, null, "slot names the missing node `$id`")
        return null
      }
      if (!seen.add(id)) {
        refuse(id, node.componentId, "is placed more than once")
        return null
      }
      val component = node.componentId
      if (component == RAW) return rawNode(id, node, reasons)
      placedNodes += node
      if (!options.lenient && component in ELEMENTS)
        WatchFaceDesignValidation.nodeIssues(node).forEach { refuse(id, component, it.second) }
      if (!component.startsWith(PREFIX) || component !in ELEMENTS) {
        refuse(id, component, "is not a Watch Face Format element; this export cannot write it")
        return null
      }
      if (node.modifiers.isNotEmpty())
        refuse(id, component, "modifiers: Watch Face Format has no modifiers; use properties")
      if (node.eventBindings.isNotEmpty())
        refuse(id, component, "eventBindings: Watch Face Format has no event bindings")
      if (node.component != null)
        refuse(id, component, "component instances are not supported; write the elements out")
      val spec = ELEMENTS.getValue(component)
      node.slots.keys
        .filter { it !in spec.slots }
        .forEach { refuse(id, component, "slot `$it` is not part of this element") }
      val props = Props(node, reasons)
      props.parentWidth = frame.first
      props.parentHeight = frame.second
      // What this element's own children sit in: its box, else the one it sits in.
      val box =
        (props.number("width")?.toDoubleOrNull()?.toInt() ?: frame.first) to
          (props.number("height")?.toDoubleOrNull()?.toInt() ?: frame.second)
      val children = mutableListOf<WffNode>()
      for ((slot, accepted) in spec.slots) {
        for (childId in node.slots[slot].orEmpty()) {
          val childComponent = document.nodes[childId]?.componentId
          if (childComponent != null && childComponent in ELEMENTS && childComponent !in accepted) {
            refuse(childId, childComponent, "cannot be placed in `$component` slot `$slot`")
            continue
          }
          element(childId, id, box)?.let(children::add)
        }
      }
      return spec
        .write(id, props, children, width, height) { why -> refuse(id, component, why) }
        ?.let { props.arrange(it, ATTR_ORDER, "") }
    }

    val sceneChildren =
      root.slots["content"].orEmpty().mapNotNull { child ->
        val childComponent = document.nodes[child]?.componentId
        if (
          childComponent != null && childComponent in ELEMENTS && childComponent !in SCENE_CONTENT
        ) {
          refuse(child, childComponent, "cannot be placed in `$SCENE`")
          null
        } else element(child, rootId)
      }
    fun frame(slot: String, allowed: Set<String> = setOf(RAW)): List<WffNode> =
      root.slots[slot].orEmpty().mapNotNull { child ->
        val childComponent = document.nodes[child]?.componentId
        if (childComponent != null && childComponent !in allowed) {
          refuse(
            child,
            childComponent,
            "only ${allowed.sorted().joinToString(" and ") { "`$it`" }} nodes can be placed in " +
              "`$SCENE` slot `$slot`",
          )
          null
        } else element(child, rootId)
      }
    val faceBefore = frame(FACE, FACE_CONTENT)
    val faceAfter = frame(FACE_AFTER, FACE_CONTENT)
    val prolog = frame(PROLOG)
    val epilog = frame(EPILOG)
    (prolog + epilog)
      .filter { it !is WffComment }
      .forEach { refuse(rootId, SCENE, "slots `$PROLOG` and `$EPILOG` hold comments only") }
    root.slots.keys
      .filter { it != "content" && it !in FRAME_SLOTS }
      .forEach { refuse(rootId, SCENE, "slot `$it` is not part of this element") }
    if (root.modifiers.isNotEmpty()) refuse(rootId, SCENE, "modifiers: not supported")
    if (!options.lenient)
      WatchFaceDesignValidation.slotIssues(placedNodes, version).forEach { (id, _, why) ->
        refuse(id, document.nodes[id]?.componentId, why)
      }
    if (reasons.isNotEmpty()) return Built.Refused(reasons)

    val face: WffElement
    val generated: List<WffComment>
    if (verbatim) {
      // An imported face: the frame is held in the document, so nothing is generated around it.
      generated = emptyList()
      val faceAttributes =
        listOfNotNull(
          "width" to (scene.text("width") ?: width.toString()),
          "height" to (scene.text("height") ?: height.toString()),
          scene.text("clipShape")?.let { "clipShape" to it },
        )
      val sceneAttributes =
        listOfNotNull(scene.text("backgroundColor")?.let { "backgroundColor" to it })
      face =
        scene.arrange(
          el(
            "WatchFace",
            faceAttributes,
            withMetadata(faceBefore, scene) +
              sceneElement(scene, sceneAttributes, sceneChildren) +
              faceAfter,
          ),
          FACE_ATTR_ORDER,
          "face:",
          mapOf("width" to "width", "height" to "height", "clipShape" to "clipShape"),
        )
    } else {
      val clipShape = scene.string("clipShape") ?: "CIRCLE"
      if (clipShape !in setOf("CIRCLE", "RECTANGLE", "NONE"))
        return Built.Refused(listOf("nodes.$rootId (`$SCENE`): unknown clipShape `$clipShape`"))
      val clockType =
        scene.string("clockType")
          ?: if (hasDescendant(document, root, ANALOG_CLOCK)) "ANALOG" else "DIGITAL"
      val metadata = buildList {
        add(el("Metadata", listOf("key" to "CLOCK_TYPE", "value" to clockType)))
        scene.string("previewTime")?.let {
          add(el("Metadata", listOf("key" to "PREVIEW_TIME", "value" to it)))
        }
      }
      val sceneAttributes = buildList {
        scene.string("backgroundColor")?.let { add("backgroundColor" to it) }
      }
      generated =
        listOf(
          WffComment(
            " Watch Face Format version $version: declare the same value in the manifest " +
              "(com.google.wear.watchface.format.version). ",
          ),
        )
      face =
        el(
          "WatchFace",
          listOf(
            "width" to width.toString(),
            "height" to height.toString(),
            "clipShape" to clipShape,
          ),
          faceBefore + metadata + sceneElement(scene, sceneAttributes, sceneChildren) + faceAfter,
        )
    }
    if (reasons.isNotEmpty()) return Built.Refused(reasons)
    val notes = mutableListOf<String>()
    val built =
      WffDocument(
        // Only the real export: an import's lossless round-trip check (lenient) must see the face
        // as written.
        if (options.lenient) face else WffIntegerGeometry.normalise(face, notes),
        generated + prolog.filterIsInstance<WffComment>(),
        epilog.filterIsInstance<WffComment>(),
      )
    if (!options.lenient) {
      // The face as a whole against the format's element table at this version: an element or
      // attribute newer than the version, a child its parent does not allow, a required attribute
      // missing. Unknown elements and attributes are passed through, as imports hold them.
      val target = WffVersion.fromNumber(version) ?: WffVersion.V1
      val errors = WffValidator.errors(built, target)
      if (errors.isNotEmpty()) return Built.Refused(errors.map { "xml ${it.path}: ${it.message}" })
    }
    return Built.Tree(built, version, notes)
  }

  private fun sceneElement(
    scene: Props,
    attributes: List<Pair<String, String>>,
    children: List<WffNode>,
  ): WffElement = scene.arrange(el("Scene", attributes, children), ATTR_ORDER, "")

  /**
   * An imported face keeps its `<Metadata>` as raw nodes; the scene's `clockType` and
   * `previewTime`, when set, are written into the matching `<Metadata key="?">` (its `value`), or
   * added after the last one. A face that never set them is written as it was.
   */
  private fun withMetadata(before: List<WffNode>, scene: Props): List<WffNode> {
    var nodes = before
    for ((key, property) in listOf("CLOCK_TYPE" to "clockType", "PREVIEW_TIME" to "previewTime")) {
      val value = if (scene.has(property)) scene.string(property) else null
      if (value == null) continue
      fun isKey(node: WffNode) =
        node is WffElement && node.name == "Metadata" && node.attr("key") == key
      nodes =
        if (nodes.any(::isKey))
          nodes.map { if (isKey(it)) (it as WffElement).withAttribute("value", value) else it }
        else {
          val last = nodes.indexOfLast { it is WffElement && it.name == "Metadata" }
          nodes.take(last + 1) +
            el("Metadata", listOf("key" to key, "value" to value)) +
            nodes.drop(last + 1)
        }
    }
    return nodes
  }

  /** A [RAW] node's `xml`, parsed back: exactly one element or comment, or a refusal. */
  private fun rawNode(id: String, node: UiBuilderNode, reasons: MutableList<String>): WffNode? {
    val xml = Props(node, reasons).text("xml")
    fun refuse(why: String): WffNode? {
      reasons += "nodes.$id (`$RAW`): $why"
      return null
    }
    if (xml.isNullOrBlank()) return refuse("xml: the element or comment to write is required")
    if (node.slots.isNotEmpty()) return refuse("a raw node has no slots")
    val parsed =
      try {
        WffXmlParser.parse("<r>$xml</r>").root.children
      } catch (e: WffParseException) {
        return refuse("xml is not well formed: ${e.message?.replace("</r>", "the end of the xml")}")
      }
    val single = parsed.singleOrNull()
    return if (single is WffElement || single is WffComment) single
    else refuse("xml must be exactly one element or comment")
  }

  /** The canonical `xml` for a node: what a [RAW] node holds. */
  internal fun rawXml(node: WffNode): String =
    when (node) {
      is WffElement -> WffXmlWriter.write(node)
      is WffComment -> "<!--${node.text}-->"
      is WffText -> error("text is held inside its element")
      is WffCData -> error("text is held inside its element")
    }

  private fun hasDescendant(document: UiBuilderDocument, from: UiBuilderNode, id: String): Boolean {
    val seen = mutableSetOf<String>()
    fun walk(node: UiBuilderNode): Boolean =
      node.componentId == id ||
        node.slots.values.flatten().any { child ->
          seen.add(child) && document.nodes[child]?.let(::walk) == true
        }
    return walk(from)
  }

  // ---- The element table ---------------------------------------------------------------------

  private class ElementSpec(
    /** Slot name to the components it accepts, in write order. */
    val slots: Map<String, Set<String>>,
    val write:
      (
        id: String,
        props: Props,
        children: List<WffNode>,
        sceneWidth: Int,
        sceneHeight: Int,
        refuse: (String) -> Unit,
      ) -> WffElement?,
  )

  private val DRAWABLE =
    setOf(
      GROUP,
      PART_DRAW,
      PART_IMAGE,
      PART_TEXT,
      ANALOG_CLOCK,
      DIGITAL_CLOCK,
      COMPLICATION_SLOT,
      CONDITION,
      SCENE_BOOLEAN_CONFIGURATION,
      SCENE_LIST_CONFIGURATION,
    )
  internal val SCENE_CONTENT = DRAWABLE + VARIANT + TRANSFORM

  /** What may sit among the `<WatchFace>` children around the `<Scene>`, besides raw XML. */
  internal val FACE_CONTENT = setOf(RAW, USER_CONFIGURATIONS)

  /** What sits beside an element's own parts: an override, or a tap target. */
  private val OVERRIDES = setOf(VARIANT, LAUNCH)
  private val WITH_VARIANTS = mapOf("variants" to OVERRIDES)

  /** What moves a part: a transform and the wrist's tilt. Clocks and their hands take neither. */
  private val MOTION = setOf(TRANSFORM, GYRO)

  /** The overrides of a part that can move: a `PartText`'s or `PartImage`'s. */
  private val PART_OVERRIDES = mapOf("variants" to OVERRIDES + MOTION)

  /**
   * A `PartDraw`'s children in document order: its shapes, and the overrides between them. The
   * legacy `variants` slot is kept for designs written before shapes were nodes.
   */
  private val PART_DRAW_SLOTS =
    mapOf("content" to setOf(SHAPE) + OVERRIDES + MOTION, "variants" to OVERRIDES + MOTION)

  /** Whatever a leaf-like element holds besides its own attributes: only raw XML. */
  private val RAW_ONLY = mapOf("content" to emptySet<String>())

  /** What a `<Compare>`, `<Default>` or `<Complication>` holds: the drawable parts. */
  private val BRANCH_CONTENT = DRAWABLE + OVERRIDES

  /**
   * An element's attributes in the order the face wrote them, when the document says so: a
   * space-separated list of attribute names in [ATTR_ORDER] (and, for the elements this export
   * synthesises inside a part, `<name>AttrOrder`). It is authoritative: an attribute it does not
   * list is not written, so a face that omits `x` stays without one. A listed attribute with no
   * computed value is read from the property of the same name, prefixed for the inner elements
   * (`font:weight`), which is how attributes this vocabulary has no property for survive.
   */
  internal const val ATTR_ORDER = "wffAttrOrder"
  internal const val EXTRAS = "wffExtras"

  /** Where an element's own synthesised part sits among its slot children (see `withCore`). */
  internal const val CORE_AT = "coreAt"
  internal const val SHAPE_CHILD_ORDER = "shapeChildOrder"
  internal const val TEMPLATE_LAYOUT = "templateLayout"
  internal const val SLOT_CHILD_ORDER = "slotChildOrder"
  internal const val FACE_ATTR_ORDER = "faceAttrOrder"
  private val FONT_SOURCES = mapOf("family" to "family", "size" to "fontSize", "color" to "color")

  private fun Props.bounds(sceneWidth: Int, sceneHeight: Int): List<Pair<String, String>> =
    listOfNotNull(
      "x" to (number("x") ?: "0"),
      "y" to (number("y") ?: "0"),
      "width" to (number("width") ?: sceneWidth.toString()),
      "height" to (number("height") ?: sceneHeight.toString()),
      number("pivotX")?.let { "pivotX" to it },
      number("pivotY")?.let { "pivotY" to it },
      number("angle")?.let { "angle" to it },
      number("alpha")?.let { "alpha" to it },
    )

  private val CONFIGURATION_ATTRIBUTES =
    arrayOf("id", "displayName", "screenReaderText", "icon", "defaultValue", "highlight")

  /** The first-class shape attributes of each `PartDraw` shape element, attribute to property. */
  private val SHAPE_PROPERTIES: Map<String, Map<String, String>> =
    mapOf(
      "round-rectangle" to
        mapOf("cornerRadiusX" to "cornerRadiusX", "cornerRadiusY" to "cornerRadiusY"),
      "arc" to
        mapOf(
          "centerX" to "centerX",
          "centerY" to "centerY",
          "width" to "shapeWidth",
          "height" to "shapeHeight",
          "startAngle" to "startAngle",
          "endAngle" to "endAngle",
          "direction" to "direction",
        ),
      "line" to
        mapOf("startX" to "startX", "startY" to "startY", "endX" to "endX", "endY" to "endY"),
    )

  /** The attributes a `wff/shape` property may stand for, per shape kind. */
  private val SHAPE_NODE_ATTRIBUTES: Map<String, Set<String>> =
    mapOf(
      "ellipse" to setOf("x", "y", "width", "height"),
      "rectangle" to setOf("x", "y", "width", "height"),
      "round-rectangle" to setOf("x", "y", "width", "height", "cornerRadiusX", "cornerRadiusY"),
      "arc" to
        setOf(
          "centerX",
          "centerY",
          "width",
          "height",
          "startAngle",
          "endAngle",
          "direction",
        ),
      "line" to setOf("startX", "startY", "endX", "endY"),
    )

  /**
   * The properties of [node] that do not apply to the shape it currently has: a `round-rectangle`'s
   * corner radii on an `ellipse`, an `arc`'s angles on a `line`. The editor hides them, and the
   * export leaves them out rather than refusing the design.
   */
  fun inapplicableProperties(
    node: UiBuilderNode,
    document: UiBuilderDocument? = null,
  ): Set<String> {
    val props = Props(node, mutableListOf())
    return when (node.componentId) {
      PART_DRAW -> {
        // Shapes held as `wff/shape` children are the part's drawing, and the `shape` property
        // then means none (see the part's writer).
        val hasShapes =
          document != null &&
            node.slots.values.flatten().any { document.nodes[it]?.componentId == SHAPE }
        val shape = if (hasShapes) "none" else props.string("shape") ?: "ellipse"
        // Colours and a stroke width style a shape; a part with none has nothing to style.
        if (shape == "none")
          SHAPE_PROPERTIES.values.flatMap { it.values }.toSet() +
            setOf("strokeColor", "fillColor", "strokeWidth")
        else if (shape !in SHAPE_TAGS) emptySet()
        else {
          val own = SHAPE_PROPERTIES[shape].orEmpty().values.toSet()
          SHAPE_PROPERTIES.values.flatMap { it.values }.filter { it !in own }.toSet()
        }
      }
      SHAPE -> {
        val kind = props.string("shape")
        val own = SHAPE_NODE_ATTRIBUTES[kind] ?: return emptySet()
        SHAPE_NODE_ATTRIBUTES.values.flatten().filter { it !in own }.toSet()
      }
      else -> emptySet()
    }
  }

  internal val SHAPE_TAGS: Map<String, String> =
    mapOf(
      "ellipse" to "Ellipse",
      "rectangle" to "Rectangle",
      "round-rectangle" to "RoundRectangle",
      "arc" to "Arc",
      "line" to "Line",
    )

  /**
   * The slot children of an element with the element's own [core] parts written at the position the
   * document records ([CORE_AT], the first by default): a face may put a `<Variant>` before the
   * `<Text>`.
   */
  private fun Props.withCore(core: List<WffNode>, children: List<WffNode>): List<WffNode> {
    val at = (number(CORE_AT)?.toDoubleOrNull()?.toInt() ?: 0).coerceIn(0, children.size)
    return children.take(at) + core + children.drop(at)
  }

  /**
   * The `<Template>` of a part's text: the `text` property, then one `<Parameter>` per line of
   * `parameters`. An imported face's other nodes (CDATA, whitespace between parameters) are kept as
   * a layout in [TEMPLATE_LAYOUT]: a JSON array with `m` for the text, `M` for the text as CDATA,
   * `p` for the next parameter, and `t?` / `c?` for a literal text or CDATA node.
   */
  private fun template(props: Props): WffElement {
    val text = props.string("text")
    val parameters =
      props.string("parameters")?.split('\n').orEmpty().map {
        el("Parameter", listOf("expression" to it))
      }
    val layout =
      props.string(TEMPLATE_LAYOUT)?.let {
        try {
          Json.parseToJsonElement(it).let { array ->
            (array as? kotlinx.serialization.json.JsonArray)?.map { item ->
              (item as JsonPrimitive).content
            }
          }
        } catch (e: Exception) {
          null
        }
      }
    if (layout == null)
      return WffElement("Template", emptyList(), listOfNotNull(text?.let(::WffText)) + parameters)
    val remaining = parameters.toMutableList()
    val children = buildList {
      for (item in layout) {
        when {
          item == "m" -> text?.let { add(WffText(it)) }
          item == "M" -> text?.let { add(WffCData(it)) }
          item == "p" -> remaining.removeFirstOrNull()?.let(::add)
          item.startsWith("t") -> add(WffText(item.drop(1)))
          item.startsWith("c") -> add(WffCData(item.drop(1)))
        }
      }
      addAll(remaining)
    }
    return WffElement("Template", emptyList(), children)
  }

  /** An element whose attributes are its properties of the same name, in [names] order. */
  private fun simple(
    tag: String,
    slots: Map<String, Set<String>>,
    vararg names: String,
  ): ElementSpec =
    ElementSpec(slots) { _, props, children, _, _, _ ->
      el(tag, names.mapNotNull { name -> props.string(name)?.let { name to it } }, children)
    }

  private val ELEMENTS: Map<String, ElementSpec> =
    mapOf(
      GROUP to
        ElementSpec(mapOf("content" to SCENE_CONTENT + GYRO, "variants" to OVERRIDES + MOTION)) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          el(
            "Group",
            listOfNotNull(props.string("name")?.let { "name" to it }) + props.bounds(w, h),
            children,
          )
        },
      PART_DRAW to
        ElementSpec(PART_DRAW_SLOTS) { _, props, children, w, h, refuse ->
          // Shapes held as `wff/shape` children are the part's drawing; the `shape` property is the
          // legacy way to write one inline, and the two do not mix.
          val shapeTags = SHAPE_TAGS.values.toSet()
          val hasShapes = children.any { it is WffElement && it.name in shapeTags }
          val shape = props.string("shape") ?: if (hasShapes) "none" else "ellipse"
          if (hasShapes && shape != "none")
            return@ElementSpec refuse(
                "shape `$shape`: this part holds `$SHAPE` children; set shape to none, or delete them",
              )
              .let { null }
          if (shape != "none" && shape !in SHAPE_TAGS)
            return@ElementSpec refuse(
                "shape `$shape` is not ${(SHAPE_TAGS.keys + "none").joinToString(", ")}",
              )
              .let { null }
          // Leftover properties that do not apply (a stroke width with no stroke colour, colours on
          // a part with no shape, another shape's attributes) are dropped, not refused: changing a
          // part's shape in the editor leaves them behind, and nothing is lost by omitting them.
          val bounds = props.bounds(w, h)
          val stroke =
            props
              .string("strokeColor")
              ?.takeIf { shape != "none" }
              ?.let {
                props.arrange(
                  el(
                    "Stroke",
                    listOf("color" to it, "thickness" to (props.number("strokeWidth") ?: "1")),
                  ),
                  "strokeAttrOrder",
                  "stroke:",
                  mapOf("color" to "strokeColor", "thickness" to "strokeWidth"),
                )
              }
          val fill =
            props
              .string("fillColor")
              ?.takeIf { shape != "none" }
              ?.let {
                props.arrange(
                  el("Fill", listOf("color" to it)),
                  "fillAttrOrder",
                  "fill:",
                  mapOf("color" to "fillColor"),
                )
              }
          val order = (props.string(SHAPE_CHILD_ORDER) ?: "stroke fill").split(' ')
          val built = mapOf("stroke" to stroke, "fill" to fill)
          val shapeChildren =
            order.mapNotNull { built[it] } +
              built.filterKeys { it !in order }.values.filterNotNull()
          val sources = SHAPE_PROPERTIES[shape].orEmpty()
          val own =
            when (shape) {
              "ellipse",
              "rectangle",
              "round-rectangle" ->
                bounds
                  .filter { it.first in setOf("x", "y", "width", "height") }
                  .map { if (it.first == "x" || it.first == "y") it.first to "0" else it } +
                  sources.mapNotNull { (attribute, property) ->
                    props.number(property)?.let { attribute to it }
                  }
              else ->
                sources.mapNotNull { (attribute, property) ->
                  (if (attribute == "direction") props.string(property) else props.number(property))
                    ?.let { attribute to it }
                }
            }
          val core =
            if (shape == "none") emptyList()
            else
              listOf(
                props.arrange(
                  el(SHAPE_TAGS.getValue(shape), own, shapeChildren),
                  "shapeAttrOrder",
                  "shape:",
                  sources,
                ),
              )
          el("PartDraw", bounds, props.withCore(core, children))
        },
      SHAPE to
        ElementSpec(mapOf("content" to setOf(FILL, STROKE))) { _, props, children, w, h, refuse ->
          val kind = props.string("shape")
          if (kind == null || kind !in SHAPE_TAGS) {
            refuse("shape `${kind.orEmpty()}` is not ${SHAPE_TAGS.keys.joinToString(", ")}")
            return@ElementSpec null
          }
          fun number(name: String) = props.number(name)?.let { name to it }
          val own =
            when (kind) {
              "ellipse",
              "rectangle",
              "round-rectangle" ->
                listOf(
                  "x" to (props.number("x") ?: "0"),
                  "y" to (props.number("y") ?: "0"),
                  "width" to (props.number("width") ?: (props.parentWidth ?: w).toString()),
                  "height" to (props.number("height") ?: (props.parentHeight ?: h).toString()),
                ) +
                  if (kind == "round-rectangle")
                    listOfNotNull(number("cornerRadiusX"), number("cornerRadiusY"))
                  else emptyList()
              "arc" ->
                listOfNotNull(
                  number("centerX"),
                  number("centerY"),
                  number("width"),
                  number("height"),
                  number("startAngle"),
                  number("endAngle"),
                  props.string("direction")?.let { "direction" to it },
                )
              else ->
                listOfNotNull(
                  number("startX"),
                  number("startY"),
                  number("endX"),
                  number("endY"),
                )
            }
          el(SHAPE_TAGS.getValue(kind), own, children)
        },
      FILL to simple("Fill", RAW_ONLY, "color"),
      STROKE to
        ElementSpec(RAW_ONLY) { _, props, children, _, _, _ ->
          val color = props.string("color")
          el(
            "Stroke",
            listOfNotNull(
              color?.let { "color" to it },
              (props.number("thickness") ?: "1".takeIf { color != null })?.let {
                "thickness" to it
              },
            ),
            children,
          )
        },
      USER_CONFIGURATIONS to
        simple(
          "UserConfigurations",
          mapOf(
            "content" to
              setOf(COLOR_CONFIGURATION, LIST_CONFIGURATION, BOOLEAN_CONFIGURATION, FLAVORS),
          ),
        ),
      COLOR_CONFIGURATION to
        simple(
          "ColorConfiguration",
          mapOf("options" to setOf(COLOR_OPTION)),
          *CONFIGURATION_ATTRIBUTES,
        ),
      COLOR_OPTION to
        simple(
          "ColorOption",
          emptyMap(),
          "id",
          "displayName",
          "screenReaderText",
          "icon",
          "colors",
        ),
      LIST_CONFIGURATION to
        simple(
          "ListConfiguration",
          mapOf("options" to setOf(LIST_OPTION)),
          *CONFIGURATION_ATTRIBUTES,
        ),
      LIST_OPTION to
        simple(
          "ListOption",
          emptyMap(),
          "id",
          "displayName",
          "screenReaderText",
          "icon",
          "childSettingIds",
          "complicationSlotIds",
        ),
      BOOLEAN_CONFIGURATION to simple("BooleanConfiguration", RAW_ONLY, *CONFIGURATION_ATTRIBUTES),
      SCENE_BOOLEAN_CONFIGURATION to
        simple("BooleanConfiguration", mapOf("options" to setOf(SCENE_BOOLEAN_OPTION)), "id"),
      SCENE_BOOLEAN_OPTION to simple("BooleanOption", mapOf("content" to DRAWABLE), "id"),
      SCENE_LIST_CONFIGURATION to
        simple("ListConfiguration", mapOf("options" to setOf(SCENE_LIST_OPTION)), "id"),
      SCENE_LIST_OPTION to simple("ListOption", mapOf("content" to DRAWABLE), "id"),
      FLAVORS to simple("Flavors", mapOf("content" to setOf(FLAVOR)), "defaultValue"),
      FLAVOR to
        simple(
          "Flavor",
          mapOf("content" to setOf(FLAVOR_CONFIGURATION)),
          "id",
          "displayName",
          "screenReaderText",
          "icon",
        ),
      FLAVOR_CONFIGURATION to
        simple("Configuration", emptyMap(), "id", "optionId", "screenReaderText"),
      PART_IMAGE to
        ElementSpec(PART_OVERRIDES) { _, props, children, w, h, refuse ->
          val resource = props.string("resource")
          if (resource.isNullOrBlank()) {
            refuse("resource: name the bitmap resource this image draws")
            return@ElementSpec null
          }
          // `tintColor` is an attribute of PartImage; `Image` has only `resource`.
          el(
            "PartImage",
            props.bounds(w, h) +
              listOfNotNull(props.string("tintColor")?.let { "tintColor" to it }),
            props.withCore(
              listOf(
                props.arrange(
                  el("Image", listOf("resource" to resource)),
                  "imageAttrOrder",
                  "image:",
                  mapOf("resource" to "resource"),
                ),
              ),
              children,
            ),
          )
        },
      PART_TEXT to
        ElementSpec(PART_OVERRIDES) { _, props, children, w, h, _ ->
          val template = template(props)
          val font =
            props.arrange(
              el(
                "Font",
                listOf(
                  "family" to (props.string("family") ?: "SYNC_TO_DEVICE"),
                  "size" to (props.number("fontSize") ?: "32"),
                  "color" to (props.string("color") ?: "#ffffffff"),
                ),
                listOf(template),
              ),
              "fontAttrOrder",
              "font:",
              FONT_SOURCES,
            )
          el(
            "PartText",
            props.bounds(w, h),
            props.withCore(
              listOf(
                props.arrange(
                  el(
                    "Text",
                    listOfNotNull(props.string("align")?.let { "align" to it }),
                    listOf(font),
                  ),
                  "textAttrOrder",
                  "text:",
                  mapOf("align" to "align"),
                ),
              ),
              children,
            ),
          )
        },
      ANALOG_CLOCK to
        ElementSpec(mapOf("hands" to setOf(CLOCK_HAND) + OVERRIDES, "variants" to OVERRIDES)) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          el("AnalogClock", props.bounds(w, h), children)
        },
      CLOCK_HAND to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, refuse ->
          val tag =
            when (val type = props.string("type") ?: "") {
              "hour" -> "HourHand"
              "minute" -> "MinuteHand"
              "second" -> "SecondHand"
              else ->
                return@ElementSpec refuse("type `$type` is not hour, minute or second").let { null }
            }
          val resource = props.string("resource")
          if (resource.isNullOrBlank()) {
            refuse("resource: name the bitmap resource this hand draws")
            return@ElementSpec null
          }
          el(
            tag,
            listOf("resource" to resource) +
              props.bounds(w, h) +
              listOfNotNull(props.string("tintColor")?.let { "tintColor" to it }),
            children,
          )
        },
      DIGITAL_CLOCK to
        ElementSpec(mapOf("texts" to setOf(TIME_TEXT) + OVERRIDES, "variants" to OVERRIDES)) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          el("DigitalClock", props.bounds(w, h), children)
        },
      TIME_TEXT to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, _ ->
          val attributes =
            listOfNotNull(
              "format" to (props.string("format") ?: "hh:mm"),
              "hourFormat" to (props.string("hourFormat") ?: "SYNC_TO_DEVICE"),
              props.string("align")?.let { "align" to it },
            ) +
              // TimeText carries its own geometry (the schema requires it). A new one fills the
              // DigitalClock it sits in; an imported one is written as the face had it.
              listOf("x", "y", "width", "height").mapNotNull { name ->
                (props.number(name)
                    ?: when {
                      props.has(ATTR_ORDER) -> null
                      name == "width" -> (props.parentWidth ?: w).toString()
                      name == "height" -> (props.parentHeight ?: h).toString()
                      else -> "0"
                    })
                  ?.let { name to it }
              }
          val font =
            props.arrange(
              el(
                "Font",
                listOf(
                  "family" to (props.string("family") ?: "SYNC_TO_DEVICE"),
                  "size" to (props.number("fontSize") ?: "48"),
                  "color" to (props.string("color") ?: "#ffffffff"),
                ),
              ),
              "fontAttrOrder",
              "font:",
              FONT_SOURCES,
            )
          el("TimeText", attributes, props.withCore(listOf(font), children))
        },
      COMPLICATION_SLOT to
        ElementSpec(mapOf("variants" to OVERRIDES + COMPLICATION)) {
          _,
          props,
          children,
          w,
          h,
          refuse ->
          val slotId = props.int("slotId")
          val types = props.string("supportedTypes")
          if (slotId == null || slotId < 0) {
            refuse("slotId: a non-negative integer is required")
            return@ElementSpec null
          }
          if (types.isNullOrBlank()) {
            refuse("supportedTypes: name at least one complication type, space separated")
            return@ElementSpec null
          }
          val bounds = props.bounds(w, h)
          val box =
            bounds
              .filter { it.first in setOf("width", "height") }
              .let { listOf("x" to "0", "y" to "0") + it }
          if (props.has("defaultSystemProviderType") && !props.has("defaultSystemProvider"))
            refuse("defaultSystemProviderType: a provider policy needs a defaultSystemProvider")
          val policy =
            props.string("defaultSystemProvider")?.let {
              props.arrange(
                el(
                  "DefaultProviderPolicy",
                  listOf(
                    "defaultSystemProvider" to it,
                    "defaultSystemProviderType" to
                      (props.string("defaultSystemProviderType")
                        ?: types.trim().substringBefore(' ')),
                  ),
                ),
                "policyAttrOrder",
                "policy:",
                mapOf(
                  "defaultSystemProvider" to "defaultSystemProvider",
                  "defaultSystemProviderType" to "defaultSystemProviderType",
                ),
              )
            }
          val boundsTag =
            when (val type = props.string("boundsType") ?: "OVAL") {
              "OVAL" -> "BoundingOval"
              "RECTANGLE" -> "BoundingBox"
              "ROUND_RECTANGLE" -> "BoundingRoundBox"
              else ->
                return@ElementSpec refuse(
                    "boundsType `$type` is not OVAL, RECTANGLE or ROUND_RECTANGLE",
                  )
                  .let { null }
            }
          val boundsElement =
            props.arrange(el(boundsTag, box), "boundsAttrOrder", "bounds:", emptyMap())
          val order = (props.string(SLOT_CHILD_ORDER) ?: "bounds policy").split(' ')
          val built = mapOf("bounds" to boundsElement, "policy" to policy)
          val core =
            order.mapNotNull { built[it] } +
              built.filterKeys { it !in order }.values.filterNotNull()
          el(
            "ComplicationSlot",
            listOf("slotId" to slotId.toString(), "supportedTypes" to types.trim()) + bounds,
            props.withCore(core, children),
          )
        },
      CONDITION to
        ElementSpec(mapOf("branches" to setOf(COMPARE, DEFAULT))) { _, _, children, _, _, _ ->
          el("Condition", emptyList(), children)
        },
      COMPARE to simple("Compare", mapOf("content" to BRANCH_CONTENT), "expression"),
      DEFAULT to simple("Default", mapOf("content" to BRANCH_CONTENT)),
      LAUNCH to simple("Launch", emptyMap(), "target"),
      COMPLICATION to simple("Complication", mapOf("content" to BRANCH_CONTENT), "type"),
      VARIANT to
        ElementSpec(emptyMap()) { _, props, _, _, _, refuse ->
          val mode = props.string("mode") ?: "AMBIENT"
          val target = props.string("target")
          val value = props.string("value") ?: props.number("value")
          if (target.isNullOrBlank() || value == null) {
            refuse("target and value: state the attribute this variant overrides and its value")
            return@ElementSpec null
          }
          el("Variant", listOf("mode" to mode, "target" to target, "value" to value))
        },
      // An `<Animation>` inside a transform has no builtin: it is held raw, in `content`.
      TRANSFORM to
        ElementSpec(RAW_ONLY) { _, props, children, _, _, refuse ->
          val transform =
            transformOf(
              props.string("target"),
              props.string("rotateWith"),
              props.string("value") ?: props.number("value"),
            )
          when (transform) {
            is TransformReading.Refused -> {
              refuse(transform.why)
              null
            }
            is TransformReading.Read ->
              el(
                "Transform",
                listOfNotNull(
                  "target" to transform.target,
                  "value" to transform.value,
                  props.string("mode")?.let { "mode" to it },
                ),
                children,
              )
          }
        },
      GYRO to
        ElementSpec(emptyMap()) { _, props, _, _, _, _ ->
          el("Gyro", GYRO_ATTRIBUTES.mapNotNull { name -> props.number(name)?.let { name to it } })
        },
    )

  /** What a [TRANSFORM] drives and the expression it writes, or why it writes nothing. */
  sealed interface TransformReading {
    data class Read(val target: String, val value: String) : TransformReading

    data class Refused(val why: String) : TransformReading
  }

  /**
   * A [TRANSFORM]'s `target` and written `value` from its properties: a `rotateWith` preset drives
   * `angle` (the default target) with the preset's expression, else the node's own `value`. Shared
   * with the canvas, so that what is drawn is what is written.
   */
  fun transformOf(target: String?, rotateWith: String?, value: String?): TransformReading {
    val preset =
      rotateWith
        ?.takeIf { it.isNotEmpty() && it != ROTATE_WITH_CUSTOM }
        ?.let {
          ROTATE_WITH[it]
            ?: return TransformReading.Refused(
              "rotateWith `$it` is not ${(ROTATE_WITH.keys + ROTATE_WITH_CUSTOM).joinToString(", ")}",
            )
        }
    val written = target?.takeIf { it.isNotEmpty() } ?: if (preset != null) "angle" else null
    if (preset != null && written != "angle")
      return TransformReading.Refused(
        "rotateWith `$rotateWith` drives the angle; set target to angle, or rotateWith to custom",
      )
    val own = value?.takeIf { it.isNotEmpty() }
    if (preset != null && own != null && own != preset)
      return TransformReading.Refused(
        "value: rotateWith `$rotateWith` writes $preset; set rotateWith to custom to write `$own`",
      )
    val expression = preset ?: own
    if (written == null || expression == null)
      return TransformReading.Refused(
        "target and value: state the attribute this transform drives and its expression",
      )
    return TransformReading.Read(written, expression)
  }

  // ---- Property reading and the XML tree -----------------------------------------------------

  /** Literal property reads; a binding or any non-literal value is refused by name. */
  internal class Props(val node: UiBuilderNode, val reasons: MutableList<String>) {
    /** The width and height of the element this one sits in, when it is written inside one. */
    var parentWidth: Int? = null
    var parentHeight: Int? = null

    /** The strings of the node's `wffExtras` property: what has no property of its own. */
    private val extras: Map<String, String> by lazy {
      val encoded =
        ((node.properties[EXTRAS] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
      if (encoded == null) emptyMap()
      else
        try {
          (Json.parseToJsonElement(encoded) as? JsonObject)
            ?.mapNotNull { (key, value) ->
              (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }
            ?.toMap() ?: emptyMap()
        } catch (e: Exception) {
          emptyMap()
        }
    }

    /** A property's encoded value: its own, else the string held in `wffExtras`. */
    private fun encoded(name: String): JsonElement? =
      node.properties[name]
        ?: extras[name]?.let {
          JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(it)))
        }

    fun has(name: String): Boolean = encoded(name) != null

    private fun scalar(name: String): JsonPrimitive? {
      val raw: JsonElement = encoded(name) ?: return null
      val wrapper = raw as? JsonObject
      val type = (wrapper?.get("type") as? JsonPrimitive)?.contentOrNull
      val value = wrapper?.get("value") as? JsonPrimitive
      if (type !in LITERAL_TYPES || value == null) {
        reasons +=
          "nodes.${node.id} (`${node.componentId}`).$name: only literal values can be exported"
        return null
      }
      return value
    }

    private fun typeOf(name: String): String? =
      (((encoded(name) as? JsonObject)?.get("type")) as? JsonPrimitive)?.contentOrNull

    fun string(name: String): String? = scalar(name)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** The literal as written into XML, empty included: numbers as [formatNumber] writes them. */
    fun text(name: String): String? {
      val value = scalar(name) ?: return null
      return if (typeOf(name) == "int" || typeOf(name) == "float")
        value.doubleOrNull?.let(::formatNumber) ?: value.contentOrNull
      else value.contentOrNull
    }

    fun int(name: String): Int? = scalar(name)?.doubleOrNull?.toInt()

    /**
     * A number as WFF writes it. A `string`-typed value is written as it is: that is how a value
     * that is not a canonical number (`2.0`) or not a number at all (`[WEATHER.TEMPERATURE]`)
     * survives.
     */
    fun number(name: String): String? =
      scalar(name)?.let { value ->
        if (typeOf(name) == "string") value.contentOrNull?.takeIf { it.isNotEmpty() }
        else value.doubleOrNull?.let(::formatNumber)
      }

    fun bool(name: String): Boolean? = scalar(name)?.booleanOrNull

    /**
     * Applies the document's attribute order to [element]; see [ATTR_ORDER]. An attribute the order
     * does not list is still written, after the listed ones, when the property it is computed from
     * is set: [sources] names that property per attribute (the attribute's own name on an element
     * with no [prefix]). That keeps a property set after import.
     */
    fun arrange(
      element: WffElement,
      orderProperty: String,
      prefix: String,
      sources: Map<String, String> = emptyMap(),
    ): WffElement {
      if (!has(orderProperty)) return element
      val order = text(orderProperty).orEmpty().split(' ').filter { it.isNotEmpty() }
      val computed = element.attributes.associate { it.name to it.value }
      val listed = order.mapNotNull { name ->
        // A value kept under its prefixed name overrides the computed one: it is how an attribute
        // that differs from what the part would write (an inner box of another size) survives.
        val own = if (prefix.isNotEmpty() && has(prefix + name)) text(prefix + name) else null
        (own ?: computed[name] ?: text(prefix + name))?.let { WffAttribute(name, it) }
      }
      val added =
        element.attributes.filter { attribute ->
          attribute.name !in order &&
            (sources[attribute.name] ?: attribute.name.takeIf { prefix.isEmpty() })?.let(::has) ==
              true
        }
      return element.copy(attributes = listed + added)
    }
  }

  private val LITERAL_TYPES = setOf("string", "enum", "int", "float", "bool", "color")

  internal fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  /** An element with attributes, children and, for a text-only element, [text]. */
  private fun el(
    tag: String,
    attributes: List<Pair<String, String>> = emptyList(),
    children: List<WffNode> = emptyList(),
    text: String? = null,
  ): WffElement =
    WffElement(
      tag,
      attributes.map { WffAttribute(it.first, it.second) },
      if (text.isNullOrEmpty()) children else children + WffText(text),
    )
}
