package ee.schimke.composeai.uibuilder.export

import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Remote Compose authoring JSON — what the `remotecompose-json` codec reads, and what the
 * RemoteCompose-Examples corpus is written in — read back into a design the builder can edit.
 *
 * The layout tree becomes `layout/box`, `row` and `column` nodes and `remote-m3/remote-text`; a
 * canvas becomes a `draw/canvas` whose viewport is the document's own units, so its pixel
 * coordinates draw where the codec drew them. Its commands become drawing operations: the paint
 * state the codec carries from command to command is copied onto each operation, a transform or
 * clip wraps the commands after it in a group or clip, and loops and conditionals become
 * `draw/repeat` and `draw/if`. The codec's formulas are translated into the builder's, its named
 * variables inlined where they are read.
 *
 * What the builder has no equivalent for is skipped and reported in [Result.gaps], located by its
 * path in the source, so a document imports as far as it can and the gaps say what is missing.
 */
object RemoteJsonImporter {
  class Result(val document: UiBuilderDocument, val gaps: List<String>)

  /**
   * Lengths are taken 1:1 as dp: the document's units are the design's.
   *
   * @param systemId the catalog the document is pinned to: the one whose layout and drawing
   *   components play Remote Compose, which the caller knows and the importer does not.
   */
  fun import(
    source: JsonObject,
    systemId: String,
    id: String = "imported",
    title: String = "Imported",
  ): Result = Importer(source).run(systemId, id, title)

  private const val ROOT_ID = "root"

  /** `[1 -] touchX() [/ n] [* m]`: a scaled touch position, the corpus's touch expressions. */
  private val TOUCH_EXPRESSION =
    Regex("""(1\s*-\s*)?touch([XY])\(\)\s*(?:/\s*([0-9.]+))?\s*(?:\*\s*([0-9.]+))?""")

  /** The node kinds that can be written keyed, `{"<kind>": {…}}`. */
  private val KEYED_NODES =
    setOf("box", "column", "row", "canvas", "text", "spacer", "flow", "fitBox", "image")

  /** An inlined formula past this length is reported rather than written out. */
  private const val MAX_FORMULA = 4_000

  /** `PathExpression`'s flags: straight segments, and a closed path. */
  private const val PATH_LINEAR = 4
  private const val PATH_LOOP = 1
  private const val PATH_WINDING_MASK = 0x3000000

  /** A path expression's parameter, as its formulas read it. */
  private val PARAMETER = Regex("""\ba\[0]""")

  private class Gap(message: String) : Exception(message)

  /** The paint the codec carries between commands. */
  private data class Paint(
    val color: String = "#FF000000",
    val stroke: Boolean = false,
    val strokeWidth: JsonObject? = null,
    val strokeCap: String? = null,
    val strokeJoin: String? = null,
    val alpha: JsonObject? = null,
    val textSize: JsonObject? = null,
  )

  /**
   * The loop indices in scope, and the loop or condition body being read: a path is built where the
   * player builds it only while every command appending to it is in the body that created it.
   */
  private data class Scope(
    val indices: Set<String> = emptySet(),
    val bodies: List<Int> = listOf(0),
  ) {
    val body: Int
      get() = bodies.last()

    fun inBody(body: Int): Scope = copy(bodies = bodies + body)
  }

  /**
   * A path built by commands — `pathCreate`, then `pathAppendLineTo`s and `pathAppendClose` — as
   * its runs of points so far. A polyline draws it, reading each point's number as [index].
   */
  private class LinePath(val body: Int, val index: String) {
    val runs = mutableListOf<Run>()
    var closed = false
    val size: Int
      get() = runs.sumOf { it.size }
  }

  /**
   * [size] points of a [LinePath] in a row: one appended point, its [literal] value when it has
   * one, or a loop's, whose formulas read the path's index for the loop's.
   */
  private class Run(
    val size: Int,
    val x: String,
    val y: String,
    val literal: Pair<Double, Double>? = null,
  )

  private class Importer(val source: JsonObject) {
    val gaps = mutableListOf<String>()
    /** Every variable defined so far, in document order, as a builder formula. */
    val globals = linkedMapOf<String, String>()
    /** Every path expression defined so far, by its id, as the polyline geometry it draws. */
    val pathExpressions = mutableMapOf<String, Map<String, JsonElement>>()
    /** Every path built by commands so far, by its id. */
    val linePaths = mutableMapOf<String, LinePath>()
    var bodyCount = 0
    val nodes = linkedMapOf<String, UiBuilderNode>()
    val ids = mutableMapOf<String, Int>()
    val header = source["header"] as? JsonObject
    val width = header?.number("width") ?: 360.0
    val height = header?.number("height") ?: 360.0

