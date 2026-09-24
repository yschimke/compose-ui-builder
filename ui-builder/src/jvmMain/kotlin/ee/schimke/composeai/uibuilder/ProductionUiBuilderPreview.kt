package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
 * Fixed daemon entrypoint for revision-pinned production exports.
 *
 * The persisted document travels as a named string override on every render. It is decoded inside
 * composition and rendered by the same [UiBuilderSurface] as the interactive Wasm editor; the
 * daemon therefore produces both the exact PNG and its `compose/figma-svg` product from one
 * override-bearing frame. Malformed input fails the render rather than falling back to a different
 * document.
 */
@Preview(widthDp = 1280, heightDp = 800)
@Composable
fun ProductionUiBuilderPreview() {
  val source = previewOverrideString(PRODUCTION_DOCUMENT_OVERRIDE, DEFAULT_DOCUMENT_JSON)
  ProductionUiBuilderSurface(decodeProductionRendererDocument(source))
}

/** The document drawn with its pinned catalog's canvas vocabulary, as a production export is. */
@Composable
internal fun ProductionUiBuilderSurface(document: UiBuilderDocument) {
  val catalog = productionPreviewCatalog(document)
  if (catalog == null) {
    UiBuilderSurface(document = document, editorOverlay = false)
  } else {
    CompositionLocalProvider(
      LocalUiBuilderCanvasAdapters provides catalog.productionCanvasAdapterIds(),
      // The editor provides both of these beside the adapter ids, and this entrypoint did not: a
      // mapping is what routes a `remote-m3/remote-title-card`'s slots into the Wear card that
      // draws it, and the component ids are what let an adapter-less component draw as a named
      // placeholder rather than as an error.
      LocalUiBuilderCanvasAdapterMappings provides catalog.productionCanvasAdapterMappings(),
      LocalUiBuilderCatalogComponentIds provides catalog.componentsById.keys,
      LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
      LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
    ) {
      UiBuilderSurface(document = document, editorOverlay = false)
    }
  }
}

internal fun decodeProductionRendererDocument(source: String): UiBuilderDocument =
  productionPreviewJson.decodeFromString(UiBuilderDocument.serializer(), source)

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
 * Canvas adapters for components a published catalog serves and the packaged catalog predates.
 *
 * This renderer reads the frozen `remote-m3` golden, while preview.coo.ee serves wear-m3-catalog's
 * published `remote-m3`, which writes text as `remote-m3/remote-text`. The golden has no entry for
 * it, so every widget's text drew as "Unsupported component" in a PNG export. `RemoteText` takes
 * the same arguments as Wear's `Text`, which is the adapter the golden already uses for the rest of
 * the Remote Material 3 family, so that is what draws it here. A catalog that names an adapter of
 * its own for one of these ids keeps it.
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

private val productionPreviewJson = Json {
  ignoreUnknownKeys = false
  explicitNulls = false
}

private const val DEFAULT_DOCUMENT_JSON =
  """{"schema":"compose-ui-builder-document/v1-candidate","id":"production-default","title":"Production UI builder","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":1280,"heightDp":800,"density":1.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T12:00:00Z","animations":"settled","networkAccess":false},"stateVariables":{},"roots":["root"],"nodes":{"root":{"id":"root","componentId":"m3/text","properties":{"text":{"type":"string","value":"UI builder"}},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
