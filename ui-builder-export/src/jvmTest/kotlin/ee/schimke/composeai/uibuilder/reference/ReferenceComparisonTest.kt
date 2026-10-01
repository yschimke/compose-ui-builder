package ee.schimke.composeai.uibuilder.reference

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The whole comparison as a host without Compose calls it: a design drawn at 1×, a reference at 2×,
 * both covering a 100 × 60 dp frame.
 */
class ReferenceComparisonTest {
  private val white = 0xFFFFFFFF.toInt()
  private val ink = 0xFF202020.toInt()

  /** A [width] × [height] raster with dark blocks at dp rects scaled by [scale]. */
  private fun raster(scale: Int, vararg blocks: IntArray): ReferenceRaster {
    val width = 100 * scale
    val height = 60 * scale
    val pixels = IntArray(width * height) { white }
    blocks.forEach { (l, t, r, b) ->
      for (y in t * scale until b * scale) for (x in l * scale until r * scale) {
        pixels[y * width + x] = ink
      }
    }
    return ReferenceRaster(width, height, pixels)
  }

  private val card = intArrayOf(10, 10, 50, 30)
  private val cardInReference = intArrayOf(16, 12, 56, 32)

  private fun input(layers: List<ReferenceLayer> = emptyList()) =
    ReferenceComparisonInput(
      design = raster(1, card, intArrayOf(10, 40, 12, 50)),
      reference = raster(2, cardInReference, intArrayOf(10, 40, 12, 50)),
      frameWidthDp = 100f,
      frameHeightDp = 60f,
      facts = ReferenceFacts(200, 120, 100f, 60f, declaredDensity = null, designDensity = 1f),
      layers = layers,
    )

  @Test
  fun `differences name the layer they fall in`() {
    val layers = listOf(ReferenceLayer("card", ReferenceBox(10f, 10f, 50f, 30f), text = false))
    val diff = assertNotNull(compareDifferences(input(layers)).diff)
    assertTrue(diff.mismatch > 0f)
    assertTrue(diff.regions.isNotEmpty())
    assertTrue(diff.regions.any { it.nodeId == "card" })
  }

  @Test
  fun `a 2x reference is measured in dp`() {
    val layers = listOf(ReferenceLayer("card", ReferenceBox(8f, 8f, 52f, 32f), text = false))
    val result = compareLayer(input(layers), "card", node = null)
    val alignment = assertNotNull(result.alignment, result.message)
    assertEquals(6, alignment.moveXDp)
    assertEquals(2, alignment.moveYDp)
    assertNull(alignment.fontSizeSp)
  }

  @Test
  fun `a picture of another shape is not pixel-compared`() {
    val wide =
      ReferenceComparisonInput(
        design = raster(1, card),
        reference = ReferenceRaster(400, 60, IntArray(400 * 60) { white }),
        frameWidthDp = 100f,
        frameHeightDp = 60f,
        facts = ReferenceFacts(400, 60, 100f, 60f, declaredDensity = null, designDensity = 1f),
      )
    val result = compareDifferences(wide)
    assertNull(result.diff)
    assertTrue(result.message!!.startsWith("Not measured"))
  }

  @Test
  fun `a layer missing from the render is said so`() {
    assertTrue(compareLayer(input(), "nope", null).message!!.contains("nope"))
  }

  @Test
  fun `font size comes from the node, else the type scale`() {
    fun text(properties: Map<String, JsonObject>) =
      UiBuilderNode(id = "t", componentId = "m3/text", properties = JsonObject(properties))
    fun literal(type: String, value: Any) =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive(type),
          "value" to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString()),
        )
      )
    assertEquals(18f, text(mapOf("fontSizeSp" to literal("float", 18))).referenceFontSize()?.sp)
    assertEquals(
      22f,
      text(mapOf("style" to literal("typographyToken", "titleLarge"))).referenceFontSize()?.sp,
    )
    assertEquals(16f, text(emptyMap()).referenceFontSize()?.sp)
  }
}