    fun run(systemId: String, id: String, title: String): Result {
      // A list of nodes, or the one node.
      val roots =
        when (val root = source["root"]) {
          is JsonArray -> root
          is JsonObject -> listOf(root)
          else -> {
            gaps += "root: no layout tree to import"
            emptyList()
          }
        }
      val children = roots.mapIndexedNotNull { index, element -> layout(element, "root[$index]") }
      nodes[ROOT_ID] =
        UiBuilderNode(
          ROOT_ID,
          "layout/box",
          modifiers = JsonArray(listOf(type("fillMaxSize"))),
          slots = mapOf("children" to children),
        )
      val document =
        UiBuilderDocument(
          schema = "compose-ui-builder-document/v1-candidate",
          id = id,
          title = title,
          revision = 0,
          catalogPin =
            JsonObject(
              mapOf(
                "systemId" to JsonPrimitive(systemId),
                "catalogRevision" to JsonPrimitive("candidate"),
                "capabilityDigest" to JsonPrimitive("candidate"),
                "nativeRuntimeId" to JsonPrimitive("candidate"),
              )
            ),
          environment =
            JsonObject(
              mapOf(
                "widthDp" to JsonPrimitive(width),
                "heightDp" to JsonPrimitive(height),
                "density" to JsonPrimitive(1.0),
                "fontScale" to JsonPrimitive(1.0),
              )
            ),
          stateVariables = JsonObject(emptyMap()),
          roots = listOf(ROOT_ID),
          nodes = nodes.toMap(),
        )
      return Result(document, gaps.toList())
    }

    fun nextId(kind: String): String {
      val n = (ids[kind] ?: 0) + 1
      ids[kind] = n
      return "$kind-$n"
    }

    fun add(node: UiBuilderNode): String {
      nodes[node.id] = node
      return node.id
    }

    // ---- Layout --------------------------------------------------------------------------------

    /**
     * A node in either spelling the codec reads: keyed (`{"column": {…}}`, `{"text": {…}}`) or
     * typed (`{"type": "column", …}`).
     */
    fun layout(element: JsonElement, path: String): String? {
      val node = element as? JsonObject ?: return gap("$path: not a layout node")
      val typed = node.string("type")
      val key = node.keys.singleOrNull()?.takeIf { typed == null && it in KEYED_NODES }
      val obj =
        when {
          key == null -> node
          node[key] is JsonObject -> node[key] as JsonObject
          // `{"text": "Hello"}`
          else -> JsonObject(mapOf("value" to node.getValue(key)))
        }
      val at = if (key == null) path else "$path.$key"
      return when (val type = typed ?: key) {
        "box",
        "column",
        "row" -> container(type, obj, at)
        "canvas" -> canvas(obj, at)
        "text" -> text(obj, at)
        // An empty box of the spacer's size: what a spacer is, and remote-m3 has no spacer.
        "spacer" ->
          add(
            UiBuilderNode(
              nextId("spacer"),
              "layout/box",
              modifiers = modifiers(obj["modifiers"], "$path.modifiers"),
            )
          )
        else -> gap("$path: `${type ?: obj.keys.firstOrNull()}` has no builder equivalent")
      }
    }

    fun container(kind: String, obj: JsonObject, path: String): String {
      val id = nextId(kind)
      val children =
        (obj["children"] as? JsonArray).orEmpty().mapIndexedNotNull { index, child ->
          layout(child, "$path.children[$index]")
        }
      val properties = buildMap {
        when (kind) {
          "column" ->
            obj.string("horizontalAlignment")?.let { put("horizontalAlignment", enum(it)) }
          "row" -> obj.string("verticalAlignment")?.let { put("verticalAlignment", enum(it)) }
        }
      }
      return add(
        UiBuilderNode(
          id,
          "layout/$kind",
          properties = JsonObject(properties),
          modifiers = modifiers(obj["modifiers"], "$path.modifiers"),
          slots = mapOf("children" to children),
        )
      )
    }

    fun text(obj: JsonObject, path: String): String? {
      val value =
        obj["value"]
          ?: obj["text"]
          ?: (obj["textFromFloat"] as? JsonObject)?.let {
            JsonObject(mapOf("type" to JsonPrimitive("textFromFloat")) + it)
          }
      val text =
        try {
          if (value is JsonObject && value.string("type") == "textFromFloat")
            parsed(textFromFloat(value, Scope()))
          else textValue(value, Scope())
        } catch (e: Gap) {
          return gap("$path.value: ${e.message}")
        }
      val properties = buildMap {
        put("text", text)
        obj.string("color")?.let(::color)?.let { put("color", it) }
        obj.number("fontSize")?.let { put("fontSize", float(it)) }
      }
      return add(
        UiBuilderNode(
          nextId("text"),
          REMOTE_TEXT_COMPONENT_ID,
          properties = JsonObject(properties),
          modifiers = modifiers(obj["modifiers"], "$path.modifiers"),
        )
      )
    }

    fun modifiers(element: JsonElement?, path: String): JsonArray =
      JsonArray(
        (element as? JsonArray).orEmpty().mapIndexedNotNull { index, modifier ->
          modifier(modifier, "$path[$index]")
        }
      )

    /** `[a, b, …]` as numbers; null when it is not a list of numbers. */
    fun numbers(value: JsonElement?): List<Double>? =
      (value as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }

    /** `[x, y]` or `{"x": …, "y": …}`. */
    fun pair(value: JsonElement?): Pair<Double, Double>? {
      numbers(value)
        ?.takeIf { it.size == 2 }
        ?.let {
          return it[0] to it[1]
        }
      val obj = value as? JsonObject ?: return null
      return (obj.number("x") ?: return null) to (obj.number("y") ?: return null)
    }

