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

  /** Comfortably above anything these cases add; the ceiling has its own test. */
  private val CAP = 64

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
  fun `an icon that is drawn gets an outline, and nothing else is touched`() {
    val stale = IconOutlineKeyV1("home")
    val assets = mapOf(upload("logo"), stale.assetKey() to IconOutlineAssets.binding("M0 0Z"))

    val filled = IconOutlineAssets.withOutlines(assets, setOf(search), CAP) { path }

    assertEquals(setOf("logo", stale.assetKey(), search.assetKey()), filled.keys)
    assertEquals(path, IconOutlineAssets.pathData(filled.getValue(search.assetKey())))
    // The upload is somebody's bytes and is not this object's to touch.
    assertEquals(assets.getValue("logo"), filled.getValue("logo"))
  }

  @Test
  fun `an outline nothing draws any more is kept, because undo needs it`() {
    // The first version of this pruned it. Undo restores a node without restoring assets pruned
    // when it changed, so recreating the outline would need the resolver — and the documented
    // cold-cache case is exactly when there is none. Undo would then return a document that is
    // not the one it undid, with blank icons where there had been pictures.
    val dropped = IconOutlineKeyV1("home")
    val assets = mapOf(dropped.assetKey() to IconOutlineAssets.binding("M0 0Z"))
    val filled = IconOutlineAssets.withOutlines(assets, setOf(search), CAP) { path }
    assertEquals("M0 0Z", IconOutlineAssets.pathData(filled.getValue(dropped.assetKey())))
  }

  @Test
  fun `nothing to add is the same map, not a copy`() {
    // So a caller can tell "unchanged" by identity and leave the document alone, which is what
    // keeps a write touching no icon from producing a new assets map in a new revision.
    val assets = mapOf(search.assetKey() to IconOutlineAssets.binding(path))
    assertTrue(assets === IconOutlineAssets.withOutlines(assets, setOf(search), CAP) { path })
    assertTrue(assets === IconOutlineAssets.withOutlines(assets, emptySet(), CAP) { path })
    assertTrue(
      assets === IconOutlineAssets.withOutlines(assets, setOf(IconOutlineKeyV1("x")), CAP) { null }
    )
  }

  @Test
  fun `an entry already present is not resolved again`() {
    val assets = mapOf(search.assetKey() to IconOutlineAssets.binding(path))
    var calls = 0
    val filled =
      IconOutlineAssets.withOutlines(assets, setOf(search), CAP) {
        calls++
        "M1 1Z"
      }
    assertEquals(0, calls, "an outline the design already carries is what gets drawn")
    assertEquals(path, IconOutlineAssets.pathData(filled.getValue(search.assetKey())))
  }

  @Test
  fun `a host that cannot resolve keeps the pictures a design already had`() {
    // The cold-cache case. Answering null must not erase a design's icons — that would turn one
    // offline write into a design that renders empty boxes everywhere afterwards.
    val existing = mapOf(search.assetKey() to IconOutlineAssets.binding(path))
    val kept =
      IconOutlineAssets.withOutlines(existing, setOf(search, IconOutlineKeyV1("home")), CAP) {
        null
      }
    assertEquals(path, IconOutlineAssets.pathData(kept.getValue(search.assetKey())))
    assertEquals(1, kept.size, "an icon that could not be resolved simply has no entry yet")
  }

  @Test
  fun `additions stop at the design's asset ceiling`() {
    // Keeping every outline is only safe while it is bounded. Change one node's icon enough times
    // and the additions alone would pass a limit that only the upload route enforces, and the next
    // image somebody tried to upload would be refused because of pictures nobody asked for.
    val assets = mapOf(upload("logo"))
    val wanted = (1..10).map { IconOutlineKeyV1("icon_$it") }.toSet()

    val filled = IconOutlineAssets.withOutlines(assets, wanted, maximumAssets = 4) { path }

    assertEquals(4, filled.size, "the ceiling counts every asset, uploads included")
    assertEquals(assets.getValue("logo"), filled.getValue("logo"))
    // At the ceiling it stops rather than evicting: an icon with no recorded outline is a state
    // the design already understands, and eviction is what undo cannot survive.
    val full = IconOutlineAssets.withOutlines(filled, wanted, maximumAssets = 4) { path }
    assertTrue(full === filled)
  }
}
