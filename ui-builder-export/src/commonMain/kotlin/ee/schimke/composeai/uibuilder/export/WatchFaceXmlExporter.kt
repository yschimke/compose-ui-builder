package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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
 * The vocabulary mirrors WFF elements one to one (see the `wff/…` constants), with the WFF
 * attribute names as property names, positioned absolutely in pixels of the face's `width` ×
 * `height` (the [SCENE] node's properties, 450 × 450 unless stated).
 *
 * The WFF version (1–4, [DEFAULT_WFF_VERSION] = 2) is the `wffVersion` property of the [SCENE]
 * node, or [Options.wffVersion] when the host states one. It is a node property, not an
 * `environment` entry, because `DesignEnvironmentV1` (compose-preview-contracts) is a closed type
 * that drops unknown keys on the wire.
 *
 * Bitmap resources (`resource="…"`) are named, not bundled: this writes the XML only. Packaging the
 * drawables (and the DWF resource bundle) is the host's job.
 */
// TODO: replace with ee.schimke.watchface:wff-core once published (model + XML writer + validator).
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

  const val DEFAULT_WFF_VERSION = 2
  val WFF_VERSIONS: IntRange = 1..4
  const val DEFAULT_SIZE = 450

  /** What a host may state about one export. */
  data class Options(
    /** Overrides the scene's `wffVersion` when set. */
    val wffVersion: Int? = null,
  )

  sealed interface Result {
    data class Emitted(val xml: String, val wffVersion: Int) : Result

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

  fun export(document: UiBuilderDocument, options: Options = Options()): Result {
    val reasons = mutableListOf<String>()
    fun refuse(id: String, component: String?, why: String) {
      reasons += "nodes.$id${component?.let { " (`$it`)" }.orEmpty()}: $why"
    }
    if (document.stateVariables.isNotEmpty())
      reasons += "stateVariables: Watch Face Format values come from the watch, not design state"
    if (document.roots.size != 1) {
      return Result.Refused(reasons + "roots: a watch face requires exactly one `$SCENE` root")
    }
    val rootId = document.roots.single()
    val root =
      document.nodes[rootId] ?: return Result.Refused(reasons + "roots: unknown node $rootId")
    if (root.componentId != SCENE) {
      refuse(rootId, root.componentId, "the root of a watch face must be `$SCENE`")
      return Result.Refused(reasons)
    }

    val scene = Props(root, reasons)
    val width = scene.int("width") ?: DEFAULT_SIZE
    val height = scene.int("height") ?: DEFAULT_SIZE
    if (width <= 0 || height <= 0) refuse(rootId, SCENE, "width and height must be positive")
    val version = options.wffVersion ?: scene.int("wffVersion") ?: DEFAULT_WFF_VERSION
    if (version !in WFF_VERSIONS)
      refuse(rootId, SCENE, "wffVersion $version is not supported; use ${WFF_VERSIONS}")

    val seen = mutableSetOf<String>()
    fun element(id: String, parent: String): Xml? {
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
      val children = mutableListOf<Xml>()
      for ((slot, accepted) in spec.slots) {
        for (childId in node.slots[slot].orEmpty()) {
          val childComponent = document.nodes[childId]?.componentId
          if (childComponent != null && childComponent in ELEMENTS && childComponent !in accepted) {
            refuse(childId, childComponent, "cannot be placed in `$component` slot `$slot`")
            continue
          }
          element(childId, id)?.let(children::add)
        }
      }
      return spec.write(id, props, children, width, height) { why -> refuse(id, component, why) }
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
    root.slots.keys
      .filter { it != "content" }
      .forEach { refuse(rootId, SCENE, "slot `$it` is not part of this element") }
    if (root.modifiers.isNotEmpty()) refuse(rootId, SCENE, "modifiers: not supported")
    if (reasons.isNotEmpty()) return Result.Refused(reasons)

    val clipShape = scene.string("clipShape") ?: "CIRCLE"
    if (clipShape !in setOf("CIRCLE", "RECTANGLE", "NONE"))
      return Result.Refused(listOf("nodes.$rootId (`$SCENE`): unknown clipShape `$clipShape`"))
    val clockType =
      scene.string("clockType")
        ?: if (hasDescendant(document, root, ANALOG_CLOCK)) "ANALOG" else "DIGITAL"
    val metadata = buildList {
      add(Xml("Metadata", listOf("key" to "CLOCK_TYPE", "value" to clockType)))
      scene.string("previewTime")?.let {
        add(Xml("Metadata", listOf("key" to "PREVIEW_TIME", "value" to it)))
      }
    }
    val sceneAttributes = buildList {
      scene.string("backgroundColor")?.let { add("backgroundColor" to it.lowercase()) }
    }
    val face =
      Xml(
        "WatchFace",
        listOf(
          "width" to width.toString(),
          "height" to height.toString(),
          "clipShape" to clipShape,
        ),
        metadata + Xml("Scene", sceneAttributes, sceneChildren),
      )
    if (reasons.isNotEmpty()) return Result.Refused(reasons)
    val xml =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<!-- Watch Face Format version $version: declare the same value in the manifest " +
        "(com.google.wear.watchface.format.version). -->\n" +
        face.render(0) +
        "\n"
    return Result.Emitted(xml, version)
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
        children: List<Xml>,
        sceneWidth: Int,
        sceneHeight: Int,
        refuse: (String) -> Unit,
      ) -> Xml?,
  )

  private val DRAWABLE =
    setOf(GROUP, PART_DRAW, PART_IMAGE, PART_TEXT, ANALOG_CLOCK, DIGITAL_CLOCK, COMPLICATION_SLOT)
  private val SCENE_CONTENT = DRAWABLE + VARIANT
  private val WITH_VARIANTS = mapOf("variants" to setOf(VARIANT))

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

  private val ELEMENTS: Map<String, ElementSpec> =
    mapOf(
      GROUP to
        ElementSpec(mapOf("content" to SCENE_CONTENT, "variants" to setOf(VARIANT))) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          Xml(
            "Group",
            listOfNotNull(props.string("name")?.let { "name" to it }) + props.bounds(w, h),
            children,
          )
        },
      PART_DRAW to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, refuse ->
          val shape = props.string("shape") ?: "ellipse"
          val tag =
            when (shape) {
              "ellipse" -> "Ellipse"
              "rectangle" -> "Rectangle"
              else ->
                return@ElementSpec refuse("shape `$shape` is not ellipse or rectangle").let { null }
            }
          val shapeChildren = buildList {
            props.string("strokeColor")?.let {
              add(
                Xml(
                  "Stroke",
                  listOf(
                    "color" to it.lowercase(),
                    "thickness" to (props.number("strokeWidth") ?: "1"),
                  ),
                ),
              )
            }
            props.string("fillColor")?.let { add(Xml("Fill", listOf("color" to it.lowercase()))) }
          }
          val bounds = props.bounds(w, h)
          Xml(
            "PartDraw",
            bounds,
            listOf(
              Xml(
                tag,
                bounds
                  .filter { it.first in setOf("x", "y", "width", "height") }
                  .map { if (it.first == "x" || it.first == "y") it.first to "0" else it },
                shapeChildren,
              ),
            ) + children,
          )
        },
      PART_IMAGE to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, refuse ->
          val resource = props.string("resource")
          if (resource.isNullOrBlank()) {
            refuse("resource: name the bitmap resource this image draws")
            return@ElementSpec null
          }
          val tint =
            props.string("tintColor")?.let { listOf("tintColor" to it.lowercase()) }.orEmpty()
          Xml(
            "PartImage",
            props.bounds(w, h),
            listOf(Xml("Image", listOf("resource" to resource) + tint)) + children,
          )
        },
      PART_TEXT to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, _ ->
          val font =
            Xml(
              "Font",
              listOf(
                "family" to (props.string("family") ?: "SYNC_TO_DEVICE"),
                "size" to (props.number("fontSize") ?: "32"),
                "color" to (props.string("color") ?: "#ffffffff").lowercase(),
              ),
              listOf(Xml("Template", text = props.string("text").orEmpty())),
            )
          Xml(
            "PartText",
            props.bounds(w, h),
            listOf(
              Xml(
                "Text",
                listOfNotNull(props.string("align")?.let { "align" to it }),
                listOf(font),
              ),
            ) + children,
          )
        },
      ANALOG_CLOCK to
        ElementSpec(mapOf("hands" to setOf(CLOCK_HAND), "variants" to setOf(VARIANT))) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          Xml("AnalogClock", props.bounds(w, h), children)
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
          Xml(
            tag,
            listOf("resource" to resource) +
              props.bounds(w, h) +
              listOfNotNull(props.string("tintColor")?.let { "tintColor" to it.lowercase() }),
            children,
          )
        },
      DIGITAL_CLOCK to
        ElementSpec(mapOf("texts" to setOf(TIME_TEXT), "variants" to setOf(VARIANT))) {
          _,
          props,
          children,
          w,
          h,
          _ ->
          Xml("DigitalClock", props.bounds(w, h), children)
        },
      TIME_TEXT to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, _ ->
          val attributes =
            listOfNotNull(
              "format" to (props.string("format") ?: "hh:mm"),
              "hourFormat" to (props.string("hourFormat") ?: "SYNC_TO_DEVICE"),
              props.string("align")?.let { "align" to it },
            )
          val font =
            Xml(
              "Font",
              listOf(
                "family" to (props.string("family") ?: "SYNC_TO_DEVICE"),
                "size" to (props.number("fontSize") ?: "48"),
                "color" to (props.string("color") ?: "#ffffffff").lowercase(),
              ),
            )
          // TimeText sits inside its DigitalClock's box; a bare bounds attribute is not WFF.
          Xml("TimeText", attributes, listOf(font) + children)
        },
      COMPLICATION_SLOT to
        ElementSpec(WITH_VARIANTS) { _, props, children, w, h, refuse ->
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
          val policy =
            props.string("defaultSystemProvider")?.let {
              Xml(
                "DefaultProviderPolicy",
                listOf(
                  "defaultSystemProvider" to it,
                  "defaultSystemProviderType" to
                    (props.string("defaultSystemProviderType")
                      ?: types.trim().substringBefore(' ')),
                ),
              )
            }
          Xml(
            "ComplicationSlot",
            listOf("slotId" to slotId.toString(), "supportedTypes" to types.trim()) +
              bounds.filter { it.first in setOf("x", "y", "width", "height") },
            listOfNotNull(
              Xml(
                if (props.string("boundsType") == "RECTANGLE") "BoundingBox" else "BoundingOval",
                box,
              ),
              policy,
            ) + children,
          )
        },
      VARIANT to
        ElementSpec(emptyMap()) { _, props, _, _, _, refuse ->
          val mode = props.string("mode") ?: "AMBIENT"
          val target = props.string("target")
          val value = props.string("value") ?: props.number("value")
          if (target.isNullOrBlank() || value == null) {
            refuse("target and value: state the attribute this variant overrides and its value")
            return@ElementSpec null
          }
          Xml("Variant", listOf("mode" to mode, "target" to target, "value" to value))
        },
    )

  // ---- Property reading and the XML writer ---------------------------------------------------

  /** Literal property reads; a binding or any non-literal value is refused by name. */
  private class Props(val node: UiBuilderNode, val reasons: MutableList<String>) {
    private fun scalar(name: String): JsonPrimitive? {
      val raw: JsonElement = node.properties[name] ?: return null
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

    fun string(name: String): String? = scalar(name)?.contentOrNull?.takeIf { it.isNotEmpty() }

    fun int(name: String): Int? = scalar(name)?.doubleOrNull?.toInt()

    fun number(name: String): String? = scalar(name)?.let { it.doubleOrNull?.let(::formatNumber) }

    @Suppress("unused") fun bool(name: String): Boolean? = scalar(name)?.booleanOrNull
  }

  private val LITERAL_TYPES = setOf("string", "enum", "int", "float", "bool")

  private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  // TODO: replace with ee.schimke.watchface:wff-core once published.
  private class Xml(
    val tag: String,
    val attributes: List<Pair<String, String>> = emptyList(),
    val children: List<Xml> = emptyList(),
    val text: String? = null,
  ) {
    fun render(depth: Int): String {
      val indent = "  ".repeat(depth)
      val open = buildString {
        append(indent).append('<').append(tag)
        attributes.forEach { (key, value) ->
          append(' ').append(key).append("=\"").append(escape(value)).append('"')
        }
      }
      return when {
        text != null -> "$open>${escape(text)}</$tag>"
        children.isEmpty() -> "$open/>"
        else ->
          open + ">\n" + children.joinToString("\n") { it.render(depth + 1) } + "\n$indent</$tag>"
      }
    }

    private fun escape(value: String): String =
      value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
  }
}