    fun modifier(element: JsonElement, path: String): JsonObject? {
      val (name, value) =
        when (element) {
          is JsonPrimitive -> element.content to null
          is JsonObject -> (element.keys.firstOrNull() ?: return null) to element.values.first()
          else -> return gap("$path: not a modifier")
        }
      val number = (value as? JsonPrimitive)?.doubleOrNull
      return when (name) {
        "fillMaxSize",
        "fillMaxWidth",
        "fillMaxHeight" -> type(name)
        "width" -> number?.let { type("width", "widthDp" to JsonPrimitive(it)) }
        "height" -> number?.let { type("height", "heightDp" to JsonPrimitive(it)) }
        "size" ->
          (number?.let { it to it } ?: pair(value))?.let { (w, h) ->
            type("size", "widthDp" to JsonPrimitive(w), "heightDp" to JsonPrimitive(h))
          }
        // One number for every side, [horizontal, vertical], or [start, top, end, bottom].
        "padding" -> {
          val sides = numbers(value)
          val (start, top, end, bottom) =
            when {
              number != null -> listOf(number, number, number, number)
              sides?.size == 2 -> listOf(sides[0], sides[1], sides[0], sides[1])
              sides?.size == 4 -> sides
              else -> null
            } ?: return gap("$path: `padding` as written here has no builder equivalent")
          type(
            "padding",
            "startDp" to JsonPrimitive(start),
            "topDp" to JsonPrimitive(top),
            "endDp" to JsonPrimitive(end),
            "bottomDp" to JsonPrimitive(bottom),
          )
        }
        "offset" ->
          pair(value)?.let { (x, y) ->
            type("offset", "xDp" to JsonPrimitive(x), "yDp" to JsonPrimitive(y))
          }
        "background" ->
          (value as? JsonPrimitive)?.contentOrNull?.let(::color)?.let {
            type("background", "color" to it)
          }
        "weight" -> number?.let { type("weight", "weight" to JsonPrimitive(it)) }
        else -> null
      } ?: gap("$path: the `$name` modifier as written here has no builder equivalent")
    }

    // ---- Canvas --------------------------------------------------------------------------------

    fun canvas(obj: JsonObject, path: String): String {
      val modifierList = modifiers(obj["modifiers"], "$path.modifiers")
      // The commands are in the document's pixels; a viewport of the canvas's stated size, or the
      // document's, draws them there whatever size the canvas ends up.
      // A size, or a width and a height apart, is the canvas's stated extent on that axis.
      fun stated(type: String, field: String) = modifierList.firstNotNullOfOrNull { m ->
        (m as JsonObject).takeIf { it.string("type") == type }?.number(field)
      }
      val viewportWidth = stated("size", "widthDp") ?: stated("width", "widthDp") ?: width
      val viewportHeight = stated("size", "heightDp") ?: stated("height", "heightDp") ?: height
      val ops =
        commands(obj["commands"], "$path.commands", Paint(), Scope(), viewportWidth, viewportHeight)
      return add(
        UiBuilderNode(
          nextId("canvas"),
          UiDrawing.CANVAS,
          properties =
            JsonObject(
              mapOf(
                "viewportWidth" to float(viewportWidth),
                "viewportHeight" to float(viewportHeight),
              )
            ),
          modifiers = modifierList,
          slots = mapOf(UiDrawing.OPS_SLOT to ops),
        )
      )
    }

    /** Context the commands of one canvas share: its viewport, for paths drawn in it. */
    private var viewport = 0.0 to 0.0

    fun commands(
      element: JsonElement?,
      path: String,
      startPaint: Paint,
      startScope: Scope,
      viewportWidth: Double,
      viewportHeight: Double,
    ): List<String> {
      viewport = viewportWidth to viewportHeight
      return commandList(nested((element as? JsonArray).orEmpty()), path, startPaint, startScope)
    }

    /** Flat `save` … `restore` pairs folded into the nested `save {commands}` form. */
    fun nested(list: List<JsonElement>): List<JsonElement> {
      val out = mutableListOf<JsonElement>()
      var i = 0
      while (i < list.size) {
        val command = list[i] as? JsonObject
        val save = command?.get("save")
        if (
          command != null &&
            command.keys.singleOrNull() == "save" &&
            (save as? JsonObject)?.get("commands") == null
        ) {
          var depth = 1
          var j = i + 1
          while (j < list.size && depth > 0) {
            val key = (list[j] as? JsonObject)?.keys?.firstOrNull()
            if (
              key == "save" &&
                ((list[j] as JsonObject)["save"] as? JsonObject)?.get("commands") == null
            )
              depth++
            if (key == "restore") depth--
            j++
          }
          val body = list.subList(i + 1, if (depth == 0) j - 1 else j)
          out +=
            JsonObject(mapOf("save" to JsonObject(mapOf("commands" to JsonArray(nested(body))))))
          i = j
        } else if (command?.keys?.firstOrNull() == "restore") {
          i++
        } else {
          out += list[i]
          i++
        }
      }
      return out
    }

