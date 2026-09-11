package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EmbeddedAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import kotlin.io.encoding.Base64

/** One icon as a design names it: a Material Symbols name, a face, and an axis position. */
public data class IconOutlineKeyV1(
  val name: String,
  val style: String = "outlined",
  val fill: Float = 0f,
  val weight: Float = 400f,
  val grade: Float = 0f,
  val opticalSize: Float = 24f,
) {

  /**
   * The asset key this icon is stored under.
   *
   * Readable rather than hashed, because somebody reading a design's JSON to work out why an icon
   * looks wrong should be able to see which icon it is and at what axis position. Every axis is
   * written, defaults included: a key that changes shape when a value happens to be the default is
   * one that two writers can spell differently for the same picture.
   */
  public fun assetKey(): String =
    "$PREFIX$style/$name@FILL${number(fill)},GRAD${number(grade)}," +
      "opsz${number(opticalSize)},wght${number(weight)}"

  private fun number(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

  public companion object {
    /**
     * What marks an asset as an outline this host resolved rather than an image somebody uploaded.
     *
     * The two share one map because `DesignDocumentV1.assets` is the only per-design byte store the
     * released protocol has, and it already carries everything an outline registry was specified to
     * need: an arbitrary key, a content digest, and bytes inline. What it does not carry is the
     * distinction, so this prefix is it. Anything walking `assets` — an export, a pack, the asset
     * store — has to treat a key under this prefix as a picture the host can regenerate rather than
     * one only the design has.
     */
    public const val PREFIX: String = "icon-outline/"

    /**
     * The media type these bytes are.
     *
     * Not `image/svg+xml`: what is stored is an SVG *path data* string, the `d` attribute alone,
     * which is what `ImageVector` and the export both want and what the renderer parses. Wrapping
     * it in a document would mean every reader unwrapping it again.
     */
    public const val MEDIA_TYPE: String = "application/vnd.composeai.icon-path-data"
  }
}

/**
 * Resolves an icon to its path data, or null when this host cannot right now.
 *
 * A seam rather than a direct call because the thing that can resolve a glyph lives in the server,
 * which sits above this module: the variable fonts, their cache and the reader are all up there,
 * and a layer cannot reach up. A host with no icon source passes nothing and every other lane is
 * exactly as it was.
 */
public fun interface IconOutlineResolver {
  public fun pathData(icon: IconOutlineKeyV1): String?
}

/**
 * Resolved icon outlines, stored in the design beside its images.
 *
 * A design records the picture it drew so that an export, a daemon render on a runner with no
 * network, and the canvas cannot disagree — and so that a later upstream refresh cannot silently
 * redraw an old design. An entry is created once by a lookup; from then on it *is* the icon, and
 * replacing it is an explicit operation that lands in the revision history like any other edit.
 *
 * See
 * [`UI_BUILDER_MATERIAL_SYMBOLS.md`](../../../../../../../../../docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md).
 */
public object IconOutlineAssets {

  /** The binding for [pathData], content-digested like every other asset in the map. */
  public fun binding(pathData: String): AssetBindingV1 {
    val bytes = pathData.encodeToByteArray()
    return AssetBindingV1(
      mediaType = IconOutlineKeyV1.MEDIA_TYPE,
      contentDigest = UiBuilderAssetDigests.of(bytes),
      source = EmbeddedAssetSourceV1(base64 = Base64.encode(bytes)),
      // Deliberately absent. An outline has no pixel size: it is drawn at whatever the node's
      // `sizeDp` says, in a 960-unit viewport. Filling these in with 24 would be inventing one.
      widthPx = null,
      heightPx = null,
    )
  }

  /** The path data an outline entry carries, or null when [binding] is not one of ours. */
  public fun pathData(binding: AssetBindingV1): String? {
    if (binding.mediaType != IconOutlineKeyV1.MEDIA_TYPE) return null
    val source = binding.source as? EmbeddedAssetSourceV1 ?: return null
    return runCatching { Base64.decode(source.base64).decodeToString() }.getOrNull()
  }

  /** True for a key this object owns, so a caller can leave uploads alone. */
  public fun isOutlineKey(assetKey: String): Boolean = assetKey.startsWith(IconOutlineKeyV1.PREFIX)

  /**
   * The icons [nodes] draw, read from whatever each node carries.
   *
   * A node names its icon with `iconName` and positions it with the four axes; anything absent is
   * the face's own default. A node still on the old `iconKey` contributes nothing here — it draws
   * through the generated table as it always has, and migrating it is a separate, deliberate edit
   * rather than something a write of an unrelated property does behind somebody's back.
   */
  public fun drawnBy(nodes: Iterable<DesignNodeV1>): Set<IconOutlineKeyV1> =
    nodes
      .mapNotNull { node ->
        val name = node.text("iconName")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        IconOutlineKeyV1(
          name = name,
          style = node.text("iconStyle") ?: "outlined",
          fill = node.number("iconFill") ?: 0f,
          weight = node.number("iconWeight") ?: 400f,
          grade = node.number("iconGrade") ?: 0f,
          opticalSize = node.number("iconOpticalSize") ?: 24f,
        )
      }
      .toSet()

  private fun DesignNodeV1.text(property: String): String? =
    when (val value = properties[property]) {
      is StringValueV1 -> value.value
      is EnumValueV1 -> value.value
      else -> null
    }

  private fun DesignNodeV1.number(property: String): Float? =
    when (val value = properties[property]) {
      is DecimalValueV1 -> value.value.toFloat()
      is IntegerValueV1 -> value.value.toFloat()
      else -> null
    }

  /**
   * [assets] with an outline present for every icon in [wanted], and nothing removed.
   *
   * **Adding only, deliberately.** An outline nothing references looks like dead weight, and the
   * first version of this dropped it. That is wrong: undo restores a node without restoring the
   * assets pruned when it changed, so recreating the outline would need the resolver — and the
   * documented cold-cache case is exactly when there is none. Undo would then return a document
   * that is not the one it undid, with blank icons where there were pictures. So a design keeps the
   * outline of every icon it has drawn, bounded by the distinct icons somebody chose at about 550
   * bytes each, and pruning becomes an explicit operation if it ever earns one.
   *
   * [resolve] answering null leaves that icon without an entry yet. It never removes one.
   *
   * [maximumAssets] is the design's own asset ceiling, and it binds here because keeping every
   * outline is otherwise unbounded: change one node's icon enough times and the additions alone
   * could pass a limit only the upload route enforces, which would then refuse somebody's next
   * image because of pictures nobody asked for. At the ceiling this simply stops adding — an icon
   * without a recorded outline, the same state a host with no resolver leaves — rather than
   * evicting one, because eviction is what undo cannot survive.
   */
  public fun withOutlines(
    assets: Map<String, AssetBindingV1>,
    wanted: Set<IconOutlineKeyV1>,
    maximumAssets: Int,
    resolve: (IconOutlineKeyV1) -> String?,
  ): Map<String, AssetBindingV1> {
    val room = maximumAssets - assets.size
    if (room <= 0) return assets
    val missing = wanted.filterNot { it.assetKey() in assets }
    if (missing.isEmpty()) return assets
    val added =
      missing
        .asSequence()
        .mapNotNull { icon -> resolve(icon)?.let { icon.assetKey() to binding(it) } }
        .take(room)
        .toList()
    return if (added.isEmpty()) assets else assets + added
  }
}
