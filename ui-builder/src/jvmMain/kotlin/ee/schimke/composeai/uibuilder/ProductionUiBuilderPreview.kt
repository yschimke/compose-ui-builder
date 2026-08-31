package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.overrides.previewOverrideString
import kotlinx.serialization.json.Json

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
  val document = decodeProductionRendererDocument(source)
  UiBuilderSurface(document = document, editorOverlay = false)
}

internal fun decodeProductionRendererDocument(source: String): UiBuilderDocument =
  productionPreviewJson.decodeFromString(UiBuilderDocument.serializer(), source)

const val PRODUCTION_DOCUMENT_OVERRIDE: String = "uiBuilder.document.v1"

private val productionPreviewJson = Json {
  ignoreUnknownKeys = false
  explicitNulls = false
}

private const val DEFAULT_DOCUMENT_JSON =
  """{"schema":"compose-ui-builder-document/v1-candidate","id":"production-default","title":"Production UI builder","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":1280,"heightDp":800,"density":1.0,"theme":"dark","dynamicColor":false,"locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","windowPosture":"flat","browserZoomPercent":100,"fixedTime":"2024-05-16T12:00:00Z","animations":"settled","networkAccess":false},"stateVariables":{},"roots":["root"],"nodes":{"root":{"id":"root","componentId":"m3/text","properties":{"text":{"type":"string","value":"UI builder"}},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