    fun commandList(
      list: List<JsonElement>,
      path: String,
      startPaint: Paint,
      startScope: Scope,
    ): List<String> {
      var paint = startPaint
      var scope = startScope
      val ops = mutableListOf<String>()
      for ((index, element) in list.withIndex()) {
        val at = "$path[$index]"
        val command = element as? JsonObject ?: continue
        val name = command.keys.firstOrNull() ?: continue
        val body = command[name]
        try {
          when (name) {
            "paint" -> paint = paint(paint, body as? JsonObject ?: throw Gap("not a paint"), scope)
            "variable" ->
              scope = variable(body as? JsonObject ?: throw Gap("not a variable"), scope)
            "touchExpression" ->
              touchExpression(body as? JsonObject ?: throw Gap("not a touch expression"))
            "save" ->
              ops += group(emptyMap(), (body as? JsonObject)?.get("commands"), at, paint, scope)
            // A transform or clip holds for the rest of its list: those commands go inside it.
            "rotate",
            "translate",
            "scale" -> {
              val rest = JsonArray(list.drop(index + 1))
              ops +=
                group(
                  transform(name, body as JsonObject, scope),
                  rest,
                  "$path[${index + 1}..]",
                  paint,
                  scope,
                )
              return ops
            }
            "clipRect" -> {
              val rest = list.drop(index + 1)
              ops += clip(body as JsonObject, rest, "$path[${index + 1}..]", paint, scope)
              return ops
            }
            "loop" ->
              if (!appendingLoop(body as JsonObject, scope)) ops += loop(body, at, paint, scope)
            "conditionalOperations" -> ops += conditional(body as JsonObject, at, paint, scope)
            "pathExpression",
            "polarPathExpression" -> pathExpression(name, body as JsonObject, scope)
            "pathCreate",
            "pathAppendLineTo",
            "pathAppendClose" -> linePath(name, body as JsonObject, scope)
            else ->
              ops +=
                draw(name, body, paint, scope) ?: throw Gap("`$name` has no builder equivalent")
          }
        } catch (e: Gap) {
          gaps += "$at: ${e.message}"
        } catch (e: ClassCastException) {
          gaps += "$at: `$name` is not in the shape the importer reads"
        } catch (e: NullPointerException) {
          gaps += "$at: `$name` is not in the shape the importer reads"
        }
      }
      return ops
    }

    fun group(
      properties: Map<String, JsonElement>,
      commands: JsonElement?,
      path: String,
      paint: Paint,
      scope: Scope,
    ): String {
      val id = nextId("group")
      val ops =
        commandList(nested((commands as? JsonArray).orEmpty()), "$path.commands", paint, scope)
      return add(
        UiBuilderNode(
          id,
          UiDrawing.GROUP,
          JsonObject(properties),
          slots = mapOf(UiDrawing.OPS_SLOT to ops),
        )
      )
    }

    fun transform(name: String, body: JsonObject, scope: Scope): Map<String, JsonElement> =
      when (name) {
        "rotate" ->
          buildMap {
            put("rotate", value(body["angle"] ?: body["degrees"], scope))
            // The codec rotates about the origin unless it is given a pivot; the group's default is
            // the canvas's centre, so an absent pivot is written as 0.
            put("pivotXDp", value(body["pivotX"] ?: JsonPrimitive(0), scope))
            put("pivotYDp", value(body["pivotY"] ?: JsonPrimitive(0), scope))
          }
        "translate" ->
          buildMap {
            body["dx"]?.let { put("translateXDp", value(it, scope)) }
            body["dy"]?.let { put("translateYDp", value(it, scope)) }
          }
        else ->
          buildMap {
            val sx = body["sx"] ?: body["scaleX"] ?: body["scale"]
            val sy = body["sy"] ?: body["scaleY"] ?: sx
            sx?.let { put("scaleX", value(it, scope)) }
            sy?.let { put("scaleY", value(it, scope)) }
            put("pivotXDp", value(body["pivotX"] ?: body["cx"] ?: JsonPrimitive(0), scope))
            put("pivotYDp", value(body["pivotY"] ?: body["cy"] ?: JsonPrimitive(0), scope))
          }
      }

    fun clip(
      body: JsonObject,
      rest: List<JsonElement>,
      path: String,
      paint: Paint,
      scope: Scope,
    ): String {
      val id = nextId("clip")
      val bounds = JsonObject(box(body, scope))
      val ops = commandList(rest, path, paint, scope)
      return add(
        UiBuilderNode(
          id,
          UiDrawing.CLIP,
          bounds,
          slots = mapOf(UiDrawing.OPS_SLOT to ops),
        )
      )
    }

    fun loop(body: JsonObject, path: String, paint: Paint, scope: Scope): String {
      val index = body.string("index") ?: "i"
      val id = nextId("repeat")
      val inner = scope.copy(indices = scope.indices + index).inBody(++bodyCount)
      // The bounds first: a loop refused for them must not leave its commands behind as nodes
      // nothing reaches.
      val properties = buildMap {
        put("from", value(body["from"] ?: JsonPrimitive(0), scope))
        put("until", value(body["until"] ?: throw Gap("a loop needs `until`"), scope))
        put("step", value(body["step"] ?: JsonPrimitive(1), scope))
        put("index", string(index))
      }
      val ops =
        commandList(
          nested((body["commands"] as? JsonArray).orEmpty()),
          "$path.commands",
          paint,
          inner,
        )
      return add(
        UiBuilderNode(
          id,
          UiDrawing.REPEAT,
          JsonObject(properties),
          slots = mapOf(UiDrawing.OPS_SLOT to ops),
        )
      )
    }

    fun conditional(body: JsonObject, path: String, paint: Paint, scope: Scope): String {
      val symbol =
        when (body.string("condition")?.lowercase()) {
          "lt" -> "<"
          "le" -> "<="
          "gt" -> ">"
          "ge" -> ">="
          "eq" -> "=="
          "ne" -> "!="
          else -> throw Gap("condition `${body.string("condition")}` is not one the importer reads")
        }
      val formula = "(${formula(body["v1"], scope)}) $symbol (${formula(body["v2"], scope)})"
      val id = nextId("if")
      val ops =
        commandList(
          nested((body["commands"] as? JsonArray).orEmpty()),
          "$path.commands",
          paint,
          scope.inBody(++bodyCount),
        )
      return add(
        UiBuilderNode(
          id,
          UiDrawing.IF,
          JsonObject(mapOf("condition" to parsed(formula))),
          slots = mapOf(UiDrawing.OPS_SLOT to ops),
        )
      )
    }

