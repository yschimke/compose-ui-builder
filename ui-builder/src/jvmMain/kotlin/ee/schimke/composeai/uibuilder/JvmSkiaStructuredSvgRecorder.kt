package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGCanvas

/**
 * JVM execution bridge from the same native Compose renderer to Skia's structured SVG canvas.
 * `convertTextToPaths=false` asks Skia to retain text where its backend can represent it.
 *
 * This remains a feasibility spike, not the production render-host bridge. Its deterministic output
 * claim is scoped to the same Compose/Skiko/font/OS/architecture runtime. Generic Skia-created
 * images remain anonymous and fail closed. Declared asset images are correlated by re-rendering
 * that exact node through the same pinned raster provider and matching the unique embedded payload
 * digest, never by assigning document nodes to SVG image order. `matchParentSize` assets use bounds
 * from a clean Compose inspection pass, and those bounds must remain identical during SVG
 * recording. Known catalog icons are emitted from their `iconKey` ImageVector path data without a
 * tint filter; unsupported vector structures and every remaining anonymous image fail closed.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
object JvmSkiaStructuredSvgRecorder : StructuredSvgSceneRecorder {
  override val kind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS

  fun record(document: UiBuilderDocument): StructuredSvgRecording =
    record(StructuredSvgRecordingRequest(document, emptyList()))

  override fun record(request: StructuredSvgRecordingRequest): StructuredSvgRecording {
    val document = request.document
    val layoutProvenance =
      request.declaredRasterFallbackNodeIds.takeIf(List<String>::isNotEmpty)?.let {
        measureLayout(document)
      }
    val rasterAssets =
      JvmStructuredSvgRasterAssets.create(
        document,
        request.declaredRasterFallbackNodeIds,
        layoutProvenance?.nodeBounds.orEmpty(),
      )
    return try {
      val expectedPayloads =
        request.declaredRasterFallbackNodeIds.associateWith { nodeId ->
          val node = document.nodes.getValue(nodeId)
          require(node.componentId == "asset/image") {
            "JVM recorder can correlate declared raster assets only; $nodeId is ${node.componentId}"
          }
          val isolated =
            document.copy(
              id = "${document.id}-raster-probe-$nodeId",
              roots = listOf(nodeId),
              nodes = mapOf(nodeId to node),
            )
          val probe = parseStrictSvg(recordRaw(isolated, rasterAssets))
          val images = requireNotNull(probe.document) { "raster probe $nodeId was invalid" }.images
          require(images.size == 1) {
            "raster probe $nodeId emitted ${images.size} images; correlation is ambiguous"
          }
          requireNotNull(images.single().embeddedImagePayloadDigest()) {
            "raster probe $nodeId did not emit an embedded raster payload"
          }
        }
      require(expectedPayloads.values.toSet().size == expectedPayloads.size) {
        "declared raster asset payloads are not unique enough for node correlation"
      }
      var recordedLayout: UiBuilderInspectionSnapshot? = null
      val svg =
        recordRaw(document, rasterAssets) { if (layoutProvenance != null) recordedLayout = it }
      layoutProvenance?.requireStableRasterBounds(
        checkNotNull(recordedLayout) { "SVG recording produced no layout inspection snapshot" },
        request.declaredRasterFallbackNodeIds,
      )
      val emittedDigests =
        parseStrictSvg(svg).document?.images.orEmpty().mapNotNull {
          it.embeddedImagePayloadDigest()
        }
      val records = expectedPayloads.map { (nodeId, digest) ->
        require(emittedDigests.count { it == digest } == 1) {
          "saved scene did not emit exactly one payload correlated to $nodeId"
        }
        val asset = rasterAssets.identities.getValue(nodeId)
        StructuredSvgRasterRecord(
          nodeId = nodeId,
          sourceIdentity = asset.sourceIdentity,
          sourceIdentitySha256 = asset.sourceIdentitySha256,
          renderedWidthPx = asset.widthPx,
          renderedHeightPx = asset.heightPx,
          embeddedPayloadSha256 = digest,
          reason = "pinned asset image rendered by node $nodeId",
        )
      }
      StructuredSvgRecording(
        svg = svg,
        producer = "skia-svg-canvas/0.144.6",
        rasterRecords = records,
        determinismScope = SAME_RUNTIME_DETERMINISM_SCOPE,
      )
    } finally {
      rasterAssets.close()
    }
  }

  private fun recordRaw(
    document: UiBuilderDocument,
    rasterAssets: JvmStructuredSvgRasterAssets,
    onInspectionSnapshot: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
  ): String {
    val widthDp = document.environmentNumber("widthDp")
    val heightDp = document.environmentNumber("heightDp")
    val density = document.environmentNumber("density")
    val fontScale = document.environmentNumber("fontScale")
    val widthPx = (widthDp * density).roundToInt()
    val heightPx = (heightDp * density).roundToInt()
    val layoutDirection =
      if (document.environmentText("layoutDirection") == "rtl") LayoutDirection.Rtl
      else LayoutDirection.Ltr
    val output = DynamicMemoryWStream()
    val skiaCanvas =
      SVGCanvas.make(
        Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()),
        output,
        convertTextToPaths = false,
        prettyXML = true,
      )
    val scene =
      CanvasLayersComposeScene(
        density = Density(density, fontScale),
        layoutDirection = layoutDirection,
        size = IntSize(widthPx, heightPx),
        coroutineContext = EmptyCoroutineContext,
        invalidate = {},
      )
    return try {
      try {
        scene.setContent {
          CompositionLocalProvider(
            LocalUiBuilderExportRasterAssets provides rasterAssets.bitmaps,
            LocalUiBuilderExportStructuredIcons provides true,
          ) {
            UiBuilderSurface(
              document = document,
              editorOverlay = false,
              onInspectionSnapshot = onInspectionSnapshot,
            )
          }
        }
        scene.render(skiaCanvas.asComposeCanvas(), document.fixedFrameNanos())
      } finally {
        try {
          scene.close()
        } finally {
          skiaCanvas.close()
        }
      }
      val bytes = ByteArray(output.bytesWritten())
      check(output.read(bytes, 0, bytes.size)) { "Skia SVG stream could not be read" }
      bytes.decodeToString().canonicalizeSkiaResourceIds()
    } finally {
      output.close()
    }
  }

  private fun measureLayout(document: UiBuilderDocument): JvmStructuredSvgLayoutProvenance {
    val widthDp = document.environmentNumber("widthDp")
    val heightDp = document.environmentNumber("heightDp")
    val density = document.environmentNumber("density")
    val fontScale = document.environmentNumber("fontScale")
    val widthPx = (widthDp * density).roundToInt()
    val heightPx = (heightDp * density).roundToInt()
    val layoutDirection =
      if (document.environmentText("layoutDirection") == "rtl") LayoutDirection.Rtl
      else LayoutDirection.Ltr
    val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
    val scene =
      CanvasLayersComposeScene(
        density = Density(density, fontScale),
        layoutDirection = layoutDirection,
        size = IntSize(widthPx, heightPx),
        coroutineContext = EmptyCoroutineContext,
        invalidate = {},
      )
    var snapshot: UiBuilderInspectionSnapshot? = null
    return try {
      scene.setContent {
        UiBuilderSurface(
          document = document,
          editorOverlay = false,
          onInspectionSnapshot = { snapshot = it },
        )
      }
      scene.render(surface.canvas.asComposeCanvas(), document.fixedFrameNanos())
      val measured =
        checkNotNull(snapshot) { "layout provenance pass produced no inspection snapshot" }
      require(
        measured.documentId == document.id && measured.documentRevision == document.revision
      ) {
        "layout provenance does not match the saved document revision"
      }
      JvmStructuredSvgLayoutProvenance(
        documentId = measured.documentId,
        documentRevision = measured.documentRevision,
        nodeBounds =
          measured.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap(),
      )
    } finally {
      scene.close()
      surface.close()
    }
  }
}

internal data class JvmStructuredSvgLayoutProvenance(
  val documentId: String,
  val documentRevision: Int,
  val nodeBounds: Map<String, UiBuilderPixelBounds>,
) {
  fun requireStableRasterBounds(
    recorded: UiBuilderInspectionSnapshot,
    rasterNodeIds: List<String>,
  ) {
    require(recorded.documentId == documentId && recorded.documentRevision == documentRevision) {
      "SVG layout inspection does not match measured layout provenance"
    }
    val recordedBounds =
      recorded.nodes.mapNotNull { node -> node.bounds?.let { node.nodeId to it } }.toMap()
    rasterNodeIds.forEach { nodeId ->
      require(nodeBounds[nodeId] == recordedBounds[nodeId]) {
        "raster node $nodeId changed bounds between provenance and SVG recording"
      }
    }
  }
}

internal class JvmStructuredSvgRasterAssets
private constructor(
  val bitmaps: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
  val identities: Map<String, JvmStructuredSvgRasterIdentity>,
  private val nativeImages: List<Image>,
) : AutoCloseable {
  override fun close() {
    nativeImages.forEach(Image::close)
  }

  companion object {
    fun create(
      document: UiBuilderDocument,
      nodeIds: List<String>,
      layoutBounds: Map<String, UiBuilderPixelBounds> = emptyMap(),
    ): JvmStructuredSvgRasterAssets {
      val images = mutableListOf<Image>()
      return try {
        val identities = nodeIds.associateWith { nodeId ->
          val node = document.nodes.getValue(nodeId)
          require(node.componentId == "asset/image") {
            "raster export node $nodeId is not an asset/image"
          }
          val assetKey = node.stringProperty("assetKey")
          val (widthPx, heightPx) = node.rasterPixelSize(document, layoutBounds[nodeId])
          val sourceIdentity = "generated-placeholder/v1/$assetKey/${widthPx}x$heightPx"
          val sourceRecipe =
            "$sourceIdentity|argb=${pinnedAssetColors(assetKey).joinToString(",") { it.toUInt().toString(16) }}"
          JvmStructuredSvgRasterIdentity(
              image = createPinnedAssetImage(assetKey, widthPx, heightPx),
              sourceIdentity = sourceIdentity,
              sourceIdentitySha256 = sha256Hex(sourceRecipe),
              widthPx = widthPx,
              heightPx = heightPx,
            )
            .also { images += it.image }
        }
        JvmStructuredSvgRasterAssets(
          bitmaps = identities.mapValues { it.value.image.toComposeImageBitmap() },
          identities = identities,
          nativeImages = images,
        )
      } catch (failure: Throwable) {
        images.forEach(Image::close)
        throw failure
      }
    }
  }
}

internal data class JvmStructuredSvgRasterIdentity(
  val image: Image,
  val sourceIdentity: String,
  val sourceIdentitySha256: String,
  val widthPx: Int,
  val heightPx: Int,
)

private fun createPinnedAssetImage(assetKey: String, widthPx: Int, heightPx: Int): Image {
  val colors = pinnedAssetColors(assetKey)
  val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
  return try {
    surface.canvas.clear(colors[0])
    Paint().use { paint ->
      paint.color = colors[1]
      surface.canvas.drawRect(Rect.makeXYWH(0f, 0f, widthPx / 2f, heightPx.toFloat()), paint)
      paint.color = colors[2]
      val shortest = minOf(widthPx, heightPx).toFloat()
      surface.canvas.drawCircle(widthPx * 0.72f, heightPx * 0.31f, shortest * 0.22f, paint)
    }
    surface.makeImageSnapshot()
  } finally {
    surface.close()
  }
}

private fun pinnedAssetColors(assetKey: String): IntArray =
  when (assetKey) {
    "jetcaster.cover.android-developers-backstage" ->
      intArrayOf(0xFF0B57D0.toInt(), 0xFF00A896.toInt(), 0xFF101828.toInt())
    "jetcaster.cover.google-developers-podcast" ->
      intArrayOf(0xFFEA4335.toInt(), 0xFFFBBC04.toInt(), 0xFF174EA6.toInt())
    "ui-builder.gate0.cover" ->
      intArrayOf(0xFF6750A4.toInt(), 0xFFB69DF8.toInt(), 0xFF21005D.toInt())
    else -> error("no pinned JVM raster asset for '$assetKey'")
  }

private fun UiBuilderNode.rasterPixelSize(
  document: UiBuilderDocument,
  layoutBounds: UiBuilderPixelBounds?,
): Pair<Int, Int> {
  val size =
    modifiers
      .mapNotNull { it as? JsonObject }
      .singleOrNull { modifier -> (modifier["type"] as? JsonPrimitive)?.content == "size" }
  val (widthPx, heightPx) =
    if (size != null) {
      val widthDp = (size["widthDp"] as? JsonPrimitive)?.content?.toFloatOrNull()
      val heightDp = (size["heightDp"] as? JsonPrimitive)?.content?.toFloatOrNull()
      val density = document.environmentNumber("density")
      ((widthDp ?: error("raster node $id has no widthDp")) * density).roundToInt() to
        ((heightDp ?: error("raster node $id has no heightDp")) * density).roundToInt()
    } else {
      require(
        modifiers.count { modifier ->
          (modifier as? JsonObject)?.get("type")?.let { it as? JsonPrimitive }?.content ==
            "matchParentSize"
        } == 1
      ) {
        "raster node $id needs one explicit size or matchParentSize modifier for deterministic export"
      }
      val bounds =
        requireNotNull(layoutBounds) {
          "raster node $id has no measured layout provenance for matchParentSize"
        }
      bounds.width.roundToInt() to bounds.height.roundToInt()
    }
  require(widthPx > 0 && heightPx > 0) { "raster node $id has a non-positive pixel size" }
  return widthPx to heightPx
}

private fun UiBuilderNode.stringProperty(name: String): String =
  (properties[name] as? kotlinx.serialization.json.JsonObject)
    ?.get("value")
    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
    ?.content
    .orEmpty()

private fun UiBuilderDocument.environmentNumber(name: String): Float =
  requireNotNull(
    (environment[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
  ) {
    "validated environment.$name is missing"
  }

private fun UiBuilderDocument.environmentText(name: String): String =
  (environment[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private fun UiBuilderDocument.fixedFrameNanos(): Long {
  // The pinned fixture is settled; a fixed non-zero frame avoids wall-clock output variance. The
  // ISO timestamp remains in export metadata even though ComposeScene consumes monotonic nanos.
  return 1_000_000_000L
}

/**
 * Skia allocates SVG resource ids from a process-global counter; normalize declarations and actual
 * fragment references per document without rewriting unrelated `#` text or color values.
 */
private fun String.canonicalizeSkiaResourceIds(): String {
  val ids = linkedMapOf<String, String>()
  Regex("id=\"([^\"]+)\"").findAll(this).forEach { match ->
    ids.getOrPut(match.groupValues[1]) { "builder_${ids.size}" }
  }
  if (ids.isEmpty()) return this
  val withDeclarations =
    Regex("id=\"([^\"]+)\"").replace(this) { match ->
      "id=\"${ids.getValue(match.groupValues[1])}\""
    }
  val withUrlReferences =
    Regex("url\\(\\s*#([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)").replace(withDeclarations) { match ->
      ids[match.groupValues[1]]?.let { "url(#$it)" } ?: match.value
    }
  return Regex("((?:xlink:)?href\\s*=\\s*[\"'])#([^\"']+)([\"'])").replace(withUrlReferences) {
    match ->
    ids[match.groupValues[2]]?.let { "${match.groupValues[1]}#$it${match.groupValues[3]}" }
      ?: match.value
  }
}
