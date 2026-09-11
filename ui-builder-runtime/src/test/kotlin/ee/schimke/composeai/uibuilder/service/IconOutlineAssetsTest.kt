package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outline registry, which shares `assets` with somebody's uploaded images.
 *
 * Sharing one map is the whole risk here: the released `DesignDocumentV1` has no separate home for
 * resolved outlines, so the only thing keeping an upload from being pruned as a stale icon — or an
 * icon from being served as a picture the design alone has — is a key prefix and a media type. Both
 * are pinned below.
 */
class IconOutlineAssetsTest {

  private val search = IconOutlineKeyV1("search")
  private val path = "M120 120L840 120L840 840L120 840Z"

  private fun upload(key: String) =
    key to
      AssetBindingV1(
        mediaType = "image/png",
        contentDigest = "sha256:${"0".repeat(64)}",
        source = UploadedAssetSourceV1(storageKey = "sha256:${"0".repeat(64)}"),
        widthPx = 32,
        heightPx = 32,
      )

  @Test
  fun `a key names the icon and every axis, defaults included`() {
    assertEquals(
      "icon-outline/outlined/search@FILL0,GRAD0,opsz24,wght400",
      search.assetKey(),
    )
    assertEquals(
      "icon-outline/rounded/arrow_back@FILL1,GRAD-25,opsz40,wght700",
      IconOutlineKeyV1(
          "arrow_back",
          "rounded",
          fill = 1f,
          weight = 700f,
          grade = -25f,
          opticalSize = 40f,
        )
        .assetKey(),
    )
    // Off-grid values survive, because the axes are continuous and a slider can land anywhere.
    assertTrue(IconOutlineKeyV1("search", weight = 137.5f).assetKey().endsWith("wght137.5"))
    // Two spellings of the same picture would be two entries, so the axis order is fixed and every
    // axis is always written.
    assertEquals(
      search.assetKey(),
      IconOutlineKeyV1("search", "outlined", 0f, 400f, 0f, 24f).assetKey(),
    )
  }

  @Test
  fun `path data round-trips through the binding`() {
    val binding = IconOutlineAssets.binding(path)
    assertEquals(IconOutlineKeyV1.MEDIA_TYPE, binding.mediaType)
    assertEquals(path, IconOutlineAssets.pathData(binding))
    assertEquals(UiBuilderAssetDigests.of(path.encodeToByteArray()), binding.contentDigest)
    // No pixel size: an outline is drawn at whatever the node asks for, in a 960-unit viewport.
    assertNull(binding.widthPx)
    assertNull(binding.heightPx)
  }

  @Test
  fun `an uploaded image is never read as an outline`() {
    assertNull(IconOutlineAssets.pathData(upload("logo").second))
    assertTrue(!IconOutlineAssets.isOutlineKey("logo"))
    assertTrue(IconOutlineAssets.isOutlineKey(search.assetKey()))
  }

  @Test
  fun `refreshing adds what is drawn and drops what is not`() {
    val stale = IconOutlineKeyV1("home")
    val assets = mapOf(upload("logo"), stale.assetKey() to IconOutlineAssets.binding("M0 0Z"))

    val refreshed = IconOutlineAssets.refreshed(assets, setOf(search)) { path }

    assertEquals(setOf("logo", search.assetKey()), refreshed.keys)
    assertEquals(path, IconOutlineAssets.pathData(refreshed.getValue(search.assetKey())))
    // The upload is somebody's bytes and is not this object's to prune.
    assertEquals(assets.getValue("logo"), refreshed.getValue("logo"))
  }

  @Test
  fun `an entry already present is not resolved again`() {
    val assets = mapOf(search.assetKey() to IconOutlineAssets.binding(path))
    var calls = 0
    val refreshed =
      IconOutlineAssets.refreshed(assets, setOf(search)) {
        calls++
        "M1 1Z"
      }
    assertEquals(0, calls, "an outline the design already carries is what gets drawn")
    assertEquals(path, IconOutlineAssets.pathData(refreshed.getValue(search.assetKey())))
  }

  @Test
  fun `a host that cannot resolve keeps the pictures a design already had`() {
    // The cold-cache case. Answering null must not erase a design's icons — that would turn one
    // offline write into a design that renders empty boxes everywhere afterwards.
    val existing = mapOf(search.assetKey() to IconOutlineAssets.binding(path))
    val kept =
      IconOutlineAssets.refreshed(existing, setOf(search, IconOutlineKeyV1("home"))) { null }
    assertEquals(path, IconOutlineAssets.pathData(kept.getValue(search.assetKey())))
    assertEquals(1, kept.size, "an icon that could not be resolved simply has no entry yet")
  }
}
