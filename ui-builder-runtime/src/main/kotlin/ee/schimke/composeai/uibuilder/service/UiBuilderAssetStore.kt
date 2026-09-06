package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Where the **bytes** behind a design's `assets` map live.
 *
 * ## Why the bytes are not in the document
 *
 * `DesignDocumentV1.assets` binds an `assetKey` to an [AssetBindingV1], and the protocol offers
 * three sources for one: a catalog key, an embedded base64 payload, and an uploaded storage key.
 * The embedded form is the tempting one — a self-contained document renders anywhere without a
 * second fetch — and it is the wrong one for anything a person uploads. This service keeps a
 * whole-document snapshot per retained revision (`retainedRevisionSnapshots`, over a thousand of
 * them) inside one state file, so a 300 KiB photograph embedded in the document would be written
 * again on every accepted edit until the state file's ceiling refused the next one. The document
 * carries the **name** of the bytes; this store carries the bytes, once.
 *
 * ## Content-addressed, deliberately
 *
 * The storage key *is* the SHA-256 of the bytes, which is also the binding's `contentDigest`. That
 * gives three things for free: the same picture pinned under two keys or in two designs is stored
 * once; a document naming a digest can be checked against what the store hands back; and there is
 * nothing to delete when a key is re-pointed — the old bytes are simply no longer named. There is
 * no eviction: an asset is design content, and content that vanished under a design would be a
 * worse failure than a directory that grows. The per-asset and per-design caps in
 * [UiBuilderServiceLimits] bound how fast it can.
 */
public interface UiBuilderAssetStore {
  /** The bytes stored under [storageKey], or null when nothing is. */
  public fun read(storageKey: String): ByteArray?

  /**
   * Store [bytes] under [storageKey]. Idempotent: writing the bytes a key already holds is a no-op,
   * and a key never changes what it holds because the key is the digest of the content.
   */
  public fun write(storageKey: String, bytes: ByteArray)
}

/**
 * One file per digest under [root], written atomically so a crash mid-write leaves either the whole
 * asset or none of it, never a truncated PNG a renderer would fail on.
 */
public class FileUiBuilderAssetStore(private val root: Path) : UiBuilderAssetStore {
  init {
    Files.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder asset root is not a directory: $root" }
  }

  override fun read(storageKey: String): ByteArray? {
    val file = fileFor(storageKey) ?: return null
    return try {
      if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    } catch (_: IOException) {
      null
    }
  }

