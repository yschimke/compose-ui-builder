package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.reference.FIGMA_PASTE_ADVICE
import ee.schimke.composeai.uibuilder.reference.MAX_REFERENCE_BYTES
import ee.schimke.composeai.uibuilder.reference.ReferenceDiffMode
import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceImage
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import ee.schimke.composeai.uibuilder.reference.ReferenceMark
import ee.schimke.composeai.uibuilder.reference.ReferenceMarkupKind
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlaySettings
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.reference.ReferencePiece
import ee.schimke.composeai.uibuilder.reference.ReferenceUrl
import ee.schimke.composeai.uibuilder.reference.RestoredReference
import ee.schimke.composeai.uibuilder.reference.parseReferenceUrl
import ee.schimke.composeai.uibuilder.reference.referenceImportRefusal
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The JVM half of the reference overlay: a file dialog, a link fetcher, and a file per design.
 *
 * The browser host keeps references on the serve host and refuses to hold a design-tool credential,
 * because that page is served to whoever has the link. This one runs as the operator, on their
 * machine, so it may read *their* Figma token from the environment — `FIGMA_TOKEN`, the name
 * Figma's own tooling uses — and render a frame through Figma's images API. Without one, a Figma
 * link is refused with the paste route, exactly as on the web.
 */
class JvmReferenceHost(
  /** Where this design's reference is kept, or null to keep it for the session only. */
  private val storeFile: Path?,
  private val figmaToken: () -> String? = {
    System.getenv("FIGMA_TOKEN")?.takeIf(String::isNotBlank)
  },
  private val http: HttpClient =
    HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build(),
) {
  private var sequence = 0

  /** A picture chosen in a native dialog, or [ReferenceImportOutcome.Cancelled]. */
  fun pickFile(owner: Frame? = null): ReferenceImportOutcome {
    val dialog = FileDialog(owner, "Import reference", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in EXTENSIONS }
    dialog.isVisible = true
    val name = dialog.file ?: return ReferenceImportOutcome.Cancelled
    return importFile(File(dialog.directory, name).toPath())
  }

  /** A picture read from disk, sniffed and refused by the same rules a paste is. */
  fun importFile(path: Path): ReferenceImportOutcome {
    val size = runCatching {
      Files.size(path)
    }
      .getOrElse {
        return ReferenceImportOutcome.Refused("${path.fileName} could not be read.")
      }
    if (size > MAX_REFERENCE_BYTES) {
      return ReferenceImportOutcome.Refused(
        "A reference must be under ${MAX_REFERENCE_BYTES / (1024 * 1024)} MB."
      )
    }
    return imported(Files.readAllBytes(path), path.fileName.toString(), sourceUrl = null)
  }

  /** A picture behind a link: a Figma frame rendered at 2×, or an image fetched as it is. */
  suspend fun fetchUrl(text: String): ReferenceImportOutcome =
    withContext(Dispatchers.IO) {
      when (val parsed = parseReferenceUrl(text)) {
        is ReferenceUrl.Unsupported -> ReferenceImportOutcome.Refused(parsed.reason)
        is ReferenceUrl.Image ->
          download(parsed.url)
            .fold(
              { imported(it, parsed.url.substringAfterLast('/').substringBefore('?'), parsed.url) },
              { ReferenceImportOutcome.Refused(it.message ?: "the link could not be fetched") },
            )
        is ReferenceUrl.Figma -> fetchFigma(parsed)
      }
    }

  private fun fetchFigma(link: ReferenceUrl.Figma): ReferenceImportOutcome {
    val nodeId =
      link.nodeId
        ?: return ReferenceImportOutcome.Refused(
          "That links the whole file. Copy a link to one frame instead."
        )
    val token =
      figmaToken()
        ?: return ReferenceImportOutcome.Refused(
          "Set FIGMA_TOKEN to a Figma personal access token to fetch frames. $FIGMA_PASTE_ADVICE"
        )
    val api =
      "https://api.figma.com/v1/images/${link.fileKey}" +
        "?ids=${URLEncoder.encode(nodeId, StandardCharsets.UTF_8)}&format=png&scale=$FIGMA_SCALE"
    val answer = runCatching {
      http.send(
        HttpRequest.newBuilder(URI.create(api))
          .header("X-Figma-Token", token)
          .timeout(Duration.ofSeconds(60))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString(),
      )
    }
      .getOrElse {
        return ReferenceImportOutcome.Refused("Figma could not be reached: ${it.message}")
      }
    if (answer.statusCode() != 200) {
      return ReferenceImportOutcome.Refused(
        when (answer.statusCode()) {
          403 -> "Figma refused the token (403): it cannot read that file."
          404 -> "Figma has no such file (404)."
          else -> "Figma answered ${answer.statusCode()}."
        }
      )
    }
    val imageUrl =
      runCatching {
        val images = Json.parseToJsonElement(answer.body()).jsonObject["images"] as? JsonObject
        (images?.get(nodeId) as? JsonPrimitive)?.contentOrNull
      }
        .getOrNull()
        ?: return ReferenceImportOutcome.Refused("Figma rendered nothing for frame $nodeId.")
    return download(imageUrl)
      .fold(
        { imported(it, link.importName(FIGMA_SCALE), link.url) },
        { ReferenceImportOutcome.Refused(it.message ?: "the rendered frame could not be fetched") },
      )
  }

  private fun download(url: String): Result<ByteArray> = runCatching {
    val response =
      http.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream(),
      )
    if (response.statusCode() != 200) {
      response.body().close()
      error("the link answered ${response.statusCode()}")
    }
    response.body().use { stream ->
      // Read one byte past the ceiling, so an oversized picture is refused without being held.
      val bytes = stream.readNBytes(MAX_REFERENCE_BYTES + 1)
      if (bytes.size > MAX_REFERENCE_BYTES) {
        error("a reference must be under ${MAX_REFERENCE_BYTES / (1024 * 1024)} MB")
      }
      bytes
    }
  }

  private fun imported(bytes: ByteArray, name: String, sourceUrl: String?): ReferenceImportOutcome {
    val mediaType =
      sniffMediaType(bytes)
        ?: return ReferenceImportOutcome.Refused("That is not a PNG, JPEG, WebP or SVG picture.")
    val svgText = if (mediaType == ReferenceImage.SVG_MEDIA_TYPE) bytes.decodeToString() else null
    referenceImportRefusal(mediaType, bytes.size, svgText)?.let {
      return ReferenceImportOutcome.Refused(it)
    }
    val decoded =
      if (svgText == null) runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
      else null
    return ReferenceImportOutcome.Imported(
      ReferenceImage(
        id = "jvm-${digest(bytes).take(16)}-${++sequence}",
        name = name.ifBlank { "Reference" },
        mediaType = mediaType,
        base64 = Base64.getEncoder().encodeToString(bytes),
        widthPx = decoded?.width ?: 0,
        heightPx = decoded?.height ?: 0,
        sourceUrl = sourceUrl,
      )
    )
  }

  /** What was kept for this design, or null when nothing was or it no longer reads. */
  fun load(): RestoredReference? {
    val file = storeFile?.takeIf(Files::isRegularFile) ?: return null
    val stored =
      runCatching {
        storeJson.decodeFromString(StoredReferenceFile.serializer(), Files.readString(file))
      }
        .getOrNull() ?: return null
    return RestoredReference(
      image = stored.image?.toImage(),
      settings =
        ReferenceOverlaySettings(
            mode = ReferenceDiffMode.ofWire(stored.settings.mode),
            visible = stored.settings.visible,
            opacityPercent = stored.settings.opacityPercent,
            offsetXDp = stored.settings.offsetXDp,
            offsetYDp = stored.settings.offsetYDp,
            scalePercent = stored.settings.scalePercent,
            splitPercent = stored.settings.splitPercent,
            alwaysShowBoxes = stored.settings.alwaysShowBoxes,
            fit = ReferenceFit.ofWire(stored.settings.fit),
          )
          .sanitized(),
      pieces =
        stored.pieces.map {
          ReferencePiece(
            id = it.id,
            image = it.image.toImage(),
            left = it.left,
            top = it.top,
            right = it.right,
            bottom = it.bottom,
            opacityPercent = it.opacityPercent,
            componentId = it.componentId,
          )
        },
      marks =
        stored.marks.map {
          ReferenceMark(
            id = it.id,
            kind = ReferenceMarkupKind.ofWire(it.kind),
            points = it.points,
            colorArgb = it.colorArgb,
            strokeWidthDp = it.strokeWidthDp,
            text = it.text,
          )
        },
    )
  }

  /** Keep [reference], or forget it when there is nothing in it. Null on success. */
  fun save(reference: ReferenceOverlayState): String? {
    val file = storeFile ?: return null
    return runCatching {
      if (!reference.hasContent) {
        Files.deleteIfExists(file)
        return null
      }
      Files.createDirectories(file.toAbsolutePath().parent)
      val settings = reference.settings
      val record =
        StoredReferenceFile(
          image = reference.image?.toStored(),
          settings =
            StoredReferenceSettingsFile(
              mode = settings.mode.wireValue,
              visible = settings.visible,
              opacityPercent = settings.opacityPercent,
              offsetXDp = settings.offsetXDp,
              offsetYDp = settings.offsetYDp,
              scalePercent = settings.scalePercent,
              splitPercent = settings.splitPercent,
              alwaysShowBoxes = settings.alwaysShowBoxes,
              fit = settings.fit.wireValue,
            ),
          pieces =
            reference.pieces.map {
              StoredReferencePieceFile(
                it.id,
                it.image.toStored(),
                it.left,
                it.top,
                it.right,
                it.bottom,
                it.opacityPercent,
                it.componentId,
              )
            },
          marks =
            reference.marks.map {
              StoredReferenceMarkFile(
                it.id,
                it.kind.wireValue,
                it.points,
                it.colorArgb,
                it.strokeWidthDp,
                it.text,
              )
            },
        )
      DesignFiles.writeText(
        file,
        storeJson.encodeToString(StoredReferenceFile.serializer(), record),
      )
      null
    }
      .getOrElse { "the reference could not be kept: ${it.message}" }
  }

  companion object {
    /**
     * The file a design's reference is kept in under [directory], named by a digest of [key] — a
     * design id or a file path is caller-supplied text and never becomes a path segment.
     */
    fun storeFor(directory: Path, key: String): Path =
      directory.resolve("${digest(key.toByteArray()).take(32)}.json")

    /** The media type the bytes declare by their first bytes, or null for none this accepts. */
    fun sniffMediaType(bytes: ByteArray): String? {
      fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
      return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        bytes.size >= 12 &&
          String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
          String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> {
          val head =
            String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿').trimStart()
          if (head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head)) {
            ReferenceImage.SVG_MEDIA_TYPE
          } else null
        }
      }
    }

    private fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "svg")

    /**
     * Figma's export scale for a fetched frame; 2× matches what "Copy as PNG" puts on a clipboard.
     */
    private const val FIGMA_SCALE = 2

    private val storeJson = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}

