package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.rcplayer.protocol.RcCustomLayout
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.uibuilder.REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.canvas.LocalRemoteComposeCaptures
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_CUSTOM_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A mobile screen with Remote Compose content in the middle of it, and Compose back inside that:
 * the three-scope tree from `docs/design/UI_BUILDER_REMOTE_COMPOSE.md`, drawn through
 * [UiBuilderSurface]. The outer dashed frame marks the switch to `@RemoteComposable`; the inner one
 * names the custom component a host must register.
 */
@Preview(widthDp = 320, heightDp = 260)
@Composable
fun InlineRemoteContentPreview() {
  UiBuilderSurface(
    document = inlineRemoteContentDocument(),
    editorOverlay = false,
  )
}

/**
 * The same screen once its remote content has been **captured** and is played for real:
 * `RcComposePlayer` draws the document and the design's Compose fills the `field` custom component
 * through
 * [RcCustomComponentRegistry][ee.schimke.composeai.rcplayer.compose.RcCustomComponentRegistry], as
 * a host would. The frame stays, labelled `played`.
 *
 * The fixture is built here (no Android daemon to run `captureSingleRemoteDocument`), but its bytes
 * go through the real codec and player.
 */
@Preview(widthDp = 320, heightDp = 260)
@Composable
fun PlayedInlineRemoteContentPreview() {
  CompositionLocalProvider(
    LocalRemoteComposeCaptures provides
      { nodeId ->
        if (nodeId == "remote") Result.success(capturedInlineDocument()) else null
      }
  ) {
    UiBuilderSurface(
      document = inlineRemoteContentDocument(),
      editorOverlay = false,
    )
  }
}

/**
 * The smallest honest capture of the design's `remote` node: one `LAYOUT_CUSTOM` operation naming
 * `field`, as `RemoteCustomComponent(name = "field")` writes, so the custom-component seam is
 * exercised.
 */
internal fun capturedInlineDocument(width: Int = 300, height: Int = 96): RcDocument =
  RcDocument(
    header = RcHeader(RcVersion(0, 1, 0), legacyWidth = width, legacyHeight = height),
    operations =
      listOf(
        RcTextData(1, "field"),
        RcRootLayout(10),
        RcCustomLayout(componentId = 20, animationId = 0, configId = 1, properties = emptyList()),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      ),
  )

/**
 * The same screen with its remote content sourced by URL. Nothing resolves it here, so it draws the
 * *waiting* surface, not an error.
 */
@Preview(widthDp = 320, heightDp = 200)
@Composable
fun UnresolvedRemoteComposeUrlPreview() {
  UiBuilderSurface(
    document = remoteComposeUrlDocument(),
    editorOverlay = false,
  )
}

internal fun inlineRemoteContentDocument(): UiBuilderDocument =
  previewDocument(
    title = "Player screen",
    roots = listOf("screen"),
    nodes =
      listOf(
        column("screen", listOf("intro", "remote", "outro")),
        text("intro", "Library"),
        UiBuilderNode(
          id = "remote",
          componentId = REMOTE_COMPOSE_INLINE_COMPONENT_ID,
          slots = mapOf("content" to listOf("remoteColumn")),
        ),
        column("remoteColumn", listOf("remoteLabel", "custom", "remoteHint")),
        text("remoteLabel", "Search"),
        UiBuilderNode(
          id = "custom",
          componentId = REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
          properties =
            buildJsonObject {
              putJsonObject("name") {
                put("type", "string")
                put("value", "field")
              }
            },
          slots = mapOf("content" to listOf("field")),
        ),
        text("field", "Type to search…"),
        text("remoteHint", "Recent"),
        text("outro", "Recently played"),
      ),
  )

private fun remoteComposeUrlDocument(): UiBuilderDocument =
  previewDocument(
    title = "Player screen",
    roots = listOf("screen"),
    nodes =
      listOf(
        column("screen", listOf("intro", "embedded")),
        text("intro", "Library"),
        UiBuilderNode(
          id = "embedded",
          componentId = REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID,
          properties =
            buildJsonObject {
              putJsonObject("documentUrl") {
                put("type", "string")
                put("value", "/remote-m3/render/appcard__ideal__default__compact.rc")
              }
            },
        ),
      ),
  )

private fun column(id: String, children: List<String>) =
  UiBuilderNode(id = id, componentId = "layout/column", slots = mapOf("children" to children))

private fun text(id: String, value: String) =
  UiBuilderNode(
    id = id,
    componentId = "m3/text",
    properties =
      buildJsonObject {
        putJsonObject("text") {
          put("type", "string")
          put("value", value)
        }
      },
  )

private fun previewDocument(title: String, roots: List<String>, nodes: List<UiBuilderNode>) =
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "inline-remote-content-preview",
    title = title,
    revision = 1,
    catalogPin = inlineRemoteContentCatalogPin,
    environment = inlineRemoteContentEnvironment,
    stateVariables = JsonObject(emptyMap()),
    roots = roots,
    nodes = nodes.associateBy { it.id },
  )

private val inlineRemoteContentCatalogPin: JsonObject = buildJsonObject {
  put("systemId", JsonPrimitive("m3-catalog"))
  put("catalogRevision", JsonPrimitive("candidate"))
  put("capabilityDigest", JsonPrimitive("candidate"))
  put("nativeRuntimeId", JsonPrimitive("candidate"))
}

/** Settled and fixed, for the reason the Wear widget previews give: a moving render cannot diff. */
private val inlineRemoteContentEnvironment: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "widthDp": 320,
        "heightDp": 260,
        "density": 1.0,
        "theme": "light",
        "dynamicColor": false,
        "locale": "en-US",
        "fontScale": 1.0,
        "layoutDirection": "ltr",
        "windowPosture": "flat",
        "browserZoomPercent": 100,
        "fixedTime": "2024-05-16T12:00:00Z",
        "animations": "settled",
        "networkAccess": false
      }
      """
    )
    .jsonObject
