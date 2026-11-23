package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** Material Symbols' SVG paths, written as Kobento's `$icon` writes them. */
class KobentoIconsTest {
  private fun ops(d: String) = KobentoIcons.ops(d).toString()

  private fun json(text: String) = Json.parseToJsonElement(text).toString()

  @Test
  fun `relative commands are absolute and y is measured from the top`() {
    // Material's `home`, filled: a move, lines, `H`, `V` and a close.
    assertEquals(
      json(
        """[{"type":"moveTo","x":160,"y":840},{"type":"lineTo","x":160,"y":360},
          {"type":"lineTo","x":480,"y":120},{"type":"lineTo","x":800,"y":360},
          {"type":"lineTo","x":800,"y":840},{"type":"lineTo","x":560,"y":840},
          {"type":"lineTo","x":560,"y":560},{"type":"lineTo","x":400,"y":560},
          {"type":"lineTo","x":400,"y":840},{"type":"close"}]""",
      ),
      ops("M160-120v-480l320-240 320 240v480H560v-280H400v280H160Z"),
    )
  }

  @Test
  fun `smooth curves reflect the last control point, and a fraction is kept`() {
    assertEquals(
      json(
        """[{"type":"moveTo","x":480,"y":840},{"type":"quadTo","x1":438,"y1":840,"x2":409,"y2":811},
          {"type":"quadTo","x1":380,"y1":782,"x2":380,"y2":740},
          {"type":"cubicTo","x1":380,"y1":700,"x2":400.5,"y2":680,"x3":440,"y3":680},
          {"type":"cubicTo","x1":479.5,"y1":680,"x2":500,"y2":700,"x3":500,"y3":740},{"type":"close"}]""",
      ),
      ops("M480-120q-42 0-71-29t-29-71c0-40 20.5-60 60-60s60 20 60 60Z"),
    )
  }

  @Test
  fun `a line back to the start, and a size marker, are left out`() {
    assertEquals(
      json(
        """[{"type":"moveTo","x":0,"y":960},{"type":"lineTo","x":100,"y":960},
          {"type":"lineTo","x":100,"y":860},{"type":"close"}]""",
      ),
      // The triangle's last line returns to its start; then a point and a line out and back.
      ops("M0 0h100v-100L0 0ZM480-480ZM200-200v-560 560Z"),
    )
    // A flat subpath of two segments is drawn, as Kobento draws Material's `drafts`.
    assertEquals(
      json(
        """[{"type":"moveTo","x":480,"y":760},{"type":"lineTo","x":800,"y":760},
          {"type":"lineTo","x":160,"y":760},{"type":"close"}]""",
      ),
      ops("M480-200h320-640 320Z"),
    )
  }

  @Test
  fun `a shard is named by style, fill and first character`() {
    val template = "kobento/icons/{variant}/{shard}.json"
    assertEquals(
      "kobento/icons/rounded-filled/b.json",
      KobentoIcons.shardPath(template, "bolt", "rounded", true),
    )
    assertEquals("icon_bolt_rounded_filled", KobentoIcons.pathName("bolt", "rounded", true))
    assertEquals(216, KobentoIcons.shardPaths(template).toSet().size)
    val source =
      KobentoIcons.source(template) { path ->
        """{"bolt": "M0 0Z"}""".takeIf { path == "kobento/icons/rounded-filled/b.json" }
      }
    assertEquals("M0 0Z", source.path("bolt", "rounded", true))
    assertEquals(null, source.path("bolt", "outlined", true))
    assertEquals(null, source.path("bell", "rounded", true))
  }
}