  override fun write(storageKey: String, bytes: ByteArray) {
    val file = requireNotNull(fileFor(storageKey)) { "malformed asset storage key: $storageKey" }
    if (Files.isRegularFile(file)) return
    val temporary = Files.createTempFile(root, "asset-", ".tmp")
    try {
      Files.write(temporary, bytes)
      try {
        Files.move(
          temporary,
          file,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  /**
   * The file a key names, or null for a key that is not one this store minted. A key is a digest
   * ([UiBuilderAssetDigests]), so the path is a fixed-width hex name and nothing an uploader chose
   * ever becomes a filename.
   */
  private fun fileFor(storageKey: String): Path? {
    val hex = UiBuilderAssetDigests.hexOf(storageKey) ?: return null
    return root.resolve("$hex.bin")
  }
}

/** The spelling of an asset digest, shared by the store, the service and whoever checks one. */
public object UiBuilderAssetDigests {
  private const val PREFIX = "sha256:"
  private val HEX = Regex("[0-9a-f]{64}")

  /** `sha256:<64 lowercase hex>` of [bytes]. */
  public fun of(bytes: ByteArray): String =
    PREFIX +
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** The hex half of a well-formed digest, or null for anything else. */
  public fun hexOf(digest: String): String? =
    digest.removePrefix(PREFIX).takeIf { it != digest && HEX.matches(it) }
}

/**
 * The asset lane of the UI-builder service: bytes in, a pinned binding out.
 *
 * A port beside [UiBuilderServicePort] rather than a request inside it, because the released
 * `UiBuilderRequestV1` / `DesignMutationV1` wire has no asset write and adding one means releasing
 * `ui-builder-protocol`. The HTTP route and the MCP tool that call this are the same kind of thing
 * as the native-preview route: a door beside the envelope, gated by the same authorization and the
 * same design access control, until the wire grows the request.
 */
public interface UiBuilderAssetPort {
  /**
   * Store [UiBuilderAssetWrite.bytes] and pin them into the design's `assets` under the key.
   *
   * Answers [UiBuilderServiceResponse.OperationOutcome] — accepted with the new revision, or
   * `idempotentReplay` when the key already names exactly these bytes — or
   * [UiBuilderServiceResponse.Error] for a design that cannot be found or written, bytes that are
   * not a raster image, or a limit.
   */
  public suspend fun putAsset(write: UiBuilderAssetWrite): UiBuilderServiceResponse

  /** The bytes a design's `assets` entry names, read as the actor through the design's ACL. */
  public suspend fun readAsset(read: UiBuilderAssetRead): UiBuilderAssetReadResult
}

/** One asset upload. A class rather than a data class: a byte array has no useful equality. */
public class UiBuilderAssetWrite(
  public val actor: AuthenticatedUiBuilderActor,
  public val designId: String,
  public val assetKey: String,
  public val bytes: ByteArray,
)

public data class UiBuilderAssetRead(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  val assetKey: String,
)

public sealed interface UiBuilderAssetReadResult {
  public class Found(public val binding: AssetBindingV1, public val bytes: ByteArray) :
    UiBuilderAssetReadResult

  public data class Failed(val error: UiBuilderServiceError) : UiBuilderAssetReadResult
}

/**
 * What a design may name an asset: the same alphabet as a node id, bounded, so a key is safe in a
 * URL path segment, a Kotlin identifier after sanitising, and a filename none of this ever makes.
 */
public object UiBuilderAssetKeys {
  public const val MAXIMUM_LENGTH: Int = 64
  private val KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

  public fun isValid(assetKey: String): Boolean = KEY.matches(assetKey)

  public const val RULE: String =
    "an asset key is 1 to 64 characters of letters, digits, '.', '_' or '-', starting with a " +
      "letter or digit"
}

/**
 * A raster image, sniffed from its bytes. The formats are the ones every renderer lane here decodes
 * through Skia and a browser draws natively; SVG is deliberately absent because it is a document
 * that can carry script, and an asset is bytes a design shows, not a page it runs.
 */
internal data class UiBuilderImageBytes(
  val mediaType: String,
  val widthPx: Int?,
  val heightPx: Int?,
) {
  internal companion object {
    const val KNOWN = "PNG, JPEG, GIF or WebP"

    fun sniff(bytes: ByteArray): UiBuilderImageBytes? =
      when {
        isPng(bytes) -> UiBuilderImageBytes("image/png", bytes.intAt(16), bytes.intAt(20))
        isGif(bytes) -> UiBuilderImageBytes("image/gif", bytes.shortLeAt(6), bytes.shortLeAt(8))
        isWebp(bytes) -> UiBuilderImageBytes("image/webp", null, null).withWebpSize(bytes)
        isJpeg(bytes) ->
          jpegSize(bytes).let { UiBuilderImageBytes("image/jpeg", it?.first, it?.second) }
        else -> null
      }

    private fun isPng(bytes: ByteArray): Boolean =
      bytes.size >= 24 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() &&
        bytes[3] == 'G'.code.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte() &&
        bytes.ascii(12, 4) == "IHDR"

    private fun isGif(bytes: ByteArray): Boolean =
      bytes.size >= 10 && (bytes.ascii(0, 6) == "GIF87a" || bytes.ascii(0, 6) == "GIF89a")

    private fun isWebp(bytes: ByteArray): Boolean =
      bytes.size >= 16 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"

    private fun isJpeg(bytes: ByteArray): Boolean =
      bytes.size >= 4 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

    private fun UiBuilderImageBytes.withWebpSize(bytes: ByteArray): UiBuilderImageBytes {
      if (bytes.size < 30) return this
      return when (bytes.ascii(12, 4)) {
        "VP8X" -> copy(widthPx = bytes.int24LeAt(24) + 1, heightPx = bytes.int24LeAt(27) + 1)
        "VP8L" -> {
          val b0 = bytes[21].toInt() and 0xFF
          val b1 = bytes[22].toInt() and 0xFF
          val b2 = bytes[23].toInt() and 0xFF
          val b3 = bytes[24].toInt() and 0xFF
          copy(
            widthPx = ((b1 and 0x3F) shl 8 or b0) + 1,
            heightPx = ((b3 and 0x0F) shl 10 or (b2 shl 2) or (b1 shr 6)) + 1,
          )
        }
        "VP8 " ->
          copy(
            widthPx = bytes.shortLeAt(26)?.and(0x3FFF),
            heightPx = bytes.shortLeAt(28)?.and(0x3FFF),
          )
        else -> this
      }
    }

    /** Walks the JPEG marker table to the first start-of-frame; null when it is not there. */
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
      var offset = 2
      while (offset + 9 < bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) return null
        val marker = bytes[offset + 1].toInt() and 0xFF
        if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 || marker == 0xFF) {
          offset += if (marker == 0xFF) 1 else 2
          continue
        }
        val length =
          ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        if (length < 2) return null
        val startOfFrame =
          marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (startOfFrame) {
          val height =
            ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
          val width =
            ((bytes[offset + 7].toInt() and 0xFF) shl 8) or (bytes[offset + 8].toInt() and 0xFF)
          return if (width > 0 && height > 0) width to height else null
        }
        offset += 2 + length
      }
      return null
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String? =
      if (offset + length > size) null else String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.intAt(offset: Int): Int? =
      if (offset + 4 > size) null
      else
        ((this[offset].toInt() and 0xFF) shl 24) or
          ((this[offset + 1].toInt() and 0xFF) shl 16) or
          ((this[offset + 2].toInt() and 0xFF) shl 8) or
          (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.shortLeAt(offset: Int): Int? =
      if (offset + 2 > size) null
      else (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.int24LeAt(offset: Int): Int =
      (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
  }
}
