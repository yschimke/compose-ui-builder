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
 * The layout tree becomes `layout/box`, `row` and `column` nodes and `remote-m3/remote-text`; a canvas becomes a
 * `draw/canvas` whose viewport is the document's own units, so its pixel coordinates draw where the
 * codec drew them. Its commands become drawing operations: the paint state the codec carries from
 * command to command is copied onto each operation, a transform or clip wraps the commands after it
 * in a group or clip, and loops and conditionals become `draw/repeat` and `draw/if`. The codec's
 * formulas are translated into the builder's, its named variables inlined where they are read.
 *
 * What the builder has no equivalent for is skipped and reported in [Result.gaps], located by its
 * path in the source, so a document imports as far as it can and the gaps say what is missing.
 */
object RemoteJsonImporter {
  class Result(val document: UiBuilderDocument, val gaps: List<String>)

  /** Lengths are taken 1:1 as dp: the document's units are the design's. */
  fun import(source: JsonObject, id: String = "imported", title: String = "Imported"): Result =
    Importer(source).run(id, title)

  private const val ROOT_ID = "root"

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

  /** Variables by name, as builder formulas, and the loop indices in scope. */
  private data class Scope(
    val variables: Map<String, String> = emptyMap(),
    val indices: Set<String> = emptySet(),
  )

  private class Importer(val source: JsonObject) {
    val gaps = mutableListOf<String>()
    val nodes = linkedMapOf<String, UiBuilderNode>()
    val ids = mutableMapOf<String, Int>()
    val header = source["header"] as? JsonObject
    val width = header?.number("width") ?: 360.0
    val height = header?.number("height") ?: 360.0

