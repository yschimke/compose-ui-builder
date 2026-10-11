package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fixed daemon entrypoint for revision-pinned production exports. The persisted document arrives as
 * a named string override, is decoded in composition and drawn by the same [UiBuilderSurface] as
 * the Wasm editor, so one frame yields both the PNG and its `compose/figma-svg`. Malformed input
 * fails the render rather than falling back.
 */
@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun ProductionUiBuilderPreview() {
  val source = previewOverrideString(PRODUCTION_DOCUMENT_OVERRIDE, DEFAULT_DOCUMENT_JSON)
  val servedCatalog = previewOverrideString(PRODUCTION_CATALOG_OVERRIDE, "")
  ProductionUiBuilderSurface(
    decodeProductionRendererDocument(source),
    servedCatalog = servedCatalog,
  )
}

/**
 * The document drawn with its pinned catalog's canvas vocabulary, as a production export is, in
 * [fonts]: the design's typefaces, there for the first frame, since this daemon draws one.
 */
@Composable
internal fun ProductionUiBuilderSurface(
  document: UiBuilderDocument,
  fonts: Map<String, androidx.compose.ui.text.font.FontFamily> = ProductionFontFamilies.production,
  /** See [ProvideProductionCatalog]. */
  servedCatalog: String = "",
) {
  ProvideProductionCatalog(document, servedCatalog) {
    CompositionLocalProvider(
      LocalUiBuilderFontFamilies provides fonts,
      LocalUiBuilderFontVariants provides (fonts as? UiBuilderFontVariants),
    ) {
      UiBuilderSurface(document = document, editorOverlay = false)
    }
  }
}

/**
 * [content] with [document]'s pinned catalog's canvas vocabulary provided, or bare when this
 * renderer carries no catalog for the pin. Separate so previews drawing their own surface get the
 * same adapters.
 */
@Composable
internal fun ProvideProductionCatalog(
  document: UiBuilderDocument,
  /**
   * The catalog the host serves for the pin ([PRODUCTION_CATALOG_OVERRIDE]), used when this
   * renderer packages none for it. Blank when the host sent none.
   */
  servedCatalog: String = "",
  content: @Composable () -> Unit,
) {
  val catalog =
    remember(document.catalogPin, servedCatalog) { productionCatalogFor(document, servedCatalog) }
  if (catalog == null) {
    content()
  } else {
    CompositionLocalProvider(
      LocalUiBuilderCanvasAdapters provides catalog.productionCanvasAdapterIds(),
      // Provide the adapter mappings (e.g. routing `remote-m3/remote-title-card` slots into the
      // Wear card) and component ids (so adapter-less components draw as named placeholders), as
      // the editor does.
      LocalUiBuilderCanvasAdapterMappings provides catalog.productionCanvasAdapterMappings(),
      LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
      LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
      LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
      content = content,
    )
  }
}

internal fun decodeProductionRendererDocument(source: String): UiBuilderDocument =
  productionPreviewJson.decodeFromString(UiBuilderDocument.serializer(), source)

/**
 * The catalog [document] is drawn with: the one this renderer packages for its pin, else the
 * host-served one ([servedCatalog]), else none. The served catalog covers catalogs this renderer
 * predates (e.g. `glimmer-catalog`), so unknown components draw as named placeholders. An
 * unreadable served catalog is ignored.
 */
internal fun productionCatalogFor(
  document: UiBuilderDocument,
  servedCatalog: String,
): CapabilityCatalog? =
  productionPreviewCatalog(document)
    ?: servedCatalog
      .takeIf { it.isNotBlank() }
      ?.let { runCatching { CapabilityCatalogParser.parse(it) }.getOrNull() }

/** The packaged catalog artifact for the document's pin, if this renderer carries one. */
internal fun productionPreviewCatalog(document: UiBuilderDocument): CapabilityCatalog? {
  val systemId =
    document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull
      ?: document.catalogPin["catalogId"]?.jsonPrimitive?.contentOrNull
      ?: return null
  val source =
    UiBuilderDocument::class.java.getResource("/$systemId-capabilities-v1.json") ?: return null
  return CapabilityCatalogParser.parse(source.readText())
}

/**
 * Canvas adapters for components a published catalog serves and the packaged catalog predates: the
 * packaged `remote-m3` golden lacks `remote-m3/remote-text`, which takes the same arguments as
 * Wear's `Text`. A catalog naming its own adapter keeps it.
 */
internal val PUBLISHED_COMPONENT_CANVAS_FALLBACKS: Map<String, String> =
  mapOf(REMOTE_TEXT_COMPONENT_ID to "wear-m3/text")

/** `RemoteText`'s argument names, spelled the way the `wear-m3/text` adapter reads them. */
internal val PUBLISHED_COMPONENT_CANVAS_MAPPINGS: Map<String, CanvasAdapterMappingV1> =
  mapOf(REMOTE_TEXT_COMPONENT_ID to remoteTextCanvasMapping())

private fun remoteTextCanvasMapping(): CanvasAdapterMappingV1 {
  val builder = CanvasAdapterMappingV1.Builder()
  builder.properties = mapOf("fontSizeSp" to "fontSize")
  return builder.build()
}

internal fun CapabilityCatalog.productionCanvasAdapterIds(): Map<String, String> =
  PUBLISHED_COMPONENT_CANVAS_FALLBACKS + canvasAdapterIds

internal fun CapabilityCatalog.productionCanvasAdapterMappings():
  Map<String, CanvasAdapterMappingV1> =
  PUBLISHED_COMPONENT_CANVAS_MAPPINGS.filterKeys { it !in canvasAdapterIds } + canvasAdapterMappings

const val PRODUCTION_DOCUMENT_OVERRIDE: String = "uiBuilder.document.v1"

/**
 * The served catalog a host passes beside the document: `ui-builder-runtime`'s
 * `PackagedUiBuilderRenderBundle.CATALOG_OVERRIDE_KEY`. See [productionCatalogFor].
 */
const val PRODUCTION_CATALOG_OVERRIDE: String = "uiBuilder.catalog.v1"

private val productionPreviewJson = Json {
  ignoreUnknownKeys = false
  explicitNulls = false
}

private const val DEFAULT_DOCUMENT_JSON =
  """{"schema":"compose-ui-builder-document/v1-candidate","id":"production-default","title":"Production UI builder","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":1280,"heightDp":800,"density":1.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T12:00:00Z","animations":"settled","networkAccess":false},"stateVariables":{},"roots":["root"],"nodes":{"root":{"id":"root","componentId":"m3/text","properties":{"text":{"type":"string","value":"UI builder"}},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
