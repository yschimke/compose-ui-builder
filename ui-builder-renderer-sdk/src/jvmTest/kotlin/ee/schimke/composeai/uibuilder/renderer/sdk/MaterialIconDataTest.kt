package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.graphics.vector.PathNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser draws Material icons from [MaterialIconData], not from the compiled builders, so the
 * data has to be the builders exactly: every icon, every path node, every float bit.
 */
class MaterialIconDataTest {
  @Test
  fun `every icon round-trips through its shard file to the compiled vector`() {
    GoogleMaterialIcons.chunked(MaterialIconData.SHARD_SIZE).forEachIndexed { shard, icons ->
      val compiled = icons.associate { it.key to it.imageVector }
      val decoded = MaterialIconData.decodeShard(MaterialIconData.encodeShard(compiled))
      assertEquals(compiled.keys, decoded.keys, "shard $shard")
      compiled.forEach { (key, vector) -> assertEquals(vector, decoded.getValue(key), key) }
    }
  }

  @Test
  fun `every key's shard is the file that holds it`() {
    assertEquals(11_431, GoogleMaterialIcons.size)
    assertEquals(64, MaterialIconData.shardCount)
    GoogleMaterialIcons.forEachIndexed { index, icon ->
      assertEquals(index / MaterialIconData.SHARD_SIZE, MaterialIconData.shardOf(icon.key))
    }
    assertEquals(null, MaterialIconData.shardOf("not/an-icon"))
  }

  @Test
  fun `the picker's first page is one file`() {
    val firstPage = SelectableGoogleMaterialIcons.take(80)
    assertEquals(setOf(0), firstPage.mapNotNull { MaterialIconData.shardOf(it.key) }.toSet())
  }

  @Test
  fun `path text keeps negative zero and exponents`() {
    val nodes =
      listOf(
        PathNode.MoveTo(-0f, 0f),
        PathNode.RelativeLineTo(1.0E-4f, -2.5E7f),
        PathNode.RelativeArcTo(1f, 2f, 45f, true, false, -3.25f, 0.125f),
        PathNode.Close,
      )
    val text = with(MaterialIconData) { nodes.toPathString() }
    assertEquals(nodes, MaterialIconData.parsePath(text))
    assertTrue(text.startsWith("M-0,0"), text)
  }
}
