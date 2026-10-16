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

  /** The loop indices in scope. */
  private data class Scope(val indices: Set<String> = emptySet())

  private class Importer(val source: JsonObject) {
    val gaps = mutableListOf<String>()
    /** Every variable defined so far, in document order, as a builder formula. */
    val globals = linkedMapOf<String, String>()
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
            "loop" -> ops += loop(body as JsonObject, at, paint, scope)
            "conditionalOperations" -> ops += conditional(body as JsonObject, at, paint, scope)
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
      val inner = scope.copy(indices = scope.indices + index)
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
          scope,
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
      return add(
        UiBuilderNode(nextId(component.removePrefix("draw/")), component, JsonObject(properties))
      )
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

    fun formula(element: JsonElement?, scope: Scope): String {
      val primitive = element as? JsonPrimitive ?: throw Gap("expected a number or a formula")
      primitive.doubleOrNull?.let {
        return number(it)
      }
      val translated =
        try {
          RemoteJsonFormulas.translate(primitive.content, globals, scope.indices)
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
