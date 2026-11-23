package ee.schimke.composeai.uibuilder.export

import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Material Symbols, as Kobento's `$icon` draws them: a 24px symbol's SVG path (its `viewBox` is `0
 * -960 960 960`) written as the absolute ops of a Remote Compose path, on a 960 grid with y from
 * the top.
 *
 * What Kobento writes, matched against its own expansions: relative commands made absolute, `H` and
 * `V` as `lineTo`, `T` and `S` as the `quadTo` and `cubicTo` their reflected control point makes, a
 * `lineTo` back to the subpath's start dropped before its `close`, and a subpath then left with
 * fewer than two segments (Material's `M480-480Z` and `v480-480Z` size markers) left out.
 */
object KobentoIcons {
  /** The name of the path [name] in [style] (`outlined`, `rounded`, `sharp`) draws. */
  fun pathName(name: String, style: String, filled: Boolean): String =
    "icon_${name}_$style" + if (filled) "_filled" else ""

  /**
   * Where [template] puts [name]'s shard: `{variant}` is the style, with `-filled` for the filled
   * symbol, and `{shard}` the name's first character. kobento-catalog publishes each shard as a
   * JSON object from name to SVG path, so an editor fetches only the shards a design draws from.
   */
  fun shardPath(template: String, name: String, style: String, filled: Boolean): String =
    template
      .replace("{variant}", style + if (filled) "-filled" else "")
      .replace("{shard}", name.take(1))

  /** Every shard [template] can name: three styles, filled or not, by first character. */
  fun shardPaths(template: String): List<String> = STYLES.flatMap { style ->
    listOf(false, true).flatMap { filled ->
      SHARDS.map { shard -> shardPath(template, shard.toString(), style, filled) }
    }
  }

  /**
   * The icons [read] answers, a shard at a time through [template]: null for a shard it does not
   * have (yet), so a host can fetch the shard and expand again.
   */
  fun source(template: String, read: (String) -> String?): KobentoComponentExpander.IconSource {
    val shards = HashMap<String, Map<String, String>?>()
    return KobentoComponentExpander.IconSource { name, style, filled ->
      val path = shardPath(template, name, style, filled)
      val shard =
        shards.getOrPut(path) {
          read(path)?.let { text ->
            runCatching {
              kotlinx.serialization.json.Json.parseToJsonElement(text).let {
                (it as JsonObject).mapValues { (_, d) -> (d as JsonPrimitive).content }
              }
            }
              .getOrNull()
          }
        }
      shard?.get(name)
    }
  }

  val STYLES: List<String> = listOf("outlined", "rounded", "sharp")

  private val SHARDS = ('0'..'9') + ('a'..'z')

  /** [d], an SVG path in Material Symbols' grid, as Remote Compose path ops. */
  fun ops(d: String): JsonArray {
    val tokens = TOKEN.findAll(d).map { it.value }.toList()
    var i = 0
    fun next(): Double = tokens[i++].toDouble()
    val ops = mutableListOf<Op>()
    var x = 0.0
    var y = 0.0
    var startX = 0.0
    var startY = 0.0
    var command = ' '
    var lastCubic: Pair<Double, Double>? = null
    var lastQuad: Pair<Double, Double>? = null
    while (i < tokens.size) {
      val token = tokens[i]
      if (token.length == 1 && token[0].isLetter()) {
        command = token[0]
        i++
      } else if (command == 'M') {
        command = 'L'
      } else if (command == 'm') {
        command = 'l'
      }
      val relative = command.isLowerCase()
      val cubic = lastCubic
      val quad = lastQuad
      lastCubic = null
      lastQuad = null
      fun ax(value: Double) = if (relative) value + x else value
      fun ay(value: Double) = if (relative) value + y else value
      when (command.uppercaseChar()) {
        'Z' -> {
          val last = ops.lastOrNull()
          if (last is Op.Line && last.x == startX && last.y == startY) ops.removeAt(ops.lastIndex)
          // A point, or one line out and back, is a size marker, not a shape.
          val begin = ops.indexOfLast { it is Op.Move }
          val subpath = if (begin < 0) emptyList() else ops.subList(begin, ops.size)
          if (subpath.isNotEmpty() && encloses(subpath)) ops += Op.Close
          else repeat(subpath.size) { ops.removeAt(ops.lastIndex) }
          x = startX
          y = startY
        }
        'M' -> {
          val nx = ax(next())
          val ny = ay(next())
          x = nx
          y = ny
          startX = x
          startY = y
          ops += Op.Move(x, y)
          command = if (relative) 'l' else 'L'
        }
        'L' -> {
          val nx = ax(next())
          val ny = ay(next())
          x = nx
          y = ny
          ops += Op.Line(x, y)
        }
        'H' -> {
          x = ax(next())
          ops += Op.Line(x, y)
        }
        'V' -> {
          y = ay(next())
          ops += Op.Line(x, y)
        }
        'Q',
        'T' -> {
          val (cx, cy) =
            if (command.uppercaseChar() == 'Q') ax(next()) to ay(next())
            else quad?.let { (qx, qy) -> 2 * x - qx to 2 * y - qy } ?: (x to y)
          val nx = ax(next())
          val ny = ay(next())
          ops += Op.Quad(cx, cy, nx, ny)
          lastQuad = cx to cy
          x = nx
          y = ny
        }
        'C',
        'S' -> {
          val (c1x, c1y) =
            if (command.uppercaseChar() == 'C') ax(next()) to ay(next())
            else cubic?.let { (qx, qy) -> 2 * x - qx to 2 * y - qy } ?: (x to y)
          val c2x = ax(next())
          val c2y = ay(next())
          val nx = ax(next())
          val ny = ay(next())
          ops += Op.Cubic(c1x, c1y, c2x, c2y, nx, ny)
          lastCubic = c2x to c2y
          x = nx
          y = ny
        }
        else -> throw IllegalArgumentException("an icon path command $command is not drawn")
      }
    }
    return JsonArray(ops.map { it.json() })
  }

  /**
   * Whether Kobento draws [subpath] (a move, then its segments): it leaves out one of fewer than
   * two segments once the line back to its start is gone, Material's `M480-480Z` and `v480-480Z`
   * markers, and keeps any longer one, flat or not.
   */
  private fun encloses(subpath: List<Op>): Boolean = subpath.size >= 3

  private sealed interface Op {
    fun json(): JsonObject

    data class Move(val x: Double, val y: Double) : Op {
      override fun json() = op("moveTo", "x" to x, "y" to y + SHIFT)
    }

    data class Line(val x: Double, val y: Double) : Op {
      override fun json() = op("lineTo", "x" to x, "y" to y + SHIFT)
    }

    data class Quad(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Op {
      override fun json() =
        op("quadTo", "x1" to x1, "y1" to y1 + SHIFT, "x2" to x2, "y2" to y2 + SHIFT)
    }

    data class Cubic(
      val x1: Double,
      val y1: Double,
      val x2: Double,
      val y2: Double,
      val x3: Double,
      val y3: Double,
    ) : Op {
      override fun json() =
        op(
          "cubicTo",
          "x1" to x1,
          "y1" to y1 + SHIFT,
          "x2" to x2,
          "y2" to y2 + SHIFT,
          "x3" to x3,
          "y3" to y3 + SHIFT,
        )
    }

    data object Close : Op {
      override fun json() = JsonObject(mapOf("type" to JsonPrimitive("close")))
    }
  }

  private fun op(type: String, vararg points: Pair<String, Double>): JsonObject =
    JsonObject(
      mapOf("type" to JsonPrimitive(type)) + points.map { (key, value) -> key to number(value) },
    )

  /** [value] to six places, an integer where it is one, as Kobento writes a coordinate. */
  private fun number(value: Double): JsonPrimitive {
    val rounded = round(value * 1_000_000) / 1_000_000
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) JsonPrimitive(whole) else JsonPrimitive(rounded)
  }

  /** SVG's `viewBox="0 -960 960 960"` puts y in -960..0; Remote Compose's starts at 0. */
  private const val SHIFT = 960.0

  private val TOKEN = Regex("""[MmLlHhVvQqTtCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
}
