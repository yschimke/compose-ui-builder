package ee.schimke.composeai.uibuilder.reference

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The text property a reference match resizes. Every catalog's text component declares it. */
const val REFERENCE_FONT_SIZE_PROPERTY = "fontSizeSp"

/**
 * [chain] with the node moved [dx], [dy] dp, or null when [declared] allows no way to move it.
 *
 * Padding first (layout: neighbours make room): the leading edge grows by the move and the trailing
 * edge gives back what it has. A move further than the leading padding allows goes on an `offset`.
 * Either alone is used when only it is declared. New padding goes at the front, outermost.
 */
fun movedModifierChain(
  chain: List<JsonElement>,
  dx: Int,
  dy: Int,
  declared: Set<String>,
): List<JsonElement>? {
  if (dx == 0 && dy == 0) return chain
  val canPad = "padding" in declared
  val canOffset = "offset" in declared
  if (!canPad && !canOffset) return null
  val result = chain.toMutableList()
  var residualX = dx.toDouble()
  var residualY = dy.toDouble()
  if (canPad) {
    var index = result.indexOfFirst { it.modifierType() == "padding" }
    if (index < 0) {
      result.add(0, padding(0.0, 0.0, 0.0, 0.0))
      index = 0
    }
    val existing = result[index] as JsonObject
    var start = existing.number("startDp")
    var top = existing.number("topDp")
    var end = existing.number("endDp")
    var bottom = existing.number("bottomDp")
    fun shift(lead: Double, trail: Double, by: Double): Triple<Double, Double, Double> =
      if (by >= 0) {
        val given = min(trail, by)
        Triple(lead + by, trail - given, 0.0)
      } else {
        val taken = min(lead, -by)
        Triple(lead - taken, trail + taken, by + taken)
      }
    shift(start, end, residualX).let { (lead, trail, left) ->
      start = lead
      end = trail
      residualX = left
    }
    shift(top, bottom, residualY).let { (lead, trail, left) ->
      top = lead
      bottom = trail
      residualY = left
    }
    val updated = JsonObject(existing + padding(start, top, end, bottom))
    // A padding that has come back to nothing is removed rather than left as `padding(0.dp)`.
    if (start == 0.0 && top == 0.0 && end == 0.0 && bottom == 0.0 && updated.size == 5) {
      result.removeAt(index)
    } else {
      result[index] = updated
    }
  }
  if (residualX != 0.0 || residualY != 0.0) {
    if (!canOffset) return null
    val index = result.indexOfFirst { it.modifierType() == "offset" }
    if (index < 0) {
      result += offset(residualX, residualY)
    } else {
      val existing = result[index] as JsonObject
      val x = existing.number("xDp") + residualX
      val y = existing.number("yDp") + residualY
      if (x == 0.0 && y == 0.0) result.removeAt(index)
      else result[index] = JsonObject(existing + offset(x, y))
    }
  }
  return result
}

private fun JsonElement.modifierType(): String? =
  ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(name: String): Double =
  (get(name) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0

/** Whole numbers stay whole, as the editor's modifier field edits keep them. */
private fun Double.json(): JsonPrimitive =
  if (this == floor(this) && abs(this) < 1e12) JsonPrimitive(toLong()) else JsonPrimitive(this)

private fun padding(start: Double, top: Double, end: Double, bottom: Double): JsonObject =
  buildJsonObject {
    put("type", "padding")
    put("startDp", start.json())
    put("topDp", top.json())
    put("endDp", end.json())
    put("bottomDp", bottom.json())
  }

private fun offset(x: Double, y: Double): JsonObject = buildJsonObject {
  put("type", "offset")
  put("xDp", x.json())
  put("yDp", y.json())
}
