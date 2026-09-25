package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeDocuments
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.reference.decodeReferenceBitmap
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an `asset/image` node's `assetKey` resolves to, decided once and read by every lane that
 * draws or exports one — the canvas, the daemon render, the SVG recorder and its readiness gate.
 *
 * One resolution rather than a `when` per lane, because the lanes used to disagree: the canvas knew
 * two Jetcaster keys and a gradient and threw on anything else, the SVG recorder knew only the
 * Jetcaster keys and threw on the gradient, and the daemon render knew nothing the canvas did not.
 * A key nothing resolves is [Missing], which every lane draws as a visible placeholder and none of
 * them fails on — a design holding a picture the host cannot show is an ordinary state, not a
 * broken frame.
 */
internal sealed interface ResolvedUiBuilderAsset {
  /** Bytes carried in the document itself, as the registry's `embedded` source. */
  data class Embedded(val contentDigest: String, val mediaType: String, val bytes: ByteArray) :
    ResolvedUiBuilderAsset

  /**
   * Bytes the document names by storage key and a host has to fetch. The canvas asks
   * [LocalUiBuilderAssetBitmaps] for them; a lane with no host resolver draws the placeholder.
   */
  data class Uploaded(val contentDigest: String, val mediaType: String, val storageKey: String) :
    ResolvedUiBuilderAsset

  /** One of the two project-owned Jetcaster covers, drawn from this build's own resources. */
  data class ProjectOwned(val assetKey: String) : ResolvedUiBuilderAsset

  /** The gate-0 cover, a generated gradient with no bytes behind it. */
  data object Generated : ResolvedUiBuilderAsset

  /** Nothing the document or this build can draw. */
  data class Missing(val assetKey: String) : ResolvedUiBuilderAsset
}

/** The gate-0 fixture's cover key, kept for the fixtures and previews that still name it. */
internal const val GATE0_COVER_ASSET_KEY = "ui-builder.gate0.cover"

/** Resolve [assetKey] against this document's registry, then against what the build ships. */
internal fun UiBuilderDocument.resolveAsset(assetKey: String): ResolvedUiBuilderAsset {
  val binding = assets[assetKey] as? JsonObject
  if (binding != null) {
    val digest = binding.text("contentDigest").orEmpty()
    val mediaType = binding.text("mediaType").orEmpty()
    val source = binding["source"] as? JsonObject
    when (source?.text("type")) {
      "embedded" -> {
        val encoded = source.text("base64")
        val bytes =
          try {
            if (encoded == null) null else Base64.Default.decode(encoded)
          } catch (_: IllegalArgumentException) {
            null
          }
        if (bytes != null && bytes.isNotEmpty()) {
          return ResolvedUiBuilderAsset.Embedded(digest, mediaType, bytes)
        }
      }
      "uploaded" -> {
        val storageKey = source.text("storageKey")
        if (!storageKey.isNullOrBlank()) {
          return ResolvedUiBuilderAsset.Uploaded(digest, mediaType, storageKey)
        }
      }
      // A `catalog` source names a key the catalog is meant to ship; it resolves below or not at
      // all, exactly as the bare key would.
      else -> Unit
    }
  }
  return when (assetKey) {
    ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY,
    GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY -> ResolvedUiBuilderAsset.ProjectOwned(assetKey)
    GATE0_COVER_ASSET_KEY -> ResolvedUiBuilderAsset.Generated
    else -> ResolvedUiBuilderAsset.Missing(assetKey)
  }
}

/** Whether an SVG export can embed real pixels for this key on the JVM, without a host fetch. */
internal fun ResolvedUiBuilderAsset.hasRasterBytesOffline(): Boolean =
  this is ResolvedUiBuilderAsset.Embedded || this is ResolvedUiBuilderAsset.ProjectOwned

/**
 * Pixels for an uploaded asset, by **content digest**, answered by whatever host is drawing.
 *
 * A composition local for the reason [LocalRemoteComposeDocuments] is one: the canvas, the
 * thumbnails and the previews all draw a document, and none of them should have to thread a fetcher
 * through. A lookup rather than a fetch, for the same reason too — loading bytes is suspending,
 * size-limited and cancellable, and the host owns all three. Keyed by digest rather than by asset
 * key so re-pointing a key at a new picture invalidates the old one for free, and two keys naming
 * one picture decode it once.
 *
 * `null` is *not available here*: not fetched yet, failed to decode, or a host with no resolver at
 * all. The node draws its placeholder for it and nothing else happens.
 */
public val LocalUiBuilderAssetBitmaps: ProvidableCompositionLocal<(String) -> ImageBitmap?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * The encoded bytes of an uploaded asset, by **content digest**, for hosts that hand a design to a
 * renderer they cannot share pixels with.
 *
 * A catalog runtime draws in a sandboxed, opaque-origin frame: it cannot fetch the design's assets
 * and must not be given a credential to try. What it can be given is the document, so the host
 * inlines these bytes into the copy it posts ([withInlinedUploadedAssets]) and the runtime resolves
 * them as `embedded`. `null` is not available (yet) here, exactly as for
 * [LocalUiBuilderAssetBitmaps].
 */
public val LocalUiBuilderAssetBytes: ProvidableCompositionLocal<(String) -> ByteArray?> =
  staticCompositionLocalOf {
    { _ -> null }
  }

/**
 * This document with every uploaded asset whose bytes [bytesByDigest] has re-sourced as `embedded`.
 *
 * Only the `source` changes: the key, digest and media type stay what the design stored, so a
 * runtime resolves the same asset it would have asked the host for. Assets whose bytes are not
 * available yet are left as they are, and a document with none is returned unchanged.
 */
internal fun UiBuilderDocument.withInlinedUploadedAssets(
  bytesByDigest: (String) -> ByteArray?
): UiBuilderDocument {
  val inlined =
    uploadedAssets().mapNotNull { (key, asset) ->
      val bytes =
        bytesByDigest(asset.contentDigest)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      val binding = assets[key] as? JsonObject ?: return@mapNotNull null
      key to
        JsonObject(
          binding +
            ("source" to
              JsonObject(
                mapOf(
                  "type" to JsonPrimitive("embedded"),
                  "base64" to JsonPrimitive(Base64.Default.encode(bytes)),
                )
              ))
        )
    }
  return if (inlined.isEmpty()) this else copy(assets = JsonObject(assets + inlined))
}

/**
 * Bytes to pixels for a design asset. Null rather than throwing, because a picture that does not
 * decode must leave the design drawing, with a placeholder in its frame, rather than fail it.
 */
internal fun decodeUiBuilderAssetBitmap(bytes: ByteArray): ImageBitmap? =
  decodeReferenceBitmap(bytes, vector = false, targetWidthPx = 0, targetHeightPx = 0)

private fun JsonObject.text(name: String): String? =
  try {
    this[name]?.jsonPrimitive?.contentOrNull
  } catch (_: IllegalArgumentException) {
    null
  }

/** The registry entries of this document that a host must fetch, keyed by asset key. */
internal fun UiBuilderDocument.uploadedAssets(): Map<String, ResolvedUiBuilderAsset.Uploaded> =
  assets.keys
    .sorted()
    .mapNotNull { key ->
      (resolveAsset(key) as? ResolvedUiBuilderAsset.Uploaded)?.let { key to it }
    }
    .toMap()
