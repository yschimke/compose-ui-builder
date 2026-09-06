package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A mobile screen with Remote Compose content in the middle of it, and Compose back inside that.
 *
 * The three-scope tree from `docs/design/UI_BUILDER_REMOTE_COMPOSE.md`, drawn through
 * [UiBuilderSurface] — the same renderer the editor canvas and the production export use — so this
 * is what an author sees rather than a mock of it. The two dashed frames are the point: the outer
 * one marks where the vocabulary switches to `@RemoteComposable`, the inner one names the custom
 * component a host has to register for the content inside it to draw on a real player.
 *
 * Wired here so the next change to either frame is diffed by the preview workflow rather than
 * noticed by somebody.
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
 * The same screen with its remote content sourced by URL instead of authored.
 *
 * Nothing resolves it here — a preview has no host to fetch with — which is exactly the state worth
 * having a picture of: an unresolved `documentUrl` draws as a *waiting* surface rather than an
 * error box, because a design pointing at a URL nobody has fetched is not a broken design.
 */
@Preview(widthDp = 320, heightDp = 200)
@Composable
fun UnresolvedRemoteComposeUrlPreview() {
  UiBuilderSurface(
    document = remoteComposeUrlDocument(),
    editorOverlay = false,
  )
}

private fun inlineRemoteContentDocument(): UiBuilderDocument =
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