    fun paint(current: Paint, body: JsonObject, scope: Scope): Paint {
      // Either `{"ops": [{"color": …}, …]}` or the same keys inline.
      val entries =
        (body["ops"] as? JsonArray)?.mapNotNull {
          (it as? JsonObject)?.entries?.firstOrNull()?.toPair()
        } ?: body.entries.map { it.toPair() }
      var paint = current
      for ((key, value) in entries) {
        paint =
          when (key) {
            "color" ->
              paint.copy(
                color =
                  color((value as JsonPrimitive).content)?.get("value")?.jsonPrimitive?.content
                    ?: throw Gap("paint colour `$value` is not a colour")
              )
            "style" ->
              paint.copy(stroke = (value as JsonPrimitive).content.lowercase().startsWith("stroke"))
            "strokeWidth",
            "width" -> paint.copy(strokeWidth = value(value, scope))
            "strokeCap" -> paint.copy(strokeCap = (value as JsonPrimitive).content.lowercase())
            "strokeJoin" -> paint.copy(strokeJoin = (value as JsonPrimitive).content.lowercase())
            "alpha" -> paint.copy(alpha = value(value, scope))
            "textSize" -> paint.copy(textSize = value(value, scope))
            else -> throw Gap("the paint's `$key` has no builder equivalent")
          }
      }
      return paint
    }

    fun variable(body: JsonObject, scope: Scope): Scope {
      val name = body.string("name") ?: throw Gap("a variable needs a name")
      val value = body["value"]
      val formula =
        if (value is JsonObject && value.string("type") == "textFromFloat")
          textFromFloat(value, scope)
        else formula(value, scope)
      // The codec's variables are the document's, read by any node after the one defining them.
      globals[name] = formula
      return scope
    }

    /**
     * A touch expression as `dragX` / `dragY`: the corpus's are a touch position scaled (`touchX()
     * / 480`, `touchY() / 400 * 11`, `1 - touchY() / 360`), and the player moves the value by the
     * expression's change since the touch went down, so only the scale matters. The document's
     * pixels are its dp here, so the scale per pixel is the scale per dp.
     */
    fun touchExpression(body: JsonObject) {
      val name = body.string("name") ?: throw Gap("a touch expression needs a name")
      val source =
        (body["expression"] as? JsonPrimitive)?.contentOrNull
          ?: throw Gap("touch expression `$name` has no expression the importer reads")
      val match =
        TOUCH_EXPRESSION.matchEntire(source.trim())
          ?: throw Gap("touch expression `$name`'s `$source` is not a scaled touch position")
      val (inverted, axis, divisor, factor) = match.destructured
      val perPixel =
        (if (inverted.isNotEmpty()) -1.0 else 1.0) / (divisor.toDoubleOrNull() ?: 1.0) *
          (factor.toDoubleOrNull() ?: 1.0)
      val min = body.number("min") ?: 0.0
      val max = body.number("max") ?: 1.0
      val default = body.number("defaultValue") ?: min
      if (min >= max) throw Gap("touch expression `$name`'s minimum is not below its maximum")
      // `dragX` / `dragY` stop gently: the codec's touch mode 0, which the corpus spells `gently`.
      val touchMode = body.number("touchMode") ?: 0.0
      val stopMode = body.string("stopMode") ?: "gently"
      if (touchMode != 0.0 || stopMode != "gently") {
        throw Gap("touch expression `$name` stops other than gently")
      }
      globals[name] =
        "drag$axis(${plain(default)}, ${plain(min)}, ${plain(max)}, ${plain(perPixel)})"
    }

    private fun plain(value: Double): String =
      if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString()
      else value.toString()

    /** The codec's number-to-text: `whole` digits before the point, `decimal` after. */
    fun textFromFloat(value: JsonObject, scope: Scope): String {
      val decimals = (value.number("decimal") ?: 0.0).toInt().coerceIn(0, 6)
      val whole = (value.number("whole") ?: 1.0).toInt().coerceIn(1, 9)
      return "format(${formula(value["value"], scope)}, $decimals, $whole)"
    }