private fun ReferenceImage.toStored() =
  StoredReferenceImageFile(id, name, mediaType, base64, widthPx, heightPx, sourceUrl)

private fun StoredReferenceImageFile.toImage() =
  ReferenceImage(
    id = id.ifBlank { "stored-${base64.length}" },
    name = name,
    mediaType = mediaType,
    base64 = base64,
    widthPx = widthPx,
    heightPx = heightPx,
    sourceUrl = sourceUrl,
  )

// The browser host's wire, field for field, so a reference file and a served record read alike.
@Serializable
private data class StoredReferenceFile(
  val image: StoredReferenceImageFile? = null,
  val settings: StoredReferenceSettingsFile = StoredReferenceSettingsFile(),
  val pieces: List<StoredReferencePieceFile> = emptyList(),
  val marks: List<StoredReferenceMarkFile> = emptyList(),
)

@Serializable
private data class StoredReferenceImageFile(
  val id: String = "",
  val name: String = "reference",
  val mediaType: String = "image/png",
  val base64: String = "",
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  val sourceUrl: String? = null,
)

@Serializable
private data class StoredReferenceSettingsFile(
  val mode: String = "overlay",
  val visible: Boolean = true,
  val opacityPercent: Int = 50,
  val offsetXDp: Float = 0f,
  val offsetYDp: Float = 0f,
  val scalePercent: Int = 100,
  val splitPercent: Int = 50,
  val alwaysShowBoxes: Boolean = false,
  val fit: String = "contain",
)

@Serializable
private data class StoredReferencePieceFile(
  val id: String,
  val image: StoredReferenceImageFile,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val opacityPercent: Int = 100,
  val componentId: String? = null,
)

@Serializable
private data class StoredReferenceMarkFile(
  val id: String,
  val kind: String,
  val points: List<Float>,
  val colorArgb: Long,
  val strokeWidthDp: Float = 2f,
  val text: String? = null,
)
