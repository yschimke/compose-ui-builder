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
import ee.schimke.composeai.uibuilder.artwork.readProjectOwnedJetcasterArtwork
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.Image
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
    val layoutProvenance = measureLayout(document)
    val rasterAssets =
      JvmStructuredSvgRasterAssets.create(
        document,
        request.declaredRasterFallbackNodeIds,
        layoutProvenance.nodeBounds,
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
      val rawSvg = recordRaw(document, rasterAssets) { recordedLayout = it }
      val stableLayout =
        checkNotNull(recordedLayout) { "SVG recording produced no layout inspection snapshot" }
      layoutProvenance.requireStableRasterBounds(
        stableLayout,
        request.declaredRasterFallbackNodeIds,
      )
      val svg = rawSvg.annotateTextTypography(document, stableLayout)
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
          val encoded = runBlocking { readProjectOwnedJetcasterArtwork(assetKey) }
          val sourceIdentity =
            "project-owned-artwork/v1/$assetKey/square-512/rendered-${widthPx}x$heightPx"
          JvmStructuredSvgRasterIdentity(
              image = createScaledProjectOwnedImage(encoded, widthPx, heightPx),
              sourceIdentity = sourceIdentity,
              sourceIdentitySha256 = encoded.sha256(),
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

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
    it.toUByte().toString(16).padStart(2, '0')
  }

private fun createScaledProjectOwnedImage(
  encoded: ByteArray,
  widthPx: Int,
  heightPx: Int,
): Image {
  val source = Image.makeFromEncoded(encoded)
  val surface = Surface.makeRasterN32Premul(widthPx, heightPx)
  return try {
    surface.canvas.drawImageRect(source, Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()))
    surface.makeImageSnapshot()
  } finally {
    surface.close()
    source.close()
  }
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

/**
 * Skia retains text as independently positioned line fragments but loses the authored Compose
 * identity and normalizes the platform family to a localized generic list. Correlate each fragment
 * with the renderer's measured node bounds/baselines, then publish a Figma-supported deterministic
 * family plus the effective Material weight/style and source node identity.
 */
private fun String.annotateTextTypography(
  document: UiBuilderDocument,
  inspection: UiBuilderInspectionSnapshot,
): String {
  val parsed =
    requireNotNull(parseStrictSvg(this).document) { "Skia text output is not valid structured SVG" }
  val textElements = parsed.elements.filter { it.name == "text" }
  val viewportWidthPx =
    document.environmentNumber("widthDp") * document.environmentNumber("density")
  val viewportHeightPx =
    document.environmentNumber("heightDp") * document.environmentNumber("density")
  val inspectedText =
    inspection.nodes
      .filter { node ->
        val bounds = node.bounds
        node.componentId == "m3/text" &&
          bounds != null &&
          node.text != null &&
          bounds.right > 0f &&
          bounds.bottom > 0f &&
          bounds.x < viewportWidthPx &&
          bounds.y < viewportHeightPx
      }
      .associateBy(UiBuilderNodeInspection::nodeId)
  if (textElements.isEmpty()) {
    require(inspectedText.isEmpty()) { "SVG omitted ${inspectedText.size} measured text nodes" }
    return this
  }

  val fragmentCounts = mutableMapOf<String, Int>()
  val correlations = textElements.associateWith { element ->
    val position = element.absoluteTextBaseline()
    val candidates =
      inspectedText.values
        .mapNotNull { candidate ->
          val bounds = checkNotNull(candidate.bounds)
          val text = checkNotNull(candidate.text)
          val baselineDistance = text.expectedBaselines().minOf { kotlin.math.abs(it - position.y) }
          val outsideX =
            when {
              position.x < bounds.x -> bounds.x - position.x
              position.x > bounds.right -> position.x - bounds.right
              else -> 0f
            }
          if (baselineDistance <= TEXT_BASELINE_TOLERANCE_PX && outsideX <= TEXT_X_TOLERANCE_PX) {
            candidate.nodeId to (baselineDistance + outsideX)
          } else null
        }
        .sortedWith(compareBy<Pair<String, Float>> { it.second }.thenBy { it.first })
    val selected =
      candidates.firstOrNull()
        ?: error(
          "SVG text at ${position.x},${position.y} has no measured authored node; nearest=" +
            inspectedText.values
              .sortedBy { kotlin.math.abs(checkNotNull(it.text).firstBaselineY - position.y) }
              .take(3)
              .joinToString { candidate ->
                "${candidate.nodeId}:${candidate.bounds}:${candidate.text}"
              }
        )
    require(candidates.drop(1).none { kotlin.math.abs(it.second - selected.second) < 0.01f }) {
      "SVG text at ${position.x},${position.y} has ambiguous authored nodes " +
        candidates.take(3).joinToString()
    }
    val nodeId = selected.first
    val fragmentIndex = fragmentCounts.getOrDefault(nodeId, 0)
    fragmentCounts[nodeId] = fragmentIndex + 1
    SvgTextCorrelation(nodeId, fragmentIndex)
  }
  val unmatched = inspectedText.keys - correlations.values.map(SvgTextCorrelation::nodeId).toSet()
  require(unmatched.isEmpty()) { "SVG omitted measured text nodes: ${unmatched.sorted()}" }

  var annotated = this
  correlations.entries
    .sortedByDescending { it.key.nameEnd }
    .forEach { (element, correlation) ->
      val nodeId = correlation.nodeId
      val node = document.nodes.getValue(nodeId)
      val typography = node.svgTypography()
      val tagTail = annotated.substring(element.nameEnd, element.startTagEnd)
      require("id" !in element.attributes) { "Skia output pre-asserted text id for $nodeId" }
      require(!Regex("\\sdata-compose-(?:node-id|typography-[^=]+)=").containsMatchIn(tagTail)) {
        "Skia output pre-asserted text provenance for $nodeId"
      }
      val cleanedTail =
        tagTail.replace(Regex("\\s(?:font-family|font-weight|font-style)=([\"'])[^\"']*\\1"), "")
      val attributes =
        " id=\"compose-text-${nodeId.xmlIdSegment()}-${correlation.fragmentIndex}\"" +
          " data-compose-node-id=\"${nodeId.xmlAttributeValue()}\"" +
          " data-compose-typography-fragment=\"${correlation.fragmentIndex}\"" +
          " data-compose-typography-source=\"material3-token-v1\"" +
          " data-compose-typography-token=\"${typography.token.xmlAttributeValue()}\"" +
          " data-compose-typography-family=\"${typography.family.xmlAttributeValue()}\"" +
          " data-compose-typography-family-source=\"figma-inter-adapter-v1\"" +
          " data-compose-typography-weight=\"${typography.weight}\"" +
          " data-compose-typography-style=\"${typography.style.xmlAttributeValue()}\"" +
          " font-family=\"${typography.family.xmlAttributeValue()}\"" +
          " font-weight=\"${typography.weight}\"" +
          " font-style=\"${typography.style.xmlAttributeValue()}\""
      annotated =
        annotated.substring(0, element.nameEnd) +
          attributes +
          cleanedTail +
          annotated.substring(element.startTagEnd)
    }
  return annotated
}

private data class SvgTextCorrelation(val nodeId: String, val fragmentIndex: Int)

private fun String.xmlIdSegment(): String = buildString {
  this@xmlIdSegment.forEach { character ->
    if (character.isLetterOrDigit() || character in setOf('-', '_', '.')) append(character)
    else append('_').append(character.code.toString(16)).append('_')
  }
}

private fun String.xmlAttributeValue(): String =
  replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private data class SvgTextPosition(val x: Float, val y: Float)

private fun ParsedSvgElement.absoluteTextBaseline(): SvgTextPosition {
  val transform = attributes["transform"]
  val translation =
    if (transform == null) {
      0f to 0f
    } else {
      val translate =
        Regex("translate\\(([-+0-9.eE]+)[ ,]+([-+0-9.eE]+)\\)").matchEntire(transform)
          ?: error("SVG text has unsupported transform '$transform'")
      translate.groupValues[1].toFloat() to translate.groupValues[2].toFloat()
    }
  val localX = attributes["x"].orEmpty().substringBefore(',').trim().toFloatOrNull() ?: 0f
  val localY =
    requireNotNull(attributes["y"].orEmpty().substringBefore(',').trim().toFloatOrNull()) {
      "SVG text has no numeric baseline"
    }
  return SvgTextPosition(
    x = translation.first + localX,
    y = translation.second + localY,
  )
}

private fun UiBuilderTextInspection.expectedBaselines(): List<Float> =
  when (lineCount) {
    1 -> listOf(firstBaselineY)
    else ->
      List(lineCount) { line ->
        firstBaselineY + (lastBaselineY - firstBaselineY) * line / (lineCount - 1)
      }
  }

private data class SvgTypography(
  val family: String,
  val weight: Int,
  val style: String,
  val token: String,
)

private fun UiBuilderNode.svgTypography(): SvgTypography {
  val token = stringProperty("style").ifEmpty { "local" }
  val weight =
    when (stringProperty("fontWeight")) {
      "bold" -> 700
      "semiBold" -> 600
      "medium" -> 500
      "" ->
        when (token) {
          "titleMedium",
          "titleSmall",
          "labelLarge",
          "labelSmall" -> 500
          else -> 400
        }
      else -> error("text node $id has unsupported fontWeight")
    }
  val style =
    when (val value = stringProperty("fontStyle")) {
      "",
      "normal" -> "normal"
      "italic" -> "italic"
      else -> error("text node $id has unsupported fontStyle '$value'")
    }
  return SvgTypography(
    family = FIGMA_SVG_FONT_FAMILY,
    weight = weight,
    style = style,
    token = token,
  )
}

private const val FIGMA_SVG_FONT_FAMILY = "Inter"
private const val TEXT_BASELINE_TOLERANCE_PX = 1.25f
private const val TEXT_X_TOLERANCE_PX = 1f