    fun run(id: String, title: String): Result {
      val roots = (source["root"] as? JsonArray).orEmpty()
      if (source["root"] !is JsonArray) gaps += "root: no layout tree to import"
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
                "systemId" to JsonPrimitive("remote-m3"),
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

    fun layout(element: JsonElement, path: String): String? {
      val obj = element as? JsonObject ?: return gap("$path: not a layout node")
      listOf("box", "column", "row").forEach { kind ->
        (obj[kind] as? JsonObject)?.let { return container(kind, it, "$path.$kind") }
      }
      return when (val type = obj.string("type")) {
        "canvas" -> canvas(obj, path)
        "text" -> text(obj, path)
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
          "column" -> obj.string("horizontalAlignment")?.let { put("horizontalAlignment", enum(it)) }
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
      val value = obj["value"] ?: obj["text"]
      val text =
        try {
          textValue(value, Scope())
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
          number?.let {
            type("size", "widthDp" to JsonPrimitive(it), "heightDp" to JsonPrimitive(it))
          }
        "padding" ->
          number?.let { all ->
            type(
              "padding",
              *listOf("startDp", "topDp", "endDp", "bottomDp")
                .map { it to JsonPrimitive(all) }
                .toTypedArray(),
            )
          }
        "background" ->
          (value as? JsonPrimitive)?.contentOrNull?.let(::color)?.let { type("background", "color" to it) }
        "weight" -> number?.let { type("weight", "weight" to JsonPrimitive(it)) }
        else -> null
      } ?: gap("$path: the `$name` modifier as written here has no builder equivalent")
    }

    // ---- Canvas --------------------------------------------------------------------------------

    fun canvas(obj: JsonObject, path: String): String {
      val modifierList = modifiers(obj["modifiers"], "$path.modifiers")
      // The commands are in the document's pixels; a viewport of the canvas's stated size, or the
      // document's, draws them there whatever size the canvas ends up.
      val stated =
        modifierList.firstNotNullOfOrNull { m ->
          (m as JsonObject).takeIf { it.string("type") == "size" }
        }
      val viewportWidth = stated?.number("widthDp") ?: width
      val viewportHeight = stated?.number("heightDp") ?: height
      val ops = commands(obj["commands"], "$path.commands", Paint(), Scope(), viewportWidth, viewportHeight)
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
        if (command != null && command.keys.singleOrNull() == "save" && (save as? JsonObject)?.get("commands") == null) {
          var depth = 1
          var j = i + 1
          while (j < list.size && depth > 0) {
            val key = (list[j] as? JsonObject)?.keys?.firstOrNull()
            if (key == "save" && ((list[j] as JsonObject)["save"] as? JsonObject)?.get("commands") == null) depth++
            if (key == "restore") depth--
            j++
          }
          val body = list.subList(i + 1, if (depth == 0) j - 1 else j)
          out += JsonObject(mapOf("save" to JsonObject(mapOf("commands" to JsonArray(nested(body))))))
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

    fun commandList(list: List<JsonElement>, path: String, startPaint: Paint, startScope: Scope): List<String> {
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
            "variable" -> scope = variable(body as? JsonObject ?: throw Gap("not a variable"), scope)
            "save" ->
              ops += group(emptyMap(), (body as? JsonObject)?.get("commands"), at, paint, scope)
            // A transform or clip holds for the rest of its list: those commands go inside it.
            "rotate",
            "translate",
            "scale" -> {
              val rest = JsonArray(list.drop(index + 1))
              ops += group(transform(name, body as JsonObject, scope), rest, "$path[${index + 1}..]", paint, scope)
              return ops
            }
            "clipRect" -> {
              val rest = list.drop(index + 1)
              ops += clip(body as JsonObject, rest, "$path[${index + 1}..]", paint, scope)
              return ops
            }
            "loop" -> ops += loop(body as JsonObject, at, paint, scope)
            "conditionalOperations" -> ops += conditional(body as JsonObject, at, paint, scope)
            else -> ops += draw(name, body, paint, scope) ?: throw Gap("`$name` has no builder equivalent")
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

    fun group(properties: Map<String, JsonElement>, commands: JsonElement?, path: String, paint: Paint, scope: Scope): String {
      val id = nextId("group")
      val ops = commandList(nested((commands as? JsonArray).orEmpty()), "$path.commands", paint, scope)
      return add(UiBuilderNode(id, UiDrawing.GROUP, JsonObject(properties), slots = mapOf(UiDrawing.OPS_SLOT to ops)))
    }

    fun transform(name: String, body: JsonObject, scope: Scope): Map<String, JsonElement> =
      when (name) {
        "rotate" -> buildMap {
          put("rotate", value(body["angle"] ?: body["degrees"], scope))
          // The codec rotates about the origin unless it is given a pivot; the group's default is
          // the canvas's centre, so an absent pivot is written as 0.
          put("pivotXDp", value(body["pivotX"] ?: JsonPrimitive(0), scope))
          put("pivotYDp", value(body["pivotY"] ?: JsonPrimitive(0), scope))
        }
        "translate" -> buildMap {
          body["dx"]?.let { put("translateXDp", value(it, scope)) }
          body["dy"]?.let { put("translateYDp", value(it, scope)) }
        }
        else -> buildMap {
          val sx = body["sx"] ?: body["scaleX"] ?: body["scale"]
          val sy = body["sy"] ?: body["scaleY"] ?: sx
          sx?.let { put("scaleX", value(it, scope)) }
          sy?.let { put("scaleY", value(it, scope)) }
          put("pivotXDp", value(body["pivotX"] ?: body["cx"] ?: JsonPrimitive(0), scope))
          put("pivotYDp", value(body["pivotY"] ?: body["cy"] ?: JsonPrimitive(0), scope))
        }
      }

    fun clip(body: JsonObject, rest: List<JsonElement>, path: String, paint: Paint, scope: Scope): String {
      val id = nextId("clip")
      val ops = commandList(rest, path, paint, scope)
      return add(UiBuilderNode(id, UiDrawing.CLIP, JsonObject(box(body, scope)), slots = mapOf(UiDrawing.OPS_SLOT to ops)))
    }

    fun loop(body: JsonObject, path: String, paint: Paint, scope: Scope): String {
      val index = body.string("index") ?: "i"
      val id = nextId("repeat")
      val inner = scope.copy(indices = scope.indices + index)
      val ops = commandList(nested((body["commands"] as? JsonArray).orEmpty()), "$path.commands", paint, inner)
      val properties = buildMap {
        put("from", value(body["from"] ?: JsonPrimitive(0), scope))
        put("until", value(body["until"] ?: throw Gap("a loop needs `until`"), scope))
        put("step", value(body["step"] ?: JsonPrimitive(1), scope))
        put("index", string(index))
      }
      return add(UiBuilderNode(id, UiDrawing.REPEAT, JsonObject(properties), slots = mapOf(UiDrawing.OPS_SLOT to ops)))
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
      val ops = commandList(nested((body["commands"] as? JsonArray).orEmpty()), "$path.commands", paint, scope)
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
        (body["ops"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.entries?.firstOrNull()?.toPair() }
          ?: body.entries.map { it.toPair() }
      var paint = current
      for ((key, value) in entries) {
        paint =
          when (key) {
            "color" -> paint.copy(color = color((value as JsonPrimitive).content)?.get("value")?.jsonPrimitive?.content ?: throw Gap("paint colour `$value` is not a colour"))
            "style" -> paint.copy(stroke = (value as JsonPrimitive).content.lowercase().startsWith("stroke"))
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
        if (value is JsonObject && value.string("type") == "textFromFloat") {
          val decimals = value.number("decimal") ?: 0.0
          val whole = value.number("whole") ?: 1.0
          "format(${formula(value["value"], scope)}, ${decimals.toInt()}, ${whole.toInt().coerceIn(1, 9)})"
        } else formula(value, scope)
      return scope.copy(variables = scope.variables + (name to formula))
    }

    fun draw(name: String, body: JsonElement?, paint: Paint, scope: Scope): String? {
      val obj = body as? JsonObject
      val (component, geometry) =
        when (name) {
          "drawRect" -> "draw/rect" to box(obj!!, scope)
          "drawRoundRect" ->
            "draw/rect" to
              (box(obj!!, scope) + listOfNotNull(obj["rx"]?.let { "cornerRadiusDp" to value(it, scope) }))
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
                    "useCenter" to JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(it == "true")))
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
            val data = (body as? JsonPrimitive)?.contentOrNull ?: obj?.string("pathData") ?: obj?.string("path")
              ?: throw Gap("only SVG path data is read for `drawPath`")
            // Path data is in the canvas's units: a path viewport of the canvas's draws it in place.
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
          paint.strokeCap?.takeIf { it in setOf("round", "square", "butt") }?.let { properties["strokeCap"] = enum(it) }
          paint.strokeJoin?.takeIf { it in UiDrawing.STROKE_JOINS }?.let { properties["strokeJoin"] = enum(it) }
        }
      }
      return add(UiBuilderNode(nextId(component.removePrefix("draw/")), component, JsonObject(properties)))
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
      primitive.doubleOrNull?.let { return float(it) }
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
      val formula = scope.variables[name] ?: throw Gap("text variable `$name` is not defined here")
      return parsed(formula)
    }

    fun formula(element: JsonElement?, scope: Scope): String {
      val primitive = element as? JsonPrimitive ?: throw Gap("expected a number or a formula")
      primitive.doubleOrNull?.let { return number(it) }
      val translated =
        try {
          RemoteJsonFormulas.translate(primitive.content, scope.variables, scope.indices)
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
    return JsonObject(mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#${argb.uppercase()}")))
  }

  private fun number(value: Double): String =
    if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong().toString() else value.toString()

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
