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
   * The asset key this icon is stored under: readable, not hashed, so the design JSON shows which
   * icon and axes. Every axis is written, defaults included, so two writers cannot spell one
   * picture differently.
   */
  public fun assetKey(): String =
    "$PREFIX$style/$name@FILL${number(fill)},GRAD${number(grade)}," +
      "opsz${number(opticalSize)},wght${number(weight)}"

  private fun number(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

  public companion object {
    /**
     * What marks an asset as a host-resolved outline rather than an uploaded image. Both share
     * `DesignDocumentV1.assets` (the only per-design byte store), so anything walking `assets` must
     * treat this prefix as regenerable.
     */
    public const val PREFIX: String = "icon-outline/"

    /**
     * The media type of these bytes: SVG path data (the `d` attribute alone), not `image/svg+xml`,
     * since that is what `ImageVector`, the export and the renderer use.
     */
    public const val MEDIA_TYPE: String = "application/vnd.composeai.icon-path-data"
  }
}

/**
 * Resolves an icon to its path data, or null when this host cannot right now. A seam because the
 * fonts and cache live in the server, above this module.
 */
public fun interface IconOutlineResolver {
  public fun pathData(icon: IconOutlineKeyV1): String?
}

/**
 * Resolved icon outlines, stored in the design beside its images, so export, offline daemon
 * renders and the canvas agree and upstream refreshes cannot silently redraw old designs.
 * Replacing one is an explicit, recorded edit. See
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
   * The icons [nodes] draw (`iconName` plus the four axes, absent ones at the face default). Nodes
   * on the old `iconKey` contribute nothing; migrating them is a separate, deliberate edit.
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
   * Add-only because undo restores nodes but not pruned assets, and a cold cache could not recreate
   * them. [resolve] answering null leaves that icon without an entry. [maximumAssets] (the design's
   * asset ceiling) stops further additions rather than evicting, so outlines cannot crowd out
   * uploads.
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