    fun draw(name: String, body: JsonElement?, paint: Paint, scope: Scope): String? {
      val obj = body as? JsonObject
      val (component, geometry) =
        when (name) {
          "drawRect" -> "draw/rect" to box(obj!!, scope)
          "drawRoundRect" ->
            "draw/rect" to
              (box(obj!!, scope) +
                listOfNotNull(obj["rx"]?.let { "cornerRadiusDp" to value(it, scope) }))
          "drawOval" -> "draw/oval" to box(obj!!, scope)
          "drawCircle" ->
            "draw/circle" to
              mapOf(
                "centerXDp" to value(obj!!["cx"], scope),
                "centerYDp" to value(obj["cy"], scope),
                "radiusDp" to value(obj["radius"], scope),
              )
          "drawArc" ->
            "draw/arc" to
              (box(obj!!, scope) +
                mapOf(
                  "startAngle" to value(obj["startAngle"], scope),
                  "sweepAngle" to value(obj["sweepAngle"], scope),
                ) +
                listOfNotNull(
                  (obj["useCenter"] as? JsonPrimitive)?.contentOrNull?.let {
                    "useCenter" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("bool"),
                          "value" to JsonPrimitive(it == "true"),
                        )
                      )
                  }
                ))
          "drawLine" ->
            "draw/line" to
              mapOf(
                "startXDp" to value(obj!!["x1"], scope),
                "startYDp" to value(obj["y1"], scope),
                "endXDp" to value(obj["x2"], scope),
                "endYDp" to value(obj["y2"], scope),
              )
          "drawPath" -> {
            val named = obj?.string("path")
            if (named != null && named in pathExpressions) {
              return add(painted(UiDrawing.POLYLINE, pathExpressions.getValue(named), paint))
            }
            named?.let(linePaths::get)?.let {
              if (it.body !in scope.bodies) {
                throw Gap("path `$named` is drawn outside the loop or condition that built it")
              }
              return add(painted(it, paint, scope))
            }
            if (named != null && named in failedPaths) {
              throw Gap("path `$named` was not imported")
            }
            val data =
              (body as? JsonPrimitive)?.contentOrNull
                ?: obj?.string("pathData")
                ?: obj?.string("path")
                ?: throw Gap("only SVG path data is read for `drawPath`")
            // Path data is in the canvas's units: a path viewport of the canvas's draws it in
            // place.
            "draw/path" to
              mapOf(
                "pathData" to string(data),
                "viewportWidth" to float(viewport.first),
                "viewportHeight" to float(viewport.second),
              )
          }
          "drawTextAnchored" -> "draw/text" to anchoredText(obj!!, scope)
          else -> return null
        }
      return add(painted(component, geometry, paint))
    }

    /** A drawing operation of [component]: its [geometry] with the codec's [paint] copied on. */
    fun painted(
      component: String,
      geometry: Map<String, JsonElement>,
      paint: Paint,
    ): UiBuilderNode {
      val properties = geometry.toMutableMap()
      properties["color"] = color(paint.color)!!
      paint.alpha?.let { properties["alpha"] = it }
      if (component == "draw/text") {
        paint.textSize?.let { properties["textSizeSp"] = it }
      } else {
        if (paint.stroke && component != "draw/line") properties["style"] = enum("stroke")
        if (paint.stroke || component == "draw/line") {
          paint.strokeWidth?.let { properties["strokeWidthDp"] = it }
          paint.strokeCap
            ?.takeIf { it in setOf("round", "square", "butt") }
            ?.let { properties["strokeCap"] = enum(it) }
          paint.strokeJoin
            ?.takeIf { it in UiDrawing.STROKE_JOINS }
            ?.let { properties["strokeJoin"] = enum(it) }
        }
      }
      return UiBuilderNode(
        nextId(component.removePrefix("draw/")),
        component,
        JsonObject(properties),
      )
    }

    /**
     * A command building a path. Its points are translated where they are written, so a loop's
     * index reads that iteration's; appending from another loop or condition body than the one that
     * created the path would build it differently from the player, and is refused.
     */
    fun linePath(name: String, body: JsonObject, scope: Scope) {
      val id =
        (if (name == "pathCreate") body.string("id") else body.string("path"))
          ?: throw Gap("`$name` names no path")
      if (name == "pathCreate") {
        failedPaths -= id
        pathExpressions -= id
        linePaths[id] = LinePath(scope.body, freeIndex(scope))
      }
      val path =
        linePaths[id]
          ?: run {
            if (id !in failedPaths) throw Gap("path `$id` is not created before here")
            return
          }
      fun refuse(reason: String): Nothing {
        linePaths -= id
        failedPaths += id
        throw Gap("path `$id` $reason")
      }
      if (path.body != scope.body)
        refuse("is appended to from another loop or condition than created it")
      if (path.closed) refuse("is appended to after it is closed")
      if (name == "pathAppendClose") {
        path.closed = true
        return
      }
      if (path.size >= UiDrawing.MAX_POINTS) refuse("has more than ${UiDrawing.MAX_POINTS} points")
      val x =
        try {
          formula(body["x"], scope)
        } catch (e: Gap) {
          refuse(": ${e.message}")
        }
      val y =
        try {
          formula(body["y"], scope)
        } catch (e: Gap) {
          refuse(": ${e.message}")
        }
      val lx = (body["x"] as? JsonPrimitive)?.doubleOrNull
      val ly = (body["y"] as? JsonPrimitive)?.doubleOrNull
      path.runs += Run(1, x, y, if (lx != null && ly != null) lx to ly else null)
    }

    /**
     * A loop whose every pass appends one line to a path made outside it, as one run of that path's
     * points: the loop's index, at the path's point number `p` of a run starting at `k`, is `from +
     * (p - k) * step`. The loop's bounds must be numbers, so the run's length is known as the
     * design is written. False when the loop is not one, and is read as any other.
     */
    fun appendingLoop(body: JsonObject, scope: Scope): Boolean {
      val commands = nested((body["commands"] as? JsonArray).orEmpty())
      val append = (commands.singleOrNull() as? JsonObject)?.get("pathAppendLineTo") as? JsonObject
      val id = append?.string("path") ?: return false
      val path = linePaths[id]?.takeIf { it.body == scope.body && !it.closed } ?: return false
      val from = body.number("from") ?: if (body["from"] == null) 0.0 else return false
      val until = body.number("until") ?: return false
      val step = body.number("step") ?: if (body["step"] == null) 1.0 else return false
      if (step <= 0) return false
      // The player's loop adds the step to a float until it reaches `until`.
      var passes = 0
      var at = from.toFloat()
      while (at < until.toFloat() && passes <= UiDrawing.MAX_POINTS) {
        passes++
        at += step.toFloat()
      }
      if (passes == 0) return true
      if (path.size + passes > UiDrawing.MAX_POINTS) {
        linePaths -= id
        failedPaths += id
        throw Gap("path `$id` has more than ${UiDrawing.MAX_POINTS} points")
      }
      val index = body.string("index") ?: "i"
      val pass = "(${number(from)} + (@${path.index} - ${path.size}) * ${number(step)})"
      val inner = scope.copy(indices = scope.indices - index + path.index)
      fun at(key: String) =
        try {
          formula(append[key], inner, mapOf(index to pass))
        } catch (e: Gap) {
          linePaths -= id
          failedPaths += id
          throw Gap("path `$id`: ${e.message}")
        }
      path.runs += Run(passes, at("x"), at("y"))
      return true
    }

    /**
     * A path built by commands as an operation: its SVG path data when every point is a number,
     * else a polyline whose point formulas pick each point's coordinates by its index, so they stay
     * live.
     */
    fun painted(path: LinePath, paint: Paint, scope: Scope): UiBuilderNode {
      if (path.size < 2) throw Gap("a path needs a line to draw")
      val literals = path.runs.mapNotNull { it.literal }
      if (literals.size == path.size) {
        val data = buildString {
          literals.forEachIndexed { i, (x, y) ->
            if (i > 0) append(' ')
            append(if (i == 0) "M" else "L").append(number(x)).append(' ').append(number(y))
          }
          if (path.closed) append(" Z")
        }
        return painted(
          "draw/path",
          mapOf(
            "pathData" to string(data),
            "viewportWidth" to float(viewport.first),
            "viewportHeight" to float(viewport.second),
          ),
          paint,
        )
      }
      val index = path.index
      if (index in scope.indices)
        throw Gap("a path is drawn in a loop whose index is its own, `$index`")
      val starts = path.runs.runningFold(0) { at, run -> at + run.size }
      // A balanced choice between the runs by the point's number, each run from where it starts.
      fun pick(value: (Run) -> String, runs: IntRange): String =
        if (runs.first == runs.last) value(path.runs[runs.first])
        else {
          val half = (runs.first + runs.last + 1) / 2
          "select(@$index < ${starts[half]}, ${pick(value, runs.first until half)}, " +
            "${pick(value, half..runs.last)})"
        }
      val x = pick({ it.x }, path.runs.indices)
      val y = pick({ it.y }, path.runs.indices)
      if (x.length + y.length > MAX_FORMULA) throw Gap("a path's points are too long to write out")
      return painted(
        UiDrawing.POLYLINE,
        buildMap {
          put("count", float(path.size.toDouble()))
          put("index", string(index))
          put("pointXDp", parsed(x))
          put("pointYDp", parsed(y))
          if (path.closed)
            put(
              "closed",
              JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(true))),
            )
        },
        paint,
      )
    }

    /** A polyline's index name, free of the loops around it and the document's variables. */
    fun freeIndex(scope: Scope): String =
      generateSequence(0) { it + 1 }
        .map { if (it == 0) "point" else "point$it" }
        .first { it !in scope.indices && it !in globals }

    /** Path expression ids whose definition was refused, so drawing them is not read as SVG. */
    val failedPaths = mutableSetOf<String>()

    /**
     * A path expression as the polyline it draws. The player samples the expression at `count`
     * values of `a[0]` spread evenly from `start` to `end` and, with the `LINEAR` flag, joins them
     * with straight lines: a polyline whose point formulas read `a[0]` as `start` plus the point's
     * index times the step. A `LOOP` path closes, and its points stop a step short of `end`, as the
     * player's do. A polar one's expression is the radius at angle `a[0]` about its centre. The
     * player's smoothed paths (no `LINEAR` flag) have no builder equivalent.
     */
    fun pathExpression(name: String, body: JsonObject, scope: Scope) {
      val id = body.string("id") ?: throw Gap("a path expression needs an id")
      linePaths -= id
      failedPaths += id
      val flags = body.number("flags")?.toInt() ?: 0
      if (flags and PATH_LINEAR == 0) {
        throw Gap("path expression `$id` is smoothed; only straight (`LINEAR`) ones are read")
      }
      // A polyline fills by the builder's default rule; a path asking for another is not it.
      if (flags and PATH_WINDING_MASK != 0) {
        throw Gap("path expression `$id` sets a winding the builder's polyline does not keep")
      }
      val loop = flags and PATH_LOOP != 0
      val count =
        body.number("count")?.takeIf {
          it == kotlin.math.floor(it) && it >= 2 && it <= UiDrawing.MAX_POINTS
        }
          ?: throw Gap(
            "path expression `$id` needs a literal count from 2 to ${UiDrawing.MAX_POINTS}"
          )
      fun source(key: String): String {
        val element = body[key] as? JsonPrimitive ?: throw Gap("path expression `$id` needs `$key`")
        return element.contentOrNull ?: throw Gap("path expression `$id` needs `$key`")
      }
      val index = freeIndex(scope)
      val steps = number(if (loop) count else count - 1)
      val start = source("start")
      val end = source("end")
      val parameter = "(($start) + $index * (($end) - ($start)) / $steps)"
      val inner = scope.copy(indices = scope.indices + index)
      fun at(key: String): String = source(key).replace(PARAMETER, parameter)
      val (x, y) =
        if (name == "polarPathExpression") {
          val radius = at("expressionR")
          val cx = (body["centerX"] ?: body["cx"])?.jsonPrimitive?.content ?: "0"
          val cy = (body["centerY"] ?: body["cy"])?.jsonPrimitive?.content ?: "0"
          "($cx) + ($radius) * cos($parameter)" to "($cy) + ($radius) * sin($parameter)"
        } else at("expressionX") to at("expressionY")
      pathExpressions[id] = buildMap {
        put("count", float(count))
        put("index", string(index))
        put("pointXDp", parsed(formula(JsonPrimitive(x), inner)))
        put("pointYDp", parsed(formula(JsonPrimitive(y), inner)))
        if (loop)
          put(
            "closed",
            JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(true))),
          )
      }
      failedPaths -= id
    }

    fun box(obj: JsonObject, scope: Scope): Map<String, JsonElement> {
      val left = obj["left"] ?: JsonPrimitive(0)
      val top = obj["top"] ?: JsonPrimitive(0)
      return mapOf(
        "xDp" to value(left, scope),
        "yDp" to value(top, scope),
        "widthDp" to difference(obj["right"] ?: throw Gap("a box needs `right`"), left, scope),
        "heightDp" to difference(obj["bottom"] ?: throw Gap("a box needs `bottom`"), top, scope),
      )
    }

    fun anchoredText(obj: JsonObject, scope: Scope): Map<String, JsonElement> {
      fun pan(key: String): Double = (obj[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
      val panX = pan("panX")
      val panY = pan("panY")
      val align =
        when {
          abs(panX + 1) < 1e-6 -> "start"
          abs(panX) < 1e-6 -> "center"
          abs(panX - 1) < 1e-6 -> "end"
          else -> throw Gap("panX $panX is not -1, 0 or 1")
        }
      // The player's panY runs the other way: 1 hangs the text below the anchor.
      val verticalAlign =
        when {
          abs(panY - 1) < 1e-6 -> "top"
          abs(panY) < 1e-6 -> "center"
          abs(panY + 1) < 1e-6 -> "bottom"
          else -> throw Gap("panY $panY is not -1, 0 or 1")
        }
      return mapOf(
        "text" to textValue(obj["text"], scope),
        "xDp" to value(obj["x"], scope),
        "yDp" to value(obj["y"], scope),
        "align" to enum(align),
        "verticalAlign" to enum(verticalAlign),
      )
    }

    // ---- Values --------------------------------------------------------------------------------

    /** A number, or a formula over variables and the clock. */
    fun value(element: JsonElement?, scope: Scope): JsonObject {
      val primitive = element as? JsonPrimitive ?: throw Gap("expected a number or a formula")
      primitive.doubleOrNull?.let {
        return float(it)
      }
      return parsed(formula(primitive, scope))
    }

    fun difference(end: JsonElement, start: JsonElement, scope: Scope): JsonObject {
      val e = (end as? JsonPrimitive)?.doubleOrNull
      val s = (start as? JsonPrimitive)?.doubleOrNull
      if (e != null && s != null) return float(e - s)
      return parsed("(${formula(end, scope)}) - (${formula(start, scope)})")
    }

    /** Text: a literal, or `@name` of a text variable. */
    fun textValue(element: JsonElement?, scope: Scope): JsonObject {
      val text = (element as? JsonPrimitive)?.contentOrNull ?: throw Gap("expected text")
      if (!text.startsWith("@") && !text.startsWith("$")) return string(text)
      val name = text.removePrefix("@").removePrefix("$").removePrefix("vars.")
      val formula = globals[name] ?: throw Gap("text variable `$name` is not defined before here")
      return parsed(formula)
    }

    fun formula(
      element: JsonElement?,
      scope: Scope,
      bindings: Map<String, String> = emptyMap(),
    ): String {
      val primitive = element as? JsonPrimitive ?: throw Gap("expected a number or a formula")
      primitive.doubleOrNull?.let {
        return number(it)
      }
      val translated =
        try {
          RemoteJsonFormulas.translate(primitive.content, globals + bindings, scope.indices)
        } catch (e: RemoteJsonFormulas.Untranslatable) {
          throw Gap(e.message.orEmpty())
        }
      if (translated.length > MAX_FORMULA) throw Gap("an inlined formula is too long to write out")
      return translated
    }

    fun parsed(formula: String): JsonObject =
      try {
        UiExpressions.parseFormula(formula, emptySet())
      } catch (e: IllegalArgumentException) {
        throw Gap("formula does not parse: ${e.message}")
      }

    fun <T> gap(message: String): T? {
      gaps += message
      return null
    }
  }

  // ---- Wrappers ------------------------------------------------------------------------------

  private fun type(name: String, vararg fields: Pair<String, JsonElement>) =
    JsonObject(mapOf("type" to JsonPrimitive(name)) + fields)

  private fun float(value: Double) =
    JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

  private fun string(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun enum(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive(value)))

  /** `#RGB`-free hex as the builder's `#AARRGGBB` colour wrapper; null when it is not one. */
  private fun color(hex: String): JsonObject? {
    val digits = hex.removePrefix("#")
    val argb =
      when (digits.length) {
        6 -> "FF$digits"
        8 -> digits
        else -> return null
      }
    if (!argb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return JsonObject(
      mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#${argb.uppercase()}"))
    )
  }

  private fun number(value: Double): String =
    if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong().toString() else value.toString()

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
